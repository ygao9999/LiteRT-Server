package com.litert.client.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_document_chunks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE // 文档物理删除时，自动级联删除旗下所有段落块
        )
    ],
    indices = [Index(value = ["documentId"])]
)
data class DocumentChunkEntity(
    @PrimaryKey
    val id: String,
    val documentId: String,
    val chunkIndex: Int,
    val content: String // 该切片的小段落文字内容
)
