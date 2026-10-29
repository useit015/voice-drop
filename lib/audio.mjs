// Download a sound and turn it into a WhatsApp voice note (mono Opus in OGG + duration + waveform).
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { UA } from './myinstants.mjs'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
export const CACHE_DIR = join(ROOT, 'cache')
const MAX_SECONDS = 60

export function run(cmd, args) {
  return new Promise((ok, fail) => {
    const p = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'] })
    const out = []
    let err = ''
    p.stdout.on('data', (d) => out.push(d))
    p.stderr.on('data', (d) => { err += d })
    p.on('error', (e) => fail(e.code === 'ENOENT' ? new Error(`${cmd} not found - install ffmpeg (brew install ffmpeg)`) : e))
    p.on('close', (code) => (code === 0 ? ok(Buffer.concat(out)) : fail(new Error(`${cmd} exited ${code}: ${err.trim().split('\n').pop()}`))))
  })
}

let active = 0
const waiting = []
async function limited(fn) {
  if (active >= 2) await new Promise((r) => waiting.push(r))
  active++
  try { return await fn() } finally { active--; waiting.shift()?.() }
}

const inflight = new Map()
const once = (key, fn) => {
  if (!inflight.has(key)) inflight.set(key, fn().finally(() => inflight.delete(key)))
  return inflight.get(key)
}

// Cached copy of a remote sound on disk; returns its path.
export function fetchSound(url) {
  mkdirSync(CACHE_DIR, { recursive: true })
  const key = createHash('sha1').update(url).digest('hex').slice(0, 16)
  const dst = join(CACHE_DIR, `${key}${url.match(/\.\w+$/)?.[0] ?? '.mp3'}`)
  if (existsSync(dst)) return Promise.resolve(dst)
  return once(dst, async () => {
    const res = await fetch(url, { headers: { 'user-agent': UA } })
    if (!res.ok) throw new Error(`download failed (${res.status})`)
    const tmp = `${dst}.part`
    writeFileSync(tmp, Buffer.from(await res.arrayBuffer()))
    renameSync(tmp, dst)
    return dst
  })
}

// 64 bars, 0-100, like the bars WhatsApp draws for a recorded voice note.
async function waveform(file) {
  const pcm = await run('ffmpeg', ['-v', 'error', '-i', file, '-f', 's16le', '-ac', '1', '-ar', '8000', '-'])
  const n = pcm.length >> 1
  const bars = 64
  const size = Math.max(1, Math.floor(n / bars))
  const vals = []
  for (let b = 0; b < bars; b++) {
    let sum = 0
    for (let i = b * size; i < Math.min(n, (b + 1) * size); i++) sum += Math.abs(pcm.readInt16LE(i * 2)) / 32768
    vals.push(sum / size)
  }
  const max = Math.max(...vals, 1e-6)
  return Array.from(vals, (v) => Math.round((v / max) * 100))
}

// sound: { url } for a remote sound or { file } for a local audio file.
export async function toVoiceNote(sound) {
  const src = sound.file ?? (await fetchSound(sound.url))
  const dst = src.replace(/\.\w+$/, '') + '.ogg'
  const meta = `${dst}.json`
  if (!existsSync(dst) || !existsSync(meta)) {
    await once(dst, () => limited(async () => {
      // mono Opus in OGG at 48k is what the mobile apps record; -application voip keeps speech/effects punchy.
      const tmp = `${dst}.part`
      await run('ffmpeg', ['-y', '-v', 'error', '-i', src, '-vn', '-t', String(MAX_SECONDS), '-ac', '1', '-ar', '48000',
        '-c:a', 'libopus', '-b:a', '32k', '-application', 'voip', '-f', 'ogg', tmp])
      renameSync(tmp, dst)
      const seconds = Math.max(1, Math.ceil(parseFloat((await run('ffprobe', ['-v', 'error', '-show_entries', 'format=duration',
        '-of', 'default=nw=1:nk=1', dst])).toString())))
      writeFileSync(meta, JSON.stringify({ seconds, waveform: await waveform(dst) }))
    }))
  }
  const { seconds, waveform: wf } = JSON.parse(readFileSync(meta, 'utf8'))
  return { path: dst, buffer: readFileSync(dst), seconds, waveform: new Uint8Array(wf) }
}
