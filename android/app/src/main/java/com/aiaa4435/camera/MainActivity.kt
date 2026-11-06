package com.aiaa4435.camera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.aiaa4435.camera.databinding.ActivityMainBinding
import com.aiaa4435.camera.detect.AnchorStabilizer
import com.aiaa4435.camera.detect.CompositionAdvisor
import com.aiaa4435.camera.detect.FrameAnalyzer
import com.aiaa4435.camera.detect.FrameMetrics
import com.aiaa4435.camera.detect.Subject
import com.aiaa4435.camera.detect.SubjectDetector
import com.aiaa4435.camera.log.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var analysisExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    // P2-a：帧分析
    private val frameAnalyzer = FrameAnalyzer()
    private var hudVisible = true

    // P3：主体检测
    private val subjectDetector = SubjectDetector()
    private var lastSubjects: List<Subject> = emptyList()
    private var lastSubjectMs = 0L
    /** 用户是否手动锁定过主体（锁定后不再自动跟随"最突出"的那个） */
    private var manualLock = false
    /** 当前锁定/跟随的主体，用来判断实时流有没有跟丢 */
    private var lockedSubject: Subject? = null
    /** 诊断：实时流已回调的帧数（不涨 = 检测根本没跑） */
    private var subjectFrameCount = 0
    /** 诊断：最近一次检测的错误信息 */
    private var lastSubjectError: String? = null

    /** 实点稳定器：抑制框抖动传导到实点 */
    private val anchorStabilizer = AnchorStabilizer()
    /** 诊断：本帧实点位移（归一化），用来量化抖动 */
    private var lastAnchorJitter = 0f
    private var prevAnchorX = Float.NaN
    private var prevAnchorY = Float.NaN

    /** 待处理的点击选主体请求 */
    @Volatile private var pickRequested = false
    @Volatile private var pickTapX = 0.5f
    @Volatile private var pickTapY = 0.5f

    // P2-b2：水平仪 + 校准
    private lateinit var sensorLevel: SensorLevel
    private lateinit var calibration: LevelCalibration
    private var calibrating = false
    /** 进入校准模式那一刻的相机画面角度，用来算镜头装配偏差 */
    private var calibEntryHorizon = 0f
    private var calibEntryValid = false
    /** 最近一次水平仪读数 */
    private var lastRoll = 0f
    private var lastPitch = 0f
    private var sensorValid = false

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_AUTO
    private var showGrid = true

    private var minZoom = 1f
    private var maxZoom = 1f
    private var currentZoom = 1f

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private var lastSavedUri: Uri? = null

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 保持屏幕常亮（相机 App 常态）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraExecutor = Executors.newSingleThreadExecutor()
        analysisExecutor = Executors.newSingleThreadExecutor()

        AppLog.init(this)
        AppLog.i("App", "onCreate v${BuildConfig.VERSION_NAME}")

        sensorLevel = SensorLevel(this)
        calibration = LevelCalibration(this)

        setupGestures()
        setupButtons()
        setupZoomPresets()
        setupAnalysis()
        setupLevelSensor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /* ==================== 手势 ==================== */

    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (currentZoom * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                setZoom(target)
                binding.zoomLabel.visibility = View.VISIBLE
                binding.zoomLabel.text = String.format(Locale.US, "%.1fx", currentZoom)
                return true
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tapToFocus(e.x, e.y)
                // 点击同时也是"指定主体"：请求一次静态图检测，看这里有什么物体
                requestPick(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // 长按：锁定 AE/AF（专业相机做法，效果最明显）
                lockFocus(e.x, e.y)
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 双击：1x <-> 2x
                val target = if (currentZoom > 1.05f) minZoom else 2f.coerceAtMost(maxZoom)
                setZoomAnimated(target)
                return true
            }
        })

        binding.previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            // 缩放结束后隐藏倍数提示
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                mainHandler.postDelayed({
                    if (!scaleDetector.isInProgress) binding.zoomLabel.visibility = View.GONE
                }, 1200)
            }
            true
        }
    }

    /* ==================== 对焦 ==================== */

    /**
     * 请求一次"点击选主体"：把点击位置传给分析线程，
     * 下一帧到了就用静态图检测找那里的物体。
     */
    private fun requestPick(x: Float, y: Float) {
        val n = normalizePoint(x, y)
        pickTapX = n.first
        pickTapY = n.second
        pickRequested = true
    }

    /** 把屏幕触点换算成画面内的归一化坐标（0..1），用于主体选择 */
    private fun normalizePoint(x: Float, y: Float): Pair<Float, Float> {
        val w = binding.previewView.width.toFloat().takeIf { it > 0f } ?: 1f
        val h = binding.previewView.height.toFloat().takeIf { it > 0f } ?: 1f
        return (x / w).coerceIn(0f, 1f) to (y / h).coerceIn(0f, 1f)
    }

    private fun tapToFocus(x: Float, y: Float) {
        val cam = camera ?: return

        // 先取消上一次的对焦，再发起新的（否则部分机型不生效）
        cam.cameraControl.cancelFocusAndMetering()

        binding.focusRing.beginFocus(x, y)

        val point = binding.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(5, TimeUnit.SECONDS).build()

        val future = cam.cameraControl.startFocusAndMetering(action)

        // 用真实结果驱动 UI：成功变绿、失败变红
        future.addListener({
            try {
                val success = future.get().isFocusSuccessful
                runOnUiThread { binding.focusRing.setFocusResult(success) }
            } catch (e: Exception) {
                runOnUiThread { binding.focusRing.setFocusResult(false) }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun lockFocus(x: Float, y: Float) {
        val cam = camera ?: return
        cam.cameraControl.cancelFocusAndMetering()

        binding.focusRing.lock(x, y)

        val point = binding.previewView.meteringPointFactory.createPoint(x, y)
        // 锁定：持续时间设很长（100 秒），等于锁住
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(100, TimeUnit.SECONDS).build()

        cam.cameraControl.startFocusAndMetering(action)
        Toast.makeText(this, "AE/AF 已锁定（再点一下可解除）", Toast.LENGTH_SHORT).show()
    }

    /* ==================== 缩放 ==================== */

    private fun setZoom(ratio: Float) {
        val cam = camera ?: return
        currentZoom = ratio.coerceIn(minZoom, maxZoom)
        cam.cameraControl.setZoomRatio(currentZoom)
        updateZoomPresetSelection()
    }

    private fun setZoomAnimated(ratio: Float) {
        setZoom(ratio)
        binding.zoomLabel.visibility = View.VISIBLE
        binding.zoomLabel.text = String.format(Locale.US, "%.1fx", currentZoom)
        mainHandler.postDelayed({ binding.zoomLabel.visibility = View.GONE }, 1200)
    }

    private fun setupZoomPresets() {
        binding.btnZoom1.setOnClickListener { setZoomAnimated(1f) }
        binding.btnZoom2.setOnClickListener { setZoomAnimated(2f.coerceAtMost(maxZoom)) }
        binding.btnZoomMax.setOnClickListener { setZoomAnimated(maxZoom) }
    }

    private fun updateZoomPresetSelection() {
        val near = { v: Float -> Math.abs(currentZoom - v) < 0.15f }
        markSelected(binding.btnZoom1, near(1f))
        markSelected(binding.btnZoom2, near(2f.coerceAtMost(maxZoom)))
        markSelected(binding.btnZoomMax, near(maxZoom))
    }

    private fun markSelected(v: android.widget.Button, selected: Boolean) {
        v.setTextColor(if (selected) Color.parseColor("#3FD6C0") else Color.WHITE)
        v.alpha = if (selected) 1f else 0.75f
    }

    /* ==================== 帧分析 + 主体检测（P2/P3） ==================== */

    private fun setupAnalysis() {
        frameAnalyzer.onMetrics = { m ->
            runOnUiThread { updateHud(m) }
        }

        // 实时主体检测（单目标，稳定）
        subjectDetector.onStreamSubjects = { list, ms ->
            runOnUiThread { handleSubjects(list, ms) }
        }
        subjectDetector.onError = { msg ->
            runOnUiThread {
                lastSubjectError = msg
                AppLog.e("Detector", "检测失败: $msg")
            }
        }
        // 点击选主体（静态图多目标，只在点击时跑一次）
        subjectDetector.onPickResult = { picked ->
            runOnUiThread { onSubjectPicked(picked) }
        }
        // 几何检测和主体检测共用同一帧；主体检测处理完才 close
        frameAnalyzer.frameTap = { image, onDone ->
            // 有待处理的点击？优先跑静态图检测（低频，不担心延迟）
            if (pickRequested) {
                pickRequested = false
                subjectDetector.pickAt(image, pickTapX, pickTapY, onDone)
            } else {
                subjectDetector.submit(image, onDone)
            }
        }

        binding.hud.setOnClickListener {
            hudVisible = !hudVisible
            binding.hud.visibility = if (hudVisible) View.VISIBLE else View.GONE
        }
    }

    /**
     * 自动锁定：把当前帧检测到的主体立刻锁住，并显示框和实点。
     *
     * 用途：
     *  - 测试：确认检测链路通了（框/实点能显示）
     *  - 兜底：用户不想点屏幕时的一键锁定
     *
     * 如果之后用户点屏幕，仍然听从用户的选择（[onSubjectPicked] 会覆盖）。
     */
    private fun autoLockSubject() {
        val current = lockedSubject ?: lastSubjects.firstOrNull()
        if (current == null) {
            Toast.makeText(this, "没检测到主体，换个角度试试", Toast.LENGTH_SHORT).show()
            return
        }
        manualLock = true
        lockedSubject = current
        binding.subjectOverlay.setSubject(current)
        val advice = CompositionAdvisor.advise(current)
        binding.subjectOverlay.setAnchor(
            advice.anchorX, advice.anchorY, advice.alreadyGood, advice.hint
        )
        anchorStabilizer.reset()
        prevAnchorX = Float.NaN
        prevAnchorY = Float.NaN
        AppLog.i("Lock", "自动锁定 主体(${fmt2(current.cx)},${fmt2(current.cy)}) 实点(${fmt2(advice.anchorX)},${fmt2(advice.anchorY)})")
        Toast.makeText(this, "已锁定主体", Toast.LENGTH_SHORT).show()
    }

    /**
     * 实时流结果。现在实时流是**单目标模式**，返回 0 或 1 个对象 = 检测器认为最突出的那个。
     *
     * 三种情况：
     *  1. **用户手动锁定过** → 只有当实时流里的物体跟锁定物体**位置基本重合**时才更新框，
     *     否则视为"检测器跟丢了/跳到别的物体了"，保持锁定框不动。
     *     这是防止锁定被实时流无意中覆盖的关键。
     *  2. 没有手动锁定 → 直接跟随实时流（自动模式）。
     *  3. 实时流没检测到 → 保留上一次的框一小段时间，避免框闪一下就消失。
     */
    private fun handleSubjects(list: List<Subject>, ms: Long) {
        lastSubjectMs = ms
        subjectFrameCount++
        val detected = list.firstOrNull()

        // 每 30 帧记一次，既能回溯又不刷屏
        if (subjectFrameCount % 30 == 0) {
            AppLog.d(
                "Stream",
                "帧#$subjectFrameCount 检测${list.size}个 耗时${ms}ms " +
                    "框=${detected?.let { "(${fmt2(it.cx)},${fmt2(it.cy)}) ${fmt2(it.width)}x${fmt2(it.height)}" } ?: "无"}"
            )
        }

        val toShow: Subject? = when {
            manualLock && lockedSubject != null -> {
                // 只有确认是同一个物体才更新（重叠度够高），否则保持锁定
                if (detected != null && overlaps(lockedSubject!!, detected)) {
                    lockedSubject = detected
                    detected
                } else {
                    lockedSubject   // 跟丢了也继续显示锁定框，等它回来
                }
            }
            else -> {
                lockedSubject = detected
                detected
            }
        }

        if (toShow != null) {
            lastSubjects = list
            binding.subjectOverlay.setSubject(toShow)

            val advice = CompositionAdvisor.advise(toShow)

            // 稳定化：框抖不再直接传导到实点
            val (ax, ay) = anchorStabilizer.stabilize(
                toShow.cx, toShow.cy, advice.anchorX, advice.anchorY
            )

            binding.subjectOverlay.setAnchor(ax, ay, advice.alreadyGood, advice.hint)

            // 诊断：记录实点本帧位移量
            if (!prevAnchorX.isNaN()) {
                lastAnchorJitter = kotlin.math.hypot(ax - prevAnchorX, ay - prevAnchorY)
            }
            prevAnchorX = ax
            prevAnchorY = ay

            // 只在实点真的更新时打日志，避免刷屏
            if (anchorStabilizer.lastUpdated) {
                AppLog.d(
                    "Anchor",
                    "更新 主体(${fmt2(toShow.cx)},${fmt2(toShow.cy)}) " +
                        "实点(${fmt2(ax)},${fmt2(ay)}) " +
                        "抖动${fmt2(lastAnchorJitter)} 累计${anchorStabilizer.updateCount}"
                )
            }
        } else {
            lastSubjects = emptyList()
            binding.subjectOverlay.setSubject(null)
            binding.subjectOverlay.clearAnchor()
            anchorStabilizer.reset()
            prevAnchorX = Float.NaN
            prevAnchorY = Float.NaN
        }
    }

    private fun fmt2(v: Float) = String.format(Locale.US, "%.3f", v)

    /** 两个框是否基本重合（用中心距离 + 尺寸相近度判断，够用且便宜） */
    private fun overlaps(a: Subject, b: Subject): Boolean {
        val dx = a.cx - b.cx
        val dy = a.cy - b.cy
        val dist = kotlin.math.hypot(dx, dy)
        val sizeRatio = if (a.area > 0f) b.area / a.area else 1f
        return dist < 0.18f && sizeRatio > 0.45f && sizeRatio < 2.2f
    }

    /**
     * 用户点击后，静态图检测的结果：明确锁定这个主体。
     * @param picked 点击处找到的物体，null = 那里没有物体
     */
    private fun onSubjectPicked(picked: Subject?) {
        if (picked == null) {
            // 点的位置没有可识别物体：保持当前锁定不变，只做对焦
            AppLog.w("Pick", "点击处没找到物体 (${fmt2(pickTapX)},${fmt2(pickTapY)})")
            return
        }
        manualLock = true
        lockedSubject = picked
        binding.subjectOverlay.setSubject(picked)
        val advice = CompositionAdvisor.advise(picked)
        binding.subjectOverlay.setAnchor(
            advice.anchorX, advice.anchorY, advice.alreadyGood, advice.hint
        )
        anchorStabilizer.reset()
        prevAnchorX = Float.NaN
        prevAnchorY = Float.NaN
        AppLog.i("Pick", "点击锁定 主体(${fmt2(picked.cx)},${fmt2(picked.cy)}) 实点(${fmt2(advice.anchorX)},${fmt2(advice.anchorY)})")
    }

    private fun updateHud(m: FrameMetrics) {
        if (hudVisible) {
            val hOk = m.horizonConfidence > 0.2f
            val vOk = m.verticalConfidence > 0.2f
            binding.hud.text = buildString {
                // 水平仪（权威）：扣掉标定零位后的真实倾斜
                if (sensorValid) {
                    val roll = lastRoll - calibration.zeroRoll
                    val pitch = lastPitch
                    append("水平仪 ").append(signed1(roll)).append("°")
                    if (kotlin.math.abs(roll) <= 0.6f) append("  ✓")
                    append('\n')
                    append("俯仰 ").append(signed1(pitch)).append("°")
                    append('\n')
                } else {
                    append("水平仪 不可用\n")
                }

                // 主体（诊断用：能看到检测到底有没有在跑）
                append("主体 帧#").append(subjectFrameCount)
                append(" ").append(lastSubjects.size).append("个")
                append(" ").append(lastSubjectMs).append("ms")
                if (manualLock) append(" 锁定")
                if (lastSubjectError != null) append("\n错误: ").append(lastSubjectError)
                append('\n')

                // 相机画面检测（受场景影响，仅供参考）
                append("画面线 ").append(signed1(m.horizonAngle - calibration.sensorOffsetDeg)).append("°")
                append(if (hOk) "" else " (无)")
                append('\n')
                append("画面竖 ").append(signed1(m.verticalAngle)).append("°")
                append(if (vOk) "" else " (无)")
                append('\n')
                append("亮度 ").append(m.meanLuma.toInt())
                append("  ").append(m.analyzeMs).append("ms")
                if (!calibration.calibrated) append("\n未校准")
            }
        }

        // 校准模式下，把最新角度喂给靶心（提示文案由传感器回调实时刷新）
        if (calibrating) {
            binding.calibrationOverlay.setTilt(
                lastRoll - calibration.zeroRoll, lastPitch, sensorValid
            )
        }
    }

    /* ==================== 水平仪 + 校准（P2-b2） ==================== */

    private fun setupLevelSensor() {
        // 传感器回调在后台线程，全部转主线程处理，避免 UI 跨线程读写
        sensorLevel.onReading = { r ->
            runOnUiThread {
                lastRoll = r.roll
                lastPitch = r.pitch
                sensorValid = r.valid
                if (calibrating) refreshCalibrationTilt()
            }
        }
        binding.calibrationOverlay.targetX = 0.5f
        binding.calibrationOverlay.targetY = CalibrationOverlay.DEFAULT_TARGET_Y

        binding.btnCalibrate.setOnClickListener { enterCalibration() }
        binding.btnCalibDone.setOnClickListener { finishCalibration() }
        binding.btnCalibCancel.setOnClickListener { exitCalibration() }
        updateCalibrateButton()

        // 自动锁定：立即用当前实时流的检测结果锁住主体（测试/兜底用）
        binding.btnAutoLock.setOnClickListener { autoLockSubject() }

        // 点头部 HUD 开关数据；长按 HUD 打开日志查看器
        binding.hud.setOnLongClickListener {
            startActivity(Intent(this, LogActivity::class.java))
            true
        }
    }

    private fun refreshCalibrationTilt() {
        val rollErr = lastRoll - calibration.zeroRoll
        binding.calibrationOverlay.setTilt(rollErr, lastPitch, sensorValid)

        val within = sensorValid &&
            kotlin.math.abs(rollErr) <= 0.6f &&
            kotlin.math.abs(lastPitch) <= 0.6f
        binding.btnCalibDone.isEnabled = sensorValid
        binding.calibHint.text = when {
            !sensorValid -> "这台手机没有重力传感器，无法校准"
            within -> "已对齐 · 点「完成校准」保存"
            else -> String.format(
                Locale.US,
                "画面线 %s° · 竖线 %s° · 还差 %s°",
                signed1(frameAnalyzer.horizonAngle - calibration.sensorOffsetDeg),
                signed1(frameAnalyzer.verticalAngle),
                signed1(rollErr)
            )
        }
    }

    /**
     * 进入校准：
     *  - 记下此刻相机画面检测到的水平线角度（= 镜头装配偏差）
     *  - 显示靶心，让用户把画面里的水平线压到靶心上
     *  - 水平仪实时显示还差多少度
     */
    private fun enterCalibration() {
        calibrating = true
        calibEntryHorizon = frameAnalyzer.horizonAngle
        calibEntryValid = frameAnalyzer.horizonConfidence > 0.2f

        // 进入校准时先把零位清掉，否则显示的是"已经减过旧零位"的数，用户会绕晕
        binding.calibrationOverlay.show = true
        binding.calibrationOverlay.clearCurrentFocus()
        // 主体层让位：校准时画面里只该有校准靶心
        binding.subjectOverlay.show = false
        binding.calibHint.visibility = View.VISIBLE
        binding.btnCalibrate.visibility = View.GONE
        binding.btnCalibDone.visibility = View.VISIBLE
        binding.btnCalibCancel.visibility = View.VISIBLE
        binding.hud.visibility = View.GONE

        refreshCalibrationTilt()
        Toast.makeText(
            this,
            "举起手机，让画面里的水平线压到靶心（黄点→靶心）",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 完成校准：
     *  - zeroRoll = 当前传感器 roll（此刻用户认为"正"）
     *  - sensorOffsetDeg = 当前画面水平线角度（镜头装配偏差）
     */
    private fun finishCalibration() {
        if (!sensorValid) {
            Toast.makeText(this, "没有传感器读数，无法校准", Toast.LENGTH_SHORT).show()
            return
        }
        val currentHorizon = if (frameAnalyzer.horizonConfidence > 0.2f)
            frameAnalyzer.horizonAngle else calibEntryHorizon

        calibration.save(
            zeroRoll = lastRoll,
            sensorOffsetDeg = currentHorizon
        )

        exitCalibration()
        Toast.makeText(
            this,
            String.format(
                Locale.US,
                "已校准：零位 %s° / 镜头 %s°",
                signed1(calibration.zeroRoll),
                signed1(calibration.sensorOffsetDeg)
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun exitCalibration() {
        calibrating = false
        binding.calibrationOverlay.show = false
        binding.subjectOverlay.show = true
        binding.calibHint.visibility = View.GONE
        binding.btnCalibrate.visibility = View.VISIBLE
        binding.btnCalibDone.visibility = View.GONE
        binding.btnCalibCancel.visibility = View.GONE
        binding.hud.visibility = if (hudVisible) View.VISIBLE else View.GONE
        // 取消时不动已存数据，只是回到普通拍摄
        frameAnalyzer.resetSmoothing()
        updateCalibrateButton()
    }

    private fun updateCalibrateButton() {
        binding.btnCalibrate.text =
            if (calibration.calibrated) "重新校准" else "校准水平"
    }

    private fun signed1(v: Float) =
        String.format(Locale.US, "%s%.1f", if (v >= 0) "+" else "", v)

    /* ==================== 相机 ==================== */

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(flashMode)
                    .build()

                // P2/P3：逐帧分析。
                // 几何检测（便宜，每帧跑）+ 主体检测（ML Kit，内部限流到 ~5fps）
                // 共用一个分析器：FrameAnalyzer 在 close 之前把帧交给主体检测，
                // 避免开两条 ImageAnalysis 抢同一条相机管线。
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor, frameAnalyzer)
                    }

                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this, selector, preview, imageCapture, imageAnalysis
                )

                setupZoomRange()
                setupExposure()
                binding.focusRing.clear()
            } catch (e: Exception) {
                Toast.makeText(this, "打开相机失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupZoomRange() {
        val cam = camera ?: return
        cam.cameraInfo.zoomState.observe(this) { state ->
            if (state == null) return@observe
            minZoom = state.minZoomRatio
            maxZoom = state.maxZoomRatio
            currentZoom = state.zoomRatio
            updateZoomPresetSelection()
            // MAX 按钮显示实际最大倍数
            binding.btnZoomMax.text = if (maxZoom >= 10) "${maxZoom.toInt()}x" else String.format(Locale.US, "%.0fx", maxZoom)
        }
    }

    private fun setupExposure() {
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        val range = state.exposureCompensationRange   // androidx.camera.core.Range<Int>

        val steps = range.upper - range.lower
        if (steps <= 0) {
            binding.exposureBar.isEnabled = false
            binding.exposureLabel.text = "—"
            return
        }

        binding.exposureBar.isEnabled = true
        binding.exposureBar.max = steps
        binding.exposureBar.progress = state.exposureCompensationIndex - range.lower
        binding.exposureLabel.text = formatEv(state.exposureCompensationIndex)

        binding.exposureBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val index = range.lower + progress
                cam.cameraControl.setExposureCompensationIndex(index)
                binding.exposureLabel.text = formatEv(index)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
    }

    private fun formatEv(index: Int): String =
        if (index == 0) "0" else "${if (index > 0) "+" else ""}$index"

    /* ==================== 按钮 ==================== */

    private fun setupButtons() {
        binding.btnCapture.setOnClickListener { takePhoto() }

        binding.btnFlip.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            currentZoom = 1f
            binding.focusRing.clear()
            binding.subjectOverlay.clear()
            lastSubjects = emptyList()
            manualLock = false
            startCamera()
        }

        // 闪光：整颗胶囊都可以点（图标 + 文字）
        val toggleFlash = View.OnClickListener {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                else -> ImageCapture.FLASH_MODE_AUTO
            }
            imageCapture?.flashMode = flashMode
            applyFlashUi()
        }
        binding.btnFlash.setOnClickListener(toggleFlash)
        binding.flashBox.setOnClickListener(toggleFlash)
        applyFlashUi()

        binding.btnGrid.setOnClickListener {
            showGrid = !showGrid
            binding.gridOverlay.showGrid = showGrid
            binding.btnGrid.alpha = if (showGrid) 1f else 0.45f
        }
    }

    private fun applyFlashUi() {
        when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash)
                binding.flashLabel.text = "开"
            }
            ImageCapture.FLASH_MODE_OFF -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_off)
                binding.flashLabel.text = "关"
            }
            else -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash)
                binding.flashLabel.text = "自动"
            }
        }
    }

    /* ==================== 拍照 ==================== */

    private fun takePhoto() {
        val capture = imageCapture ?: return

        // 快门动画
        binding.flashOverlay.animate().alpha(0.72f).setDuration(55).withEndAction {
            binding.flashOverlay.animate().alpha(0f).setDuration(190).start()
        }.start()

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AIPhoto")
            }
        }

        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        capture.takePicture(
            options,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lastSavedUri = output.savedUri
                    runOnUiThread {
                        output.savedUri?.let { showThumbnail(it) }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "保存失败：${exception.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun showThumbnail(uri: Uri) {
        try {
            val bmp = contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it)
            }
            if (bmp != null) {
                binding.thumb.setImageBitmap(bmp)
                binding.thumb.visibility = View.VISIBLE
            }
            binding.thumb.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { startActivity(intent) } catch (e: Exception) { /* 无图库 App */ }
            }
        } catch (e: Exception) {
            // 缩略图失败不影响主流程
        }
    }

    override fun onResume() {
        super.onResume()
        sensorLevel.start()
    }

    override fun onPause() {
        super.onPause()
        sensorLevel.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorLevel.stop()
        sensorLevel.onReading = null
        frameAnalyzer.onMetrics = null
        frameAnalyzer.frameTap = null
        subjectDetector.onStreamSubjects = null
        subjectDetector.onPickResult = null
        subjectDetector.close()
        cameraExecutor.shutdown()
        analysisExecutor.shutdown()
    }
}
