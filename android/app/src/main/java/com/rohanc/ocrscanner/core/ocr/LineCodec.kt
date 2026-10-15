package com.rohanc.ocrscanner.core.ocr

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class LineTensorSize(val resizedWidth: Int, val tensorWidth: Int)
data class LineRecognition(val text: String, val confidence: Float)

/** BGR, NCHW, height 48. Geometry belongs to the caller's actual line crop. */
object LineCodec {
    fun dimensions(width: Int, height: Int): LineTensorSize {
        require(width > 0 && height > 0)
        val tensorWidth = min(3200, (48 * max(320.0 / 48, width.toDouble() / height)).toInt())
        return LineTensorSize(min(tensorWidth, ceil(48.0 * width / height).toInt()), tensorWidth)
    }

    fun normalizeBgr(pixels: ByteArray, size: LineTensorSize): FloatArray {
        require(size.tensorWidth in 1..3200 && size.resizedWidth in 1..size.tensorWidth)
        require(pixels.size == 48 * size.resizedWidth * 3)
        val plane = 48 * size.tensorWidth
        val result = FloatArray(3 * plane)
        for (y in 0 until 48) for (x in 0 until size.resizedWidth) for (channel in 0..2) {
            val value = pixels[(y * size.resizedWidth + x) * 3 + channel].toInt() and 255
            result[channel * plane + y * size.tensorWidth + x] = (value / 255f - .5f) / .5f
        }
        return result
    }

    fun decode(scores: Array<FloatArray>, alphabet: List<String>): LineRecognition {
        require(alphabet.size > 1 && alphabet.first() == "blank")
        val text = StringBuilder(); var previous = -1; var confidence = 0.0; var count = 0
        for (row in scores) {
            require(row.size == alphabet.size && row.all { it.isFinite() }) { "Invalid recognizer output." }
            var index = 0
            for (i in 1 until row.size) if (row[i] > row[index]) index = i
            if (index != 0 && index != previous) {
                text.append(alphabet[index]); confidence += row[index]; count++
            }
            previous = index
        }
        return LineRecognition(text.toString(), if (count == 0) 0f else (confidence / count).toFloat())
    }
}
