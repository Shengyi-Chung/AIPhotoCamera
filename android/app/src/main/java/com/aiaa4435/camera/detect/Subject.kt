package com.aiaa4435.camera.detect

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 一个检测到的主体。
 *
 * 坐标全部是**归一化**的（0..1，相对整帧），这样不管分析分辨率是多少、
 * 界面怎么缩放，都能直接映射到预览上。
 */
data class Subject(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** ML Kit 的跟踪 ID，同一物体跨帧保持不变；-1 表示没有跟踪 */
    val trackingId: Int = -1,
    /** 分类标签（ML Kit 默认模型只有粗分类，常常为空） */
    val label: String = "",
    val confidence: Float = 0f
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f

    fun contains(nx: Float, ny: Float): Boolean =
        nx in left..right && ny in top..bottom

    /** 到某个归一化点的距离（用于"离用户点击处最近"） */
    fun distanceTo(nx: Float, ny: Float): Float {
        val dx = abs(cx - nx)
        val dy = abs(cy - ny)
        return dx * dx + dy * dy
    }
}

/**
 * 主体选择策略。
 *
 * **为什么要有这一层**：之前用的是"逐列找最强水平边"，它选的是**对比度最高的那条边**，
 * 而用户关心的是**画面里的主体在哪**。这两件事没有必然关系 —— 桌沿可能比远处的地平线
 * 对比度高得多，于是算法锁到桌沿，用户看着莫名其妙。
 *
 * 现在改成：先定主体（ML Kit），再谈构图。边界检测只在有这个需要时才用。
 *
 * 策略优先级：
 *  1. **用户点了屏幕** → 优先选点击点附近的主体（用户意图最高优先）
 *  2. 有锁定中的主体（trackingId 稳定） → 保持不变，避免框乱跳
 *  3. 否则 → 选"离画面中心最近 + 面积够大"的那个（构图往往围绕中心主体）
 */
object SubjectSelector {

    /** 面积小于这个比例的框直接忽略（噪点、远处小人） */
    const val MIN_AREA_RATIO = 0.015f

    /** 面积超过这个比例的框也不太像"主体"，多半是整屏背景误判 */
    const val MAX_AREA_RATIO = 0.95f

    /** 用户点击时，优先搜索半径（归一化，相对较长边） */
    const val TAP_SEARCH_RADIUS = 0.35f

    /**
     * @param candidates 本帧所有候选
     * @param lockedId 当前锁定的 trackingId，-1 表示未锁定
     * @param tapX 用户点击处归一化 x，null 表示本帧没有点击
     */
    fun select(
        candidates: List<Subject>,
        lockedId: Int = -1,
        tapX: Float? = null,
        tapY: Float? = null
    ): Subject? {
        val usable = candidates.filter {
            it.area >= MIN_AREA_RATIO && it.area <= MAX_AREA_RATIO
        }
        if (usable.isEmpty()) return null

        // 1. 用户刚点过屏幕：优先选点击点附近的主体
        if (tapX != null && tapY != null) {
            val nearby = usable
                .filter { it.contains(tapX, tapY) || it.distanceTo(tapX, tapY) <= TAP_SEARCH_RADIUS }
                .minByOrNull { it.distanceTo(tapX, tapY) }
            if (nearby != null) return nearby
        }

        // 2. 已锁定：跟踪 ID 还在就跟住，别乱跳
        if (lockedId >= 0) {
            usable.firstOrNull { it.trackingId == lockedId }?.let { return it }
        }

        // 3. 默认：离画面中心最近的（面积作为次要权重，大一点的更可能是主体）
        return usable.minByOrNull { s ->
            val dx = s.cx - 0.5f
            val dy = s.cy - 0.5f
            dx * dx + dy * dy - s.area * 0.15f
        }
    }

    /**
     * 计算主体的"锚点"：让主体落在三分法交点上的位置。
     *
     * 这里返回的是**实点应该画在画面哪个位置**，而不是主体的位置。
     * 实点是固定的三分交点，用户要做的是把主体挪到那儿。
     */
    fun anchorPoint(subject: Subject?, preferRight: Boolean = false): Pair<Float, Float> {
        // 主体偏左 → 用右三分线，反之用左三分线（给主体留出前方的空间）
        val onRight = subject?.let { it.cx < 0.5f } ?: !preferRight
        val ax = if (onRight) 2f / 3f else 1f / 3f
        val ay = 1f / 3f
        return ax to ay
    }
}
