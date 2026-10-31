package app.voicedrop.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The Go engine (engine/ in the repo root) behind its JSON bridge. */
interface Engine {
    /** Every event the engine emits, in order. */
    val events: Flow<EngineEvent>

    /** Runs one engine method; throws [EngineException] with the engine's message on failure. */
    suspend fun call(method: String, args: JsonObject = JsonObject(emptyMap())): JsonElement
}

class EngineEvent(val kind: String, val payload: String)

class EngineException(message: String) : Exception(message)

val WireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

// Shapes below mirror engine/parse.go and engine/handlers.go.

@Serializable
data class WireMeta(
    val seconds: Int = 0,
    val wave: String? = null,
    val thumb: String? = null,
    val w: Int = 0,
    val h: Int = 0,
    val mime: String? = null,
    val size: Long = 0,
    val name: String? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val options: List<String> = emptyList(),
    val url: String? = null,
    val title: String? = null,
    val desc: String? = null,
    val animated: Boolean = false,
    val viewOnce: Boolean = false,
)

@Serializable
data class WireQuote(val id: String, val sender: String? = null, val kind: String? = null, val text: String? = null)

@Serializable
data class WireRow(
    val jid: String,
    val id: String,
    val fromMe: Boolean = false,
    val ts: Long,
    val sender: String? = null,
    val push: String? = null,
    val kind: String,
    val text: String? = null,
    val meta: WireMeta? = null,
    val raw: String? = null,
    val quote: WireQuote? = null,
    val mentions: List<String> = emptyList(),
    val forwarded: Boolean = false,
)

@Serializable
data class WireReaction(val jid: String, val id: String, val sender: String, val fromMe: Boolean = false, val emoji: String = "", val ts: Long = 0)

@Serializable
data class WireRef(val jid: String, val id: String, val text: String? = null)

@Serializable
data class WireBatch(
    val rows: List<WireRow>? = null,
    val revokes: List<WireRef>? = null,
    val edits: List<WireRef>? = null,
    val reactions: List<WireReaction>? = null,
    val live: Boolean = false,
    val onDemand: Boolean = false,
    val progress: Int? = null,
    val syncType: String? = null,
)

@Serializable
data class WireChat(
    val jid: String,
    val name: String? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    val mutedUntil: Long? = null,
    val unread: Int? = null,
    val markedUnread: Boolean? = null,
)

@Serializable
data class WireContact(val jid: String, val name: String? = null, val notify: String? = null, val lid: String? = null)

@Serializable
data class WireGroup(val jid: String, val name: String = "", val topic: String? = null, val size: Int = 0, val participants: List<WireParticipant> = emptyList())

@Serializable
data class WireParticipant(val jid: String, val admin: Boolean = false, val superAdmin: Boolean = false)

@Serializable
data class WireMe(val jid: String, val name: String? = null)

@Serializable
data class WireState(val status: String, val me: WireMe? = null)

@Serializable
data class WireReceipt(val jid: String, val ids: List<String>, val type: String, val sender: String? = null, val fromMe: Boolean = false, val ts: Long = 0)

@Serializable
data class WireTyping(val jid: String, val sender: String? = null, val state: String, val media: String? = null)

@Serializable
data class WirePresence(val jid: String, val online: Boolean, val lastSeen: Long = 0)

@Serializable
data class WireError(val code: String, val message: String)
