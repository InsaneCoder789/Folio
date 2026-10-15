package com.rohanc.ocrscanner.diagnostics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import com.rohanc.ocrscanner.core.ocr.OnnxTextDetector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.system.exitProcess

/** Debug-only map comparison. No app Context, database, settings or capture data is accessed. */
object DetectorProbe {
    @JvmStatic fun main(args: Array<String>) {
        try {
            require(args.size == 1)
            val root = File(args[0]).canonicalFile
            require(root.path.matches(Regex("/data/local/tmp/folio-detector-[a-f0-9]{32}")))
            val manifest = JSONObject(File(root, "cases.json").readText()); val cases = manifest.getJSONArray("cases")
            require(cases.length() == 15)
            val absoluteTolerance = manifest.getDouble("absoluteTolerance"); val relativeTolerance = manifest.getDouble("relativeTolerance")
            require(absoluteTolerance == .0005 && relativeTolerance == .0005)
            val results = JSONArray(); var maximumPss = 0
            OnnxTextDetector(File(root, "model.onnx")).use { detector ->
                repeat(cases.length()) { index ->
                    val case = cases.getJSONObject(index); val document = case.getString("document")
                    require(document.matches(Regex("[a-f0-9]{16}")))
                    val image = File(root, "$document.png"); val reference = File(root, "$document.bin")
                    require(sha256(image) == case.getString("pngSha256") && sha256(reference) == case.getString("referenceSha256"))
                    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
                    val bitmap = BitmapFactory.decodeFile(image.path, options) ?: error("Cannot decode detector probe page.")
                    val start = SystemClock.elapsedRealtimeNanos()
                    val map = try { detector.probabilityMap(bitmap) } finally { bitmap.recycle() }
                    val milliseconds = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    require(map.width == case.getInt("width") && map.height == case.getInt("height"))
                    require(reference.length() == map.values.size.toLong() * 4)
                    val expected = ByteBuffer.wrap(reference.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    var maximumError = 0.0; var totalError = 0.0; var outside = 0; var maskDifferences = 0
                    for (actual in map.values) {
                        val value = expected.get(); require(value.isFinite())
                        val error = abs(actual.toDouble() - value.toDouble())
                        maximumError = maxOf(maximumError, error); totalError += error
                        if (error > absoluteTolerance + relativeTolerance * abs(value)) outside++
                        if ((actual > .3f) != (value > .3f)) maskDifferences++
                    }
                    val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory); maximumPss = maxOf(maximumPss, memory.totalPss)
                    results.put(JSONObject().put("document", document).put("pixels", map.values.size)
                        .put("outsideTolerancePixels", outside).put("thresholdMaskDifferences", maskDifferences)
                        .put("maximumAbsoluteError", maximumError).put("meanAbsoluteError", totalError / map.values.size)
                        .put("inferenceMilliseconds", milliseconds))
                }
            }
            val failures = (0 until results.length()).count { results.getJSONObject(it).getInt("outsideTolerancePixels") != 0 }
            val report = JSONObject().put("status", if (failures == 0) "passed" else "failed-map-parity")
                .put("pages", results.length()).put("mismatchPages", failures).put("results", results)
                .put("absoluteTolerance", absoluteTolerance).put("relativeTolerance", relativeTolerance)
                .put("maximumObservedProcessPssKiB", maximumPss)
                .put("scope", "Standalone Android ART maps on canonical page pixels; no box, camera, installed UI, Room or worker acceptance")
            println("FOLIO_DETECTOR_RESULT " + report.toString())
            if (failures != 0) exitProcess(1)
        } catch (failure: Throwable) {
            failure.printStackTrace()
            println("FOLIO_DETECTOR_RESULT " + JSONObject().put("status", "failed-runtime").put("error", failure.toString()))
            exitProcess(1)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
