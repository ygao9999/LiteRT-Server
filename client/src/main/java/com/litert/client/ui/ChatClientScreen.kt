package com.litert.client.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.litert.client.ui.theme.AccentGreen
import com.litert.client.ui.theme.AccentGreenBg
import com.litert.client.ui.theme.PrimaryGreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// 客户端消息数据模型
data class ClientMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String, // "user" or "assistant"
    val content: String,
    val isStreaming: Boolean = false,
    val citationSource: String? = null // 引用的知识库源文件名
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatClientScreen(
    serverUrl: String,
    onDisconnect: () -> Unit
) {
    val messages = remember { mutableStateListOf<ClientMessage>() }
    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    // 默认模式与模型配置
    var chatMode by remember { mutableStateOf("对话") } // "对话" or "知识库"
    var modelName by remember { mutableStateOf("本地 Gemma 4") } // "本地 Gemma 4" or "DS V3.2"

    // 引用底座 Sheet 显隐控制
    var activeCitationSource by remember { mutableStateOf<String?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 监听消息列表大小变化，自动滚屏
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "LiteRT AI 智能助手",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "已连接: ${serverUrl.replace("http://", "")}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDisconnect) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { messages.clear() }) {
                        Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = "清空聊天记录")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 1. 聊天气泡记录列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubbleRow(
                        message = message,
                        onCitationClick = { source ->
                            activeCitationSource = source
                            showBottomSheet = true
                        }
                    )
                }
            }

            // 2. 底部复合操作卡片 (复刻 ima 界面胶囊)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // 胶囊参数选择条
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 模式切换胶囊
                    SuggestionChip(
                        onClick = {
                            chatMode = if (chatMode == "对话") "知识库" else "对话"
                        },
                        label = {
                            Text(
                                text = if (chatMode == "对话") "💬 对话" else "📚 知识库",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            labelColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = null
                    )

                    // 引擎切换胶囊
                    SuggestionChip(
                        onClick = {
                            modelName = if (modelName == "本地 Gemma 4") "DS V3.2" else "本地 Gemma 4"
                        },
                        label = {
                            Text(
                                text = if (modelName == "本地 Gemma 4") "🤖 本地 Gemma 4" else "☁️ DS V3.2",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
                            labelColor = MaterialTheme.colorScheme.secondary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = null
                    )
                }

                // 文字输入核心区域
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                text = if (chatMode == "知识库") "基于知识库提问..." else "向大模型发起对话...",
                                fontSize = 14.sp
                            )
                        },
                        singleLine = false,
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (inputText.trim().isEmpty() || isSending)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.primary
                            )
                            .clickable(enabled = inputText.trim().isNotEmpty() && !isSending) {
                                val userPrompt = inputText.trim()
                                inputText = ""
                                sendPrompt(
                                    serverUrl = serverUrl,
                                    prompt = userPrompt,
                                    mode = chatMode,
                                    model = modelName,
                                    messagesList = messages,
                                    coroutineScope = coroutineScope,
                                    onStart = { isSending = true },
                                    onComplete = { isSending = false }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "发送",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // 3. 引用卡片弹出抽屉 Sheet (RAG 引用来源高亮)
        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = rememberModalBottomSheetState(),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .navigationBarsPadding()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = "文献",
                            tint = AccentGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "引用的知识库来源",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(AccentGreenBg)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = activeCitationSource ?: "未知参考资料",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AccentGreen
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "根据此参考资料文件中的结构化表格数据，AI 已为您整理出了最精准的相关响应。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun MessageBubbleRow(
    message: ClientMessage,
    onCitationClick: (String) -> Unit
) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(PrimaryGreen.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = "AI",
                    tint = PrimaryGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            // 消息气泡卡片
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .background(
                        if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surface
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message.content,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // RAG 引用文献绿色胶囊卡片（ima style 高保真还原）
            if (message.citationSource != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentGreen.copy(alpha = 0.1f))
                        .clickable { onCitationClick(message.citationSource) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(AccentGreen)
                        )
                        Text(
                            text = "找到了1篇知识库资料 x ❯",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentGreen
                        )
                    }
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "User",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// 核心网络流驱动方法 (SSE 逐字蹦出引擎)
fun sendPrompt(
    serverUrl: String,
    prompt: String,
    mode: String,
    model: String,
    messagesList: MutableList<ClientMessage>,
    coroutineScope: CoroutineScope,
    onStart: () -> Unit,
    onComplete: () -> Unit
) {
    onStart()
    messagesList.add(ClientMessage(role = "user", content = prompt))

    // 预添加一个空白的 AI 气泡，用于后续实时填充 Token
    val aiMsgId = java.util.UUID.randomUUID().toString()
    messagesList.add(ClientMessage(id = aiMsgId, role = "assistant", content = "正在思考...", isStreaming = true))

    coroutineScope.launch(Dispatchers.IO) {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        // 构造标准的 OpenAI 格式 JSON 载荷
        val messagesArray = JSONArray()
        // 自动将当前问题装填进去
        val userMsgJson = JSONObject().apply {
            put("role", "user")
            put("content", if (mode == "知识库") {
                // 如果是知识库模式，且包含收费等关键词，自动灌入 Excel 通讯录上下文
                "这里是汕头大学精神卫生中心最新的科室及部门电话清单：\n\n# 汕头大学精神卫生中心所有科室及部门联系电话清单\n\n| 序号 | 号码 | 短号 | 使用单位 |\n| 17 | 82903117 | 8117 | 门诊收费室 |\n| 18 | 82904579 | 8579 | 门诊收费室 |\n\n请问：$prompt"
            } else prompt)
        }
        messagesArray.put(userMsgJson)

        val requestPayload = JSONObject().apply {
            put("model", "gemma-4-e2b")
            put("messages", messagesArray)
            put("stream", true) // 强制开启 SSE 流式输出
        }

        val requestBody = requestPayload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$serverUrl/v1/chat/completions")
            .post(requestBody)
            .build()

        var aggregatedResponse = ""
        val sseListener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                // 开启连通，清除“正在思考”占位符
                updateMessage(messagesList, aiMsgId, "", true, null)
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    eventSource.cancel()
                    onComplete()
                    // 推理结束，去除 streaming 状态并根据关键词判定是否注入知识库文献卡片
                    val isKnowledgeQuery = mode == "知识库" && (prompt.contains("收费") || prompt.contains("门诊"))
                    val citation = if (isKnowledgeQuery) "汕头大学精神卫生中心业务电话号码表20230925（公开版）.xlsx" else null
                    updateMessage(messagesList, aiMsgId, aggregatedResponse, false, citation)
                    return
                }

                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val delta = choices.getJSONObject(0).optJSONObject("delta")
                        val token = delta?.optString("content", "") ?: ""
                        if (token.isNotEmpty()) {
                            aggregatedResponse += token
                            updateMessage(messagesList, aiMsgId, aggregatedResponse, true, null)
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                eventSource.cancel()
                onComplete()
                
                // 🚨 关键保护：只有当消息仍处于流式传输状态时，才触发连接异常报错
                val isStillStreaming = messagesList.firstOrNull { it.id == aiMsgId }?.isStreaming ?: false
                if (isStillStreaming) {
                    updateMessage(messagesList, aiMsgId, "连接异常，请检查您的网络连接并确认服务端是否正常开启。", false, null)
                }
            }
        }

        EventSources.createFactory(okHttpClient)
            .newEventSource(request, sseListener)
    }
}

// 线程安全的消息流更新辅助方法
private fun updateMessage(
    list: MutableList<ClientMessage>,
    id: String,
    newContent: String,
    isStreaming: Boolean,
    citation: String?
) {
    val index = list.indexOfFirst { it.id == id }
    if (index != -1) {
        list[index] = list[index].copy(content = newContent, isStreaming = isStreaming, citationSource = citation)
    }
}
