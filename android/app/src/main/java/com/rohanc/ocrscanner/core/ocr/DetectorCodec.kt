package com.rohanc.ocrscanner.core.ocr

import kotlin.math.max
import kotlin.math.min

data class DetectorTensorSize(val width: Int, val height: Int)
data class TextProbabilityMap(val values: FloatArray, val width: Int, val height: Int,
    val sourceWidth: Int, val sourceHeight: Int)

/** Pinned detector uses BGR channel order, stride 32 and ties-to-even resize rounding. */
object DetectorCodec {
    fun dimensions(width: Int, height: Int): DetectorTensorSize {
        require(min(width, height) >= 32) { "Supply a full-page image at least 32 pixels per side." }
        val ratio = min(1.0, 1280.0 / max(width, height))
        fun stride(value: Int) = max(32, Math.rint(value / 32.0).toInt() * 32)
        return DetectorTensorSize(stride((width * ratio).toInt()), stride((height * ratio).toInt()))
    }

    fun normalizeBgr(pixels: ByteArray, size: DetectorTensorSize): FloatArray {
        require(size.width in 32..1280 && size.height in 32..1280 && size.width % 32 == 0 && size.height % 32 == 0)
        val plane = size.width * size.height
        require(pixels.size == plane * 3)
        val mean = doubleArrayOf(.485, .456, .406); val std = doubleArrayOf(.229, .224, .225)
        val result = FloatArray(plane * 3)
        for (channel in 0..2) {
            val alpha = ((1.0 / 255) / std[channel]).toFloat(); val beta = (-mean[channel] / std[channel]).toFloat()
            for (i in 0 until plane) {
                val value = (pixels[i * 3 + channel].toInt() and 255).toFloat()
                result[channel * plane + i] = value * alpha + beta
            }
        }
        return result
    }
}
