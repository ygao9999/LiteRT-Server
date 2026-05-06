package com.litert.server.service

import com.litert.server.data.ChatRequest
import com.litert.server.data.ChatResponse
import com.litert.server.data.ErrorResponse
import com.litert.server.data.HealthResponse
import com.litert.server.data.OaiChatRequest
import com.litert.server.data.OaiChatResponse
import com.litert.server.data.OaiChoice
import com.litert.server.data.OaiDelta
import com.litert.server.data.OaiMessage
import com.litert.server.data.OaiModelEntry
import com.litert.server.data.OaiModelsResponse
import com.litert.server.data.OaiStreamChunk
import com.litert.server.data.OaiStreamChoice
import com.litert.server.data.RequestLogEntry
import com.litert.server.data.VisionRequest
import com.litert.server.engine.LiteRTEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
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
import android.util.Log

private const val TAG = "HttpApiServer"

class HttpApiServer(
    private val engine: LiteRTEngine,
    private val onRequest: (RequestLogEntry) -> Unit
) {
    private var server: ApplicationEngine? = null
    var port: Int = 8080
        private set

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }



    fun start(): Int {
        for (tryPort in 8080..8082) {
            try {
                server = embeddedServer(CIO, port = tryPort) {
                    install(ContentNegotiation) {
                        json(json)
                    }
                    install(CORS) {
                        anyHost()
                    }
                    install(StatusPages) {
                        exception<Throwable> { call, cause ->
                            Log.e(TAG, "Unhandled exception in route", cause)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse(error = cause.message ?: "Unknown error", code = 500)
                            )
                        }
                    }

                    routing {

                        // ── health ──────────────────────────────────────────
                        get("/health") {
                            call.respond(
                                HealthResponse(
                                    status = "ok",
                                    model = "gemma-4-E2B",
                                    gpu = engine.getBackend() == "GPU",
                                    ready = engine.isReady
                                )
                            )
                        }

                        // ── OpenAI-compatible v1 routes ──────────────────────
                        route("/v1") {

                            get("/models") {
                                call.respond(
                                    OaiModelsResponse(
                                        data = listOf(
                                            OaiModelEntry(id = "gemma-4-e2b")
                                        )
                                    )
                                )
                            }

                            post("/chat/completions") {
                                if (!engine.isReady) {
                                    call.respond(
                                        HttpStatusCode.ServiceUnavailable,
                                        ErrorResponse("Engine not ready", 503)
                                    )
                                    return@post
                                }

                                val req = try {
                                    call.receive<OaiChatRequest>()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to parse OaiChatRequest", e)
                                    call.respond(
                                        HttpStatusCode.BadRequest,
                                        ErrorResponse("Invalid request body: ${e.message}", 400)
                                    )
                                    return@post
                                }

                                val start = System.currentTimeMillis()
                                var toolSystemPrompt: String? = null
                                if (!req.tools.isNullOrEmpty()) {
                                    val declarations = req.tools.joinToString("") { tool ->
                                        if (tool.type == "function") {
                                            val paramsStr = tool.function.parameters?.toString() ?: "{}"
                                            val desc = tool.function.description?.let { "description: \"$it\", " } ?: ""
                                            "<|tool>declaration:{\"name\": \"${tool.function.name}\", ${desc}\"parameters\": $paramsStr}</|tool>\n"
                                        } else ""
                                    }
                                    toolSystemPrompt = "You are a helpful AI assistant running locally on an Android device powered by Google's Gemma multimodal LLM via LiteRT. You have access to the following tools:\n$declarations"
                                }

                                // 🚨 强行洗脑：不管之前聊过什么，收到新请求一律清空历史
                                engine.clearHistory(toolSystemPrompt)
                                
                                // 手动组装 nanobot 传来的所有对话历史
                                val conversationHistoryText = buildString {
                                    for (msg in req.messages) {
                                        if (msg.role == "system") continue 
                                        val content = msg.content ?: ""
                                        if (content.isNotEmpty()) {
                                            append("${msg.role}:\n${content}\n\n")
                                        }
                                    }
                                }

                                val prompt = conversationHistoryText
                                Log.d(TAG, "Assembled history prompt (${prompt.length} chars)")

                                if (req.stream) {
                                    val reqId = "chatcmpl-${System.currentTimeMillis()}"
                                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                                        try {
                                            // 首个 chunk 携带 role
                                            val firstChunk = OaiStreamChunk(
                                                id = reqId,
                                                created = System.currentTimeMillis() / 1000,
                                                model = "gemma-4-e2b",
                                                choices = listOf(
                                                    OaiStreamChoice(
                                                        index = 0,
                                                        delta = OaiDelta(role = "assistant", content = "")
                                                    )
                                                )
                                            )
                                            write("data: ${json.encodeToString(firstChunk)}\n\n")
                                            flush()

                                            // engine 内部 mutex 已保证串行，此处直接 collect
                                            engine.generateText(prompt).collect { token ->
                                                val chunk = OaiStreamChunk(
                                                    id = reqId,
                                                    created = System.currentTimeMillis() / 1000,
                                                    model = "gemma-4-e2b",
                                                    choices = listOf(
                                                        OaiStreamChoice(
                                                            index = 0,
                                                            delta = OaiDelta(content = token)
                                                        )
                                                    )
                                                )
                                                write("data: ${json.encodeToString(chunk)}\n\n")
                                                flush()
                                            }

                                            // 结束 chunk
                                            val stopChunk = OaiStreamChunk(
                                                id = reqId,
                                                created = System.currentTimeMillis() / 1000,
                                                model = "gemma-4-e2b",
                                                choices = listOf(
                                                    OaiStreamChoice(
                                                        index = 0,
                                                        delta = OaiDelta(),
                                                        finishReason = "stop"
                                                    )
                                                )
                                            )
                                            write("data: ${json.encodeToString(stopChunk)}\n\n")
                                            write("data: [DONE]\n\n")
                                            flush()

                                            val ms = System.currentTimeMillis() - start
                                            Log.d(TAG, "SSE stream completed in ${ms}ms")
                                            onRequest(
                                                RequestLogEntry(
                                                    endpoint = "/v1/chat/completions",
                                                    responseTimeMs = ms,
                                                    statusCode = 200
                                                )
                                            )
                                        } catch (e: Exception) {
                                            // 客户端断开或 engine 异常，静默处理避免崩溃
                                            Log.e(TAG, "SSE stream error (client may have disconnected)", e)
                                            try {
                                                write("data: [DONE]\n\n")
                                                flush()
                                            } catch (_: Exception) {
                                                // 连接已断，忽略
                                            }
                                        }
                                    }
                                } else {
                                    val tokens = try {
                                        engine.generateText(prompt).toList()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "generateText failed (non-stream)", e)
                                        call.respond(
                                            HttpStatusCode.InternalServerError,
                                            ErrorResponse("Generation failed: ${e.message}", 500)
                                        )
                                        return@post
                                    }

                                    val content = tokens.joinToString("")
                                    
                                    var finishReason = "stop"
                                    var finalContent: String? = content
                                    var toolCallsList: List<com.litert.server.data.OaiToolCall>? = null

                                    if (content.contains("<|tool_call>")) {
                                        try {
                                            val jsonString = content
                                                .substringAfter("<|tool_call>call:")
                                                .substringBefore("</")
                                                .trim()
                                                .replace("\"\"}", "\"}")
                                                .replace("\"\",", "\",")
                                            
                                            val parsedJson = org.json.JSONObject(jsonString)
                                            val functionName = parsedJson.getString("name")
                                            val functionArgs = if (parsedJson.has("parameters")) parsedJson.getJSONObject("parameters").toString() else "{}"
                                            
                                            finishReason = "tool_calls"
                                            finalContent = null
                                            toolCallsList = listOf(
                                                com.litert.server.data.OaiToolCall(
                                                    id = "call_${System.currentTimeMillis()}",
                                                    type = "function",
                                                    function = com.litert.server.data.OaiToolCallFunction(
                                                        name = functionName,
                                                        arguments = functionArgs
                                                    )
                                                )
                                            )
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to parse tool call from content: $content", e)
                                        }
                                    }

                                    val ms = System.currentTimeMillis() - start
                                    Log.d(TAG, "Non-stream generation completed in ${ms}ms, ${tokens.size} tokens")
                                    onRequest(
                                        RequestLogEntry(
                                            endpoint = "/v1/chat/completions",
                                            responseTimeMs = ms,
                                            statusCode = 200
                                        )
                                    )
                                    call.respond(
                                        OaiChatResponse(
                                            id = "chatcmpl-${System.currentTimeMillis()}",
                                            created = System.currentTimeMillis() / 1000,
                                            model = "gemma-4-e2b",
                                            choices = listOf(
                                                OaiChoice(
                                                    index = 0,
                                                    message = OaiMessage(
                                                        role = "assistant",
                                                        content = finalContent,
                                                        toolCalls = toolCallsList
                                                    ),
                                                    finishReason = finishReason
                                                )
                                            )
                                        )
                                    )
                                }
                            }
                        }

                        // ── Legacy routes ────────────────────────────────────
                        post("/chat") {
                            if (!engine.isReady) {
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    ErrorResponse("Engine not ready", 503)
                                )
                                return@post
                            }

                            val start = System.currentTimeMillis()
                            val req = try {
                                call.receive<ChatRequest>()
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse ChatRequest", e)
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse("Invalid request body: ${e.message}", 400)
                                )
                                return@post
                            }

                            val tokens = try {
                                engine.generateText(req.message).toList()
                            } catch (e: Exception) {
                                Log.e(TAG, "generateText failed in /chat", e)
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    ErrorResponse("Generation failed: ${e.message}", 500)
                                )
                                return@post
                            }

                            val response = tokens.joinToString("")
                            val ms = System.currentTimeMillis() - start
                            onRequest(
                                RequestLogEntry(
                                    endpoint = "/chat",
                                    responseTimeMs = ms,
                                    statusCode = 200
                                )
                            )
                            call.respond(
                                ChatResponse(response = response, tokens = tokens.size, ms = ms)
                            )
                        }

                        post("/vision") {
                            if (!engine.isReady) {
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    ErrorResponse("Engine not ready", 503)
                                )
                                return@post
                            }

                            val start = System.currentTimeMillis()
                            val req = try {
                                call.receive<VisionRequest>()
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse VisionRequest", e)
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse("Invalid request body: ${e.message}", 400)
                                )
                                return@post
                            }

                            val tokens = try {
                                engine.analyzeImage(req.imagePath, req.prompt).toList()
                            } catch (e: Exception) {
                                Log.e(TAG, "analyzeImage failed in /vision", e)
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    ErrorResponse("Vision analysis failed: ${e.message}", 500)
                                )
                                return@post
                            }

                            val response = tokens.joinToString("")
                            val ms = System.currentTimeMillis() - start
                            onRequest(
                                RequestLogEntry(
                                    endpoint = "/vision",
                                    responseTimeMs = ms,
                                    statusCode = 200
                                )
                            )
                            call.respond(
                                ChatResponse(response = response, tokens = tokens.size, ms = ms)
                            )
                        }

                        post("/reset") {
                            try {
                                // clearHistory 现在是 suspend fun，内部带 mutex，等待生成完成后再重置
                                engine.clearHistory()
                            } catch (e: Exception) {
                                Log.e(TAG, "clearHistory failed", e)
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    ErrorResponse("Reset failed: ${e.message}", 500)
                                )
                                return@post
                            }
                            onRequest(
                                RequestLogEntry(
                                    endpoint = "/reset",
                                    responseTimeMs = 0,
                                    statusCode = 200
                                )
                            )
                            call.respond(mapOf("status" to "conversation cleared"))
                        }
                    }
                }
                server!!.start(wait = false)
                port = tryPort
                Log.i(TAG, "Server started on port $tryPort")
                return tryPort
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind port $tryPort", e)
                if (tryPort == 8082) throw e
            }
        }
        throw IllegalStateException("Could not bind to any port (8080-8082)")
    }

    fun stop() {
        server?.stop(1000, 5000)
        server = null
        Log.i(TAG, "Server stopped")
    }
}