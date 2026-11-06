package com.aiaa4435.camera

import android.content.Context

/**
 * 标定结果：这台手机"看起来正"的时候，传感器读到的角度是多少。
 *
 * 两件事都存下来：
 *  - `zeroRoll`：重力 0° 的偏移（手机传感器本身的基准误差）
 *  - `sensorOffsetDeg`：进校准那一刻，画面里那条水平线的角度
 *    （也就是"手机框正"和"相机画面正"之间的差，来自模组装配偏差）
 *
 * 之后 真实倾斜 = (sensorRoll - zeroRoll) - 0，而画面里的线
 * 真实倾斜 = detectedAngle - sensorOffsetDeg。两者都可归零。
 */
class LevelCalibration(context: Context) {

    private val prefs = context.getSharedPreferences("level_calibration", Context.MODE_PRIVATE)

    var zeroRoll: Float
        get() = prefs.getFloat(KEY_ZERO_ROLL, 0f)
        private set(value) = prefs.edit().putFloat(KEY_ZERO_ROLL, value).apply()

    var sensorOffsetDeg: Float
        get() = prefs.getFloat(KEY_SENSOR_OFFSET, 0f)
        private set(value) = prefs.edit().putFloat(KEY_SENSOR_OFFSET, value).apply()

    var calibrated: Boolean
        get() = prefs.getBoolean(KEY_DONE, false)
        private set(value) = prefs.edit().putBoolean(KEY_DONE, value).apply()

    /** 校准时间戳（毫秒），0 = 从未校准 */
    var calibratedAt: Long
        get() = prefs.getLong(KEY_AT, 0L)
        private set(value) = prefs.edit().putLong(KEY_AT, value).apply()

    fun save(zeroRoll: Float, sensorOffsetDeg: Float) {
        // 一次 edit 写完，避免中途失败出现半套状态
        prefs.edit()
            .putFloat(KEY_ZERO_ROLL, zeroRoll)
            .putFloat(KEY_SENSOR_OFFSET, sensorOffsetDeg)
            .putBoolean(KEY_DONE, true)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ZERO_ROLL = "zero_roll"
        const val KEY_SENSOR_OFFSET = "sensor_offset_deg"
        const val KEY_DONE = "calibrated"
        const val KEY_AT = "calibrated_at"
    }
}
