package com.aiaa4435.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * 校准叠加层：一个**固定的目标实点**，加上用户当前焦点的**跟随实点**。
 *
 * 设计意图（用户提出）：
 *  - 不再画满屏的水平/竖直基准线（画面上全是线反而找不到"0° 在哪"）；
 *  - 只给一个实心目标点 —— 它代表"手机框正 + 画面正"的那一个位置；
 *  - 用户挪动手机，让自己画面里的焦点（例如远处水平线与竖线的交点）
 *    压到这个目标点上，此时手机姿态 = 0°，按"完成校准"存下来。
 *
 * 视觉：目标点 = 空心双圈 + 十字，当前点 = 实心圆。两者重合时都变青绿。
 */
class CalibrationOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val accent = Color.rgb(63, 214, 192)
    private val targetColor = Color.argb(235, 255, 255, 255)
    private val currentColor = Color.rgb(255, 196, 76)

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = targetColor
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val targetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = currentColor
        style = Paint.Style.FILL
    }

    private val guidePath = Path()

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        strokeWidth = 1.2f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val currentHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
    }

    private val guideStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 196, 76)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private val doneRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(63, 214, 192)
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }

    /** 目标点归一化位置（0..1），默认画面正中 */
    var targetX = 0.5f
    var targetY = 0.5f

    /** 当前焦点归一化位置（0..1），null 表示还没开始挪 */
    private var currentNX: Float? = null
    private var currentNY: Float? = null

    /** 当前倾斜角（度），由调用方传入用于"是否已压住"的判定 */
    private var tiltRoll = 0f
    private var tiltPitch = 0f
    private var hasTilt = false

    /** 判定"已压住"的角度容差 */
    var snapToleranceDeg = 0.6f

    var show = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 更新当前焦点位置。
     * @param nx 归一化 x（0..1）
     * @param ny 归一化 y（0..1）
     */
    fun setCurrentFocus(nx: Float, ny: Float) {
        currentNX = nx.coerceIn(0f, 1f)
        currentNY = ny.coerceIn(0f, 1f)
        invalidate()
    }

    fun clearCurrentFocus() {
        currentNX = null
        currentNY = null
        invalidate()
    }

    /** 由水平仪驱动：当前手机的 roll/pitch（单位：度，已扣掉标定零位） */
    fun setTilt(roll: Float, pitch: Float, valid: Boolean) {
        tiltRoll = roll
        tiltPitch = pitch
        hasTilt = valid
        // 用倾斜角反推"当前焦点"相对目标点的位置，让用户直观看到"再转一点点就够了"。
        // 映射到屏幕：roll 越小越靠近靶心，1° ≈ 屏幕 1.6% 宽度（手机上半圈视野约 ±32°）。
        if (valid) {
            currentNX = (targetX + roll / 32f * 0.5f).coerceIn(0f, 1f)
            currentNY = (targetY + pitch / 42f * 0.5f).coerceIn(0f, 1f)
        }
        invalidate()
    }

    private fun reached(): Boolean {
        val cx = currentNX ?: return false
        val cy = currentNY ?: return false
        val dw = abs(cx - targetX) * width
        val dh = abs(cy - targetY) * height
        return hypot(dw, dh) < 18f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!show || width <= 0 || height <= 0) return

        val tx = targetX * width
        val ty = targetY * height
        val done = hasTilt && reached()
        val lvlColor = if (done) accent else targetColor

        // ---------- 目标实点：空心双圈 + 十字 ----------
        val outerR = 26f * density
        val innerR = 5.5f * density

        targetPaint.color = lvlColor
        canvas.drawCircle(tx, ty, outerR, haloPaint)
        canvas.drawCircle(tx, ty, outerR, targetPaint)

        // 同心细圈，强调"这是个要被压住的靶心"而不是随便一个点
        targetPaint.strokeWidth = 1f * density
        targetPaint.alpha = 120
        canvas.drawCircle(tx, ty, outerR * 0.62f, targetPaint)
        targetPaint.alpha = 255
        targetPaint.strokeWidth = 2f * density

        // 十字臂（四段，中间留空，不遮住靶心）
        val armIn = outerR + 8f * density
        val armOut = outerR + 22f * density
        canvas.drawLine(tx - armOut, ty, tx - armIn, ty, targetPaint)
        canvas.drawLine(tx + armIn, ty, tx + armOut, ty, targetPaint)
        canvas.drawLine(tx, ty - armOut, tx, ty - armIn, targetPaint)
        canvas.drawLine(tx, ty + armIn, tx, ty + armOut, targetPaint)

        // 靶心实心点
        targetFill.color = if (done) Color.argb(160, 63, 214, 192) else Color.argb(70, 255, 255, 255)
        canvas.drawCircle(tx, ty, innerR, targetFill)
        targetPaint.strokeWidth = 1.6f * density
        canvas.drawCircle(tx, ty, innerR, targetPaint)

        // ---------- 当前焦点：实心圆 + 到靶心的引导线 ----------
        val cx = (currentNX ?: 0.5f) * width
        val cy = (currentNY ?: 0.5f) * height

        if (!done) {
            // 引导线：从当前焦点指向靶心
            guidePath.reset()
            guidePath.moveTo(cx, cy)
            guidePath.lineTo(tx, ty)

            guideStroke.color = Color.argb(150, 255, 196, 76)
            canvas.drawPath(guidePath, guideStroke)
            currentPaint.color = currentColor
        } else {
            currentPaint.color = accent
        }

        // 当前点
        val curR = 7f * density
        canvas.drawCircle(cx, cy, curR + 2.5f * density, currentHalo)
        canvas.drawCircle(cx, cy, curR, currentPaint)

        // ---------- 对齐时的高亮脉冲环 ----------
        if (done) {
            canvas.drawCircle(tx, ty, outerR + 12f * density, doneRingPaint)
        }

        // ---------- 极简刻度：只有目标点附近的一小段，别铺满屏 ----------
        drawTargetTicks(canvas, tx, ty)
    }

    /** 在目标点四角画短刻度，帮助肉眼判断是否真的压住（不铺满屏） */
    private fun drawTargetTicks(canvas: Canvas, tx: Float, ty: Float) {
        val d = 46f * density
        val len = 9f * density
        canvas.drawLine(tx - d, ty - d, tx - d + len, ty - d, tickPaint)
        canvas.drawLine(tx - d, ty - d, tx - d, ty - d + len, tickPaint)
        canvas.drawLine(tx + d, ty - d, tx + d - len, ty - d, tickPaint)
        canvas.drawLine(tx + d, ty - d, tx + d, ty - d + len, tickPaint)
        canvas.drawLine(tx - d, ty + d, tx - d + len, ty + d, tickPaint)
        canvas.drawLine(tx - d, ty + d, tx - d, ty + d - len, tickPaint)
        canvas.drawLine(tx + d, ty + d, tx + d - len, ty + d, tickPaint)
        canvas.drawLine(tx + d, ty + d, tx + d, ty + d - len, tickPaint)
    }

    /** 供外部判断是否已压住（用于"完成校准"按钮高亮） */
    fun isReached(): Boolean = reached()

    companion object {
        /** 目标点的推荐位置：略高于画面中心（避开底部控制栏与常见前景） */
        const val DEFAULT_TARGET_Y = 0.46f
    }
}
