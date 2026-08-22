package org.levimc.launcher.ui.activities

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.core.content.FileProvider
import org.levimc.launcher.R
import org.levimc.launcher.core.crash.CrashReporter
import org.levimc.launcher.ui.animation.DynamicAnim
import java.io.File
import java.util.concurrent.Executors

class CrashActivity : BaseActivity() {

    private var executor = Executors.newSingleThreadExecutor()
    private var logPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_crash)

        logPath = intent.getStringExtra("LOG_PATH")
        val summary = intent.getStringExtra("SUMMARY")
            ?: intent.getStringExtra("EMERGENCY")
        val crashType = intent.getStringExtra("CRASH_TYPE")
        CrashReporter.sendUnsentReports()

        val tvTitle = findViewById<TextView>(R.id.crash_title)
        val tvDetails = findViewById<TextView>(R.id.crash_details)
        val btnBack = findViewById<Button>(R.id.btn_back_to_main)
        val btnShare = findViewById<Button>(R.id.btn_share_log)
        DynamicAnim.applyPressScale(btnBack)
        DynamicAnim.applyPressScale(btnShare)

        tvTitle.text = getString(R.string.crash_title)

        tvDetails.text = buildString {
            appendHeader(crashType, summary)
            if (!logPath.isNullOrBlank()) {
                append(getString(R.string.crash_log_file_label)).append("\n")
                append(logPath).append("\n\n")
            }
            append(getString(R.string.crash_loading_details))
        }

        btnBack.setOnClickListener {
            navigateToMain()
        }
        
        btnShare.setOnClickListener {
            shareLogFile()
        }

        val path = logPath
        if (!path.isNullOrBlank()) {
            waitForStableLogFile(path) {
                loadCrashDetails(tvDetails, crashType, summary)
            }
        } else {
            Handler(Looper.getMainLooper()).postDelayed({
                loadCrashDetails(tvDetails, crashType, summary)
            }, 300)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            CrashReporter.sendUnsentReports()
        }, 2000)
    }

    /**
     * xCrash writes native crash tombstones asynchronously (unwinding + symbolizing
     * a deep native stack takes real time, especially with this app's amount of
     * native code). Reading the file after a short fixed delay can catch it mid-write
     * or before it exists at all, producing an empty/0-byte read. Poll until the
     * file's size stops growing (or we hit a generous timeout) before reading it.
     */
    private fun waitForStableLogFile(path: String, onReady: () -> Unit) {
        val file = File(path)
        var lastSize = -1L
        var stableCount = 0
        val maxAttempts = 30 // ~9s total at 300ms/attempt
        var attempts = 0
        val handler = Handler(Looper.getMainLooper())
        val check = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                attempts++
                val size = if (file.exists()) file.length() else -1L
                if (size > 0 && size == lastSize) {
                    stableCount++
                } else {
                    stableCount = 0
                }
                lastSize = size
                if (stableCount >= 2 || attempts >= maxAttempts) {
                    onReady()
                } else {
                    handler.postDelayed(this, 300)
                }
            }
        }
        handler.post(check)
    }

    override fun shouldSkipNavBar(): Boolean = true

    private fun loadCrashDetails(tvDetails: TextView, crashType: String?, summary: String?) {
        if (isFinishing || isDestroyed) return

        executor.execute {
            val detailsText = buildString {
                appendHeader(crashType, summary)

                if (!logPath.isNullOrEmpty()) {
                    append(getString(R.string.crash_log_file_label)).append("\n")
                    append(logPath).append("\n\n")
                    append(getString(R.string.crash_stack_label)).append("\n")
                    try {
                        val f = File(logPath!!)
                        if (f.exists() && f.isFile) {
                            val content = f.readText()
                            val maxLength = 50000
                            if (content.length > maxLength) {
                                append(content.substring(0, maxLength))
                                append("\n\n... [truncated, full log in file] ...")
                            } else {
                                append(content)
                            }
                        } else {
                            append(getString(R.string.crash_log_file_missing)).append("\n")
                        }
                    } catch (e: Exception) {
                        append(getString(R.string.crash_read_failed, e.message)).append("\n")
                    }
                }
                if (isEmpty()) {
                    append(getString(R.string.crash_no_details))
                }
            }

            if (!isFinishing && !isDestroyed) {
                runOnUiThread {
                    tvDetails.text = detailsText
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun StringBuilder.appendHeader(crashType: String?, summary: String?) {
        if (!crashType.isNullOrBlank()) {
            append(getString(R.string.crash_type_label)).append("\n")
            append(crashType).append("\n\n")
        }
        if (!summary.isNullOrBlank()) {
            append(getString(R.string.crash_summary_label)).append("\n")
            append(summary).append("\n\n")
        }
    }

    private fun navigateToMain() {
        try {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        } catch (ignored: Exception) {
            finish()
        }
    }

    private fun shareLogFile() {
        val path = logPath ?: return
        val logFile = File(path)
        if (!logFile.exists() || logFile.length() == 0L) {
            // xCrash may still be writing this asynchronously; wait for it to
            // stabilize instead of sharing an empty/partial file.
            android.widget.Toast.makeText(
                this, getString(R.string.crash_log_still_writing), android.widget.Toast.LENGTH_SHORT
            ).show()
            waitForStableLogFile(path) { doShareLogFile(logFile) }
            return
        }
        doShareLogFile(logFile)
    }

    private fun doShareLogFile(logFile: File) {
        try {
            if (logFile.exists()) {
                val shareFile = copyLogToShareCache(logFile)
                val logFileUri = FileProvider.getUriForFile(
                    this,
                    packageName + ".fileprovider",
                    shareFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, logFileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, shareFile.name))
            }
        } catch (e: Exception) {
        }
    }

    private fun copyLogToShareCache(logFile: File): File {
        val shareDir = File(cacheDir, "crash_logs_share")
        if (!shareDir.exists()) shareDir.mkdirs()
        val shareFile = File(shareDir, logFile.name)
        logFile.copyTo(shareFile, overwrite = true)
        return shareFile
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            executor.shutdownNow()
        } catch (ignored: Exception) {}
    }
}
