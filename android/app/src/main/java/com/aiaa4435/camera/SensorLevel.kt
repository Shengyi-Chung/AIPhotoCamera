package com.aiaa4435.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 手机本体水平仪：直接用重力传感器算「这台手机现在歪了多少」，不依赖画面内容。
 *
 * 为什么需要它：
 *  - 画面几何检测（Geometry）靠场景里的水平/竖直边，遇到白墙、天空、纯色地板就没有基准，
 *    用户没法判断"0° 在哪"；
 *  - 重力传感器永远知道"下"在哪，是判定手机框正不正的唯一权威。
 *
 * 坐标系采用 Android 官方 normal 约定（屏幕竖持、正对用户）：
 *   x 轴向右，y 轴向上，z 轴指向屏幕外。
 * 重力向量在 normal 下为 (0, +9.81, 0)，拍平放桌上时为 (0, 0, +9.81)。
 *
 *   roll  = atan2(-gx, +gy)  绕 z 轴左右歪了多少（正值 = 右边高）
 *   pitch = atan2(-gz, √(gx²+gy²))  屏幕法线偏离水平多少（正值 = 顶部后仰）
 *
 * 拍平在桌上时 gy≈0，atan2(-gx, 0) 会退化成 ±90°，这时的 roll 无意义，
 * 用 [isFlat] 判断姿态即可；竖持拍照时不存在这个问题。
 */
class SensorLevel(context: Context) : SensorEventListener {

    data class Reading(
        val roll: Float,        // 左右倾斜（度），正值 = 右边高
        val pitch: Float,       // 屏幕法线相对水平的仰角（度），90 = 竖持
        val valid: Boolean
    )

    /** 收到新读数时回调（在传感器线程，UI 侧自己切主线程） */
    var onReading: ((Reading) -> Unit)? = null

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** 重力本身已是融合结果，这里只轻滤单次抖动；越接近 1 越跟手 */
    private val lowPassAlpha = 0.35f

    private var smoothRoll = 0f
    private var smoothPitch = 0f
    private var hasSample = false

    /** 最近一次读数（任意线程可读，校准/抬机快照用） */
    @Volatile
    var lastReading: Reading = Reading(0f, 0f, false)
        private set

    @Volatile
    private var lastEmit = 0L
    private val emitIntervalMs = 66L     // ~15Hz：足够顺，也不费电

    val available: Boolean get() = gravity != null

    fun start() {
        if (gravity == null) return
        sm.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]

        val norm = sqrt(gx * gx + gy * gy + gz * gz)
        if (norm < 1f) return                       // 自由落体 / 异常帧
        val nx = gx / norm
        val ny = gy / norm
        val nz = gz / norm

        // TYPE_GRAVITY 与 TYPE_ACCELEROMETER 都朝"下"，符号一致，不用区分
        val roll = Math.toDegrees(atan2(-nx.toDouble(), ny.toDouble())).toFloat()
        val pitch = Math.toDegrees(
            atan2(-nz.toDouble(), sqrt(nx * nx + ny * ny).toDouble())
        ).toFloat()

        if (!hasSample) {
            smoothRoll = roll
            smoothPitch = pitch
            hasSample = true
        } else {
            smoothRoll += (roll - smoothRoll) * lowPassAlpha
            smoothPitch += (pitch - smoothPitch) * lowPassAlpha
        }

        lastReading = Reading(smoothRoll, smoothPitch, true)

        val now = SystemClock.elapsedRealtime()
        if (now - lastEmit >= emitIntervalMs) {
            lastEmit = now
            onReading?.invoke(lastReading)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** 是否接近躺平（放在桌面上时 pitch ≈ 0，竖持拍摄时 pitch ≈ 90） */
    fun isFlat(): Boolean = hasSample && abs(smoothPitch) < 30f
}
