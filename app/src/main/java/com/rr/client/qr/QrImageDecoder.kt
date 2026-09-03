package com.rr.client.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlin.math.max

object QrImageDecoder {
    private const val MAX_DECODE_EDGE = 2048

    fun decode(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_EDGE) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val source = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null

        return try {
            decodeBitmap(source)
        } finally {
            source.recycle()
        }
    }

    private fun decodeBitmap(source: Bitmap): String? {
        val rotations = intArrayOf(0, 90, 180, 270)
        for (degrees in rotations) {
            val candidate = if (degrees == 0) {
                source
            } else {
                Bitmap.createBitmap(
                    source,
                    0,
                    0,
                    source.width,
                    source.height,
                    Matrix().apply { postRotate(degrees.toFloat()) },
                    true
                )
            }

            try {
                decodeCandidate(candidate)?.let { return it }
            } finally {
                if (candidate !== source) candidate.recycle()
            }
        }
        return null
    }

    private fun decodeCandidate(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)

        return decodeLuminance(source) ?: decodeLuminance(source.invert())
    }

    private fun decodeLuminance(source: com.google.zxing.LuminanceSource): String? {
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        return runCatching {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
    }
}
