package app.voicedrop.android

import app.voicedrop.engine.Engine
import app.voicedrop.engine.EngineEvent
import app.voicedrop.engine.EngineException
import app.voicedrop.engine.WireJson
import app.voicedrop.go.engine.Listener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import app.voicedrop.go.engine.Engine as GoEngine

/** The gomobile-bound Go engine. Events are queued in order and read by a single collector (the repository). */
class EngineBridge(dataDir: String) : Engine {
    private val queue = Channel<EngineEvent>(Channel.UNLIMITED)
    override val events: Flow<EngineEvent> = queue.receiveAsFlow()

    init {
        GoEngine.start(dataDir, object : Listener {
            override fun onEvent(kind: String, payload: String) {
                queue.trySend(EngineEvent(kind, payload))
            }
        })
    }

    override suspend fun call(method: String, args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val out = try {
            GoEngine.call(method, args.toString())
        } catch (e: Exception) {
            throw EngineException(e.message ?: "engine error")
        }
        WireJson.parseToJsonElement(out)
    }
}
