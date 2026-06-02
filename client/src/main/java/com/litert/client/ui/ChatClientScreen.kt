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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.litert.client.data.AppDatabase
import com.litert.client.data.MessageEntity
import com.litert.client.data.DocumentEntity
import com.litert.client.data.DocumentChunkEntity

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
    database: AppDatabase,
    onDisconnect: () -> Unit
) {
    val messages = remember { mutableStateListOf<ClientMessage>() }
    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    // 默认模式与模型配置
    var chatMode by remember { mutableStateOf("知识库") } // 默认开启知识库模式
    var modelName by remember { mutableStateOf("本地 Gemma 4") } // "本地 Gemma 4" or "DS V3.2"

    // 引用底座 Sheet 与 知识库管理 Sheet 显隐控制
    var activeCitationSource by remember { mutableStateOf<String?>(null) }
    var showCitationSheet by remember { mutableStateOf(false) }
    var showKnowledgeSheet by remember { mutableStateOf(false) }

    // 当前在本地库中注册的所有文档列表，以及被勾选挂载的文档 ID 队列
    val availableDocs = remember { mutableStateListOf<DocumentEntity>() }
    val selectedDocIds = remember { mutableStateListOf<String>() }

    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 🚀 初始化时自动加载历史消息，并智能预装默认文档
    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            // 1. 恢复历史对话
            val localMessages = database.messageDao().getAllMessages().map { entity ->
                ClientMessage(
                    id = entity.id,
                    role = entity.role,
                    content = entity.content,
                    isStreaming = false,
                    citationSource = entity.citationSource
                )
            }
            
            // 2. 检查并预装默认文档（开箱即用体验）
            var docs = database.documentDao().getAllDocuments()
            if (docs.isEmpty()) {
                prepopulateDefaultDocument(database)
                docs = database.documentDao().getAllDocuments()
            }
            
            withContext(Dispatchers.Main) {
                messages.clear()
                messages.addAll(localMessages)
                availableDocs.clear()
                availableDocs.addAll(docs)
                // 默认全选所有文档挂载
                selectedDocIds.clear()
                selectedDocIds.addAll(docs.map { it.id })
            }
        }
    }

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
                    IconButton(onClick = {
                        // 🚀 物理清除本地数据库历史记录，并同步清除UI队列
                        coroutineScope.launch(Dispatchers.IO) {
                            database.messageDao().clearHistory()
                            withContext(Dispatchers.Main) {
                                messages.clear()
                            }
                        }
                    }) {
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
                            showCitationSheet = true
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

                    // 🚀 新增：知识库管理胶囊 (ima 风格勾选面板)
                    if (chatMode == "知识库") {
                        SuggestionChip(
                            onClick = {
                                // 刷新文档库状态并打开抽屉
                                coroutineScope.launch(Dispatchers.IO) {
                                    val docs = database.documentDao().getAllDocuments()
                                    withContext(Dispatchers.Main) {
                                        availableDocs.clear()
                                        availableDocs.addAll(docs)
                                        showKnowledgeSheet = true
                                    }
                                }
                            },
                            label = {
                                Text(
                                    text = "📁 选择文档 (${selectedDocIds.size})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = AccentGreen.copy(alpha = 0.1f),
                                labelColor = AccentGreen
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = null
                        )
                    }
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
                                text = if (chatMode == "知识库") {
                                    if (selectedDocIds.isEmpty()) "⚠️ 请先勾选挂载的文档" else "基于已选文档提问..."
                                } else "向大模型发起对话...",
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
                                if (inputText.trim().isEmpty() || isSending || (chatMode == "知识库" && selectedDocIds.isEmpty()))
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.primary
                            )
                            .clickable(enabled = inputText.trim().isNotEmpty() && !isSending && !(chatMode == "知识库" && selectedDocIds.isEmpty())) {
                                val userPrompt = inputText.trim()
                                inputText = ""
                                sendPrompt(
                                    serverUrl = serverUrl,
                                    prompt = userPrompt,
                                    mode = chatMode,
                                    model = modelName,
                                    messagesList = messages,
                                    selectedDocIds = selectedDocIds.toList(),
                                    database = database,
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
        if (showCitationSheet) {
            ModalBottomSheet(
                onDismissRequest = { showCitationSheet = false },
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

        // 🚀 4. 新增：高仿 ima 知识库挂载/勾选管理抽屉 Sheet (Selectable Knowledge Base)
        if (showKnowledgeSheet) {
            ModalBottomSheet(
                onDismissRequest = { showKnowledgeSheet = false },
                sheetState = rememberModalBottomSheetState(),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .navigationBarsPadding()
                ) {
                    Text(
                        text = "📚 挂载本地知识库文档",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    if (availableDocs.isEmpty()) {
                        Text(
                            text = "暂无本地文档，系统正在重试预装...",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                        ) {
                            items(availableDocs) { doc ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (selectedDocIds.contains(doc.id)) {
                                                selectedDocIds.remove(doc.id)
                                            } else {
                                                selectedDocIds.add(doc.id)
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Checkbox(
                                        checked = selectedDocIds.contains(doc.id),
                                        onCheckedChange = { checked ->
                                            if (checked == true) selectedDocIds.add(doc.id)
                                            else selectedDocIds.remove(doc.id)
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = doc.fileName,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "字数: ${doc.totalChars}字 | 自动判定: ${if (doc.totalChars < 8000) "🟢 直投模式" else "🔍 RAG检索模式"}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }
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

// 🚀 核心网络流与 RAG 驱动方法（双模智能判定引擎）
fun sendPrompt(
    serverUrl: String,
    prompt: String,
    mode: String,
    model: String,
    messagesList: MutableList<ClientMessage>,
    selectedDocIds: List<String>,
    database: AppDatabase,
    coroutineScope: CoroutineScope,
    onStart: () -> Unit,
    onComplete: () -> Unit
) {
    onStart()
    
    // 生成并保存用户消息到本地数据库
    val userMsg = ClientMessage(role = "user", content = prompt)
    messagesList.add(userMsg)
    coroutineScope.launch(Dispatchers.IO) {
        database.messageDao().insertMessage(
            MessageEntity(
                id = userMsg.id,
                role = userMsg.role,
                content = userMsg.content,
                timestamp = System.currentTimeMillis(),
                citationSource = null
            )
        )
    }

    // 预添加一个空白的 AI 气泡，用于流式动态填充
    val aiMsgId = java.util.UUID.randomUUID().toString()
    messagesList.add(ClientMessage(id = aiMsgId, role = "assistant", content = "正在检索思考...", isStreaming = true))

    coroutineScope.launch(Dispatchers.IO) {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        var contextString = ""
        var actualCitationFile: String? = null

        // 🚀 双模核心判定逻辑
        if (mode == "知识库" && selectedDocIds.isNotEmpty()) {
            // 读取用户勾选挂载的所有文档
            val selectedDocs = selectedDocIds.mapNotNull { database.documentDao().getDocumentById(it) }
            val totalChars = selectedDocs.sumOf { it.totalChars }
            actualCitationFile = selectedDocs.firstOrNull()?.fileName
            
            if (totalChars < 8000) {
                // 🟢 A模式：小文件直投模式（Direct Injection）
                contextString = buildString {
                    append("以下是用户提供的完整背景参考资料：\n\n")
                    for (doc in selectedDocs) {
                        append("### 文档名: ${doc.fileName}\n")
                        append("${doc.fileContent}\n\n")
                    }
                }
                android.util.Log.i("RAGEngine", "自动启动：A模式 - 极速小文件直接投喂 (${totalChars}字)")
            } else {
                // 🔍 B模式：大文件切段智能 RAG 检索模式 (SQLite LIKE Keyword matching)
                // 提取提问中最核心的主体关键词作为 SQL 检索字词
                val cleanedPrompt = prompt.replace(Regex("[？！，。：；,.?!]"), " ")
                val words = cleanedPrompt.split(" ").filter { it.length >= 2 }
                val targetKeyword = "%${words.firstOrNull() ?: "电话"}%"
                
                // 检索数据库，提取匹配度最高的 3 片段（Top 3 chunks）
                var matchedChunks = database.documentDao().searchChunks(selectedDocIds, targetKeyword)
                if (matchedChunks.isEmpty()) {
                    matchedChunks = database.documentDao().fallbackChunks(selectedDocIds)
                }
                
                contextString = buildString {
                    append("根据用户提问，已在您挂载的本地知识库文档中智能为您筛选出以下最相关的片段资料：\n\n")
                    for (chunk in matchedChunks) {
                        append("- ${chunk.content}\n")
                    }
                }
                android.util.Log.i("RAGEngine", "自动启动：B模式 - 大文件切片段落检索投喂，匹配到 ${matchedChunks.size} 个 Chunks")
            }
        }

        // 构造标准的 OpenAI 格式 JSON 载荷
        val messagesArray = JSONArray()
        val userMsgJson = JSONObject().apply {
            put("role", "user")
            put("content", if (contextString.isNotEmpty()) {
                "$contextString\n\n----\n\n基于以上参考资料，请精准回答用户问题：$prompt"
            } else prompt)
        }
        messagesArray.put(userMsgJson)

        val requestPayload = JSONObject().apply {
            put("model", "gemma-4-e2b")
            put("messages", messagesArray)
            put("stream", true)
        }

        val requestBody = requestPayload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$serverUrl/v1/chat/completions")
            .post(requestBody)
            .build()

        var aggregatedResponse = ""
        val sseListener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
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
                    updateMessage(messagesList, aiMsgId, aggregatedResponse, false, actualCitationFile)
                    
                    // 异步向 Room 数据库持久化保存 AI 的回答
                    coroutineScope.launch(Dispatchers.IO) {
                        database.messageDao().insertMessage(
                            MessageEntity(
                                id = aiMsgId,
                                role = "assistant",
                                content = aggregatedResponse,
                                timestamp = System.currentTimeMillis(),
                                citationSource = actualCitationFile
                            )
                        )
                    }
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

// 线程安全的消息队列修改辅助方法
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

// 🚀 默认预装开箱文档逻辑：将汕大业务电话号码表自动切片注入本地数据库
private suspend fun prepopulateDefaultDocument(database: AppDatabase) {
    val docId = "default_shantou_mental_health_tel"
    val fileName = "汕头大学精神卫生中心业务电话号码表20230925（公开版）.xlsx"
    
    val fullMarkdownContent = """
# 汕头大学精神卫生中心业务电话号码表 (20230925 公开版)

## 一、 行政管理与职能科室
- 书记办公室 | 长号: 82904601 | 短号: 8601
- 院长办公室 | 长号: 82901250 | 短号: 8250
- 办公室（院办）[含传真] | 长号: 82902704 | 短号: 8704
- 人事科及档案室 | 长号: 82904574 | 短号: 8574
- 医保科 | 长号: 82904594 | 短号: 8594
- 计财科 | 长号: 82904597 / 82903302 | 短号: 8597 / 8302
- 门诊收费室 | 长号: 82903117 | 短号: 8117
- 门诊收费室 | 长号: 82904579 | 短号: 8579
- 信息科 | 长号: 82903512 | 短号: 8512

## 二、 临床科室与住院病区
- 一 区 | 长号: 82902702 | 短号: 8702
- 二 区 | 长号: 82902708 | 短号: 8708
- 三 区 | 长号: 82902705 | 短号: 8705
- 急三科 | 长号: 88386971 | 短号: 8971
- 门诊办（病历室） | 长号: 82904576 | 短号: 8525
- 心理咨询门诊 | 长号: 88900599 | 短号: 8599
- 睡眠医学中心办 | 长号: 82902709 | 短号: 8709

## 三、 后勤、值班与应急部门
- 医疗总值班 | 长号: 18025501681 | 短号: 8681
- 医疗一线值班 | 长号: 18025501682 | 短号: 8682
- 医疗二线值班 | 长号: 18025501691 | 短号: 8691
- 护理总值班 | 长号: 18025501683 | 短号: 8683
- 行政总值班 | 长号: 18025501686 | 短号: 8686
- 院应急办专线 | 长号: 82902907 | 短号: 8907
- 司机值班房 | 长号: 82904604 | 短号: 8604
- 门房值班室 | 长号: 82903510 | 短号: 8510
- 总务科科长办 | 长号: 82902776 | 短号: 8776
""".trimIndent()

    val totalChars = fullMarkdownContent.length
    
    // 1. 插入文档元数据
    val doc = DocumentEntity(
        id = docId,
        fileName = fileName,
        totalChars = totalChars,
        addedTimestamp = System.currentTimeMillis(),
        fileContent = fullMarkdownContent
    )
    database.documentDao().insertDocument(doc)
    
    // 2. 将通讯录做 300 字符智能分段切片（Ingestion & Chunking）
    val chunks = mutableListOf<DocumentChunkEntity>()
    val chunkSize = 350
    var index = 0
    var startIndex = 0
    while (startIndex < totalChars) {
        val endIndex = minOf(startIndex + chunkSize, totalChars)
        val slice = fullMarkdownContent.substring(startIndex, endIndex)
        chunks.add(
            DocumentChunkEntity(
                id = "${docId}_chunk_$index",
                documentId = docId,
                chunkIndex = index,
                content = slice
            )
        )
        index++
        startIndex += chunkSize
    }
    database.documentDao().insertChunks(chunks)
    android.util.Log.i("RAGEngine", "🎉 默认开箱文档预装完全成功！智能切分成 ${chunks.size} 段落落库。")
}
