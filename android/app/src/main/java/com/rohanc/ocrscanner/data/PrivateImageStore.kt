package com.rohanc.ocrscanner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Originals and OCR working copies are owned by the app; URI grants are not needed after import. */
class PrivateImageStore(private val context: Context) {
    private val root = File(context.filesDir, "scans")
    fun previewDirectory(id:String):File { UUID.fromString(id);return File(context.cacheDir,"scan-previews/$id") }
    private fun directory(id: String): File {
        UUID.fromString(id)
        return File(root, id)
    }
    suspend fun import(documentId: String, uri: Uri, maxSide: Int = 3000): CaptureEntity = withContext(Dispatchers.IO) {
        val dir = directory(documentId).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val original = File(dir, "$id.source")
        val upright = File(dir, "$id.jpg")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                original.outputStream().use { out ->
                    val bytes = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(bytes)
                        if (count == -1) break
                        total += count
                        require(total <= 20L * 1024 * 1024) { "Choose an image smaller than 20 MB." }
                        out.write(bytes, 0, count)
                    }
                }
            } ?: error("The selected image cannot be opened.")
            coroutineContext.ensureActive()
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(original)) { decoder, info, _ ->
                val w = info.size.width; val h = info.size.height
                require(w > 0 && h > 0 && w.toLong() * h <= 80_000_000) { "Image dimensions are too large." }
                val scale = minOf(1.0, maxSide.toDouble() / max(w, h))
                decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
            }
            try {
                coroutineContext.ensureActive()
                upright.outputStream().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "Could not store image." }
                }
                CaptureEntity(id, documentId, original.absolutePath, upright.absolutePath, bitmap.width, bitmap.height)
            } finally { bitmap.recycle() }
        } catch (failure: Throwable) {
            original.delete(); upright.delete()
            throw failure
        }
    }
    fun documentDirectory(id: String) = directory(id)
    suspend fun removeCapture(capture: CaptureEntity) = withContext(Dispatchers.IO) {
        File(capture.originalPath).delete(); File(capture.uprightPath).delete(); Unit
    }
    suspend fun removeDocument(id: String) = withContext(Dispatchers.IO) {
        val dir = directory(id)
        check(!dir.exists() || dir.deleteRecursively()) { "Some document files could not be removed." }
    }
}
