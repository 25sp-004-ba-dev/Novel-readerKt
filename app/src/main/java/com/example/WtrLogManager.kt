package com.example

import android.content.Context
import androidx.compose.runtime.mutableStateListOf

object WtrLogManager {
    private var loggingEnabled = true
    private val _logs = mutableStateListOf<String>()
    val logs: List<String> get() = _logs

    fun initialize(context: Context) {
        val sharedPrefs = context.getSharedPreferences("wtr_browser_settings", Context.MODE_PRIVATE)
        loggingEnabled = sharedPrefs.getBoolean("enable_logs", true)
        val savedLogs = sharedPrefs.getString("saved_logs_serialized", "") ?: ""
        _logs.clear()
        if (savedLogs.isNotEmpty()) {
            savedLogs.split("||LC||").forEach {
                if (it.isNotEmpty()) _logs.add(it)
            }
        }
    }

    fun setLoggingEnabled(context: Context, enabled: Boolean) {
        loggingEnabled = enabled
        val sharedPrefs = context.getSharedPreferences("wtr_browser_settings", Context.MODE_PRIVATE)
        sharedPrefs.edit().putBoolean("enable_logs", enabled).apply()
        if (!enabled) {
            _logs.clear()
            sharedPrefs.edit().putString("saved_logs_serialized", "").apply()
        }
    }

    fun isLoggingEnabled(): Boolean = loggingEnabled

    fun log(context: Context?, msg: String) {
        if (!loggingEnabled) return
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val formatted = "[$timestamp] $msg"
        
        // Add to state list (at the top/beginning so newest logs are first)
        _logs.add(0, formatted)
        while (_logs.size > 100) {
            _logs.removeAt(_logs.size - 1)
        }

        // Persist to shared preferences asynchronously if context is available
        context?.let { ctx ->
            val serialized = _logs.joinToString("||LC||")
            val sharedPrefs = ctx.getSharedPreferences("wtr_browser_settings", Context.MODE_PRIVATE)
            sharedPrefs.edit().putString("saved_logs_serialized", serialized).apply()
        }
    }

    fun clear(context: Context) {
        _logs.clear()
        val sharedPrefs = context.getSharedPreferences("wtr_browser_settings", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("saved_logs_serialized", "").apply()
    }
}
