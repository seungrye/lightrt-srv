package com.litert.tunnel.server

import com.litert.tunnel.engine.InferenceEngine
import com.litert.tunnel.engine.Message
import com.litert.tunnel.repository.RequestLog
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TunnelServer(
    private val engine: InferenceEngine,
    private val onRequest: (RequestLog) -> Unit,
) {
    private var applicationEngine: ApplicationEngine? = null
    var port: Int = 8080
        private set

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /**
     * Installs all routes into an existing [Application].
     * Used by both [start] (real server) and tests ([testApplication]).
     */
    fun install(app: Application) = app.apply {
        install(ContentNegotiation) { json(json) }
        install(CORS) { anyHost() }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(error = cause.message ?: "Unknown error", code = 500),
                )
            }
        }
        routing {
            get("/health") {
                val start = System.currentTimeMillis()
                call.respond(
                    HealthResponse(
                        status = "ok",
                        model = engine.modelName,
                        backend = engine.backendName,
                        ready = engine.isReady,
                    )
                )
                onRequest(log("/health", 200, System.currentTimeMillis() - start))
            }

            route("/v1") {
                get("/models") {
                    val start = System.currentTimeMillis()
                    call.respond(
                        ModelsResponse(data = listOf(ModelEntry(id = engine.modelName)))
                    )
                    onRequest(log("/v1/models", 200, System.currentTimeMillis() - start))
                }

                post("/chat/completions") {
                    val start = System.currentTimeMillis()
                    if (!engine.isReady) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("Engine not ready — model may still be loading", 503),
                        )
                        onRequest(log("/v1/chat/completions", 503, System.currentTimeMillis() - start))
                        return@post
                    }

                    val req = call.receive<ChatCompletionRequest>()
                    val messages = req.messages.map { Message(it.role, it.content) }

                    if (req.stream) {
                        val reqId = "chatcmpl-${System.currentTimeMillis()}"
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                            // Opening chunk carries the role
                            write(sseData(firstChunk(reqId, engine.modelName)))
                            flush()

                            engine.generate(messages).collect { token ->
                                write(sseData(tokenChunk(reqId, engine.modelName, token)))
                                flush()
                            }

                            write(sseData(stopChunk(reqId, engine.modelName)))
                            write("data: [DONE]\n\n")
                            flush()
                        }
                        onRequest(log("/v1/chat/completions", 200, System.currentTimeMillis() - start))
                    } else {
                        val tokens = engine.generate(messages).toList()
                        val content = tokens.joinToString("")
                        val elapsed = System.currentTimeMillis() - start
                        call.respond(
                            ChatCompletionResponse(
                                id = "chatcmpl-${System.currentTimeMillis()}",
                                created = System.currentTimeMillis() / 1000,
                                model = engine.modelName,
                                choices = listOf(
                                    Choice(
                                        index = 0,
                                        message = MessageDto(role = "assistant", content = content),
                                        finishReason = "stop",
                                    )
                                ),
                            )
                        )
                        onRequest(log("/v1/chat/completions", 200, elapsed))
                    }
                }
            }

            post("/reset") {
                engine.clearHistory()
                call.respond(mapOf("status" to "cleared"))
                onRequest(log("/reset", 200, 0))
            }
        }
    }

    /** Starts the real CIO server. Tries ports 8080–8082. Returns the bound port. */
    fun start(): Int {
        for (tryPort in 8080..8082) {
            try {
                applicationEngine = embeddedServer(CIO, port = tryPort) { install(this) }
                applicationEngine!!.start(wait = false)
                port = tryPort
                return port
            } catch (e: Exception) {
                if (tryPort == 8082) throw e
            }
        }
        error("Could not bind to any port in range 8080–8082")
    }

    fun stop() {
        applicationEngine?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        applicationEngine = null
    }

    // ── SSE helpers ───────────────────────────────────────────────────────

    private fun sseData(chunk: StreamChunk): String =
        "data: ${json.encodeToString(chunk)}\n\n"

    private fun firstChunk(id: String, model: String) = StreamChunk(
        id = id,
        created = System.currentTimeMillis() / 1000,
        model = model,
        choices = listOf(StreamChoice(index = 0, delta = Delta(role = "assistant", content = ""))),
    )

    private fun tokenChunk(id: String, model: String, token: String) = StreamChunk(
        id = id,
        created = System.currentTimeMillis() / 1000,
        model = model,
        choices = listOf(StreamChoice(index = 0, delta = Delta(content = token))),
    )

    private fun stopChunk(id: String, model: String) = StreamChunk(
        id = id,
        created = System.currentTimeMillis() / 1000,
        model = model,
        choices = listOf(StreamChoice(index = 0, delta = Delta(), finishReason = "stop")),
    )

    private fun log(endpoint: String, statusCode: Int, responseTimeMs: Long) =
        RequestLog(endpoint = endpoint, statusCode = statusCode, responseTimeMs = responseTimeMs)
}
