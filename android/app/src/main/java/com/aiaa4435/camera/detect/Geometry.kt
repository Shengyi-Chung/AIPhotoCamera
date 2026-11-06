package com.aiaa4435.camera.detect

/**
 * 几何检测（纯函数，不依赖 Android 框架）
 *
 * 输入统一是「紧凑排布的灰度图」：gray[y * w + x]，w×h。
 * 从 Web 版 detect.js 移植，算法保持一致，方便对照调试。
 */
object Geometry {

    data class Horizon(
        val angleDeg: Float,      // 倾斜角，正值=右高左低
        val confidence: Float,    // 0..1
        val lineY: Float,         // 画面中心处的高度（像素）
        val lineYRatio: Float,    // lineY / height
        val slope: Float,
        val sampleCount: Int
    )

    data class Vertical(
        val angleDeg: Float,      // 偏离竖直的角度
        val confidence: Float,
        val coverage: Float,      // 参与拟合的行占比
        val lineXRatio: Float = 0.5f   // 画面中心处的 x / width，用来把线画出来
    )

    data class Brightness(
        val meanLuma: Float,      // 0..255
        val darkRatio: Float,     // <40 的占比
        val brightRatio: Float    // >245 的占比（近似反光/过曝）
    )

    private class Fit(val a: Float, val b: Float)

    private fun weightedFit(px: FloatArray, py: FloatArray, pw: FloatArray, n: Int): Fit {
        var sw = 0f; var sx = 0f; var sy = 0f; var sxx = 0f; var sxy = 0f
        for (i in 0 until n) {
            val w = pw[i]
            sw += w; sx += w * px[i]; sy += w * py[i]
            sxx += w * px[i] * px[i]; sxy += w * px[i] * py[i]
        }
        val den = sw * sxx - sx * sx
        if (kotlin.math.abs(den) < 1e-6f) return Fit(0f, sy / sw)
        val a = (sw * sxy - sx * sy) / den
        val b = (sxx * sy - sx * sxy) / den
        return Fit(a, b)
    }

    private fun stddev(v: FloatArray, n: Int): Float {
        var m = 0f
        for (i in 0 until n) m += v[i]
        m /= n
        var acc = 0f
        for (i in 0 until n) {
            val d = v[i] - m
            acc += d * d
        }
        return kotlin.math.sqrt(acc / n)
    }

    /**
     * 估水平线：逐列找最强水平边 → 加权最小二乘拟合 → 内点筛选后重拟合
     */
    fun estimateHorizon(gray: ByteArray, w: Int, h: Int): Horizon {
        val empty = Horizon(0f, 0f, h / 2f, 0.5f, 0f, 0)
        if (w < 8 || h < 8) return empty

        val m = 2
        val minStrength = 12
        val xs = FloatArray(w)
        val ys = FloatArray(w)
        val ws = FloatArray(w)
        var n = 0

        for (x in m until w - m) {
            var bestG = 0
            var bestY = -1
            for (y in m until h - m) {
                val i = y * w + x
                val gy = kotlin.math.abs(gray[i - w] - gray[i + w])
                val gx = (kotlin.math.abs(gray[i - 1] - gray[i + 1]) * 0.25f).toInt()
                val g = gy - gx
                if (g > bestG) { bestG = g; bestY = y }
            }
            if (bestG >= minStrength && bestY > 0) {
                xs[n] = x.toFloat(); ys[n] = bestY.toFloat(); ws[n] = bestG.toFloat(); n++
            }
        }

        if (n < w * 0.35f) return empty

        var fit = weightedFit(xs, ys, ws, n)

        // 内点筛选
        val resid = FloatArray(n)
        for (i in 0 until n) resid[i] = kotlin.math.abs(ys[i] - (fit.a * xs[i] + fit.b))
        val sd = stddev(resid, n)
        val thresh = kotlin.math.max(2f, 2f * sd)

        val kx = FloatArray(n); val ky = FloatArray(n); val kw = FloatArray(n)
        var k = 0
        for (i in 0 until n) {
            if (resid[i] <= thresh) { kx[k] = xs[i]; ky[k] = ys[i]; kw[k] = ws[i]; k++ }
        }
        if (k < w * 0.3f) return empty

        fit = weightedFit(kx, ky, kw, k)
        val angle = Math.toDegrees(kotlin.math.atan(fit.a.toDouble())).toFloat()
        val cover = k.toFloat() / w
        val stable = if (sd < 3f) 1f else kotlin.math.max(0.4f, 3f / sd)
        val lineY = fit.a * (w / 2f) + fit.b

        return Horizon(
            angleDeg = angle,
            confidence = (kotlin.math.min(1f, cover * 1.2f) * stable),
            lineY = lineY,
            lineYRatio = (lineY / h),
            slope = fit.a,
            sampleCount = k
        )
    }

    /**
     * 估竖直线：逐行找最强竖直边 → 拟合 x = a·y + b（转置后复用最小二乘）
     */
    fun detectVerticals(gray: ByteArray, w: Int, h: Int): Vertical {
        val empty = Vertical(0f, 0f, 0f)
        if (w < 8 || h < 8) return empty

        val m = 2
        val minStrength = 12
        val px = FloatArray(h)   // 自变量 = y
        val py = FloatArray(h)   // 因变量 = x
        val pw = FloatArray(h)
        var n = 0

        for (y in m until h - m) {
            var bestG = 0
            var bestX = -1
            for (x in m until w - m) {
                val i = y * w + x
                val gx = kotlin.math.abs(gray[i - 1] - gray[i + 1])
                val gy = (kotlin.math.abs(gray[i - w] - gray[i + w]) * 0.25f).toInt()
                val g = gx - gy
                if (g > bestG) { bestG = g; bestX = x }
            }
            if (bestG >= minStrength && bestX > 0) {
                px[n] = y.toFloat(); py[n] = bestX.toFloat(); pw[n] = bestG.toFloat(); n++
            }
        }

        val coverage = n.toFloat() / h
        if (n < h * 0.30f) return empty

        var fit = weightedFit(px, py, pw, n)

        val resid = FloatArray(n)
        for (i in 0 until n) resid[i] = kotlin.math.abs(py[i] - (fit.a * px[i] + fit.b))
        val sd = stddev(resid, n)
        val thresh = kotlin.math.max(2f, 2f * sd)

        val kx = FloatArray(n); val ky = FloatArray(n); val kw = FloatArray(n)
        var k = 0
        for (i in 0 until n) {
            if (resid[i] <= thresh) { kx[k] = px[i]; ky[k] = py[i]; kw[k] = pw[i]; k++ }
        }
        if (k < h * 0.25f) return empty

        fit = weightedFit(kx, ky, kw, k)
        val angle = Math.toDegrees(kotlin.math.atan(fit.a.toDouble())).toFloat()
        val cover = k.toFloat() / h
        val stable = if (sd < 3f) 1f else kotlin.math.max(0.4f, 3f / sd)

        // x = a·y + b，取画面中心行（y = h/2）处的 x，用于把检测到的线画出来
        val lineX = fit.a * (h / 2f) + fit.b

        return Vertical(
            angleDeg = angle,
            confidence = kotlin.math.min(1f, cover * 1.2f) * stable,
            coverage = cover,
            lineXRatio = lineX / w
        )
    }

    /** 明暗统计 */
    fun measureBrightness(gray: ByteArray): Brightness {
        var sum = 0L
        var dark = 0
        var bright = 0
        for (b in gray) {
            val v = b.toInt() and 0xFF
            sum += v
            if (v < 40) dark++
            if (v > 245) bright++
        }
        val n = gray.size.toFloat()
        return Brightness(
            meanLuma = sum / n,
            darkRatio = dark / n,
            brightRatio = bright / n
        )
    }
}
