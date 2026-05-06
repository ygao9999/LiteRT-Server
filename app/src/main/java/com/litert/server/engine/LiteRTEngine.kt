package com.litert.server.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LiteRTEngine(private val context: Context) {

    companion object {
        private const val TAG = "LiteRTEngine"
    }

    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    var currentSystemPrompt: String? = null
        private set
    private var currentBackend: String = "GPU"
    private var currentSamplerConfig: SamplerConfig = SamplerConfig(
        topK = 40,
        topP = 0.9,
        temperature = 0.7
    )

    // 保证同一时刻只有一个操作访问 conversation
    private val conversationMutex = Mutex()

    var isReady = false
        private set

    suspend fun initialize(
        modelPath: String,
        useGpu: Boolean = true,
        temperature: Double = 0.7,
        maxTokens: Int = 1024,
        topK: Int = 40,
        topP: Double = 0.9
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val backend = if (useGpu) Backend.GPU() else Backend.CPU()
                val visionBackend = if (useGpu) Backend.GPU() else Backend.CPU()

                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = visionBackend,
                    cacheDir = context.cacheDir.absolutePath
                )
                val newEngine = Engine(config)
                newEngine.initialize()

                currentSamplerConfig = SamplerConfig(
                    topK = topK,
                    topP = topP,
                    temperature = temperature
                )
                val conv = createNewConversation(newEngine, currentSamplerConfig, currentSystemPrompt)

                // initialize 时也要加锁，防止和 clearHistory 竞争
                conversationMutex.withLock {
                    engine = newEngine
                    conversation = conv
                    currentBackend = if (useGpu) "GPU" else "CPU"
                    isReady = true
                }
                Log.i(TAG, "Engine initialized with $currentBackend backend")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize with ${if (useGpu) "GPU" else "CPU"} backend", e)
                if (useGpu) {
                    Log.w(TAG, "Falling back to CPU backend...")
                    initialize(modelPath, useGpu = false, temperature, maxTokens, topK, topP)
                } else {
                    isReady = false
                    false
                }
            }
        }
    }

    private fun createNewConversation(
        eng: Engine,
        samplerConfig: SamplerConfig,
        systemPrompt: String? = null
    ): com.google.ai.edge.litertlm.Conversation {
        val instructionText = systemPrompt ?: (
            "You are a helpful AI assistant running locally on an Android device " +
            "powered by Google's Gemma multimodal LLM via LiteRT."
        )

        return eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(
                    Content.Text(instructionText)
                ),
                samplerConfig = samplerConfig
            )
        )
    }

    /**
     * 生成文本。
     * 用 flow + mutex 包裹整个生成过程，保证：
     * 1. 同一时刻只有一个 sendMessageAsync 在执行
     * 2. clearHistory 不会在生成中途关闭 conversation
     */
    fun generateText(prompt: String): Flow<String> = flow {
        conversationMutex.withLock {
            val conv = conversation
                ?: throw IllegalStateException("Engine not initialized")
            conv.sendMessageAsync(prompt)
                .map { it.toString() }
                .collect { emit(it) }
        }
    }

    fun analyzeImage(imagePath: String, prompt: String): Flow<String> = flow {
        conversationMutex.withLock {
            val conv = conversation
                ?: throw IllegalStateException("Engine not initialized")
            val contents = Contents.of(
                Content.ImageFile(imagePath),
                Content.Text(prompt)
            )
            conv.sendMessageAsync(contents)
                .map { it.toString() }
                .collect { emit(it) }
        }
    }

    /**
     * 清除对话历史。
     * 支持传入新的 system prompt，如果传入了，更新它。
     * 等待当前生成完成后再重建 conversation，不会截断进行中的输出。
     */
    suspend fun clearHistory(newSystemPrompt: String? = null) {
        conversationMutex.withLock {
            val eng = engine ?: return@withLock
            
            if (newSystemPrompt != null) {
                currentSystemPrompt = newSystemPrompt
            }
            
            conversation?.close()
            conversation = createNewConversation(eng, currentSamplerConfig, currentSystemPrompt)
            Log.i(TAG, "Conversation history cleared with ${if(currentSystemPrompt != null) "custom" else "default"} system prompt")
        }
    }

    fun getBackend(): String = currentBackend

    suspend fun shutdown() {
        conversationMutex.withLock {
            isReady = false
            conversation?.close()
            conversation = null
            engine?.close()
            engine = null
        }
    }
}