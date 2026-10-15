package com.rohanc.ocrscanner.core

import com.rohanc.ocrscanner.core.ocr.*
import org.junit.Assert.*
import org.junit.Test

class DetectorCodecTest {
    @Test fun strideUsesHalfEvenRoundingAndBoundsTheLongestSide() {
        assertEquals(DetectorTensorSize(384, 448), DetectorCodec.dimensions(400, 432))
        assertEquals(DetectorTensorSize(1280, 512), DetectorCodec.dimensions(1600, 621))
        assertEquals(DetectorTensorSize(32, 32), DetectorCodec.dimensions(32, 32))
    }
    @Test fun normalizationPreservesUnsignedBgrAndSeparateChannelPlanes() {
        val pixels = ByteArray(32 * 32 * 3); pixels[1] = 127; pixels[2] = 255.toByte()
        val output = DetectorCodec.normalizeBgr(pixels, DetectorTensorSize(32, 32)); val plane = 32 * 32
        assertEquals((-.485 / .229).toFloat(), output[0], 0f)
        assertEquals(127f * ((1.0 / 255) / .224).toFloat() + (-.456 / .224).toFloat(), output[plane], 0f)
        assertEquals(255f * ((1.0 / 255) / .225).toFloat() + (-.406 / .225).toFloat(), output[2 * plane], 0f)
        assertEquals((-.456 / .224).toFloat(), output[plane + 1], 0f)
    }
    @Test(expected = IllegalArgumentException::class) fun tinyImagesAreRejected() { DetectorCodec.dimensions(1, 200) }
    @Test(expected = IllegalArgumentException::class) fun truncatedPixelsAreRejected() {
        DetectorCodec.normalizeBgr(byteArrayOf(0), DetectorTensorSize(32, 32))
    }
}
