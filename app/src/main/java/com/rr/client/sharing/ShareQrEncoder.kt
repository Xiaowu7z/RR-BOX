package com.rr.client.sharing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Encoding is local and bounded; long payloads keep their copy/file options. */
object ShareQrEncoder {
    fun encode(text: String): BitMatrix? {
        if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > 2300) return null
        return try {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 768, 768, mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4
            ))
        } catch (_: WriterException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
