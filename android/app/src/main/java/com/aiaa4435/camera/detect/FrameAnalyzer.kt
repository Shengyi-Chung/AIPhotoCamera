package com.aiaa4435.camera.detect

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/** 一帧的分析结果（会送到 UI 线程显示） */
data class FrameMetrics(
    val horizonAngle: Float,
    val horizonConfidence: Float,
    val horizonYRatio: Float,
    val verticalAngle: Float,
    val verticalConfidence: Float,
    val verticalXRatio: Float,
    val meanLuma: Float,
    val darkRatio: Float,
    val brightRatio: Float,
    val analyzeMs: Long,
    val analysisW: Int,
    val analysisH: Int,
    val rotation: Int
)

/**
 * ImageAnalysis 的分析器：
 * 1. 从 ImageProxy 抠出 Y 平面（YUV 的 Y 就是灰度，不用转 RGB）
 * 2. 降采样到 320×240 左右
 * 3. 跑几何检测
 * 4. 鲁棒平滑后回调给 UI
 *
 * 注意：ImageProxy 必须 close()，否则相机管线会卡住。
 */
class FrameAnalyzer(
    private val targetW: Int = 320,
    private val targetH: Int = 240
) : ImageAnalysis.Analyzer {

    /** 回调在分析线程，UI 侧自己切主线程 */
    var onMetrics: ((FrameMetrics) -> Unit)? = null

    /**
     * 主体检测钩子：在 ImageProxy 被 close 之前调用。
     *
     * 契约：**调用方必须在自己处理完毕后调用 onDone()**，
     * 否则这一帧的 ImageProxy 永远不会关闭，相机管线会卡住。
     *
     * 为什么不开第二条 ImageAnalysis：两条流会互相抢相机管线，
     * 而且同一帧喂两次等于白做功。这里借道一帧，省一条流。
     */
    var frameTap: ((ImageProxy, onDone: () -> Unit) -> Unit)? = null

    /** 助手关闭时可停止分析，省电 */
    @Volatile
    var enabled: Boolean = true

    /** 上一次回调时间，用于限流 */
    @Volatile
    private var lastEmit = 0L

    /** 限流间隔（毫秒）：UI 更新不用每帧都刷 */
    var emitIntervalMs: Long = 250

    private var reusable = ByteArray(0)

    /**
     * P2-b2：从"三档滤波（置信度门槛 + EMA + 死区）"换成鲁棒重加权平均。
     *
     * 之前那套的问题是坏值**等权**掺进来：只要有一个偏离 3° 的坏帧，
     * 估计就被拖走，需要十几帧才慢慢回来 —— 表现就是"数值每隔一两秒跳一次"。
     * 现在按"离当前估计多远"给权重（Cauchy），坏值几乎推不动估计，
     * 正常帧权重接近 1，所以既不钝也不跳。
     */
    private val horizonSmoother = RobustSmoother(alpha = 0.25f, outlierScaleDeg = 2.0f)
    private val verticalSmoother = RobustSmoother(alpha = 0.25f, outlierScaleDeg = 2.0f)

    /** 低于这个置信度就不更新（画面里确实没有边的时候保持上次结果） */
    private val minConfidence = 0.20f

    private var lastHorizonConf = 0f
    private var lastVerticalConf = 0f
    private var lastHorizonYRatio = 0.5f
    private var lastVerticalXRatio = 0.5f

    /** 最近一次的平滑结果，供校准流程即时读取 */
    val horizonAngle: Float get() = horizonSmoother.value
    val verticalAngle: Float get() = verticalSmoother.value
    val horizonConfidence: Float get() = lastHorizonConf
    val verticalConfidence: Float get() = lastVerticalConf
    val horizonYRatio: Float get() = lastHorizonYRatio
    val verticalXRatio: Float get() = lastVerticalXRatio

    override fun analyze(image: ImageProxy) {
        try {
            if (!enabled) return

            // 主体检测先拿帧（它有自己的节流），不能受下面几何检测的限流影响。
            // onDone 保证 ImageProxy 在主体检测真正用完之后才被 close。
            frameTap?.invoke(image) { closeOnce(image) }
                ?: closeOnce(image)

            val now = SystemClock.elapsedRealtime()
            if (now - lastEmit < emitIntervalMs) return   // 限流，避免 UI 抖动

            val t0 = SystemClock.elapsedRealtime()

            val (gray, w, h) = extractGray(image)
            if (w < 8 || h < 8) return

            var horizon = Geometry.estimateHorizon(gray, w, h)
            var vertical = Geometry.detectVerticals(gray, w, h)
            val brightness = Geometry.measureBrightness(gray)

            // 图像旋转处理：rotation=90/270 时，图像是横的，
            // "逐列找水平边" 其实找到的是现实世界里的竖线，交换过来
            val rot = image.imageInfo.rotationDegrees
            if (rot == 90 || rot == 270) {
                val tmpH = horizon
                val tmpV = vertical
                horizon = Geometry.Horizon(
                    angleDeg = tmpV.angleDeg,
                    confidence = tmpV.confidence,
                    lineY = w / 2f,
                    lineYRatio = 0.5f,
                    slope = 0f,
                    sampleCount = 0
                )
                vertical = Geometry.Vertical(
                    angleDeg = tmpH.angleDeg,
                    confidence = tmpH.confidence,
                    coverage = 0f,
                    lineXRatio = 0.5f
                )
            }

            // 置信度太低：不喂新值，沿用上次的平滑结果（避免归零跳变）
            if (horizon.confidence >= minConfidence) {
                horizonSmoother.push(horizon.angleDeg, horizon.confidence)
                lastHorizonConf = horizon.confidence
                lastHorizonYRatio = horizon.lineYRatio
            }
            if (vertical.confidence >= minConfidence) {
                verticalSmoother.push(vertical.angleDeg, vertical.confidence)
                lastVerticalConf = vertical.confidence
                lastVerticalXRatio = vertical.lineXRatio
            }

            val metrics = FrameMetrics(
                horizonAngle = horizonSmoother.value,
                horizonConfidence = lastHorizonConf,
                horizonYRatio = horizon.lineYRatio,
                verticalAngle = verticalSmoother.value,
                verticalConfidence = lastVerticalConf,
                verticalXRatio = lastVerticalXRatio,
                meanLuma = brightness.meanLuma,
                darkRatio = brightness.darkRatio,
                brightRatio = brightness.brightRatio,
                analyzeMs = SystemClock.elapsedRealtime() - t0,
                analysisW = w,
                analysisH = h,
                rotation = rot
            )

            lastEmit = now
            onMetrics?.invoke(metrics)
        } catch (t: Throwable) {
            // 分析失败不能影响拍照
            closeOnce(image)
        }
        // 注意：这里**不能**在 finally 里 close —— 帧可能已经交给异步的主体检测，
        // 由它处理完回调 closeOnce。double close 会被 closeOnce 挡掉。
    }

    /** 保证同一个 ImageProxy 只被关闭一次（每帧独立一个，不能用成员变量！） */
    private fun closeOnce(image: ImageProxy) {
        val guard = closeGuards.getOrPut(image) { java.util.concurrent.atomic.AtomicBoolean(false) }
        if (guard.compareAndSet(false, true)) {
            runCatching { image.close() }
            closeGuards.remove(image)
        }
    }

    private val closeGuards =
        java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<ImageProxy, java.util.concurrent.atomic.AtomicBoolean>()
        )

    /** 换机位 / 重新校准时清掉历史，别让旧场景的值带过来 */
    fun resetSmoothing() {
        horizonSmoother.reset()
        verticalSmoother.reset()
        lastHorizonConf = 0f
        lastVerticalConf = 0f
    }

    /**
     * 把 ImageProxy 的 Y 平面转成紧凑灰度数组（带降采样）。
     * @return Triple(gray, width, height)
     */
    private fun extractGray(image: ImageProxy): Triple<ByteArray, Int, Int> {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val srcW = image.width
        val srcH = image.height

        // 一次性把平面数据读出来，避免逐字节 get() 的开销
        val src = ByteArray(buffer.remaining())
        buffer.rewind()
        buffer.get(src)

        // 降采样步长
        val step = kotlin.math.max(1, kotlin.math.min(srcW / targetW, srcH / targetH))
        val w = srcW / step
        val h = srcH / step
        if (w < 8 || h < 8) return Triple(ByteArray(0), 0, 0)

        val need = w * h
        if (reusable.size != need) reusable = ByteArray(need)
        val gray = reusable

        var oi = 0
        var sy = 0
        while (sy < srcH && oi < need) {
            val rowBase = sy * rowStride
            var sx = 0
            while (sx < srcW && oi < need) {
                val idx = rowBase + sx * pixelStride
                gray[oi] = if (idx < src.size) src[idx] else 0
                oi++
                sx += step
            }
            sy += step
        }

        return Triple(gray, w, h)
    }
}
