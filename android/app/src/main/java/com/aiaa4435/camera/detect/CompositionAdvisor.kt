package com.aiaa4435.camera.detect

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 构图建议：算出"镜头中心应该挪到哪"。
 *
 * ## 问题定义
 *
 * 画面里已经锁定了主体（一个矩形框）。现在的问题是：
 * **镜头中心应该指向哪里，拍出来的构图才是好的？**
 *
 * 答案就是**实点** —— 用户在屏幕上要做的，就是移动手机，
 * 让画面中心（虚点，固定在屏幕中央）挪到实点上去。挪到了，就拍。
 *
 * ## 为什么实点不等于主体中心
 *
 * 如果实点就是主体中心，那拍出来的就是"主体正好在画面正中间"——
 * 这是最呆板的构图。好的构图要把主体放在三分交点附近，并给它前方留出空间。
 *
 * 所以实点的计算是：**从主体位置反推出镜头该往哪偏**。
 *
 * 类比：主体现在在画面的 (sx, sy)，
 * 我们希望它最后落在三分交点 (ax, ay)，
 * 那么镜头中心就需要往主体方向偏移，偏移量 = (主体当前偏离目标构图位置的矢量)。
 *
 * 用归一化坐标表达：
 * ```
 * 实点 = 0.5 + ((目标构图位置) - (主体当前位置))
 * ```
 * 直觉：主体在右下方太多了 → 实点偏向左上 → 用户把手机往左上移 → 主体回到构图位置。
 *
 * ## 三条构图规则（按优先级）
 *
 * 1. **三分法**：主体中心靠近最近的三分交点
 * 2. **前方留白**：主体面向哪边，就在那边多留空间（人像左看 → 主体靠右三分线）
 * 3. **水平安全**：实点不要贴边，否则用户挪不到
 */
object CompositionAdvisor {

    /** 实点离画面边缘的最小距离（归一化），防止实点跑到够不着的地方 */
    const val MARGIN = 0.08f

    /** 判为"主体已在合适构图位置"的容差 */
    const val GOOD_COMPOSITION_TOLERANCE = 0.045f

    data class Suggestion(
        /** 实点：镜头中心应该到达的位置（归一化 0..1） */
        val anchorX: Float,
        val anchorY: Float,
        /** 主体当前应落到的理想构图位置，用于内部计算和调试 */
        val targetX: Float,
        val targetY: Float,
        /** 当前构图已经够好了（不需要挪） */
        val alreadyGood: Boolean,
        /** 给用户的一句话提示 */
        val hint: String
    )

    /**
     * 计算构图建议。
     *
     * @param subject 锁定主体的框（归一化坐标）
     * @param facingRight 主体朝向：null=未知，true=朝右，false=朝左。
     *        未知时按"朝向画面中心"处理（即不做留白补偿）。
     */
    fun advise(subject: Subject, facingRight: Boolean? = null): Suggestion {
        val (ax, ay) = subject.cx to subject.cy

        // ---- 规则 1：三分法。选离主体最近的三分交点 ----
        val thirdCandidates = floatArrayOf(1f / 3f, 2f / 3f)
        var targetX = thirdCandidates.minByOrNull { abs(it - ax) } ?: 0.5f
        var targetY = thirdCandidates.minByOrNull { abs(it - ay) } ?: 0.5f

        // ---- 规则 2：前方留白 ----
        // 主体朝右 → 让它落在右三分线（左边留出空间给它"看"的方向感）；
        // 主体朝左 → 落在左三分线。
        // 未知道朝向时保持三分法结果，不强行改。
        if (facingRight != null) {
            targetX = if (facingRight) 2f / 3f else 1f / 3f
        }

        // ---- 反推实点：镜头中心应该去哪 ----
        //
        // 关键理解：镜头往右移 → 画面里所有东西往**左**移。
        // 主体现在在 ax，我们希望它到 targetX。
        // 需要的画面位移 = (targetX - ax)。若为负（主体太靠右，要往左挪到三分线），
        // 那镜头就要往**右**移，位移量相同、方向相反：
        //     实点 = 0.5 + (ax - targetX)
        //
        // 验证：主体 ax=0.8，target=0.667 → 实点 = 0.5 + 0.133 = 0.633（偏右）
        //      用户把手机往右移，主体在画面里左移到 0.667 ✓
        var anchorX = 0.5f + (ax - targetX)
        var anchorY = 0.5f + (ay - targetY)

        // ---- 规则 3：实点不能贴边，否则用户挪不过去 ----
        anchorX = anchorX.coerceIn(MARGIN, 1f - MARGIN)
        anchorY = anchorY.coerceIn(MARGIN, 1f - MARGIN)

        // 主体已经接近理想位置？
        val dist = kotlin.math.hypot(targetX - ax, targetY - ay)
        val good = dist <= GOOD_COMPOSITION_TOLERANCE

        return Suggestion(
            anchorX = anchorX,
            anchorY = anchorY,
            targetX = targetX,
            targetY = targetY,
            alreadyGood = good,
            hint = buildHint(good, anchorX, anchorY, targetX, targetY)
        )
    }

    private fun buildHint(
        good: Boolean,
        anchorX: Float,
        anchorY: Float,
        targetX: Float,
        targetY: Float
    ): String {
        if (good) return "构图可以，直接拍"

        val parts = mutableListOf<String>()
        // 用户要做的动作 = 把画面中心往实点挪，也就是往实点相对中心的那个方向挪
        val dx = anchorX - 0.5f
        val dy = anchorY - 0.5f

        if (abs(dx) > 0.045f) parts += if (dx > 0) "往右" else "往左"
        if (abs(dy) > 0.045f) parts += if (dy > 0) "往下" else "往上"

        return if (parts.isEmpty()) "微调一下" else "手机" + parts.joinToString("")
    }
}
