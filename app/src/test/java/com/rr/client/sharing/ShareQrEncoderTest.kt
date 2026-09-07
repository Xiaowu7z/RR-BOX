package com.rr.client.sharing

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test

class ShareQrEncoderTest {
    @Test fun qrCanBeDecodedBackToExactLinkIncludingUnicodeAndEscapedCredentials() {
        val source = "vless://uuid@server.example:443?type=ws&path=%2Fa%3Fb%3D1%26c%3D2#五哥的节点"
        val matrix = ShareQrEncoder.encode(source)!!
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))
        val decoded = MultiFormatReader().decode(bitmap, mapOf(DecodeHintType.CHARACTER_SET to "UTF-8"))
        assertEquals(source, decoded.text)
    }

    @Test fun oversizedAndBlankPayloadsHaveNoQrInsteadOfThrowing() {
        assertNull(ShareQrEncoder.encode(""))
        assertNull(ShareQrEncoder.encode(" "))
        assertNull(ShareQrEncoder.encode("a".repeat(2301)))
        assertNull(ShareQrEncoder.encode("中".repeat(768)))
    }

    @Test fun longSubscriptionStillProducesQrWithinTheBound() {
        assertNotNull(ShareQrEncoder.encode("https://subscription.example/sub?token=" + "a".repeat(1800)))
    }
}
