package com.rohanc.ocrscanner.core.ocr

import ai.onnxruntime.*
import android.graphics.Bitmap
import androidx.annotation.WorkerThread
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

/** Experimental probability-map component; it does not invent text boxes or reading order. */
class OnnxTextDetector(model: File) : Closeable {
    private val environment: OrtEnvironment
    private val session: OrtSession
    private var closed = false

    init {
        require(model.length() == 4_819_576L && sha256(model) == MODEL_SHA) { "Text detector integrity failed." }
        environment = OrtEnvironment.getEnvironment()
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            environment.createSession(model.absolutePath, options)
        }
        try {
            val input = session.inputInfo.getValue("x").info as TensorInfo
            val output = session.outputInfo.values.single().info as TensorInfo
            require(session.inputNames == setOf("x") && input.type == OnnxJavaType.FLOAT &&
                input.shape.size == 4 && input.shape[1] == 3L && output.type == OnnxJavaType.FLOAT &&
                output.shape.size == 4 && output.shape[1] == 1L) { "Unexpected text detector contract." }
        } catch (failure: Throwable) { session.close(); throw failure }
    }

    @WorkerThread
    @Synchronized
    fun probabilityMap(page: Bitmap): TextProbabilityMap {
        check(!closed) { "Text detector is closed." }
        require(!page.isRecycled && page.config != Bitmap.Config.HARDWARE && page.width.toLong() * page.height <= 9_000_000) {
            "Supply a bounded software page bitmap."
        }
        val size = DetectorCodec.dimensions(page.width, page.height)
        check(OpenCVLoader.initLocal()) { "Vision engine could not initialize." }
        val rgba = Mat(); val bgr = Mat(); val resized = Mat()
        try {
            Utils.bitmapToMat(page, rgba); Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.resize(bgr, resized, Size(size.width.toDouble(), size.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val pixels = ByteArray(size.width * size.height * 3); resized.get(0, 0, pixels)
            val normalized = DetectorCodec.normalizeBgr(pixels, size)
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(normalized), longArrayOf(1, 3, size.height.toLong(), size.width.toLong())).use { tensor ->
                session.run(mapOf("x" to tensor)).use { result ->
                    val output = result[0] as OnnxTensor
                    require(output.info.shape.contentEquals(longArrayOf(1, 1, size.height.toLong(), size.width.toLong()))) {
                        "Unexpected text probability-map shape."
                    }
                    val values = FloatArray(size.width * size.height)
                    output.floatBuffer.apply { rewind(); require(remaining() == values.size); get(values) }
                    require(values.all { it.isFinite() }) { "Nonfinite text detector output." }
                    return TextProbabilityMap(values, size.width, size.height, page.width, page.height)
                }
            }
        } finally { resized.release(); bgr.release(); rgba.release() }
    }

    @Synchronized
    override fun close() { if (!closed) { closed = true; session.close() } }

    companion object {
        const val MODEL_SHA = "cfcb87395c173a9f48d98d1d247b1006b7ed8224db0286709ce2b96fd38bee96"
        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
