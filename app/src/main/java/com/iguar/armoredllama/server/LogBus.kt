package com.iguar.armoredllama.server

import com.iguar.armoredllama.model.LOG_CAP
import com.iguar.armoredllama.model.LogLine
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale

/** Process-wide buffer for real server log lines; the UI observes [lines]. Also tees to a file. */
object LogBus {
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    /**
     * Every appended line body, uncapped. [lines] is capped to [LOG_CAP] for display, which makes
     * its size unsuitable for "what's new" detection (size pins once full). Consumers that must see
     * every line — e.g. throughput parsing (#7) — collect this instead. Buffered + DROP_OLDEST so
     * [append] from the service reader thread never blocks.
     */
    private val _raw = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val raw: SharedFlow<String> = _raw

    @Volatile private var logFile: File? = null

    fun attachFile(file: File) {
        logFile = file.apply { parentFile?.mkdirs() }
    }

    @Synchronized
    fun append(body: String) {
        val line = LogLine(stamp(), body)
        _lines.value = (_lines.value + line).takeLast(LOG_CAP)
        _raw.tryEmit(body)
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
