package com.rohanc.ocrscanner.core

import com.rohanc.ocrscanner.core.ocr.*
import org.junit.Assert.*
import org.junit.Test

class LineCodecTest {
    @Test fun dimensionsPreserveAspectAndBoundMemory() {
        assertEquals(LineTensorSize(48, 320), LineCodec.dimensions(100, 100))
        assertEquals(LineTensorSize(480, 480), LineCodec.dimensions(1000, 100))
        assertEquals(LineTensorSize(3200, 3200), LineCodec.dimensions(100000, 10))
    }
    @Test fun normalizationKeepsBgrChannelsAndNeutralPadding() {
        val pixels = ByteArray(48 * 3)
        repeat(48) { pixels[it * 3] = 0; pixels[it * 3 + 1] = 127; pixels[it * 3 + 2] = 255.toByte() }
        val output = LineCodec.normalizeBgr(pixels, LineTensorSize(1, 320)); val plane = 48 * 320
        assertEquals(-1f, output[0], 0f)
        assertEquals((127 / 255f - .5f) / .5f, output[plane], 0f)
        assertEquals(1f, output[2 * plane], 0f)
        assertEquals(0f, output[1], 0f)
        assertEquals(-1f, output[320], 0f)
    }
    @Test fun ctcBlanksSeparateRepeatedLettersAndKeepSpaces() {
        val alphabet = listOf("blank", "b", "o", "k", " ")
        fun scores(indices: List<Int>) = indices.map { index -> FloatArray(5) { if (it == index) 1f else 0f } }.toTypedArray()
        assertEquals("book", LineCodec.decode(scores(listOf(0, 1, 1, 2, 2, 0, 2, 3, 3)), alphabet).text)
        assertEquals("b k", LineCodec.decode(scores(listOf(1, 4, 4, 3)), alphabet).text)
        assertEquals(LineRecognition("", 0f), LineCodec.decode(scores(listOf(0, 0)), alphabet))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidDimensionsAreRejected() { LineCodec.dimensions(0, 1) }
    @Test(expected = IllegalArgumentException::class) fun truncatedPixelsAreRejected() { LineCodec.normalizeBgr(byteArrayOf(1), LineTensorSize(1, 320)) }
    @Test(expected = IllegalArgumentException::class) fun nonfiniteModelOutputIsRejected() { LineCodec.decode(arrayOf(floatArrayOf(0f, Float.NaN)), listOf("blank", "a")) }
}
