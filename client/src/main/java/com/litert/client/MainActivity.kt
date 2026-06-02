package com.litert.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.litert.client.data.AppDatabase
import com.litert.client.ui.ChatClientScreen
import com.litert.client.ui.theme.LiteRTClientTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.getDatabase(this)
        
        setContent {
            LiteRTClientTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var serverUrl by remember { mutableStateOf("192.168.20.126:8080") }
                    var isConnected by remember { mutableStateOf(false) }
                    var isConnecting by remember { mutableStateOf(false) }
                    var errorMessage by remember { mutableStateOf<String?>(null) }

                    AnimatedVisibility(
                        visible = !isConnected,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        ConnectionSetupScreen(
                            serverUrl = serverUrl,
                            isConnecting = isConnecting,
                            errorMessage = errorMessage,
                            onUrlChange = { serverUrl = it },
                            onConnect = {
                                verifyAndConnect(serverUrl) { success, msg ->
                                    isConnecting = false
                                    if (success) {
                                        isConnected = true
                                        errorMessage = null
                                    } else {
                                        errorMessage = msg
                                    }
                                }
                                isConnecting = true
                            }
                        )
                    }

                    AnimatedVisibility(
                        visible = isConnected,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        ChatClientScreen(
                            serverUrl = "http://$serverUrl",
                            database = database,
                            onDisconnect = { isConnected = false }
                        )
                    }
                }
            }
        }
    }

    private fun verifyAndConnect(url: String, callback: (Boolean, String?) -> Unit) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url("http://$url/health")
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            val json = JSONObject(body)
                            json.optBoolean("ready", false)
                        } else false
                    }
                } catch (e: Exception) {
                    false
                }
            }
            if (success) {
                callback(true, null)
            } else {
                callback(false, "无法连接到该服务地址，请确认服务端已启动且手机/电脑处于同一Wi-Fi网络。")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSetupScreen(
    serverUrl: String,
    isConnecting: Boolean,
    errorMessage: String?,
    onUrlChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = "Wifi",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "连接至 LiteRT 服务端",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "请确保您当前的设备与搭载大模型服务器的手机处于同一 Wi-Fi 网络下。",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = onUrlChange,
            label = { Text("服务端 IP与端口") },
            placeholder = { Text("例如 192.168.20.126:8080") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Lan,
                    contentDescription = "Server IP"
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = onConnect,
            enabled = !isConnecting && serverUrl.isNotEmpty(),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Text(
                    text = "开始建立连接",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}
