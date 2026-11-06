package com.aiaa4435.camera.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 构图建议测试。
 *
 * 核心语义（最容易搞反的地方）：
 * **实点 = 镜头中心应该到达的位置**，不是主体该去的位置。
 *
 * 所以实点的方向是**反的**：
 * 主体在画面右侧 → 镜头要往右移才能让主体回到三分线 → 但镜头往右移，
 * 主体会更靠右…… 这里必须仔细验证方向，错了用户就会越拍越歪。
 */
class CompositionAdvisorTest {

    private fun subject(l: Float, t: Float, r: Float, b: Float) =
        Subject(l, t, r, b, trackingId = 1)

    @Test
    fun `主体偏右时实点也在右 镜头要右移`() {
        // 主体中心 x≈0.80，目标三分线 0.667。
        // 主体要从 0.80 左移到 0.667 → 镜头要往**右**移。
        // 实点 = 0.5 + (0.80 - 0.667) = 0.633，偏右 ✓
        val s = subject(0.70f, 0.30f, 0.90f, 0.70f)
        val a = CompositionAdvisor.advise(s)
        assertTrue("主体在右，实点应偏右（镜头右移），实际 anchorX=${a.anchorX}", a.anchorX > 0.5f)
    }

    @Test
    fun `主体偏左时实点在左 镜头要左移`() {
        val s = subject(0.10f, 0.30f, 0.30f, 0.70f)
        val a = CompositionAdvisor.advise(s)
        assertTrue("主体在左，实点应偏左，实际 anchorX=${a.anchorX}", a.anchorX < 0.5f)
    }

    @Test
    fun `主体偏下时实点在下 镜头要下移`() {
        val s = subject(0.40f, 0.60f, 0.60f, 0.90f)
        val a = CompositionAdvisor.advise(s)
        assertTrue("主体在下方，实点应偏下，实际 anchorY=${a.anchorY}", a.anchorY > 0.5f)
    }

    @Test
    fun `实点始终在画面内留出边距`() {
        // 极端的框：贴着画面角落
        val s = subject(0.0f, 0.0f, 0.20f, 0.20f)
        val a = CompositionAdvisor.advise(s)
        assertTrue("anchorX=${a.anchorX}", a.anchorX in CompositionAdvisor.MARGIN..(1f - CompositionAdvisor.MARGIN))
        assertTrue("anchorY=${a.anchorY}", a.anchorY in CompositionAdvisor.MARGIN..(1f - CompositionAdvisor.MARGIN))
    }

    @Test
    fun `主体已在三分交点上时判为构图达标`() {
        // 主体中心正好在 (2/3, 1/3)
        val s = Subject(2f / 3f - 0.05f, 1f / 3f - 0.05f, 2f / 3f + 0.05f, 1f / 3f + 0.05f,
            trackingId = 1)
        val a = CompositionAdvisor.advise(s)
        assertTrue("应判为构图可以，实际 alreadyGood=${a.alreadyGood} dist", a.alreadyGood)
        assertEquals(0.5f, a.anchorX, 0.06f)
        assertEquals(0.5f, a.anchorY, 0.06f)
    }

    @Test
    fun `主体在正中时建议移到三分点`() {
        val s = subject(0.40f, 0.40f, 0.60f, 0.60f)   // 中心 (0.5, 0.5)
        val a = CompositionAdvisor.advise(s)
        assertTrue("正中的构图应被判为不够好", !a.alreadyGood)
        // 目标应是某个三分点
        assertTrue(a.targetX == 1f / 3f || a.targetX == 2f / 3f)
    }

    @Test
    fun `朝向已知时按留白规则选三分线`() {
        val s = subject(0.40f, 0.35f, 0.60f, 0.65f)
        // 朝右 → 主体应落在右三分线
        val right = CompositionAdvisor.advise(s, facingRight = true)
        assertEquals(2f / 3f, right.targetX, 1e-4f)
        // 朝左 → 主体应落在左三分线
        val left = CompositionAdvisor.advise(s, facingRight = false)
        assertEquals(1f / 3f, left.targetX, 1e-4f)
    }

    @Test
    fun `提示文字方向正确`() {
        // 主体偏右 → 实点偏右 → 提示应该让用户把手机往右挪
        val s = subject(0.70f, 0.30f, 0.90f, 0.70f)
        val a = CompositionAdvisor.advise(s)
        assertTrue("提示=『${a.hint}』", a.hint.contains("右"))
    }

    @Test
    fun `垂直方向也给出提示`() {
        val s = subject(0.40f, 0.65f, 0.60f, 0.95f)
        val a = CompositionAdvisor.advise(s)
        assertTrue("提示=『${a.hint}』", a.hint.contains("下"))
    }

    @Test
    fun `达标时提示不再是方向词`() {
        val s = Subject(2f / 3f - 0.04f, 1f / 3f - 0.04f, 2f / 3f + 0.04f, 1f / 3f + 0.04f,
            trackingId = 1)
        val a = CompositionAdvisor.advise(s)
        assertTrue("提示=『${a.hint}』", a.hint.contains("拍"))
    }

    @Test
    fun `实点到虚点的距离随主体偏离增大`() {
        val near = subject(0.60f, 0.40f, 0.70f, 0.55f)    // 略偏右
        val far = subject(0.80f, 0.40f, 0.95f, 0.55f)     // 很偏右
        val dNear = abs(CompositionAdvisor.advise(near).anchorX - 0.5f)
        val dFar = abs(CompositionAdvisor.advise(far).anchorX - 0.5f)
        assertTrue("越偏，实点应离中心越远：near=$dNear far=$dFar", dFar > dNear)
    }
}
