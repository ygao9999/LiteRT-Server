package com.litert.client.data

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader

object DocumentImporter {
    
    fun getFileName(context: Context, uri: Uri): String {
        var name = "unnamed.txt"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DocumentImporter", "Failed to query filename", e)
        }
        return name
    }

    fun readTextFromUri(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            } ?: ""
        } catch (e: Exception) {
            android.util.Log.e("DocumentImporter", "Failed to read content", e)
            ""
        }
    }

    suspend fun importDocument(
        context: Context,
        uri: Uri,
        database: AppDatabase
    ): DocumentEntity? {
        val fileName = getFileName(context, uri)
        val content = readTextFromUri(context, uri)
        if (content.isBlank()) return null

        val docId = java.util.UUID.randomUUID().toString()
        val totalChars = content.length

        // 1. 写入文档元数据
        val doc = DocumentEntity(
            id = docId,
            fileName = fileName,
            totalChars = totalChars,
            addedTimestamp = System.currentTimeMillis(),
            fileContent = content
        )
        database.documentDao().insertDocument(doc)

        // 2. 将内容按照 350 字大小自动做智能分段切片
        val chunks = mutableListOf<DocumentChunkEntity>()
        val chunkSize = 350
        var index = 0
        var startIndex = 0
        while (startIndex < totalChars) {
            val endIndex = minOf(startIndex + chunkSize, totalChars)
            val slice = content.substring(startIndex, endIndex)
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
        android.util.Log.i("DocumentImporter", "🎉 导入自定义文档完全成功！智能切分成 ${chunks.size} 段落落库。")
        return doc
    }
}
