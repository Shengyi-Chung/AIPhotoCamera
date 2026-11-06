package com.aiaa4435.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aiaa4435.camera.databinding.ActivityLogBinding
import com.aiaa4435.camera.log.AppLog
import java.io.File

/**
 * App 内日志查看器。
 *
 * 为什么要在 App 内看：手机上看不了 Logcat，也没法把 Logcat 发给别人。
 * 出了问题必须能在设备上直接看到发生了什么，并且一键分享出来。
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private var filter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        refresh()

        binding.btnRefresh.setOnClickListener { refresh() }

        binding.btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("camera-log", currentText()))
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        binding.btnShare.setOnClickListener { share() }

        binding.btnClose.setOnClickListener { finish() }

        binding.filterInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString()?.trim() ?: ""
                refresh()
            }
        })
    }

    private fun currentText(): String {
        val all = AppLog.recent(1500)
        if (filter.isEmpty()) return all.joinToString("\n") { it.format() }
        return all.filter {
            it.tag.contains(filter, true) || it.message.contains(filter, true) ||
                it.level.tag.contains(filter, true)
        }.joinToString("\n") { it.format() }
    }

    private fun refresh() {
        val text = currentText()
        binding.logText.text = if (text.isEmpty()) "（暂无日志）" else text
        // 滚到底部，看最新的
        binding.logText.post {
            val layout = binding.logText.layout ?: return@post
            val y = layout.getLineTop(layout.lineCount).coerceAtLeast(0)
            binding.logText.scrollTo(0, y)
        }
    }

    /** 分享：把所有会话日志文件拼起来发出去 */
    private fun share() {
        val files: List<File> = AppLog.listSessionFiles(this)
        val body = if (files.isNotEmpty()) AppLog.dumpFiles(files.take(3)) else currentText()

        // 先写到缓存目录，再用 FileProvider 分享
        val out = File(cacheDir, "camera-log-${System.currentTimeMillis()}.txt")
        runCatching { out.writeText(body) }

        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", out
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "相机运行日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, "分享日志"))
        }.onFailure {
            Toast.makeText(this, "分享失败，请用「复制」", Toast.LENGTH_LONG).show()
        }
    }
}
