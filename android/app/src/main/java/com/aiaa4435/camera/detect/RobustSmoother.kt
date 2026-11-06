package com.aiaa4435.camera.detect

/**
 * 帧间加权重平滑（temporal reweighted averaging）。
 *
 * 逐帧几何检测的抖动不是"高频噪声"这么简单——它有几个明显的坏点：
 *  1. 场景里不止一条水平边（桌沿 + 窗框 + 远处地平线），每帧"最强"的那条会跳；
 *  2. 手持微动让同一批边缘的权重此消彼长；
 *  3. 偶尔某一帧完全找不到边。
 *
 * 普通指数移动平均（EMA）有个致命问题：坏值是**等权**掺进来的，一旦被一个
 * 偏离 3° 的坏帧拉过去，需要十几帧才能拉回来，看起来就是"数值忽然跳一下"。
 *
 * 这里改成按"离当前估计有多远"给权重：
 *   w = 1 / (1 + (err / scale)²)          ← Cauchy 权重，坏值几乎不参与
 *   est ← est + alpha · (raw - est) · w
 *
 * 好处：跟手的时候不迟钝（err 小 → w≈1），偶发坏值几乎推不动估计
 * （err 大 → w≈0），比单纯压低 alpha 更能兼顾"稳"和"跟手"。
 */
class RobustSmoother(
    private val alpha: Float = 0.25f,
    private val outlierScaleDeg: Float = 2.0f
) {
    /** 当前估计值（度） */
    var value: Float = 0f
        private set

    var initialized: Boolean = false
        private set

    /** 最近一次喂进来的原始值（便于调试显示） */
    var lastRaw: Float = 0f
        private set

    /** 最近一次计算出的权重 0..1，越小说明这一帧被判成坏值 */
    var lastWeight: Float = 0f
        private set

    /** 连续被判定为坏值的帧数，用于"场景变了 / 用户猛转"的重新锁定 */
    private var consecutiveOutliers = 0

    fun reset() {
        initialized = false
        consecutiveOutliers = 0
        lastWeight = 0f
    }

    /**
     * @param raw 本帧检测到的角度
     * @param confidence 本帧检测置信度 0..1
     * @return 平滑后的值
     */
    fun push(raw: Float, confidence: Float): Float {
        lastRaw = raw

        if (!initialized) {
            value = raw
            initialized = true
            lastWeight = 1f
            return value
        }

        val err = raw - value
        val normalized = err / outlierScaleDeg
        val w = 1f / (1f + normalized * normalized) * confidence.coerceIn(0f, 1f)
        lastWeight = w

        // 连续很多帧都偏离 → 不是坏点，是用户真的转了/场景换了，快速跟上
        if (w < 0.08f) {
            consecutiveOutliers++
            if (consecutiveOutliers >= 8) {
                value = raw
                consecutiveOutliers = 0
                lastWeight = 1f
                return value
            }
        } else {
            consecutiveOutliers = 0
        }

        value += alpha * err * w
        return value
    }
}
