package com.playerbrowser.app.network

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of recent log lines, viewable inside the app via a
 * debug screen. Mirrors every entry to logcat so adb users still get the
 * normal stream. Bounded so we never grow without limit during a long
 * browsing session.
 */
object DebugLog {
    private const val MAX_ENTRIES = 500
    private val buffer = ArrayDeque<String>()
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append("D", tag, message)
    }

    fun w(tag: String, message: String, t: Throwable? = null) {
        if (t != null) Log.w(tag, message, t) else Log.w(tag, message)
        append("W", tag, if (t == null) message else "$message — ${t.javaClass.simpleName}: ${t.message}")
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, message, t) else Log.e(tag, message)
        append("E", tag, if (t == null) message else "$message — ${t.javaClass.simpleName}: ${t.message}")
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        _entries.value = emptyList()
    }

    private fun append(level: String, tag: String, message: String) {
        val line = "${timeFormat.format(Date())} $level/$tag: $message"
        val snapshot = synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            buffer.toList()
        }
        _entries.value = snapshot
    }
}
