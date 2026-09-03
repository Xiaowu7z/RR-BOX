package com.rr.client.qr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import com.journeyapps.barcodescanner.ViewfinderView
import kotlin.math.max

/** NekoBox-style square QR viewfinder while keeping JourneyApps' real framing rectangle. */
class RrQrViewfinderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewfinderView(context, attrs) {
    private val density = resources.displayMetrics.density
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(158, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        refreshSizes()
        val frame = framingRect ?: return

        canvas.drawRect(0f, 0f, width.toFloat(), frame.top.toFloat(), maskPaint)
        canvas.drawRect(0f, frame.top.toFloat(), frame.left.toFloat(), frame.bottom.toFloat(), maskPaint)
        canvas.drawRect(frame.right.toFloat(), frame.top.toFloat(), width.toFloat(), frame.bottom.toFloat(), maskPaint)
        canvas.drawRect(0f, frame.bottom.toFloat(), width.toFloat(), height.toFloat(), maskPaint)

        val corner = 30f * density
        val l = frame.left.toFloat()
        val t = frame.top.toFloat()
        val r = frame.right.toFloat()
        val b = frame.bottom.toFloat()

        canvas.drawLine(l, t + corner, l, t, cornerPaint)
        canvas.drawLine(l, t, l + corner, t, cornerPaint)
        canvas.drawLine(r - corner, t, r, t, cornerPaint)
        canvas.drawLine(r, t, r, t + corner, cornerPaint)
        canvas.drawLine(l, b - corner, l, b, cornerPaint)
        canvas.drawLine(l, b, l + corner, b, cornerPaint)
        canvas.drawLine(r - corner, b, r, b, cornerPaint)
        canvas.drawLine(r, b - corner, r, b, cornerPaint)

        val inset = max(18f * density, frame.width() * 0.06f)
        val progress = (SystemClock.uptimeMillis() % 1800L) / 1800f
        val y = t + inset + progress * (frame.height() - inset * 2f)
        scanPaint.shader = LinearGradient(
            l + inset,
            y,
            r - inset,
            y,
            intArrayOf(Color.TRANSPARENT, Color.rgb(0, 229, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(l + inset, y, r - inset, y, scanPaint)
        scanPaint.shader = null

        postInvalidateOnAnimation()
    }
}
