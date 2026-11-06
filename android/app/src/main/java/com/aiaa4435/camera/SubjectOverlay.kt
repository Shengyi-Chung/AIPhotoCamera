package com.aiaa4435.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.aiaa4435.camera.detect.Subject
import kotlin.math.min

/**
 * 构图引导层。
 *
 * 画面上一共四个东西，各有明确职责：
 *
 * | 元素 | 是什么 | 用户的动作 |
 * |---|---|---|
 * | 主体框 | 锁定中的那个主体（**只画这一个**） | 点屏幕可换 |
 * | 实点（靶心） | **镜头中心应该到达的位置** | 把虚点挪上去 |
 * | 虚点（十字） | 镜头中心，固定在屏幕正中 | 它不会动，动的是手机 |
 * | 引导线 | 实点 → 虚点 | 告诉用户往哪边挪 |
 *
 * 设计原则：
 *  - **只画锁定主体的框**。其他候选框一律不显示 —— 满屏虚线框严重影响观感，
 *    而且用户只关心他拍的那一个。
 *  - **实点是唯一的行动目标**。不画检测线、不画其他辅助，画面里只留一个"该去哪"。
 *  - 虚点固定在**屏幕正中**（真·镜头中心），这是用户无法通过改设置改变的事实。
 */
class SubjectOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val accent = Color.rgb(63, 214, 192)
    private val anchorColor = Color.rgb(255, 214, 102)   // 实点：暖黄
    private val centerColor = Color.argb(225, 255, 255, 255)  // 虚点：白
    private val boxColor = Color.argb(200, 255, 255, 255)

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val anchorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12.5f * density
        textAlign = Paint.Align.CENTER
    }

    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val rect = RectF()

    var show = true
        set(value) { field = value; invalidate() }

    /** 锁定中的主体（null = 还没锁定，只显示虚点） */
    private var subject: Subject? = null

    /** 实点归一化位置 */
    private var anchorNX = 0.5f
    private var anchorNY = 0.5f
    private var hasAnchor = false

    /** 构图是否已达标 */
    private var goodComposition = false

    /** 一句话提示 */
    private var hint = ""

    fun setSubject(s: Subject?) {
        subject = s
        if (s == null) {
            hasAnchor = false
            goodComposition = false
            hint = ""
        }
        invalidate()
    }

    fun setAnchor(nx: Float, ny: Float, good: Boolean, hintText: String) {
        anchorNX = nx
        anchorNY = ny
        hasAnchor = true
        goodComposition = good
        hint = hintText
        invalidate()
    }

    /** 没有主体时清掉实点，只留镜头中心 */
    fun clearAnchor() {
        hasAnchor = false
        goodComposition = false
        hint = ""
        invalidate()
    }

    fun clear() {
        subject = null
        hasAnchor = false
        goodComposition = false
        hint = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!show || width <= 0 || height <= 0) return

        drawSubjectBox(canvas)
        drawCenter(canvas)
        if (hasAnchor) drawAnchor(canvas)
        drawHint(canvas)
    }

    /** 只画锁定主体的框，且只画四角（比整圈虚线干净得多，也不会遮挡本体） */
    private fun drawSubjectBox(canvas: Canvas) {
        val s = subject ?: return
        rect.set(s.left * width, s.top * height, s.right * width, s.bottom * height)

        val c = if (goodComposition) accent else boxColor

        cornerPaint.color = c
        val len = min(20f * density, min(rect.width(), rect.height()) / 3.5f)
        // 八个角臂
        canvas.drawLine(rect.left, rect.top, rect.left + len, rect.top, cornerPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + len, cornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right - len, rect.top, cornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + len, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left + len, rect.bottom, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - len, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right - len, rect.bottom, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - len, cornerPaint)

        // 很淡的一圈连线，让"范围"可读但不抢戏
        boxPaint.color = Color.argb(70, 255, 255, 255)
        canvas.drawRect(rect, boxPaint)
    }

    /** 虚点：镜头中心，固定在屏幕正中 */
    private fun drawCenter(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 13f * density

        centerPaint.color = if (goodComposition) accent else centerColor
        canvas.drawCircle(cx, cy, r, haloPaint)
        canvas.drawCircle(cx, cy, r, centerPaint)

        // 十字臂，短一点，别太抢
        val inLen = r + 3f * density
        val outLen = r + 11f * density
        canvas.drawLine(cx - outLen, cy, cx - inLen, cy, centerPaint)
        canvas.drawLine(cx + inLen, cy, cx + outLen, cy, centerPaint)
        canvas.drawLine(cx, cy - outLen, cx, cy - inLen, centerPaint)
        canvas.drawLine(cx, cy + inLen, cx, cy + outLen, centerPaint)
    }

    /** 实点：镜头中心应该到达的位置 */
    private fun drawAnchor(canvas: Canvas) {
        val ax = anchorNX * width
        val ay = anchorNY * height
        val cx = width / 2f
        val cy = height / 2f

        val color = if (goodComposition) accent else anchorColor

        // 引导线：从虚点指向实点 —— 用户要做的就是把虚点挪过去
        if (!goodComposition) {
            guidePaint.color = Color.argb(150, 255, 214, 102)
            canvas.drawLine(cx, cy, ax, ay, guidePaint)
        }

        // 靶心
        val outerR = 20f * density
        val innerR = 4f * density

        anchorPaint.color = color
        canvas.drawCircle(ax, ay, outerR, haloPaint)
        canvas.drawCircle(ax, ay, outerR, anchorPaint)

        anchorPaint.strokeWidth = 1f * density
        canvas.drawCircle(ax, ay, outerR * 0.58f, anchorPaint)
        anchorPaint.strokeWidth = 2f * density

        // 十字臂
        val armIn = outerR + 6f * density
        val armOut = outerR + 16f * density
        canvas.drawLine(ax - armOut, ay, ax - armIn, ay, anchorPaint)
        canvas.drawLine(ax + armIn, ay, ax + armOut, ay, anchorPaint)
        canvas.drawLine(ax, ay - armOut, ax, ay - armIn, anchorPaint)
        canvas.drawLine(ax, ay + armIn, ax, ay + armOut, anchorPaint)

        // 靶心实心点
        anchorFill.color = if (goodComposition) Color.argb(150, 63, 214, 192)
        else Color.argb(90, 255, 214, 102)
        canvas.drawCircle(ax, ay, innerR, anchorFill)

        // 达标时加一圈光环
        if (goodComposition) {
            anchorPaint.strokeWidth = 1.3f * density
            canvas.drawCircle(ax, ay, outerR + 9f * density, anchorPaint)
        }
    }

    /** 一句话提示，放在画面底部，不挡主体 */
    private fun drawHint(canvas: Canvas) {
        if (hint.isEmpty()) return
        val cy = height - 18f * density
        val tw = hintPaint.measureText(hint)
        val padH = 12f * density
        val padV = 6f * density

        canvas.drawRoundRect(
            width / 2f - tw / 2f - padH, cy - hintPaint.textSize - padV,
            width / 2f + tw / 2f + padH, cy + padV,
            6f * density, 6f * density, hintBg
        )
        hintPaint.color = if (goodComposition) accent else Color.WHITE
        canvas.drawText(hint, width / 2f, cy - 2f * density, hintPaint)
    }
}
