package com.aiaa4435.camera.detect

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 实点稳定器（滞后带 / hysteresis）。
 *
 * ## 为什么需要它
 *
 * 实点公式是 `实点 = 0.5 + (主体位置 - 目标位置)`。
 * 主体框每帧抖 1%，实点就跟着抖 1%，而且被引导线在视觉上放大 —— 用户看到的就是"实点一直在抖"。
 *
 * ## 为什么不直接对实点做低通滤波
 *
 * 低通滤波会让实点在用户**真正移动手机**时也变迟钝（跟手变差），
 * 而且小幅抖动依然会缓慢地"渗透"出来。
 *
 * ## 这里用的办法：滞后带
 *
 * 关键洞察是：**三分法本身就是一个宽容的构图**。
 * 主体不必精确落在 1/3 处，落在 1/3 附近一片区域里都算好构图。
 * 所以：
 *
 *  1. 给理想位置定义一个**容差带**（默认水平 ±4.5%、垂直 ±4.5%）；
 *  2. 主体在这个带内 → **实点保持不动**（完全不抖）；
 *  3. 主体出了带 → 实点才更新，且更新后主体重新拥有整个带宽的余量。
 *
 * 这样：
 *  - 静止时框抖 ±1%：实点纹丝不动（因为没出带）
 *  - 用户真移动：一旦超带，实点立即跟上，不迟钝
 *
 * 这是图像处理里常见的"双阈值/施密特触发器"思路，用在这里刚好对上构图的宽容性。
 */
class AnchorStabilizer(
    /** 水平方向容差带（归一化）。主体偏离目标在此范围内不更新实点 */
    private val bandX: Float = 0.045f,
    /** 垂直方向容差带 */
    private val bandY: Float = 0.045f,
    /** 带外还需要连续几帧确认才更新，进一步抑制偶发跳变 */
    private val confirmFrames: Int = 2
) {
    private var stableSubjectX = Float.NaN
    private var stableSubjectY = Float.NaN
    private var stableAnchorX = Float.NaN
    private var stableAnchorY = Float.NaN

    private var pendingX = Float.NaN
    private var pendingY = Float.NaN
    private var pendingCount = 0

    /** 上一帧是否发生了更新（调试用） */
    var lastUpdated = false
        private set

    /** 累计更新次数，用来判断实点到底稳不稳 */
    var updateCount = 0
        private set

    fun reset() {
        stableSubjectX = Float.NaN
        stableSubjectY = Float.NaN
        stableAnchorX = Float.NaN
        stableAnchorY = Float.NaN
        pendingX = Float.NaN
        pendingY = Float.NaN
        pendingCount = 0
        lastUpdated = false
        updateCount = 0
    }

    /**
     * 输入本帧主体中心，返回应当显示的实点位置。
     *
     * @param subjectX 主体中心 x（归一化）
     * @param subjectY 主体中心 y（归一化）
     * @param rawAnchorX 未稳定化前的实点 x
     * @param rawAnchorY 未稳定化前的实点 y
     */
    fun stabilize(
        subjectX: Float,
        subjectY: Float,
        rawAnchorX: Float,
        rawAnchorY: Float
    ): Pair<Float, Float> {
        // 首次：直接采纳
        if (stableSubjectX.isNaN()) {
            adopt(subjectX, subjectY, rawAnchorX, rawAnchorY)
            lastUpdated = true
            return rawAnchorX to rawAnchorY
        }

        val dx = subjectX - stableSubjectX
        val dy = subjectY - stableSubjectY
        val outOfBand = abs(dx) > bandX || abs(dy) > bandY

        if (!outOfBand) {
            // 带内：保持不动（这正是消除抖动的关键）
            pendingCount = 0
            pendingX = Float.NaN
            pendingY = Float.NaN
            lastUpdated = false
            return stableAnchorX to stableAnchorY
        }

        // 带外：需要连续 confirmFrames 帧都朝同一方向偏，才认为用户真的移动了
        val sameDirection = !pendingX.isNaN() &&
            ((pendingX - stableSubjectX) * dx >= 0f) &&
            ((pendingY - stableSubjectY) * dy >= 0f)

        if (sameDirection) {
            pendingCount++
        } else {
            pendingCount = 1
        }
        pendingX = subjectX
        pendingY = subjectY

        if (pendingCount >= confirmFrames) {
            adopt(subjectX, subjectY, rawAnchorX, rawAnchorY)
            pendingCount = 0
            pendingX = Float.NaN
            pendingY = Float.NaN
            lastUpdated = true
            updateCount++
            return rawAnchorX to rawAnchorY
        }

        // 还没确认：继续显示旧值
        lastUpdated = false
        return stableAnchorX to stableAnchorY
    }

    private fun adopt(sx: Float, sy: Float, ax: Float, ay: Float) {
        stableSubjectX = sx
        stableSubjectY = sy
        stableAnchorX = ax
        stableAnchorY = ay
    }

    /**
     * 当前"已锁定的主体中心"。主体回到这个位置附近时实点不会动。
     * 用于调试显示。
     */
    fun stableCenter(): Pair<Float, Float>? =
        if (stableSubjectX.isNaN()) null else stableSubjectX to stableSubjectY

    /** 实点是否已稳定（供 UI 判断是否显示"已锁定构图"） */
    fun isSettled(): Boolean = !stableAnchorX.isNaN() && !lastUpdated

    companion object {
        /**
         * 判断主体是否"稳定存在于同一位置"（用于决定要不要锁定、要不要重新计算）。
         * 这里只做简单距离判断，真正的稳定器在 [stabilize] 里。
         */
        fun close(a: Subject, b: Subject, tol: Float = 0.06f): Boolean =
            hypot(a.cx - b.cx, a.cy - b.cy) < tol
    }
}
