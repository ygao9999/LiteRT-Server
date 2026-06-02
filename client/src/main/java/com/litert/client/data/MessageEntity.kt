package com.litert.client.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long,
    val citationSource: String? = null // 关联的知识库引用的文件名
)
