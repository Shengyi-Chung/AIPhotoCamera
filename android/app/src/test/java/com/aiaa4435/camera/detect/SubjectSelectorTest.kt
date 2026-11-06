package com.aiaa4435.camera.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主体选择策略测试。
 *
 * 这是 P3 的核心判断逻辑：选错主体，后面的构图建议全是错的。
 * 所以每条优先级规则都必须有测试锁住。
 */
class SubjectSelectorTest {

    private fun subject(
        l: Float, t: Float, r: Float, b: Float, id: Int = -1
    ) = Subject(l, t, r, b, trackingId = id)

    @Test
    fun `空列表返回 null`() {
        assertNull(SubjectSelector.select(emptyList()))
    }

    @Test
    fun `太小的框被忽略`() {
        // 面积 0.01 < MIN_AREA_RATIO(0.015)
        val tiny = subject(0.5f, 0.5f, 0.6f, 0.6f)
        assertNull(SubjectSelector.select(listOf(tiny)))
    }

    @Test
    fun `几乎铺满画面的框被忽略`() {
        val huge = subject(0.01f, 0.01f, 0.99f, 0.99f)   // 面积 0.96 > 0.95
        assertNull(SubjectSelector.select(listOf(huge)))
    }

    @Test
    fun `默认选离画面中心最近的`() {
        val nearCenter = subject(0.35f, 0.35f, 0.65f, 0.65f, id = 1)
        val corner = subject(0.02f, 0.02f, 0.30f, 0.30f, id = 2)

        val picked = SubjectSelector.select(listOf(corner, nearCenter))
        assertEquals(1, picked?.trackingId)
    }

    @Test
    fun `用户点击优先于中心距离`() {
        val nearCenter = subject(0.35f, 0.35f, 0.65f, 0.65f, id = 1)
        val tapped = subject(0.70f, 0.60f, 0.95f, 0.90f, id = 2)

        // 没点击时选中心的那个
        assertEquals(1, SubjectSelector.select(listOf(nearCenter, tapped))?.trackingId)

        // 点在右下角 → 必须改成选那个
        val picked = SubjectSelector.select(
            listOf(nearCenter, tapped), tapX = 0.80f, tapY = 0.75f
        )
        assertEquals(2, picked?.trackingId)
    }

    @Test
    fun `点击处没有主体时退回默认策略`() {
        val a = subject(0.35f, 0.35f, 0.65f, 0.65f, id = 1)
        val b = subject(0.02f, 0.70f, 0.20f, 0.95f, id = 2)

        // 点在左上角，附近啥也没有（搜索半径外）
        val picked = SubjectSelector.select(listOf(a, b), tapX = 0.05f, tapY = 0.02f)
        assertNotNull(picked)
        // 离点击点最近的 b 在半径内（0.35）？b 中心约 (0.11, 0.825)，
        // 距离点击点 sqrt(0.0036+0.648) ≈ 0.81 > 0.35，所以退回中心策略选 a
        assertEquals(1, picked?.trackingId)
    }

    @Test
    fun `已锁定的主体优先保持`() {
        val locked = subject(0.05f, 0.05f, 0.30f, 0.35f, id = 7)   // 离中心很远
        val center = subject(0.35f, 0.35f, 0.65f, 0.65f, id = 3)

        // 没有锁定 → 选中心的
        assertEquals(3, SubjectSelector.select(listOf(locked, center))?.trackingId)

        // 锁定 7 之后 → 即使远离中心也要跟住，避免框乱跳
        assertEquals(
            7,
            SubjectSelector.select(listOf(locked, center), lockedId = 7)?.trackingId
        )
    }

    @Test
    fun `锁定的主体消失后退回默认策略`() {
        val center = subject(0.35f, 0.35f, 0.65f, 0.65f, id = 3)
        // 锁定 id=99，但它不在本帧候选里
        assertEquals(
            3,
            SubjectSelector.select(listOf(center), lockedId = 99)?.trackingId
        )
    }

    @Test
    fun `实点放在主体的对侧三分交点`() {
        val left = subject(0.05f, 0.30f, 0.40f, 0.80f)     // 主体在左
        val (ax, ay) = SubjectSelector.anchorPoint(left)
        // 主体在左 → 实点放右三分线，给主体留出前方空间
        assertEquals(2f / 3f, ax, 1e-3f)
        assertEquals(1f / 3f, ay, 1e-3f)

        val right = subject(0.60f, 0.30f, 0.95f, 0.80f)    // 主体在右
        val (bx, _) = SubjectSelector.anchorPoint(right)
        assertEquals(1f / 3f, bx, 1e-3f)
    }

    @Test
    fun `没有主体时实点仍有确定位置`() {
        // 没主体也要给一个实点（退化成纯构图辅助，不弹提示）
        val (ax, ay) = SubjectSelector.anchorPoint(null)
        assertTrue("实点 x 应是三分点", ax == 1f / 3f || ax == 2f / 3f)
        assertEquals(1f / 3f, ay, 1e-3f)
    }

    @Test
    fun `contains 判定正确`() {
        val s = subject(0.2f, 0.3f, 0.6f, 0.8f)
        assertTrue(s.contains(0.4f, 0.5f))
        assertTrue(s.contains(0.2f, 0.3f))    // 边界算在内
        assertTrue(!s.contains(0.1f, 0.5f))
        assertTrue(!s.contains(0.4f, 0.9f))
    }
}
