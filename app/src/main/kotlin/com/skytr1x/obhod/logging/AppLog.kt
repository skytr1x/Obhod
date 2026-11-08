package com.skytr1x.obhod.logging

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppLog {
    data class Entry(
        val id: Long,
        val time: String,
        val level: Char,
        val tag: String,
        val message: String
    ) {
        fun asLine(): String = "$time ${level}/$tag: $message"
    }

    private const val MAX_ENTRIES = 400
    private const val MAX_MESSAGE = 2000
    private const val PUBLISH_INTERVAL_MS = 250L
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    private val nextId = AtomicLong(1)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    private val entriesLock = Any()
    private val history = ArrayDeque<Entry>(MAX_ENTRIES)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var publishScheduled = false
    private val publishRunnable = Runnable {
        synchronized(entriesLock) {
            publishScheduled = false
            _entries.value = history.toList()
        }
    }

    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun i(tag: String, message: String) = record(Log.INFO, 'I', tag, message)

    fun d(tag: String, message: String) = record(Log.DEBUG, 'D', tag, message)

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
        append('W', tag, withReason(message, error))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.e(tag, message) else Log.e(tag, message, error)
        append('E', tag, withReason(message, error))
    }

    fun clear() {
        synchronized(entriesLock) {
            history.clear()
            _entries.value = emptyList()
        }
    }

    private fun record(priority: Int, level: Char, tag: String, message: String) {
        Log.println(priority, tag, message)
        append(level, tag, message)
    }

    private fun append(level: Char, tag: String, message: String) {
        val entry = Entry(
            id = nextId.getAndIncrement(),
            time = LocalTime.now().format(clock),
            level = level,
            tag = tag.take(24),
            message = message.take(MAX_MESSAGE)
        )
        synchronized(entriesLock) {
            if (history.size == MAX_ENTRIES) history.removeFirst()
            history.addLast(entry)
            if (!publishScheduled) {
                publishScheduled = true
                mainHandler.postDelayed(publishRunnable, PUBLISH_INTERVAL_MS)
            }
        }
    }

    private fun withReason(message: String, error: Throwable?): String =
        if (error == null) message else "$message: ${error.message ?: error.javaClass.simpleName}"
}
