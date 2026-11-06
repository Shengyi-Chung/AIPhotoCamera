package com.aiaa4435.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 三分线网格：两横两竖，可开关。
 */
class GridOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 1.6f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    var showGrid: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showGrid) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 两竖
        canvas.drawLine(w / 3f, 0f, w / 3f, h, paint)
        canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, paint)
        // 两横
        canvas.drawLine(0f, h / 3f, w, h / 3f, paint)
        canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, paint)
    }
}
