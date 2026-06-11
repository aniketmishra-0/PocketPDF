package com.renameapk.pdfzip

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight crash + issue capture.
 *
 * Installs a default uncaught-exception handler that writes the full stack
 * trace (plus device / app info) to a file before the process dies, so a crash
 * that "just closes the app" can actually be diagnosed. The saved report is
 * surfaced to the user on the next launch (see MainActivity) and then cleared.
 *
 * It also exposes [logIssue] for non-fatal problems (caught exceptions, failed
 * operations) so anything that goes wrong in the app can be recorded and shared
 * even when it doesn't crash the process.
 */
object CrashReporter {

    private const val CRASH_FILE_NAME = "last_crash.txt"
    private const val ISSUE_LOG_NAME = "issues.log"
    private const val MAX_ISSUE_LOG_BYTES = 256 * 1024L // keep the log small

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Records a non-fatal problem. Use this in catch blocks where the app keeps
     * running but something went wrong, e.g.
     *
     *     } catch (e: Exception) {
     *         CrashReporter.logIssue(context, "Failed to open PDF", e)
     *     }
     */
    fun logIssue(context: Context, message: String, throwable: Throwable? = null) {
        runCatching {
            val appContext = context.applicationContext
            val entry = buildString {
                appendLine("---- ${timeFormat.format(Date())} ----")
                appendLine("Issue: $message")
                if (throwable != null) {
                    appendLine("Reason: ${throwable.javaClass.simpleName}: ${throwable.message}")
                    append(stackTraceOf(throwable))
                    appendLine()
                }
            }
            val file = File(appContext.filesDir, ISSUE_LOG_NAME)
            // Trim the log if it grows too large so it never fills up storage.
            if (file.exists() && file.length() > MAX_ISSUE_LOG_BYTES) {
                runCatching { file.delete() }
            }
            file.appendText(entry)
        }
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val report = buildString {
            appendLine("===== CRASH REPORT =====")
            appendLine("Time: ${timeFormat.format(Date())}")
            appendLine(deviceInfo(context))
            appendLine("Thread: ${thread.name}")
            appendLine("Message: ${throwable.message}")
            appendLine()
            append(stackTraceOf(throwable))
        }
        File(context.filesDir, CRASH_FILE_NAME).writeText(report)
    }

    private fun deviceInfo(context: Context): String {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        return buildString {
            appendLine("App version: $versionName")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val stackWriter = StringWriter()
        PrintWriter(stackWriter).use { throwable.printStackTrace(it) }
        return stackWriter.toString()
    }

    fun consumeReport(context: Context): String? {
        val file = File(context.applicationContext.filesDir, CRASH_FILE_NAME)
        if (!file.exists()) {
            return null
        }
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        return text?.takeIf { it.isNotBlank() }
    }

    /** Returns the recent non-fatal issue log, or null if empty. */
    fun readIssueLog(context: Context): String? {
        val file = File(context.applicationContext.filesDir, ISSUE_LOG_NAME)
        if (!file.exists()) {
            return null
        }
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clearIssueLog(context: Context) {
        runCatching { File(context.applicationContext.filesDir, ISSUE_LOG_NAME).delete() }
    }

    /**
     * Builds a share [Intent] for the given report text so the user can send it
     * via WhatsApp / email / etc. Writes the text to a shareable file under the
     * app's cache so large reports survive the share.
     */
    fun shareIntent(context: Context, report: String): Intent {
        val appContext = context.applicationContext
        val shareDir = File(appContext.cacheDir, "reports").apply { mkdirs() }
        val file = File(shareDir, "crash_report.txt")
        runCatching { file.writeText(report) }

        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PDF app report")
            putExtra(Intent.EXTRA_TEXT, report)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
