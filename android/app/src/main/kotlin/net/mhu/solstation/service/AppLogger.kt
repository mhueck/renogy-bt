package net.mhu.solstation.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val level: String,
    val message: String
)

object AppLogger {
    private const val TAG = "SolStation"
    private const val MAX_LOGS = 300
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun info(msg: String) {
        Log.i(TAG, msg)
        append("INFO", msg)
    }

    fun warn(msg: String) {
        Log.w(TAG, msg)
        append("WARN", msg)
    }

    fun error(msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, msg, throwable)
            append("ERROR", "$msg: ${throwable.message ?: throwable.javaClass.simpleName}")
        } else {
            Log.e(TAG, msg)
            append("ERROR", msg)
        }
    }

    fun debug(msg: String) {
        Log.d(TAG, msg)
    }

    fun clear() {
        synchronized(this) {
            _logs.value = emptyList()
        }
    }

    private fun append(level: String, message: String) {
        val time = synchronized(dateFormat) { dateFormat.format(Date()) }
        val entry = LogEntry(timestamp = time, level = level, message = message)
        synchronized(this) {
            val current = _logs.value
            val updated = if (current.size >= MAX_LOGS) {
                current.drop(current.size - MAX_LOGS + 1) + entry
            } else {
                current + entry
            }
            _logs.value = updated
        }
    }
}
