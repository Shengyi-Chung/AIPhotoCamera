package com.aiaa4435.camera

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 对焦框：专业相机的对焦反馈。
 *
 * 状态流转：
 *   FOCUSING（灰色虚线，正在合焦）→ SUCCESS（绿色实线）/ FAILED（红色虚线）
 *   或 LOCKED（黄色实线 + 锁，长按锁定 AE/AF）
 */
class FocusRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State { IDLE, FOCUSING, SUCCESS, FAILED, LOCKED }

    private val density = resources.displayMetrics.density
    private val base = 36f * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()
    private var cx = 0f
    private var cy = 0f

    private var state = State.IDLE
    private var scale = 1f
    private var alpha = 0f
    private var anim: ValueAnimator? = null
    private var autoHide = Runnable { startFadeOut() }

    val isLocked: Boolean get() = state == State.LOCKED

    /** 开始对焦：在 (x,y) 显示"对焦中"框 */
    fun beginFocus(x: Float, y: Float) {
        cx = x
        cy = y
        rect.set(x - base, y - base, x + base, y + base)

        anim?.cancel()
        handler.removeCallbacks(autoHide)
        state = State.FOCUSING
        setAlphaAndInvalidate(1f)

        // 出现时从大到小回弹
        anim = ValueAnimator.ofFloat(1.5f, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                scale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 对焦结果：成功变绿、失败变红，1.2 秒后淡出 */
    fun setFocusResult(success: Boolean) {
        if (state == State.LOCKED) return          // 已锁定就不覆盖
        state = if (success) State.SUCCESS else State.FAILED
        scale = 1f
        invalidate()

        // 成功后轻轻回收一下，给一点"合焦"的确认感
        anim?.cancel()
        if (success) {
            anim = ValueAnimator.ofFloat(0.86f, 1f).apply {
                duration = 160
                addUpdateListener {
                    scale = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, 1200)
    }

    /** 锁定 AE/AF（长按） */
    fun lock(x: Float, y: Float) {
        cx = x
        cy = y
        rect.set(x - base, y - base, x + base, y + base)
        anim?.cancel()
        handler.removeCallbacks(autoHide)
        state = State.LOCKED
        scale = 1f
        setAlphaAndInvalidate(1f)
    }

    fun clear() {
        anim?.cancel()
        handler.removeCallbacks(autoHide)
        state = State.IDLE
        setAlphaAndInvalidate(0f)
    }

    private fun startFadeOut() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(alpha, 0f).apply {
            duration = 260
            addUpdateListener {
                alpha = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (alpha <= 0.01f) state = State.IDLE
                }
            })
            start()
        }
    }

    private fun setAlphaAndInvalidate(a: Float) {
        alpha = a
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (alpha <= 0.01f) return

        val color = when (state) {
            State.FOCUSING -> Color.rgb(235, 235, 235)     // 白灰
            State.SUCCESS -> Color.rgb(63, 214, 192)       // 青绿
            State.FAILED -> Color.rgb(224, 102, 102)       // 红
            State.LOCKED -> Color.rgb(255, 212, 121)       // 琥珀
            State.IDLE -> Color.TRANSPARENT
        }
        paint.color = color
        paint.alpha = (alpha * 255).toInt()
        textPaint.color = color
        textPaint.alpha = (alpha * 255).toInt()

        // 对焦中/失败用虚线，成功/锁定用实线
        paint.pathEffect = when (state) {
            State.FOCUSING, State.FAILED -> DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
            else -> null
        }

        canvas.save()
        canvas.scale(scale, scale, cx, cy)

        // 角框（比整框更接近专业相机观感）
        val c = base * 0.42f
        canvas.drawLine(cx - base, cy - base, cx - base + c, cy - base, paint)
        canvas.drawLine(cx - base, cy - base, cx - base, cy - base + c, paint)

        canvas.drawLine(cx + base, cy - base, cx + base - c, cy - base, paint)
        canvas.drawLine(cx + base, cy - base, cx + base, cy - base + c, paint)

        canvas.drawLine(cx - base, cy + base, cx - base + c, cy + base, paint)
        canvas.drawLine(cx - base, cy + base, cx - base, cy + base - c, paint)

        canvas.drawLine(cx + base, cy + base, cx + base - c, cy + base, paint)
        canvas.drawLine(cx + base, cy + base, cx + base, cy + base - c, paint)

        // 状态文字
        val label = when (state) {
            State.FOCUSING -> "对焦中"
            State.SUCCESS -> "已合焦"
            State.FAILED -> "对焦失败"
            State.LOCKED -> "AE/AF 已锁定"
            State.IDLE -> ""
        }
        if (label.isNotEmpty()) {
            canvas.drawText(label, cx, cy + base + 18f * density, textPaint)
        }
        canvas.restore()
    }
}
