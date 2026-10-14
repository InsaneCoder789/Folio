package com.rohanc.ocrscanner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.ocrscanner.core.ImageInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImagePipelineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = PrivateImageStore(context)
    private fun fixture(width: Int, height: Int, draw: (Canvas) -> Unit = {}): File {
        val file = File.createTempFile("folio-fixture-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE); draw(canvas)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it)) }
        } finally { bitmap.recycle() }
        return file
    }
    @Test fun importRetainsOriginalAndBoundsWorkingCopy() = runBlocking {
        val documentId = UUID.randomUUID().toString()
        val source = fixture(4000, 2000)
        try {
            val capture = store.import(documentId, Uri.fromFile(source))
            assertArrayEquals(source.readBytes(), File(capture.originalPath).readBytes())
            assertEquals(3000, capture.width); assertEquals(1500, capture.height)
            assertTrue(File(capture.uprightPath).length() > 0)
        } finally { source.delete(); store.removeDocument(documentId) }
    }
    @Test fun jpegOrientationProducesUprightCoordinates() = runBlocking {
        val documentId = UUID.randomUUID().toString()
        val source = fixture(200, 100)
        try {
            ExifInterface(source.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val capture = store.import(documentId, Uri.fromFile(source))
            assertEquals(100, capture.width); assertEquals(200, capture.height)
        } finally { source.delete(); store.removeDocument(documentId) }
    }
    @Test fun unreadableImageLeavesNoPartialFiles() = runBlocking {
        val documentId = UUID.randomUUID().toString()
        val source = File.createTempFile("folio-invalid-", ".jpg", context.cacheDir).apply { writeText("not an image") }
        try {
            try { store.import(documentId, Uri.fromFile(source)); fail("Invalid image accepted") }
            catch (_: android.graphics.ImageDecoder.DecodeException) { }
            assertTrue(File(context.filesDir, "scans/$documentId").listFiles().orEmpty().isEmpty())
        } finally { source.delete(); store.removeDocument(documentId) }
    }
    @Test fun bundledOcrReadsSyntheticLatinTextAndReturnsPixelGeometry() = runBlocking {
        val documentId = UUID.randomUUID().toString()
        val source = fixture(1000, 400) { canvas ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f }
            canvas.drawText("FOLIO DOCUMENT", 50f, 140f, paint)
            canvas.drawText("Local OCR test", 50f, 270f, paint)
        }
        try {
            val capture = store.import(documentId, Uri.fromFile(source))
            val result = MlKitOcrEngine().recognize(ImageInput(capture.uprightPath, capture.width, capture.height, 0))
            assertTrue(result.text.uppercase().contains("FOLIO"))
            assertTrue(result.words.isNotEmpty())
            assertTrue(result.words.all { word -> word.polygon.size == 4 && word.polygon.all {
                it.x in 0f..result.width.toFloat() && it.y in 0f..result.height.toFloat()
            } })
        } finally { source.delete(); store.removeDocument(documentId) }
    }
}
