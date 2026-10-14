package com.rohanc.ocrscanner.data

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rohanc.ocrscanner.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class MlKitOcrEngine : OcrEngine {
    override suspend fun recognize(input: ImageInput): OcrResult = withContext(Dispatchers.Default) {
        val bitmap = BitmapFactory.decodeFile(input.path) ?: error("Stored image cannot be decoded.")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            // ML Kit has no cancellation API. Wait for completion before recycling its bitmap;
            // caller cancellation is checked before committing the result to Room.
            val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
            val result = suspendCoroutine<com.google.mlkit.vision.text.Text> { continuation ->
                task.addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
                    .addOnCanceledListener { continuation.resumeWithException(kotlinx.coroutines.CancellationException("OCR cancelled")) }
            }
            OcrResult(result.text, result.textBlocks.flatMap { block -> block.lines.flatMap { line ->
                line.elements.map { element -> Word(element.text,
                    element.cornerPoints?.map { Point(it.x.toFloat(), it.y.toFloat()) } ?: emptyList()) }
            } }, bitmap.width, bitmap.height)
        } finally { recognizer.close(); bitmap.recycle() }
    }
}
