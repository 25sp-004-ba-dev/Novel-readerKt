package com.example

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReportManager {
    
    private const val CRASH_LOG_DIR = "crash_reports"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    
    fun init(context: Context) {
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            saveCrashReport(context, thread, exception)
            // Rethrow to let system handle it
            originalHandler?.uncaughtException(thread, exception)
        }
    }
    
    private fun saveCrashReport(context: Context, thread: Thread, exception: Throwable) {
        val timestamp = dateFormat.format(Date())
        val crashDir = File(context.filesDir, CRASH_LOG_DIR).apply { mkdirs() }
        val crashFile = File(crashDir, "crash_${System.currentTimeMillis()}.log")
        
        val report = buildString {
            appendLine("=== CRASH REPORT ===")
            appendLine("Timestamp: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine("Exception: ${exception.javaClass.simpleName}")
            appendLine("Message: ${exception.message}")
            appendLine("\nStack Trace:")
            exception.stackTrace.forEach { element ->
                appendLine("  at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
            }
            appendLine("\nRecent Logs:")
            WtrLogManager.logs.take(20).forEach { log ->
                appendLine("  $log")
            }
        }
        
        try {
            crashFile.writeText(report)
            WtrLogManager.log(context, "Crash report saved: ${crashFile.absolutePath}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun getCrashReports(context: Context): List<File> {
        val crashDir = File(context.filesDir, CRASH_LOG_DIR)
        return crashDir.listFiles()?.toList() ?: emptyList()
    }
    
    fun clearOldCrashReports(context: Context, olderThanDays: Int = 7) {
        val crashDir = File(context.filesDir, CRASH_LOG_DIR)
        val cutoffTime = System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000)
        
        crashDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                file.delete()
            }
        }
    }
}
