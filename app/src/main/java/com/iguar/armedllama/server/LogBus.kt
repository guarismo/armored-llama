package com.iguar.armedllama.server

import com.iguar.armedllama.model.LOG_CAP
import com.iguar.armedllama.model.LogLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale

/** Process-wide buffer for real server log lines; the UI observes [lines]. Also tees to a file. */
object LogBus {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    @Volatile private var logFile: File? = null

    fun attachFile(file: File) {
        logFile = file.apply { parentFile?.mkdirs() }
    }

    @Synchronized
    fun append(body: String) {
        val line = LogLine(stamp(), body)
        _lines.value = (_lines.value + line).takeLast(LOG_CAP)
        runCatching { logFile?.appendText("${line.time} $body\n") }
    }

    @Synchronized
    fun clear() { _lines.value = emptyList() }

    private fun stamp(): String {
        val t = System.currentTimeMillis()
        val s = (t / 1000) % 86400
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", s / 3600, (s % 3600) / 60, s % 60, t % 1000)
    }
}
