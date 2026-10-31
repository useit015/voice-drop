package app.voicedrop.platform

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pull-based 16-bit mono PCM at [rate] Hz. `read` returns the number of samples written, or -1 at the end. */
internal interface PcmSource {
    val rate: Int
    fun read(dst: ShortArray, max: Int): Int
}

internal class ArraySource(private val data: ShortArray, override val rate: Int) : PcmSource {
    private var pos = 0
    override fun read(dst: ShortArray, max: Int): Int {
        if (pos >= data.size) return -1
        val n = min(max, data.size - pos)
        data.copyInto(dst, 0, pos, pos + n)
        pos += n
        return n
    }
}

/** Linear-interpolation resampler to 48 kHz; good enough for speech and sound effects. */
internal class To48k(private val src: PcmSource) : PcmSource {
    override val rate = OPUS_RATE
    private val step = src.rate.toDouble() / OPUS_RATE
    private val buf = ShortArray(8192)
    private val tmp = ShortArray(8192)
    private var len = 0
    private var pos = 0.0 // fractional index into buf
    private var ended = false

    override fun read(dst: ShortArray, max: Int): Int {
        var n = 0
        while (n < max) {
            val i = pos.toInt()
            if (i + 1 >= len) {
                if (ended) break
                // Drop consumed samples but keep sample i for interpolation across refills.
                val keep = (len - i).coerceAtLeast(0)
                if (keep > 0) buf.copyInto(buf, 0, i, len)
                pos -= i
                len = keep
                val r = src.read(tmp, buf.size - len)
                if (r <= 0) {
                    ended = true
                    break
                }
                tmp.copyInto(buf, len, 0, r)
                len += r
                continue
            }
            val f = pos - i
            dst[n++] = (buf[i] * (1 - f) + buf[i + 1] * f).toInt().toShort()
            pos += step
        }
        return if (n == 0) -1 else n
    }
}

internal const val OPUS_RATE = 48_000

internal object Opus {
    /**
     * Encodes PCM into a WhatsApp-style voice note: mono Opus 32 kbps in OGG.
     * Stops after [maxSeconds]. The 64-bar waveform is RMS per bar, scaled so the loudest bar is 100.
     */
    fun encode(source: PcmSource, out: String, maxSeconds: Int): VoiceNote {
        val src = if (source.rate == OPUS_RATE) source else To48k(source)
        val maxSamples = maxSeconds.toLong() * OPUS_RATE
        val tmpOut = "$out.part"
        val chunkEnergy = ArrayList<Float>(maxSeconds * 100)
        var chunkSum = 0.0
        var chunkN = 0

        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OPUS_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val muxer = MediaMuxer(tmpOut, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        var track = -1
        var total = 0L
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val pcm = ShortArray(8192)
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val ib = codec.getInputBuffer(i)!!.order(ByteOrder.LITTLE_ENDIAN)
                        ib.clear()
                        val want = min(min(ib.remaining() / 2, pcm.size).toLong(), maxSamples - total).toInt()
                        val n = if (want > 0) src.read(pcm, want) else -1
                        val pts = total * 1_000_000 / OPUS_RATE
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            ib.asShortBuffer().put(pcm, 0, n)
                            for (k in 0 until n) {
                                val s = pcm[k] / 32768.0
                                chunkSum += s * s
                                if (++chunkN == 480) { // 10 ms
                                    chunkEnergy += chunkSum.toFloat()
                                    chunkSum = 0.0
                                    chunkN = 0
                                }
                            }
                            codec.queueInputBuffer(i, 0, n * 2, pts, 0)
                            total += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    o >= 0 -> {
                        val ob = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            muxer.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (track >= 0) muxer.stop() }
            muxer.release()
        }
        if (chunkN > 0) chunkEnergy += chunkSum.toFloat()
        if (total == 0L) {
            File(tmpOut).delete()
            error("No audio")
        }
        File(tmpOut).renameTo(File(out))
        val seconds = max(1, ((total + OPUS_RATE / 2) / OPUS_RATE).toInt())
        return VoiceNote(out, seconds, waveform(chunkEnergy))
    }

    private fun waveform(chunks: List<Float>): ByteArray {
        val bars = FloatArray(64)
        if (chunks.isEmpty()) return ByteArray(64)
        for (b in 0 until 64) {
            val from = b * chunks.size / 64
            val to = max(from + 1, (b + 1) * chunks.size / 64).coerceAtMost(chunks.size)
            var sum = 0.0
            for (k in from until to) sum += chunks[k]
            bars[b] = sqrt(sum / ((to - from) * 480)).toFloat()
        }
        val peak = bars.max().takeIf { it > 0f } ?: return ByteArray(64)
        return ByteArray(64) { (bars[it] / peak * 100).toInt().coerceIn(0, 100).toByte() }
    }

    /** Decodes any audio file Android can read to mono 16-bit PCM at its native rate, up to [maxSeconds]. */
    fun decode(path: String, maxSeconds: Int): ArraySource {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        val trackIdx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("No audio track")
        ex.selectTrack(trackIdx)
        val inFmt = ex.getTrackFormat(trackIdx)
        val codec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        var rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var float = false
        var out = ShortArray(rate * 4)
        var len = 0
        try {
            codec.configure(inFmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            loop@ while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val frames = if (float) info.size / 4 / channels else info.size / 2 / channels
                    if (len + frames > out.size) out = out.copyOf(max(out.size * 2, len + frames))
                    if (float) {
                        val fb = ob.asFloatBuffer()
                        for (f in 0 until frames) {
                            var s = 0f
                            for (c in 0 until channels) s += fb.get()
                            out[len++] = (s / channels * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        }
                    } else {
                        val sb = ob.asShortBuffer()
                        for (f in 0 until frames) {
                            var s = 0
                            for (c in 0 until channels) s += sb.get()
                            out[len++] = (s / channels).toShort()
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (len >= maxSeconds.toLong() * rate || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        return ArraySource(out.copyOf(min(len, maxSeconds * rate)), rate)
    }
}
