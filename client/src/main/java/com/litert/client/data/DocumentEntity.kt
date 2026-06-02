package com.litert.client.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_documents")
data class DocumentEntity(
    @PrimaryKey
    val id: String,
    val fileName: String,
    val totalChars: Int,
    val addedTimestamp: Long,
    val fileContent: String // 用于直投模式直接读取全文
)
