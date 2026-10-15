package com.rohanc.ocrscanner.core.ocr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import androidx.annotation.WorkerThread
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

/** Experimental line component. Full-page detection and OcrEngine admission are separate gates. */
class OnnxLineRecognizer(model: File, configuration: File) : Closeable {
    private val alphabet: List<String>
    private val environment: OrtEnvironment
    private val session: OrtSession
    private var closed = false

    init {
        require(model.length() == 7_865_035L && sha256(model) == MODEL_SHA) { "Recognizer model integrity failed." }
        require(configuration.length() == 10_455L && sha256(configuration) == CONFIG_SHA) { "Recognizer configuration integrity failed." }
        val dictionary = JSONObject(configuration.readText(Charsets.UTF_8)).getJSONObject("PostProcess").getJSONArray("character_dict")
        alphabet = listOf("blank") + List(dictionary.length()) { dictionary.getString(it) } + " "
        require(alphabet.size == 438)
        environment = OrtEnvironment.getEnvironment()
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            environment.createSession(model.absolutePath, options)
        }
        try {
            val input = session.inputInfo.getValue("x").info as TensorInfo
            val output = session.outputInfo.values.single().info as TensorInfo
            require(session.inputNames == setOf("x") && input.type == OnnxJavaType.FLOAT &&
                input.shape.size == 4 && input.shape[1] == 3L && input.shape[2] == 48L &&
                output.type == OnnxJavaType.FLOAT && output.shape.size == 3 && output.shape[2] == 438L)
        } catch (failure: Throwable) { session.close(); throw failure }
    }

    /** Runs off the UI thread; caller owns and retains the bitmap until this returns. */
    @WorkerThread
    @Synchronized
    fun recognize(line: Bitmap): LineRecognition {
        check(!closed) { "Recognizer is closed." }
        require(!line.isRecycled && line.width.toLong() * line.height <= 4_000_000) { "Supply a bounded text-line crop." }
        check(OpenCVLoader.initLocal()) { "Vision engine could not initialize." }
        val rgba = Mat(); val bgr = Mat(); val resized = Mat()
        try {
            Utils.bitmapToMat(line, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            val size = LineCodec.dimensions(line.width, line.height)
            Imgproc.resize(bgr, resized, Size(size.resizedWidth.toDouble(), 48.0), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val pixels = ByteArray(48 * size.resizedWidth * 3); resized.get(0, 0, pixels)
            val normalized = LineCodec.normalizeBgr(pixels, size)
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(normalized), longArrayOf(1, 3, 48, size.tensorWidth.toLong())).use { tensor ->
                session.run(mapOf("x" to tensor)).use { result ->
                    val output = result[0] as OnnxTensor
                    require(output.info.shape.contentEquals(longArrayOf(1, output.info.shape[1], 438)))
                    @Suppress("UNCHECKED_CAST")
                    val scores = output.value as Array<Array<FloatArray>>
                    return LineCodec.decode(scores.single(), alphabet)
                }
            }
        } finally { resized.release(); bgr.release(); rgba.release() }
    }

    @Synchronized
    override fun close() { if (!closed) { closed = true; session.close() } }

    companion object {
        const val MODEL_SHA = "a27234082983fb2e22a905c400e321a800bff75ee76d85a10219327e242c1c75"
        const val CONFIG_SHA = "16103ac291a82fca50f0bb000d62a4b8168f6042f03c89c44cf32f241d1eb1f2"
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
