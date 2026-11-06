package com.aiaa4435.camera.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 实点稳定器测试。
 *
 * 用户反馈的原始问题："实点太抖了"。
 * 这里锁住两条必须同时满足的性质：
 *  1. **静止时框抖动，实点纹丝不动**
 *  2. **用户真的移动时，实点要跟上**（不能修好抖动却变成迟钝）
 */
class AnchorStabilizerTest {

    @Test
    fun `首次输入直接采纳`() {
        val s = AnchorStabilizer()
        val (ax, ay) = s.stabilize(0.3f, 0.4f, 0.6f, 0.55f)
        assertEquals(0.6f, ax, 1e-4f)
        assertEquals(0.55f, ay, 1e-4f)
        assertTrue(s.lastUpdated)
    }

    @Test
    fun `带内小幅抖动完全不改变实点`() {
        val s = AnchorStabilizer(bandX = 0.045f, bandY = 0.045f)
        s.stabilize(0.30f, 0.40f, 0.60f, 0.55f)

        // 模拟框抖动：主体中心在 ±2% 内晃，持续 50 帧
        val jitter = floatArrayOf(0.01f, -0.015f, 0.02f, -0.01f, 0.005f, -0.02f)
        repeat(50) { i ->
            val jx = jitter[i % jitter.size]
            val jy = jitter[(i + 2) % jitter.size]
            val (ax, ay) = s.stabilize(0.30f + jx, 0.40f + jy, 0.60f - jx, 0.55f - jy)
            // 关键断言：无论框怎么抖，输出的实点必须完全不变
            assertEquals("第 $i 帧实点 x 变了", 0.60f, ax, 1e-5f)
            assertEquals("第 $i 帧实点 y 变了", 0.55f, ay, 1e-5f)
        }
        assertEquals("不该产生任何更新", 0, s.updateCount)
    }

    @Test
    fun `用户真实移动后实点更新`() {
        val s = AnchorStabilizer(bandX = 0.045f, bandY = 0.045f, confirmFrames = 2)
        s.stabilize(0.30f, 0.40f, 0.60f, 0.55f)

        // 用户把手机挪了：主体中心明显偏移 12%（远超带宽）
        var last = Pair(0.60f, 0.55f)
        repeat(5) {
            last = s.stabilize(0.42f, 0.40f, 0.48f, 0.55f)
        }
        assertEquals("主体移动后实点应更新到 0.48", 0.48f, last.first, 1e-4f)
        assertTrue("应记录到更新", s.updateCount > 0)
    }

    @Test
    fun `单帧跳变不会立刻被采纳`() {
        val s = AnchorStabilizer(bandX = 0.045f, bandY = 0.045f, confirmFrames = 3)
        s.stabilize(0.30f, 0.40f, 0.60f, 0.55f)

        // 只有一帧跳到带外（检测器偶发误判），不应改变实点
        val (ax, _) = s.stabilize(0.50f, 0.40f, 0.40f, 0.55f)
        assertEquals("单帧跳变不应被采纳", 0.60f, ax, 1e-4f)
    }

    @Test
    fun `连续同向偏离会被采纳`() {
        val s = AnchorStabilizer(bandX = 0.045f, bandY = 0.045f, confirmFrames = 2)
        s.stabilize(0.30f, 0.40f, 0.60f, 0.55f)

        s.stabilize(0.50f, 0.40f, 0.40f, 0.55f)   // 第 1 帧
        val (ax, _) = s.stabilize(0.50f, 0.40f, 0.40f, 0.55f)  // 第 2 帧 → 确认
        assertEquals(0.40f, ax, 1e-4f)
    }

    @Test
    fun `反向偏离会重置确认计数`() {
        val s = AnchorStabilizer(bandX = 0.045f, bandY = 0.045f, confirmFrames = 2)
        s.stabilize(0.30f, 0.40f, 0.60f, 0.55f)

        s.stabilize(0.50f, 0.40f, 0.40f, 0.55f)   // 往右偏
        s.stabilize(0.10f, 0.40f, 0.80f, 0.55f)   // 又往左偏（方向相反）
        val (ax, _) = s.stabilize(0.10f, 0.40f, 0.80f, 0.55f)
        // 反向那帧重置了计数，这帧才算第 2 次同向
        assertEquals(0.80f, ax, 1e-4f)
    }

    @Test
    fun `边界附近来回抖动不触发更新`() {
        val s = AnchorStabilizer(bandX = 0.045f, bandY = 0.045f, confirmFrames = 2)
        s.stabilize(0.30f, 0.40f, 0.60f, 0.55f)

        // 在带宽边界来回穿越：每次都是单帧，不应累积成更新
        repeat(20) { i ->
            val x = if (i % 2 == 0) 0.30f + 0.05f else 0.30f - 0.05f
            s.stabilize(x, 0.40f, 0.60f, 0.55f)
        }
        assertEquals("边界抖动不该产生稳定更新", 0, s.updateCount)
    }

    @Test
    fun `reset 后重新开始`() {
        val s = AnchorStabilizer()
        s.stabilize(0.30f, 0.40f, 0.60f, 0.55f)
        s.reset()
        val (ax, ay) = s.stabilize(0.90f, 0.10f, 0.20f, 0.80f)
        assertEquals(0.20f, ax, 1e-4f)
        assertEquals(0.80f, ay, 1e-4f)
    }

    @Test
    fun `抖动幅度对比 稳定器显著优于无稳定`() {
        // 模拟 40 帧的框抖动
        val jitter = floatArrayOf(0.018f, -0.012f, 0.015f, -0.020f, 0.008f, -0.016f)

        // 无稳定：实点直接跟着抖
        var rawVariance = 0.0
        var prevRaw = 0.0
        // 有稳定
        val s = AnchorStabilizer()
        s.stabilize(0.30f, 0.40f, 0.60f, 0.55f)
        var stableMoves = 0
        var prevStable = 0.60f

        repeat(40) { i ->
            val j = jitter[i % jitter.size]
            val raw = 0.60f - j          // 实点公式：主体偏右实点也偏右
            if (i > 0) rawVariance += abs(raw - prevRaw)
            prevRaw = raw.toDouble()

            val (ax, _) = s.stabilize(0.30f + j, 0.40f, raw, 0.55f)
            if (abs(ax - prevStable) > 1e-5f) stableMoves++
            prevStable = ax
        }

        assertTrue("原始实点应该有明显累计位移（模拟有效），实际 $rawVariance", rawVariance > 0.1)
        assertEquals("稳定器后的实点应完全不动", 0, stableMoves)
    }
}
