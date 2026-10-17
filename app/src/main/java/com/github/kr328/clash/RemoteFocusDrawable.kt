package com.github.kr328.clash

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * 在窗口最上层绘制遥控焦点，不替换控件背景或改变业务选中状态。
 * 每帧使用可见区域定位，滚动和列表复用后不会留下旧框；触摸模式及窗口失焦时隐藏。
 */
internal class RemoteFocusDrawable(private val root: View, private val accent: () -> Int) : Drawable() {
    /** 复用绘制对象，避免导航和滚动时分配临时图形对象。 */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val visibleBounds = Rect()
    private val frame = RectF()
    private val rootLocation = IntArray(2)

    /** 根据当前真实焦点绘制淡色填充、反差衬线和主题色内框。 */
    override fun draw(canvas: Canvas) {
        val focused = root.findFocus() ?: return
        if (root.isInTouchMode || !root.hasWindowFocus() || !focused.isEnabled ||
            !focused.getGlobalVisibleRect(visibleBounds)) {
            return
        }
        root.getLocationOnScreen(rootLocation)
        frame.set(visibleBounds)
        frame.offset(-rootLocation[0].toFloat(), -rootLocation[1].toFloat())
        val density = root.resources.displayMetrics.density
        if (frame.width() < 4 * density || frame.height() < 4 * density) return
        frame.inset(2 * density, 2 * density)
        val color = accent()
        val radius = 12 * density
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.setAlphaComponent(color, if (focused.isPressed) 46 else 28)
        canvas.drawRoundRect(frame, radius, radius, paint)
        // 中性衬线使浅色主题和复杂图片上仍有稳定轮廓，主体保持当前主题色。
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4 * density
        paint.color = ColorUtils.setAlphaComponent(
            if (ColorUtils.calculateLuminance(color) > 0.45) Color.BLACK else Color.WHITE, 150,
        )
        canvas.drawRoundRect(frame, radius, radius, paint)
        paint.strokeWidth = 2 * density
        paint.color = color
        canvas.drawRoundRect(frame, radius, radius, paint)
    }

    /** 透明度由各绘制层控制，外部不改变焦点的可辨识度。 */
    override fun setAlpha(alpha: Int) = Unit

    /** 焦点色来自当前主题，不接受额外颜色滤镜。 */
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    /** 覆盖层始终保留透明区域。 */
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
