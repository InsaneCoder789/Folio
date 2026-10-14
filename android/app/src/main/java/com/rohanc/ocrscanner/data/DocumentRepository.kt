package com.rohanc.ocrscanner.data

import android.net.Uri
import androidx.room.withTransaction
import com.rohanc.ocrscanner.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** All edits and processing commits pass through this transactional boundary. */
class DocumentRepository(
    private val database: ScannerDatabase,
    private val images: PrivateImageStore,
    private val ocr: OcrEngine,
) {
    private val dao = database.scannerDao()
    val library: Flow<List<DocumentEntity>> = dao.observeLibrary()
    suspend fun snapshot(id: String) = dao.snapshot(id) ?: error("Document no longer exists.")
    suspend fun create(title: String = "Untitled scan"): DocumentSnapshot {
        val document = DocumentEntity(UUID.randomUUID().toString(), cleanTitle(title), updatedAt = System.currentTimeMillis())
        dao.insertDocument(document)
        return snapshot(document.id)
    }
    suspend fun rename(id: String, expectedRevision: Long, title: String) = database.withTransaction {
        val current = activeSnapshot(id)
        RevisionRules.requireCurrent(expectedRevision, current.document.revision)
        dao.updateDocument(current.document.copy(title = cleanTitle(title), revision = expectedRevision + 1, updatedAt = System.currentTimeMillis()))
    }
    suspend fun importImage(id: String, expectedRevision: Long, uri: Uri) {
        RevisionRules.requireCurrent(expectedRevision, activeSnapshot(id).document.revision)
        val capture = images.import(id, uri)
        var committed = false
        try {
            coroutineContext.ensureActive()
            database.withTransaction {
                val current = activeSnapshot(id)
                RevisionRules.requireCurrent(expectedRevision, current.document.revision)
                dao.insertCapture(capture)
                dao.insertRegion(RegionEntity(UUID.randomUUID().toString(), capture.id, id, current.regions.size))
                touch(current.document)
            }
            committed = true
        } finally {
            if (!committed) withContext(NonCancellable) { images.removeCapture(capture) }
        }
    }
    suspend fun recognize(id: String, regionId: String) {
        val source = activeSnapshot(id)
        val region = source.regions.single { it.id == regionId }
        val capture = source.captures.single { it.id == region.captureId }
        val input = ImageInput(capture.uprightPath, capture.width, capture.height, region.imageRevision)
        val result = ocr.recognize(input)
        coroutineContext.ensureActive()
        database.withTransaction {
            val current = activeSnapshot(id)
            val now = current.regions.singleOrNull { it.id == regionId } ?: throw StaleRevision()
            RevisionRules.requireCurrent(region.imageRevision, now.imageRevision)
            // A concurrent user text review must also survive an old OCR job.
            RevisionRules.requireCurrent(region.textRevision, now.textRevision)
            require(result.width == capture.width && result.height == capture.height) { "OCR geometry does not match the input image." }
            dao.updateRegion(now.copy(textRevision = now.textRevision + 1, ocrRevision = now.imageRevision, recognizedText = result.text,
                reviewedText = null, wordsJson = encodeWords(result.words)))
            touch(current.document)
        }
    }
    suspend fun saveReview(id: String, expectedRevision: Long, regionId: String, text: String) = database.withTransaction {
        val current = activeSnapshot(id)
        RevisionRules.requireCurrent(expectedRevision, current.document.revision)
        val region = current.regions.single { it.id == regionId }
        require(region.ocrRevision == region.imageRevision && region.recognizedText != null) { "Read this image before reviewing text." }
        dao.updateRegion(region.copy(reviewedText = text, textRevision = region.textRevision + 1))
        touch(current.document)
    }
    suspend fun reorder(id: String, expectedRevision: Long, order: List<String>) = database.withTransaction {
        val current = activeSnapshot(id)
        RevisionRules.requireCurrent(expectedRevision, current.document.revision)
        RevisionRules.reordered(order, current.regions.map { it.id })
        order.forEachIndexed { index, regionId ->
            dao.updateRegion(current.regions.single { it.id == regionId }.copy(position = index))
        }
        touch(current.document)
    }
    suspend fun delete(id: String, expectedRevision: Long) {
        database.withTransaction {
            val current = snapshot(id)
            RevisionRules.requireCurrent(expectedRevision, current.document.revision)
            dao.updateDocument(current.document.copy(deleting = true, revision = expectedRevision + 1))
        }
        // Tombstone first: an interrupted/failed cleanup remains visible and retryable.
        // No new edits or OCR commits are accepted for a deleting document.
        withContext(NonCancellable) {
            images.removeDocument(id)
            dao.delete(id)
        }
    }
    private suspend fun activeSnapshot(id: String): DocumentSnapshot {
        val current = snapshot(id)
        check(!current.document.deleting) { "Deletion is incomplete. Retry Delete to remove remaining files." }
        return current
    }
    private suspend fun touch(document: DocumentEntity) = dao.updateDocument(document.copy(
        revision = document.revision + 1, updatedAt = System.currentTimeMillis()))
    private fun cleanTitle(title: String) = title.trim().ifBlank { "Untitled scan" }.take(160)
    private fun encodeWords(words: List<Word>) = JSONArray().apply {
        words.forEach { word -> put(JSONObject().put("text", word.text).put("polygon", JSONArray().apply {
            word.polygon.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
        })) }
    }.toString()
}
