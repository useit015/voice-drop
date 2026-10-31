package app.voicedrop.data

import app.voicedrop.data.db.ChatEntity
import app.voicedrop.data.db.MessageEntity
import app.voicedrop.engine.WireJson
import app.voicedrop.engine.WireMeta

/** A chat row as the list draws it. */
data class ChatItem(val chat: ChatEntity, val title: String, val lastSenderName: String?) {
    val jid get() = chat.jid
    val isGroup get() = isGroup(chat.jid)
}

data class Typing(val sender: String?, val recording: Boolean, val at: Long)

/** One message plus what the bubble needs that isn't in its row. */
data class MessageItem(val msg: MessageEntity, val meta: WireMeta?, val reactions: List<Pair<String, Int>>, val myReaction: String?)

fun isGroup(jid: String) = jid.endsWith("@g.us")

fun userOf(jid: String) = jid.substringBefore('@').substringBefore(':')

fun phoneOf(jid: String) = if (jid.endsWith("@s.whatsapp.net")) "+" + userOf(jid) else userOf(jid)

fun parseMeta(json: String?): WireMeta? = json?.let { runCatching { WireJson.decodeFromString<WireMeta>(it) }.getOrNull() }

/** "📷 Photo", "🎤 0:07", ... — the one-line form used in chat previews and reply quotes. */
fun preview(kind: String?, text: String?, seconds: Int = 0): String = when (kind) {
    null -> ""
    "text" -> text.orEmpty()
    "voice" -> "🎤 Voice message" + (if (seconds > 0) " (${duration(seconds)})" else "")
    "audio" -> "🎵 Audio"
    "image" -> "📷 " + (text?.ifBlank { null } ?: "Photo")
    "video" -> "🎥 " + (text?.ifBlank { null } ?: "Video")
    "gif" -> "GIF"
    "sticker" -> "Sticker"
    "document" -> "📄 " + (text?.ifBlank { null } ?: "Document")
    "contact" -> "👤 " + (text ?: "Contact")
    "location" -> "📍 Location"
    "poll" -> "📊 " + (text ?: "Poll")
    "revoked" -> "This message was deleted"
    else -> text ?: "Unsupported message"
}

fun duration(seconds: Int) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
