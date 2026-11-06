package com.aiaa4435.camera.detect

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主体检测器。
 *
 * ## 为什么是两个检测器实例
 *
 * ML Kit 官方文档对两种模式的定位非常明确：
 *
 * > `STREAM_MODE`：低延迟，**在最初几次调用时可能返回不完整结果**（边界框或类别标签未指定）；
 * > 会给对象分配跟踪 ID。
 * > `SINGLE_IMAGE_MODE`：等边界框（和标签）确定后才返回，延迟可能更高；**不分配跟踪 ID**。
 *
 * > 该 API 针对两个核心场景优化：
 * > 1. **实时检测并跟踪取景器中最突出的对象**
 * > 2. **检测静态图片中的多个对象**
 *
 * 也就是说，"实时"场景官方做法是**单目标**（不启用 multipleObjects），
 * "多目标"是静态图片的做法。之前两个都开，导致每帧在最多 5 个对象之间
 * 重新分配 trackingId —— 框乱跳、锁不住、别的物体还干扰。
 *
 * 所以这里拆成两个实例：
 *
 * | 实例 | 模式 | 用途 |
 * |---|---|---|
 * | [streamDetector] | STREAM + **单目标** | 实时跟踪，给构图建议提供稳定输入 |
 * | [pickDetector] | SINGLE_IMAGE + **多目标** | 只在用户点击时跑一次，找出"点的到底是哪个" |
 *
 * 这样既满足官方的实时用法，又保留了"点哪个锁哪个"的能力。
 */
class SubjectDetector(
    private val streamIntervalMs: Long = 100L
) {
    /** 实时检测结果（分析线程回调） */
    var onStreamSubjects: ((List<Subject>, Long) -> Unit)? = null

    /** 点击选主体的结果（分析线程回调） */
    var onPickResult: ((Subject?) -> Unit)? = null

    /** 出错时的回调，用来在 HUD 上暴露问题（否则失败是静默的） */
    var onError: ((String) -> Unit)? = null

    @Volatile
    var enabled: Boolean = true

    // ---------------- 实时流 ----------------
    private val busy = AtomicBoolean(false)
    private var lastRun = 0L
    private var lastMs = 0L

    private var reuseBitmap: Bitmap? = null
    private var reuseCanvas: Canvas? = null

    /**
     * 实时检测器：**单目标**。
     *
     * 不开 enableClassification：文档说它只会分到"时尚商品/食品/家居用品/地点/植物"
     * 这五个粗类，对本项目没用，反而增加耗时。
     */
    private val streamDetector: ObjectDetector by lazy {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            // 不调 enableMultipleObjects() —— 默认就是只跟踪最突出的那一个
            .build()
        ObjectDetection.getClient(options)
    }

    /** 点击时用：静态图 + 多目标，用来找"用户点的是哪个" */
    private val pickDetector: ObjectDetector by lazy {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .build()
        ObjectDetection.getClient(options)
    }

    fun close() {
        runCatching { streamDetector.close() }
        runCatching { pickDetector.close() }
    }

    /**
     * 提交一帧做实时检测。
     *
     * **重要**：这里不会关闭 image，也不会阻塞。调用方必须等 [onFrameDone] 触发后才 close。
     * 因为 `InputImage.fromMediaImage()` 是**直接操作 media.Image**（官方文档明确说这种方式
     * 最省、不复制），提前 close 会让 ML Kit 读到已释放的 buffer。
     */
    fun submit(image: ImageProxy, onFrameDone: () -> Unit) {
        if (!enabled) {
            onFrameDone()
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRun < streamIntervalMs) {
            onFrameDone()
            return
        }
        if (!busy.compareAndSet(false, true)) {
            onFrameDone()
            return          // 上一帧还没出结果，丢掉这帧
        }

        lastRun = now
        val t0 = now

        frameW = image.width
        frameH = image.height
        val bmp = toBitmap(image)
        val rotation = image.imageInfo.rotationDegrees
        val input = InputImage.fromBitmap(bmp, rotation)

        streamDetector.process(input)
            .addOnSuccessListener { objects ->
                val subjects = objects.mapNotNull { toSubject(it, rotation) }
                lastMs = android.os.SystemClock.elapsedRealtime() - t0
                onStreamSubjects?.invoke(subjects, lastMs)
            }
            .addOnFailureListener { e ->
                onError?.invoke(e.javaClass.simpleName + ": " + (e.message ?: ""))
                onStreamSubjects?.invoke(emptyList(), lastMs)
            }
            .addOnCompleteListener {
                busy.set(false)
                onFrameDone()          // 到这一步才能安全 close
            }
    }

    /**
     * 用户点击时调用：在这一帧上跑一次**静态图多目标**检测，找出点击点附近的物体。
     *
     * 用 SINGLE_IMAGE_MODE 是因为它等边界框确定后才返回，结果更完整；
     * 反正只在点击时跑一次，多等几十毫秒无所谓。
     */
    fun pickAt(image: ImageProxy, tapX: Float, tapY: Float, onFrameDone: () -> Unit) {
        val t0 = android.os.SystemClock.elapsedRealtime()

        frameW = image.width
        frameH = image.height
        val bmp = toPickBitmap(image)
        val rotation = image.imageInfo.rotationDegrees
        val input = InputImage.fromBitmap(bmp, rotation)

        pickDetector.process(input)
            .addOnSuccessListener { objects ->
                val subjects = objects.mapNotNull { toSubject(it, rotation) }
                // 选"包含点击点"的，否则选离点击点最近的
                val hit = subjects.firstOrNull { it.contains(tapX, tapY) }
                    ?: subjects.minByOrNull { it.distanceTo(tapX, tapY) }
                onPickResult?.invoke(hit)
            }
            .addOnFailureListener {
                onPickResult?.invoke(null)
            }
            .addOnCompleteListener {
                onFrameDone()
            }
    }

    /**
     * 转灰度 Bitmap。
     *
     * 注意：这里**不能**复用同一张 Bitmap 给两个检测器 —— ML Kit 是异步的，
     * 实时流还在处理上一帧时如果你改了这张 Bitmap，检测结果就错了。
     * 所以实时流用复用缓冲（它自己是串行的），点击检测每次新建一张（低频，不心疼）。
     */
    private fun toBitmap(image: ImageProxy): Bitmap {
        val w = image.width
        val h = image.height
        var bmp = reuseBitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            reuseBitmap = bmp
            reuseCanvas = Canvas(bmp)
        }
        fillGray(bmp, image)
        return bmp
    }

    private fun toPickBitmap(image: ImageProxy): Bitmap {
        val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        fillGray(bmp, image)
        return bmp
    }

    /** 把 Y 平面铺成灰度像素（YUV 的 Y 就是亮度，不用转 RGB） */
    private fun fillGray(bmp: Bitmap, image: ImageProxy) {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val buf = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride

        val gray = ByteArray(rowStride * h)
        buf.rewind()
        val readable = minOf(buf.remaining(), gray.size)
        buf.get(gray, 0, readable)

        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val rowBase = y * rowStride
            val outBase = y * w
            for (x in 0 until w) {
                val idx = rowBase + x * pixelStride
                val v = if (idx < readable) (gray[idx].toInt() and 0xFF) else 0
                pixels[outBase + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /**
     * 把 ML Kit 的框转成**显示方向**下的归一化坐标。
     *
     * 关键点：`DetectedObject.boundingBox` 是**未旋转图像**的坐标。
     * 我们传给 InputImage 的 rotation 只是告诉 ML Kit "这张图该怎么转"，
     * 它返回的框仍然在原图坐标系里。所以要自己按 rotation 换算，
     * 否则竖屏下框会整体错位。
     */
    private fun toSubject(obj: DetectedObject, rotation: Int): Subject? {
        val bb = obj.boundingBox
        val base = normalizeBox(
            bb.left, bb.top, bb.right, bb.bottom,
            frameW, frameH, rotation
        ) ?: return null

        val label = obj.labels.maxByOrNull { it.confidence }?.text ?: ""
        val conf = obj.labels.maxOfOrNull { it.confidence } ?: 0f
        return base.copy(
            trackingId = obj.trackingId ?: -1,
            label = label,
            confidence = conf
        )
    }

    /** 当前帧的原始尺寸（未旋转），每次 submit 前更新 */
    @Volatile
    private var frameW = 1
    @Volatile
    private var frameH = 1

    companion object {
        /** 把像素框换算成归一化框（显示方向） */
        fun normalizeBox(
            l: Int, t: Int, r: Int, b: Int,
            imgW: Int, imgH: Int, rotation: Int
        ): Subject? {
            if (r <= l || b <= t || imgW <= 0 || imgH <= 0) return null
            var nl = l.toFloat() / imgW
            var nt = t.toFloat() / imgH
            var nr = r.toFloat() / imgW
            var nb = b.toFloat() / imgH

            when (rotation) {
                90 -> {
                    val a = nt; val c = 1f - nr; val d = nb; val e = 1f - nl
                    nl = a; nt = c; nr = d; nb = e
                }
                180 -> {
                    val a = 1f - nr; val c = 1f - nl
                    val d = 1f - nb; val e = 1f - nt
                    nl = a; nr = c; nt = d; nb = e
                }
                270 -> {
                    val a = 1f - nb; val c = nl; val d = 1f - nt; val e = nr
                    nl = a; nt = c; nr = d; nb = e
                }
            }

            nl = nl.coerceIn(0f, 1f); nr = nr.coerceIn(0f, 1f)
            nt = nt.coerceIn(0f, 1f); nb = nb.coerceIn(0f, 1f)
            if (nr <= nl || nb <= nt) return null
            return Subject(nl, nt, nr, nb)
        }
    }
}
