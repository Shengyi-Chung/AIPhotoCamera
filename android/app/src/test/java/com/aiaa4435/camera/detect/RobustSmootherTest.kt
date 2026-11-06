package com.aiaa4435.camera.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * RobustSmoother 的关键行为验证。
 * 这些数字直接对应用户反馈的"数值一直在跳"，所以必须锁住。
 */
class RobustSmootherTest {

    @Test
    fun `first sample initializes directly`() {
        val s = RobustSmoother()
        assertEquals(12.5f, s.push(12.5f, 1f), 1e-4f)
        assertTrue(s.initialized)
    }

    @Test
    fun `steady input stays steady`() {
        val s = RobustSmoother()
        s.push(3f, 1f)
        repeat(40) { s.push(3f, 1f) }
        assertEquals(3f, s.value, 1e-3f)
    }

    @Test
    fun `single outlier barely moves estimate`() {
        val s = RobustSmoother(alpha = 0.25f, outlierScaleDeg = 2.0f)
        repeat(30) { s.push(1.0f, 1f) }
        val before = s.value

        // 一帧偏了 6°：普通 EMA 会被拉走 0.25*6=1.5°，这里必须远小于这个数
        s.push(7.0f, 1f)
        val moved = abs(s.value - before)

        assertTrue("单帧坏值不应推动超过 0.2°，实际 $moved", moved < 0.2f)
        assertTrue("坏值权重应该很小，实际 ${s.lastWeight}", s.lastWeight < 0.15f)
    }

    @Test
    fun `repeated outliers are eventually accepted as real motion`() {
        val s = RobustSmoother(alpha = 0.25f, outlierScaleDeg = 2.0f)
        repeat(30) { s.push(0f, 1f) }

        // 用户真的转了 8°：连续喂同一个新值，必须在有限帧内跟上
        repeat(20) { s.push(8f, 1f) }
        assertEquals(8f, s.value, 0.3f)
    }

    @Test
    fun `low confidence damps the update`() {
        val s = RobustSmoother(alpha = 0.25f, outlierScaleDeg = 2.0f)
        repeat(20) { s.push(0f, 1f) }

        // 变化很小本应权重接近 1，但置信度 0.05 时几乎不该动
        s.push(0.5f, 0.05f)
        assertTrue("低置信度应抑制更新，实际 ${s.value}", s.value < 0.05f)
    }

    @Test
    fun `reset forgets history`() {
        val s = RobustSmoother()
        s.push(5f, 1f)
        s.reset()
        assertFalse(s.initialized)
        s.push(-2f, 1f)
        assertEquals(-2f, s.value, 1e-4f)
    }

    @Test
    fun `jittery input produces stable output`() {
        val s = RobustSmoother(alpha = 0.25f, outlierScaleDeg = 2.0f)
        s.push(0f, 1f)

        // 模拟逐帧抖动：±0.6° 的均匀噪声（真实相机的常态）
        val noise = floatArrayOf(0.6f, -0.4f, 0.5f, -0.6f, 0.3f, -0.5f, 0.6f, -0.3f)
        repeat(60) { i ->
            s.push(noise[i % noise.size], 1f)
        }
        // 收敛后不该漂太远
        assertTrue("抖动输入下输出应接近 0，实际 ${s.value}", abs(s.value) < 0.25f)
    }
}
