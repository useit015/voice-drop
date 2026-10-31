package app.voicedrop.data

import app.voicedrop.data.db.AppDatabase
import app.voicedrop.data.db.AvatarEntity
import app.voicedrop.data.db.ChatEntity
import app.voicedrop.data.db.ContactEntity
import app.voicedrop.data.db.MessageEntity
import app.voicedrop.data.db.ReactionEntity
import app.voicedrop.data.db.RecentSoundEntity
import app.voicedrop.data.db.FavSoundEntity
import app.voicedrop.data.db.Status
import app.voicedrop.engine.Engine
import app.voicedrop.engine.EngineEvent
import app.voicedrop.engine.WireBatch
import app.voicedrop.engine.WireChat
import app.voicedrop.engine.WireContact
import app.voicedrop.engine.WireError
import app.voicedrop.engine.WireGroup
import app.voicedrop.engine.WireJson
import app.voicedrop.engine.WireMe
import app.voicedrop.engine.WireMeta
import app.voicedrop.engine.WirePresence
import app.voicedrop.engine.WireReceipt
import app.voicedrop.engine.WireRow
import app.voicedrop.engine.WireState
import app.voicedrop.engine.WireTyping
import app.voicedrop.platform.Platform
import app.voicedrop.sounds.Gif
import app.voicedrop.sounds.Giphy
import app.voicedrop.sounds.MyInstants
import app.voicedrop.sounds.Sound
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

/**
 * The one place that talks to the engine. Engine events land in Room; the UI only observes Room
 * plus the small live state below (connection, typing, presence).
 */
class Repository(
    private val engine: Engine,
    private val db: AppDatabase,
    val platform: Platform,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow("starting")
    val status: StateFlow<String> = _status

    private val _me = MutableStateFlow<WireMe?>(null)
    val me: StateFlow<WireMe?> = _me

    private val _qr = MutableStateFlow<String?>(null)
    val qr: StateFlow<String?> = _qr

    /** History-sync progress 0..100 while the first sync runs, else null. */
    private val _sync = MutableStateFlow<Int?>(null)
    val sync: StateFlow<Int?> = _sync

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices

    private val _typing = MutableStateFlow<Map<String, Typing>>(emptyMap())
    val typing: StateFlow<Map<String, Typing>> = _typing

    private val _presence = MutableStateFlow<Map<String, WirePresence>>(emptyMap())
    val presence: StateFlow<Map<String, WirePresence>> = _presence

    /** The chat on screen: its incoming messages are read immediately instead of counted unread. */
    private val openChat = MutableStateFlow<String?>(null)

    val myInstants = MyInstants(platform.http)
    val gifs = Giphy(platform.http, platform.giphyKey)

    init {
        platform.mkdirs(mediaDir)
        platform.mkdirs(avatarDir)
        platform.mkdirs(soundDir)
        platform.mkdirs(gifDir)
        scope.launch {
            engine.events.collect { evt ->
                runCatching { handle(evt) }.onFailure { println("voicedrop: ${evt.kind}: $it") }
            }
        }
        scope.launch { avatarWorker() }
        scope.launch {
            runCatching { applyState(decode<WireState>(engine.call("state"))) }
        }
    }

    private val mediaDir get() = "${platform.filesDir}/media"
    private val avatarDir get() = "${platform.filesDir}/avatars"
    private val soundDir get() = "${platform.cacheDir}/sounds"
    private val gifDir get() = "${platform.cacheDir}/gifs"

    // ---------- observing ----------

    val contactNames: StateFlow<Map<String, String>> = db.contacts().all()
        .map { list -> list.mapNotNull { c -> (c.name ?: c.notify)?.let { c.jid to it } }.toMap() }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val chats: Flow<List<ChatItem>> = combine(db.chats().all(), contactNames, me) { chats, names, me ->
        chats.map { c ->
            ChatItem(
                chat = c,
                title = title(c.jid, c.name, names, me),
                lastSenderName = c.lastSender?.takeIf { isGroup(c.jid) && !c.lastFromMe }?.let { names[it] ?: phoneOf(it) },
            )
        }
    }

    fun chat(jid: String): Flow<ChatEntity?> = db.chats().observe(jid)

    fun messages(jid: String, limit: Int): Flow<List<MessageItem>> =
        combine(db.messages().latest(jid, limit), db.reactions().forChat(jid), me) { msgs, reactions, me ->
            val byMsg = reactions.groupBy { it.msgId }
            msgs.map { m ->
                val rs = byMsg[m.id].orEmpty()
                MessageItem(
                    msg = m,
                    meta = parseMeta(m.meta),
                    reactions = rs.groupingBy { it.emoji }.eachCount().toList().sortedByDescending { it.second },
                    myReaction = rs.firstOrNull { it.sender == me?.jid }?.emoji,
                )
            }
        }

    fun avatar(jid: String): Flow<String?> = db.avatars().observe(jid)
        .map { it?.path }
        .onStart { requestAvatar(jid) }

    fun title(jid: String, name: String? = null, names: Map<String, String> = contactNames.value, me: WireMe? = this.me.value): String =
        when {
            me != null && userOf(jid) == userOf(me.jid) && !isGroup(jid) -> "${me.name ?: "You"} (You)"
            !name.isNullOrBlank() -> name
            else -> names[jid] ?: phoneOf(jid)
        }

    fun senderName(jid: String?): String = jid?.let { contactNames.value[it] ?: phoneOf(it) } ?: ""

    val contacts = db.contacts().all()
    val messageTotal = db.messages().total()
    val favoriteSounds = db.sounds().favorites()
    val recentSounds = db.sounds().recent()

    // ---------- connection & linking ----------

    suspend fun connect() = guard { applyState(decode<WireState>(engine.call("connect"))) }

    suspend fun disconnect() = guard { engine.call("disconnect") }

    suspend fun pairWithQr() = guard { engine.call("pairQR") }

    /** Returns the 8-character code to type on the phone. */
    suspend fun pairWithPhone(phone: String): String =
        engine.call("pairPhone", args("phone" to phone)).jsonObject["code"]!!.jsonPrimitive.content

    suspend fun logout() {
        runCatching { engine.call("logout") }
        db.messages().clear(); db.chats().clear(); db.contacts().clear(); db.reactions().clear(); db.avatars().clear()
        _me.value = null
    }

    private fun applyState(s: WireState) {
        _status.value = s.status
        if (s.me != null) _me.value = s.me
        if (s.status != "qr") _qr.value = null
    }

    // ---------- engine events ----------

    private suspend fun handle(evt: EngineEvent) {
        val p = evt.payload
        when (evt.kind) {
            "status" -> {
                val s = WireJson.decodeFromString<WireState>(p)
                val was = _status.value
                applyState(s)
                if (s.status == "open" && was != "open") onOpen()
            }
            "qr" -> _qr.value = WireJson.parseToJsonElement(p).jsonObject["code"]?.jsonPrimitive?.contentOrNull
            "paired" -> {
                _qr.value = null
                _sync.value = 0
            }
            "pair-error" -> _notices.tryEmit("Linking failed, try again")
            "network" -> _online.value = WireJson.parseToJsonElement(p).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull ?: true
            "error" -> _notices.tryEmit(WireJson.decodeFromString<WireError>(p).message)
            "msgs" -> ingest(WireJson.decodeFromString<WireBatch>(p))
            "receipt" -> receipt(WireJson.decodeFromString<WireReceipt>(p))
            "chats" -> mergeChats(WireJson.decodeFromString<List<WireChat>>(p))
            "contacts" -> mergeContacts(WireJson.decodeFromString<List<WireContact>>(p))
            "typing" -> typing(WireJson.decodeFromString<WireTyping>(p))
            "presence" -> WireJson.decodeFromString<WirePresence>(p).let { pr -> _presence.update { it + (pr.jid to pr) } }
            "star" -> {
                val o = WireJson.parseToJsonElement(p).jsonObject
                db.messages().setStarred(o.str("jid"), o.str("id"), o["starred"]?.jsonPrimitive?.booleanOrNull == true)
            }
            "groups-changed" -> loadGroups()
            "avatar-changed" -> {
                val jid = WireJson.parseToJsonElement(p).jsonObject.str("jid")
                db.avatars().invalidate(jid)
                requestAvatar(jid)
            }
        }
    }

    private fun onOpen() {
        scope.launch {
            runCatching { mergeContacts(decode<List<WireContact>>(engine.call("contacts"))) }
            loadGroups()
            openChat.value?.let { subscribePresence(it) }
        }
    }

    private suspend fun loadGroups() {
        val groups = runCatching { decode<List<WireGroup>>(engine.call("groups")) }.getOrNull() ?: return
        mergeChats(groups.map { WireChat(jid = it.jid, name = it.name) }, sizes = groups.associate { it.jid to it.size })
    }

    private suspend fun ingest(b: WireBatch) {
        b.progress?.let { _sync.value = if (it >= 100) null else it }
        val rows = b.rows.orEmpty().map { it.toEntity() }
        if (rows.isNotEmpty()) {
            rows.chunked(500).forEach { db.messages().insertAll(it) }
            val open = openChat.value
            val toRead = mutableListOf<MessageEntity>()
            val updated = rows.groupBy { it.jid }.entries.chunked(500).flatMap { part ->
                val existing = db.chats().getMany(part.map { it.key }).associateBy { it.jid }
                part.map { (jid, list) ->
                    var c = existing[jid] ?: ChatEntity(jid)
                    val newest = list.maxBy { it.ts }
                    if (newest.ts >= c.lastTs) c = c.withLast(newest)
                    if (b.live) {
                        val incoming = list.filter { !it.fromMe }
                        when {
                            list.any { it.fromMe } -> c = c.copy(unread = 0, markedUnread = false, readUpTo = maxOf(c.readUpTo, newest.ts))
                            jid == open -> toRead += incoming
                            else -> c = c.copy(unread = c.unread + incoming.size)
                        }
                    }
                    c
                }
            }
            db.chats().upsertAll(updated)
            if (toRead.isNotEmpty()) scope.launch { markRead(toRead.first().jid) }
        }
        for (r in b.revokes.orEmpty()) {
            db.messages().revoke(r.jid, r.id)
            db.chats().get(r.jid)?.takeIf { it.lastId == r.id }?.let { db.chats().upsert(it.copy(lastKind = "revoked", lastText = null)) }
        }
        for (e in b.edits.orEmpty()) {
            val text = e.text ?: continue
            db.messages().edit(e.jid, e.id, text)
            db.chats().get(e.jid)?.takeIf { it.lastId == e.id }?.let { db.chats().upsert(it.copy(lastText = text)) }
        }
        for (r in b.reactions.orEmpty()) {
            val sender = if (r.fromMe) me.value?.jid ?: continue else r.sender
            if (r.emoji.isEmpty()) db.reactions().delete(r.jid, r.id, sender)
            else db.reactions().upsert(ReactionEntity(r.jid, r.id, sender, r.emoji, r.ts))
        }
        if (b.onDemand) historyWaiter?.complete(rows.size)
    }

    private suspend fun receipt(r: WireReceipt) {
        val status = when (r.type) {
            "delivered" -> Status.DELIVERED
            "read" -> Status.READ
            "played" -> Status.PLAYED
            else -> return
        }
        if (r.fromMe) {
            // Read on another of my devices.
            if (status >= Status.READ) db.chats().get(r.jid)?.let { db.chats().upsert(it.copy(unread = 0, markedUnread = false, readUpTo = maxOf(it.readUpTo, r.ts))) }
            return
        }
        r.ids.chunked(500).forEach { db.messages().raiseStatus(r.jid, it, status) }
        db.chats().get(r.jid)?.takeIf { it.lastFromMe && it.lastId in r.ids && it.lastStatus < status && it.lastStatus >= 0 }
            ?.let { db.chats().upsert(it.copy(lastStatus = status)) }
    }

    private suspend fun mergeChats(list: List<WireChat>, sizes: Map<String, Int> = emptyMap()) {
        list.chunked(500).forEach { part ->
            val existing = db.chats().getMany(part.map { it.jid }).associateBy { it.jid }
            db.chats().upsertAll(part.map { w ->
                val c = existing[w.jid] ?: ChatEntity(w.jid)
                c.copy(
                    name = w.name?.ifBlank { null } ?: c.name,
                    pinned = w.pinned ?: c.pinned,
                    archived = w.archived ?: c.archived,
                    mutedUntil = w.mutedUntil ?: c.mutedUntil,
                    unread = w.unread ?: c.unread,
                    markedUnread = w.markedUnread ?: c.markedUnread,
                    size = sizes[w.jid] ?: c.size,
                )
            })
        }
    }

    private suspend fun mergeContacts(list: List<WireContact>) {
        list.chunked(500).forEach { part ->
            val existing = db.contacts().getMany(part.map { it.jid }).associateBy { it.jid }
            db.contacts().upsertAll(part.map { w ->
                val c = existing[w.jid] ?: ContactEntity(w.jid)
                c.copy(name = w.name?.ifBlank { null } ?: c.name, notify = w.notify?.ifBlank { null } ?: c.notify, lid = w.lid ?: c.lid)
            })
        }
    }

    private val typingClear = mutableMapOf<String, Job>()

    private fun typing(t: WireTyping) {
        typingClear.remove(t.jid)?.cancel()
        if (t.state != "composing") {
            _typing.update { it - t.jid }
            return
        }
        _typing.update { it + (t.jid to Typing(t.sender, t.media == "audio", platform.now())) }
        typingClear[t.jid] = scope.launch {
            delay(25_000)
            _typing.update { it - t.jid }
        }
    }

    // ---------- reading ----------

    /** Call when a chat opens or closes; while open, incoming messages are marked read as they arrive. */
    fun setOpenChat(jid: String?) {
        openChat.value = jid
        if (jid != null) scope.launch {
            markRead(jid)
            subscribePresence(jid)
        }
    }

    private suspend fun subscribePresence(jid: String) {
        if (!isGroup(jid)) runCatching { engine.call("subscribePresence", args("jid" to jid)) }
    }

    suspend fun markRead(jid: String) {
        val chat = db.chats().get(jid) ?: return
        val incoming = db.messages().incomingAfter(jid, chat.readUpTo)
        if (incoming.isEmpty() && chat.unread == 0 && !chat.markedUnread) return
        db.chats().upsert(chat.copy(unread = 0, markedUnread = false, readUpTo = maxOf(chat.readUpTo, incoming.maxOfOrNull { it.ts } ?: 0)))
        if (_status.value != "open") return
        incoming.groupBy { it.sender }.forEach { (sender, msgs) ->
            runCatching { engine.call("markRead", args("jid" to jid, "ids" to msgs.map { it.id }, "sender" to sender?.takeIf { isGroup(jid) })) }
        }
        if (chat.markedUnread) setChat(chat, read = true)
    }

    /** Blue mic: the voice note was listened to. */
    fun markPlayed(m: MessageEntity) {
        if (m.fromMe) return
        scope.launch {
            runCatching { engine.call("markRead", args("jid" to m.jid, "ids" to listOf(m.id), "sender" to m.sender?.takeIf { isGroup(m.jid) }, "played" to true)) }
        }
    }

    suspend fun markUnread(jid: String) {
        val chat = db.chats().get(jid) ?: return
        db.chats().upsert(chat.copy(markedUnread = true))
        setChat(chat, read = false)
    }

    // ---------- chat settings ----------

    suspend fun setPinned(jid: String, pinned: Boolean) = update(jid, { it.copy(pinned = pinned) }) { setChat(it, pinned = pinned) }

    suspend fun setArchived(jid: String, archived: Boolean) = update(jid, { it.copy(archived = archived, pinned = if (archived) false else it.pinned) }) { setChat(it, archived = archived) }

    /** ms from now, -1 forever, 0 unmute. */
    suspend fun setMuted(jid: String, forMs: Long) {
        val until = when {
            forMs < 0 -> -1L
            forMs == 0L -> 0L
            else -> platform.now() + forMs
        }
        update(jid, { it.copy(mutedUntil = until) }) { setChat(it, muteMs = forMs) }
    }

    private suspend fun update(jid: String, local: (ChatEntity) -> ChatEntity, remote: suspend (ChatEntity) -> Unit) {
        val chat = db.chats().get(jid) ?: ChatEntity(jid)
        db.chats().upsert(local(chat))
        remote(chat)
    }

    private suspend fun setChat(c: ChatEntity, pinned: Boolean? = null, archived: Boolean? = null, muteMs: Long? = null, read: Boolean? = null) = guard {
        engine.call(
            "setChat",
            args(
                "jid" to c.jid, "pinned" to pinned, "archived" to archived, "muteMs" to muteMs, "read" to read,
                "lastId" to c.lastId, "lastTs" to c.lastTs, "lastFromMe" to c.lastFromMe,
            ),
        )
    }

    // ---------- sending ----------

    suspend fun sendText(jid: String, text: String, quote: MessageEntity? = null) {
        val body = text.trimEnd()
        if (body.isBlank()) return
        val row = pending(jid, "text", text = body, quote = quote)
        deliver(row) { engine.call("sendText", args("jid" to jid, "id" to row.id, "text" to body, "quote" to quoteArg(quote))) }
    }

    /** Photos, videos, documents, voice notes, GIFs. `path` must already be in app storage. */
    suspend fun sendMedia(
        jid: String, kind: String, path: String, mime: String,
        caption: String? = null, name: String? = null, quote: MessageEntity? = null,
        seconds: Int = 0, wave: ByteArray? = null,
    ) {
        val info = if (kind in setOf("image", "video", "gif")) runCatching { platform.describeMedia(path, kind != "image") }.getOrNull() else null
        val secs = if (seconds > 0) seconds else info?.seconds ?: 0
        val waveB64 = wave?.let { Base64.encode(it) }
        val meta = WireMeta(seconds = secs, wave = waveB64, w = info?.w ?: 0, h = info?.h ?: 0, mime = mime, name = name)
        val row = pending(jid, kind, text = caption?.ifBlank { null } ?: name.takeIf { kind == "document" }, quote = quote, meta = meta, localPath = path)
        deliver(row) {
            engine.call(
                "sendMedia",
                args(
                    "jid" to jid, "id" to row.id, "kind" to kind, "path" to path, "mime" to mime, "caption" to caption,
                    "name" to name, "seconds" to secs, "wave" to waveB64, "thumb" to info?.thumbPath,
                    "w" to info?.w, "h" to info?.h, "quote" to quoteArg(quote),
                ),
            )
        }
    }

    /** Sends a recorded or encoded voice note (OGG/Opus). */
    suspend fun sendVoice(jid: String, path: String, seconds: Int, wave: ByteArray, quote: MessageEntity? = null) =
        sendMedia(jid, "voice", path, "audio/ogg; codecs=opus", quote = quote, seconds = seconds, wave = wave)

    /** Resends a failed message under the same id. */
    suspend fun retry(m: MessageEntity) {
        if (m.status != Status.FAILED) return
        db.messages().setStatus(m.jid, m.id, Status.PENDING)
        val quote = m.quoteId?.let { db.messages().get(m.jid, it) }
        val meta = parseMeta(m.meta)
        deliver(m) {
            if (m.kind == "text") engine.call("sendText", args("jid" to m.jid, "id" to m.id, "text" to m.text, "quote" to quoteArg(quote)))
            else engine.call(
                "sendMedia",
                args(
                    "jid" to m.jid, "id" to m.id, "kind" to m.kind, "path" to m.localPath, "mime" to meta?.mime,
                    "caption" to m.text.takeIf { m.kind != "document" }, "name" to meta?.name, "seconds" to meta?.seconds,
                    "wave" to meta?.wave, "w" to meta?.w, "h" to meta?.h, "quote" to quoteArg(quote),
                ),
            )
        }
    }

    private suspend fun pending(
        jid: String, kind: String, text: String? = null, quote: MessageEntity? = null, meta: WireMeta? = null, localPath: String? = null,
    ): MessageEntity {
        val id = engine.call("newId").jsonObject.str("id")
        val row = MessageEntity(
            jid = jid, id = id, fromMe = true, ts = platform.now(), sender = me.value?.jid, kind = kind, text = text,
            meta = meta?.let { WireJson.encodeToString(WireMeta.serializer(), it) },
            quoteId = quote?.id, quoteSender = quote?.let { if (it.fromMe) me.value?.jid else it.sender },
            quoteKind = quote?.kind, quoteText = quote?.let { preview(it.kind, it.text) },
            status = Status.PENDING, localPath = localPath,
        )
        db.messages().upsert(row)
        val chat = db.chats().get(jid) ?: ChatEntity(jid)
        db.chats().upsert(chat.withLast(row).copy(unread = 0, markedUnread = false, archived = false))
        return row
    }

    private fun deliver(row: MessageEntity, call: suspend () -> JsonElement) {
        scope.launch {
            try {
                val sent = decode<WireRow>(call())
                val current = db.messages().get(row.jid, row.id) ?: row
                db.messages().upsert(
                    current.copy(
                        raw = sent.raw ?: current.raw,
                        status = maxOf(current.status, Status.SENT),
                    ),
                )
                db.chats().get(row.jid)?.takeIf { it.lastId == row.id }?.let { db.chats().upsert(it.copy(lastStatus = maxOf(it.lastStatus, Status.SENT))) }
            } catch (t: Throwable) {
                db.messages().setStatus(row.jid, row.id, Status.FAILED)
                db.chats().get(row.jid)?.takeIf { it.lastId == row.id }?.let { db.chats().upsert(it.copy(lastStatus = Status.FAILED)) }
                _notices.tryEmit("Not sent: ${t.message}")
            }
        }
    }

    private fun quoteArg(q: MessageEntity?): JsonElement? = q?.let {
        args("id" to it.id, "sender" to it.sender.takeIf { _ -> !it.fromMe }, "fromMe" to it.fromMe, "text" to preview(it.kind, it.text))
    }

    suspend fun react(m: MessageEntity, emoji: String) {
        val mine = me.value?.jid ?: return
        if (emoji.isEmpty()) db.reactions().delete(m.jid, m.id, mine)
        else db.reactions().upsert(ReactionEntity(m.jid, m.id, mine, emoji, platform.now()))
        guard { engine.call("react", args("jid" to m.jid, "id" to m.id, "sender" to m.sender, "fromMe" to m.fromMe, "emoji" to emoji)) }
    }

    suspend fun edit(m: MessageEntity, text: String) {
        if (!m.fromMe || text.isBlank()) return
        db.messages().edit(m.jid, m.id, text)
        guard { engine.call("edit", args("jid" to m.jid, "id" to m.id, "text" to text)) }
    }

    suspend fun deleteForEveryone(m: MessageEntity) = guard {
        engine.call("revoke", args("jid" to m.jid, "id" to m.id, "sender" to m.sender, "fromMe" to m.fromMe))
        db.messages().revoke(m.jid, m.id)
    }

    suspend fun deleteForMe(m: MessageEntity) {
        db.messages().delete(m.jid, m.id)
        m.localPath?.takeIf { it.startsWith(mediaDir) }?.let { platform.delete(it) }
    }

    suspend fun setStarred(m: MessageEntity, starred: Boolean) = db.messages().setStarred(m.jid, m.id, starred)

    fun setTyping(jid: String, state: String) {
        if (_status.value != "open") return
        scope.launch { runCatching { engine.call("typing", args("jid" to jid, "state" to state)) } }
    }

    suspend fun resolvePhone(phone: String): String =
        engine.call("resolve", args("phone" to phone)).jsonObject.str("jid")

    suspend fun searchMessages(q: String) = if (q.length < 2) emptyList() else db.messages().search(q)

    // ---------- media ----------

    private val inflight = mutableMapOf<String, Deferred<String?>>()
    private val inflightMu = Mutex()

    /** Local file for an attachment, downloading it first if needed. */
    suspend fun media(m: MessageEntity): String? {
        m.localPath?.takeIf { platform.exists(it) }?.let { return it }
        val raw = m.raw ?: return null
        val key = "${m.jid}/${m.id}"
        val job = inflightMu.withLock {
            inflight.getOrPut(key) {
                scope.async {
                    try {
                        val out = "$mediaDir/${userOf(m.jid)}_${m.id}.${extension(m)}"
                        val res = engine.call(
                            "downloadMedia",
                            args("raw" to raw, "out" to out, "jid" to m.jid, "id" to m.id, "fromMe" to m.fromMe, "sender" to m.sender),
                        ).jsonObject
                        res["raw"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { db.messages().setRaw(m.jid, m.id, it) }
                        db.messages().setLocalPath(m.jid, m.id, out)
                        out
                    } finally {
                        inflightMu.withLock { inflight.remove(key) }
                    }
                }
            }
        }
        return job.await()
    }

    private fun extension(m: MessageEntity): String {
        val meta = parseMeta(m.meta)
        meta?.name?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 }?.let { return it }
        val mime = meta?.mime.orEmpty()
        return when {
            m.kind == "voice" || "ogg" in mime -> "ogg"
            m.kind == "sticker" || "webp" in mime -> "webp"
            "png" in mime -> "png"
            mime.startsWith("image/") || m.kind == "image" -> "jpg"
            mime.startsWith("video/") || m.kind == "video" || m.kind == "gif" -> "mp4"
            "mpeg" in mime -> "mp3"
            "mp4" in mime || "aac" in mime -> "m4a"
            "pdf" in mime -> "pdf"
            else -> "bin"
        }
    }

    // ---------- avatars ----------

    private val avatarQueue = Channel<String>(Channel.UNLIMITED)

    private fun requestAvatar(jid: String) {
        avatarQueue.trySend(jid)
    }

    private suspend fun avatarWorker() {
        for (jid in avatarQueue) {
            val fetched = try {
                if (_status.value != "open") status.first { it == "open" }
                fetchAvatar(jid)
            } catch (_: Throwable) {
                true
            }
            if (fetched) delay(150)
        }
    }

    /** Returns false when the stored avatar was fresh enough and nothing was asked of WhatsApp. */
    private suspend fun fetchAvatar(jid: String): Boolean {
        val now = platform.now()
        val cur = db.avatars().get(jid)
        if (cur != null) {
            val age = now - cur.checkedAt
            if (cur.path == null && age < 7 * DAY) return false
            if (cur.path != null && age < DAY && platform.exists(cur.path)) return false
        }
        val out = "$avatarDir/${userOf(jid)}_$now.jpg"
        val res = engine.call("avatar", args("jid" to jid, "out" to out, "existing" to cur?.picId?.takeIf { cur.path != null && platform.exists(cur.path) })).jsonObject
        when {
            res["none"] != null -> {
                cur?.path?.let { platform.delete(it) }
                db.avatars().upsert(AvatarEntity(jid, null, null, now))
            }
            res["unchanged"] != null -> db.avatars().upsert(cur!!.copy(checkedAt = now))
            else -> {
                cur?.path?.let { platform.delete(it) }
                db.avatars().upsert(AvatarEntity(jid, out, res["id"]?.jsonPrimitive?.contentOrNull, now))
            }
        }
        return true
    }

    // ---------- older history ----------

    private val historyMu = Mutex()
    private var historyWaiter: CompletableDeferred<Int>? = null

    /**
     * Asks the phone for messages older than the oldest one stored.
     * Returns how many arrived, 0 when there are none, or -1 when the phone did not answer.
     */
    suspend fun loadOlder(jid: String): Int = historyMu.withLock {
        val oldest = db.messages().oldest(jid) ?: return 0
        val w = CompletableDeferred<Int>()
        historyWaiter = w
        try {
            engine.call(
                "fetchHistory",
                args("jid" to jid, "id" to oldest.id, "fromMe" to oldest.fromMe, "sender" to oldest.sender, "ts" to oldest.ts, "count" to 50),
            )
            withTimeoutOrNull(25_000) { w.await() } ?: -1
        } catch (t: Throwable) {
            -1
        } finally {
            historyWaiter = null
        }
    }

    suspend fun messageCount(jid: String) = db.messages().count(jid)

    // ---------- sounds & GIFs ----------

    /** Downloads and encodes a myinstants sound once; later sends reuse the cached voice note. */
    suspend fun prepareSound(s: Sound): Triple<String, Int, ByteArray> {
        require(MyInstants.isSoundUrl(s.mp3)) { "Not a myinstants sound" }
        val key = s.mp3.substringAfterLast('/').substringBeforeLast('.').filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(60) +
            "-" + s.mp3.hashCode().toUInt().toString(16)
        val ogg = "$soundDir/$key.ogg"
        val metaPath = "$soundDir/$key.json"
        val cached = platform.readText(metaPath)?.let { runCatching { WireJson.decodeFromString<WireMeta>(it) }.getOrNull() }
        if (cached != null && platform.exists(ogg)) return Triple(ogg, cached.seconds, Base64.decode(cached.wave.orEmpty()))
        val mp3 = "$soundDir/$key.src"
        platform.download(s.mp3, mp3)
        val vn = platform.encodeVoiceNote(mp3, ogg)
        platform.delete(mp3)
        platform.writeText(metaPath, WireJson.encodeToString(WireMeta.serializer(), WireMeta(seconds = vn.seconds, wave = Base64.encode(vn.wave))))
        return Triple(vn.path, vn.seconds, vn.wave)
    }

    suspend fun sendSound(jid: String, s: Sound, quote: MessageEntity? = null) {
        val (path, seconds, wave) = try {
            prepareSound(s)
        } catch (t: Throwable) {
            _notices.tryEmit("Couldn't prepare \"${s.title}\": ${t.message}")
            return
        }
        // Each send gets its own copy so deleting the message never breaks the cache.
        val copy = "$mediaDir/sound_${platform.now()}.ogg"
        platform.copy(path, copy)
        sendVoice(jid, copy, seconds, wave, quote)
        db.sounds().touchRecent(RecentSoundEntity(s.mp3, s.title, s.color, platform.now()))
    }

    suspend fun toggleFavorite(s: Sound, favorite: Boolean) {
        if (favorite) db.sounds().addFavorite(FavSoundEntity(s.mp3, s.title, s.color, platform.now()))
        else db.sounds().removeFavorite(s.mp3)
    }

    suspend fun sendGif(jid: String, g: Gif, quote: MessageEntity? = null) {
        val path = "$mediaDir/gif_${g.id}_${platform.now()}.mp4"
        try {
            platform.download(g.mp4, path)
        } catch (t: Throwable) {
            _notices.tryEmit("Couldn't download the GIF: ${t.message}")
            return
        }
        sendMedia(jid, "gif", path, "video/mp4", quote = quote)
    }

    // ---------- helpers ----------

    private suspend fun guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            _notices.tryEmit(t.message ?: "Something went wrong")
        }
    }

    private inline fun <reified T> decode(e: JsonElement): T = WireJson.decodeFromJsonElement(e)

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun WireRow.toEntity() = MessageEntity(
        jid = jid, id = id, fromMe = fromMe, ts = ts, sender = sender, push = push, kind = kind, text = text,
        meta = meta?.let { WireJson.encodeToString(WireMeta.serializer(), it) }, raw = raw,
        quoteId = quote?.id, quoteSender = quote?.sender, quoteKind = quote?.kind, quoteText = quote?.text,
        mentions = mentions.takeIf { it.isNotEmpty() }?.joinToString(","), forwarded = forwarded,
        status = Status.SENT,
    )

    private fun ChatEntity.withLast(m: MessageEntity) = copy(
        lastTs = m.ts, lastId = m.id, lastKind = m.kind, lastText = m.text,
        lastFromMe = m.fromMe, lastSender = m.sender, lastStatus = m.status,
    )

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
    }
}

/** Builds engine args, dropping nulls. */
internal fun args(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.mapNotNull { (k, v) -> toJson(v)?.let { k to it } }.toMap(),
)

private fun toJson(v: Any?): JsonElement? = when (v) {
    null -> null
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map { toJson(it) ?: JsonNull })
    else -> error("unsupported arg ${v::class}")
}
