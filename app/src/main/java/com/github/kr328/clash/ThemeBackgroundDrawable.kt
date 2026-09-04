package com.github.kr328.clash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * Draws a downloaded theme image with center-crop semantics.
 *
 * The bitmap remains in application-private storage and is decoded before this drawable is
 * created. Drawing performs no I/O and scales uniformly so the viewport is always covered.
 */
class ThemeBackgroundDrawable(
    private val bitmap: Bitmap,
    private val backgroundColor: Int = Color.TRANSPARENT,
    imageOpacity: Float = 1f,
) : Drawable() {
    /** Persisted image opacity retained independently from framework drawable alpha callbacks. */
    private val imageOpacityAlpha = (imageOpacity.coerceIn(0f, 1f) * 255).toInt()

    /** Anti-aliased bitmap paint reused for every frame. */
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = imageOpacityAlpha
    }

    /** Draws the bitmap centered after uniformly scaling it to cover the current bounds. */
    override fun draw(canvas: Canvas) {
        val target = bounds
        if (target.isEmpty || bitmap.width == 0 || bitmap.height == 0) {
            return
        }
        canvas.drawColor(backgroundColor)
        val scale = max(target.width().toFloat() / bitmap.width, target.height().toFloat() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = target.left + (target.width() - width) / 2f
        val top = target.top + (target.height() - height) / 2f
        canvas.drawBitmap(
            bitmap,
            null,
            Rect(left.toInt(), top.toInt(), (left + width).toInt(), (top + height).toInt()),
            bitmapPaint,
        )
    }

    /**
     * Combines framework alpha with the persisted image opacity instead of replacing it.
     * Android may invoke this after attachment, so direct assignment would make preview and saved output diverge.
     */
    override fun setAlpha(alpha: Int) {
        bitmapPaint.alpha = imageOpacityAlpha * alpha.coerceIn(0, 255) / 255
        invalidateSelf()
    }

    /** Applies an optional framework color filter to the bitmap paint. */
    override fun setColorFilter(colorFilter: ColorFilter?) {
        bitmapPaint.colorFilter = colorFilter
    }

    /** Theme backgrounds are opaque after center-crop fills the viewport. */
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
