package com.rohanc.ocrscanner.diagnostics

import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import com.rohanc.ocrscanner.core.ocr.OnnxLineRecognizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/** Debug-only ART probe for an existing device; it never opens the app database. */
object LineRecognizerProbe {
    @JvmStatic fun main(args: Array<String>) {
        try {
            require(args.size == 1)
            val root = File(args[0]).canonicalFile
            require(root.path.matches(Regex("/data/local/tmp/folio-onnx-[a-f0-9]{32}")))
            val cases = JSONArray(File(root, "cases.json").readText())
            require(cases.length() == 28)
            val predictions = JSONArray(); var maximumPss = 0
            OnnxLineRecognizer(File(root, "model.onnx"), File(root, "config.json")).use { recognizer ->
                repeat(cases.length()) { index ->
                    val case = cases.getJSONObject(index); val id = case.getString("id")
                    require(id.matches(Regex("[a-f0-9]{16}-[0-9]{2}")))
                    val bitmap = BitmapFactory.decodeFile(File(root, "$id.png").path) ?: error("Cannot decode probe line.")
                    val start = SystemClock.elapsedRealtimeNanos()
                    val result = try { recognizer.recognize(bitmap) } finally { bitmap.recycle() }
                    val milliseconds = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
                    maximumPss = maxOf(maximumPss, memory.totalPss)
                    predictions.put(JSONObject().put("id", id).put("text", result.text)
                        .put("confidence", result.confidence.toDouble())
                        .put("inferenceMilliseconds", milliseconds)
                        .put("matchesHost", result.text == case.getString("expected")))
                }
            }
            val failures = (0 until predictions.length()).count { !predictions.getJSONObject(it).getBoolean("matchesHost") }
            val report = JSONObject().put("status", if (failures == 0) "passed" else "failed-prediction-parity")
                .put("lines", predictions.length()).put("mismatches", failures).put("predictions", predictions)
                .put("maximumObservedProcessPssKiB", maximumPss)
                .put("scope", "Standalone Android ART line inference; no app UI, Room, worker or full-page acceptance")
            println("FOLIO_ONNX_RESULT " + report.toString())
            if (failures != 0) exitProcess(1)
        } catch (failure: Throwable) {
            failure.printStackTrace()
            println("FOLIO_ONNX_RESULT " + JSONObject().put("status", "failed-runtime").put("error", failure.toString()))
            exitProcess(1)
        }
    }
}
