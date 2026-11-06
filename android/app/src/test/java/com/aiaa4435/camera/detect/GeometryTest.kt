package com.aiaa4435.camera.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan

/**
 * 几何检测的单元测试（纯 JVM，不需要真机）
 *
 * 对应 Web 版 test_detect.js 的用例，确认移植后行为一致。
 * 跑：gradlew testDebugUnitTest
 */
class GeometryTest {

    private val w = 320
    private val h = 240

    /** 合成：上半天空亮、下半地面暗，中间一条斜线 */
    private fun makeHorizon(slopeDeg: Double, sky: Int = 200, ground: Int = 45): ByteArray {
        val slope = tan(Math.toRadians(slopeDeg))
        val g = ByteArray(w * h)
        for (x in 0 until w) {
            val yLine = h / 2.0 + slope * (x - w / 2.0)
            for (y in 0 until h) {
                val base = if (y < yLine) sky else ground
                val noise = ((x * 7 + y * 13) % 11) - 5
                g[y * w + x] = (base + noise).coerceIn(0, 255).toByte()
            }
        }
        return g
    }

    @Test
    fun `水平线应估为 0 度`() {
        val r = Geometry.estimateHorizon(makeHorizon(0.0), w, h)
        assertTrue("angle=${r.angleDeg}", abs(r.angleDeg) < 1.2f)
        assertTrue("conf=${r.confidence}", r.confidence > 0.5f)
    }

    @Test
    fun `倾斜 10 度应估在 8_5 到 11_5 之间`() {
        val r = Geometry.estimateHorizon(makeHorizon(10.0), w, h)
        assertTrue("angle=${r.angleDeg}", r.angleDeg in 8.5f..11.5f)
    }

    @Test
    fun `倾斜负 6 度应估为负值`() {
        val r = Geometry.estimateHorizon(makeHorizon(-6.0), w, h)
        assertTrue("angle=${r.angleDeg}", r.angleDeg in -7.5f..-4.5f)
    }

    @Test
    fun `全暗图的亮度统计`() {
        val dark = ByteArray(w * h) { 30.toByte() }
        val b = Geometry.measureBrightness(dark)
        assertTrue("dark=${b.darkRatio}", b.darkRatio > 0.99f)
        assertTrue("luma=${b.meanLuma}", abs(b.meanLuma - 30f) < 1f)
    }

    @Test
    fun `全亮图应统计为高光溢出`() {
        val bright = ByteArray(w * h) { 250.toByte() }
        val b = Geometry.measureBrightness(bright)
        assertTrue("bright=${b.brightRatio}", b.brightRatio > 0.99f)
    }

    @Test
    fun `竖直线检测`() {
        // 合成：竖直条纹（多列深色竖线）
        val g = ByteArray(w * h) { 150.toByte() }
        for (i in 0 until 6) {
            val x0 = (i + 0.5) * w / 6
            for (y in 0 until h) {
                val x = x0.toInt()
                if (x in 0 until w) g[y * w + x] = 30.toByte()
            }
        }
        val v = Geometry.detectVerticals(g, w, h)
        assertTrue("angle=${v.angleDeg}", abs(v.angleDeg) < 1.5f)
        assertTrue("coverage=${v.coverage}", v.coverage > 0.3f)
    }

    @Test
    fun `纯色图不应给出虚假地平线`() {
        val flat = ByteArray(w * h) { 128.toByte() }
        val r = Geometry.estimateHorizon(flat, w, h)
        assertTrue("confidence=${r.confidence}", r.confidence < 0.3f)
    }

    /** 合成：一条真实竖线，位置和角度都可控 */
    private fun makeVertical(xAtCenter: Int, angleDeg: Double): ByteArray {
        val slope = tan(Math.toRadians(angleDeg))
        val g = ByteArray(w * h) { 170.toByte() }
        for (y in 0 until h) {
            val xLine = xAtCenter + slope * (y - h / 2.0)
            val x = xLine.toInt()
            if (x in 1 until w - 1) {
                g[y * w + x] = 40.toByte()
                g[y * w + x - 1] = 60.toByte()
                g[y * w + x + 1] = 60.toByte()
            }
        }
        return g
    }

    /**
     * P2-v4 关键回归：检测到的竖线**必须能画在它真实所在的位置**。
     * 如果 lineXRatio 算错，画面上显示的线和算法认的线就对不上，
     * 那个可视化就是在骗用户 —— 比不画更糟。
     */
    @Test
    fun `竖线位置应可还原到画面中心`() {
        val v = Geometry.detectVerticals(makeVertical(160, 0.0), w, h)
        assertTrue("conf=${v.confidence}", v.confidence > 0.3f)
        assertEquals(0.5f, v.lineXRatio, 0.02f)
    }

    @Test
    fun `竖线位置应跟随实际偏移`() {
        val v = Geometry.detectVerticals(makeVertical(240, 0.0), w, h)
        assertTrue("conf=${v.confidence}", v.confidence > 0.3f)
        assertEquals(0.75f, v.lineXRatio, 0.02f)
    }

    @Test
    fun `倾斜竖线的角度和位置都要对`() {
        val v = Geometry.detectVerticals(makeVertical(200, 5.0), w, h)
        assertTrue("angle=${v.angleDeg}", abs(v.angleDeg) > 3f)
        assertEquals(200f / w, v.lineXRatio, 0.03f)
    }
}
