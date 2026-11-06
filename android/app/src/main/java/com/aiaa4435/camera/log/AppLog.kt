package com.aiaa4435.camera.log

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * 轻量日志系统。
 *
 * 设计目标：**出了问题能回溯**，而不是只能靠用户口述"它就是抖"。
 *
 * 三个层次：
 *  1. **内存环形缓冲** —— 最近 N 条，随时可读（不阻塞）；
 *  2. **文件持久化** —— 追加写到 `files/logs/session-*.log`，App 被杀也还在；
 *  3. **导出/分享** —— 一键把当前日志发出来，或复制到剪贴板。
 *
 * 为什么不直接用 Logcat：手机上没法看 Logcat，用户也没法把 Logcat 发给我。
 * 日志必须能在 App 内直接看到、直接导出来。
 *
 * 性能注意：
 *  - 写文件在**独立单线程**上做，绝不阻塞 UI / 相机线程；
 *  - 每条日志先拼接成一行字符串再入队，避免持有对象引用；
 *  - 环形缓冲固定容量，内存不会无限涨。
 */
object AppLog {

    enum class Level(val tag: String) {
        DEBUG("D"), INFO("I"), WARN("W"), ERROR("E")
    }

    data class Entry(
        val timeMs: Long,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        fun format(): String {
            val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timeMs))
            return "$t ${level.tag}/$tag: $message"
        }
    }

    /** 环形缓冲容量：够回溯最近几分钟的活动 */
    private const val CAPACITY = 2000

    private val buffer: ArrayDeque<Entry> = ArrayDeque(CAPACITY)
    private val lock = Any()

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var writer: java.io.BufferedWriter? = null

    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLog-io").apply { isDaemon = true }
    }

    @Volatile
    var enabled: Boolean = true

    /** 初始化：准备日志文件。必须在 Application/Activity 启动时调用一次。 */
    fun init(context: Context) {
        if (writer != null) return
        val dir = File(context.filesDir, "logs")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "session-$stamp.log")
        logFile = f
        ioExecutor.execute {
            runCatching {
                writer = f.bufferedWriter()
                writer?.appendLine("# session started $stamp")
            }
        }
    }

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String) = log(Level.ERROR, tag, msg)

    fun e(tag: String, msg: String, t: Throwable) =
        log(Level.ERROR, tag, "$msg | ${t.javaClass.simpleName}: ${t.message}")

    private fun log(level: Level, tag: String, message: String) {
        if (!enabled) return
        val entry = Entry(System.currentTimeMillis(), level, tag, message)

        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
        }

        val line = entry.format()
        ioExecutor.execute {
            runCatching {
                val w = writer
                if (w != null) {
                    w.appendLine(line)
                    // 每 20 条 flush 一次：既保证崩溃前大部分日志落盘，又不会写盘过频
                    flushCounter++
                    if (flushCounter >= 20) {
                        w.flush()
                        flushCounter = 0
                    }
                }
            }
        }
    }

    private var flushCounter = 0

    /** 取最近 n 条（新的在后） */
    fun recent(n: Int = 300): List<Entry> = synchronized(lock) {
        val size = buffer.size
        if (size <= n) buffer.toList() else buffer.toList().subList(size - n, size)
    }

    /** 取全部文本，用于导出/复制 */
    fun dump(n: Int = 1000): String {
        val sb = StringBuilder()
        recent(n).forEach { sb.appendLine(it.format()) }
        return sb.toString()
    }

    /** 清空内存缓冲（文件保留） */
    fun clearBuffer() = synchronized(lock) { buffer.clear() }

    /** 当前日志文件路径（导出用） */
    fun currentFile(): File? {
        writer?.let { runCatching { it.flush() } }
        return logFile
    }

    /** 列出会话日志文件，新的在前 */
    fun listSessionFiles(context: Context): List<File> {
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name } ?: emptyList()
    }

    /** 把若干日志文件拼成一份可分享的文本 */
    fun dumpFiles(files: List<File>): String {
        val sb = StringBuilder()
        files.forEach { f ->
            sb.appendLine("===== ${f.name} =====")
            runCatching { f.forEachLine { sb.appendLine(it) } }
            sb.appendLine()
        }
        return sb.toString()
    }
}
