package com.litert.client.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DocumentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<DocumentChunkEntity>)

    @Query("SELECT * FROM chat_documents ORDER BY addedTimestamp DESC")
    suspend fun getAllDocuments(): List<DocumentEntity>

    @Query("SELECT * FROM chat_documents WHERE id = :id")
    suspend fun getDocumentById(id: String): DocumentEntity?

    @Query("DELETE FROM chat_documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    // 🚀 精准内网 RAG 核心检索：优化嵌套 OR 子句，对旧版 Kapt 编译器提供 100% 稳定的语法树解析兼容性
    @Query("SELECT * FROM chat_document_chunks WHERE documentId IN (:docIds) AND content LIKE :keyword LIMIT 3")
    suspend fun searchChunks(docIds: List<String>, keyword: String): List<DocumentChunkEntity>

    // 备用通用段落提取（如果关键字不匹配，自动取最前面的段落保底）
    @Query("SELECT * FROM chat_document_chunks WHERE documentId IN (:docIds) LIMIT 3")
    suspend fun fallbackChunks(docIds: List<String>): List<DocumentChunkEntity>
}
