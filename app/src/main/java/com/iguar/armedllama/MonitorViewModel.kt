package com.iguar.armedllama

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iguar.armedllama.device.DeviceTelemetry
import com.iguar.armedllama.model.CORE_COUNT
import com.iguar.armedllama.model.HISTORY_SIZE
import com.iguar.armedllama.model.Histories
import com.iguar.armedllama.model.LOG_CAP
import com.iguar.armedllama.model.LogLine
import com.iguar.armedllama.model.Metrics
import com.iguar.armedllama.model.ModelEntry
import com.iguar.armedllama.model.ModelState
import com.iguar.armedllama.model.MonitorUiState
import com.iguar.armedllama.model.Panel
import com.iguar.armedllama.model.Release
import com.iguar.armedllama.model.ReleaseState
import com.iguar.armedllama.model.ServerSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Drives the dashboard. **Everything here is mock data** — an eased random walk on a 280 ms
 * timer, exactly mirroring the prototype. Each producer is annotated with the matching
 * "WIRE THIS" item from the README; to productionize, replace the body of [tick] (and the
 * deploy/download coroutines) with real polling / process IO and keep the rest.
 */
class MonitorViewModel : ViewModel() {

    var state by mutableStateOf(
        MonitorUiState(models = seedModels())
    )
        private set

    private var tickJob: Job? = null
    private var tickCount = 0

    /** Real device telemetry (WIRE THIS #3–5). Reads fall back to mock when unreadable. */
    private val telemetry = DeviceTelemetry()

    init {
        // Size the core grid to the real CPU and scale bars by the real max frequency,
        // both read once at startup. Falls back to the mock defaults when sysfs is hidden.
        val coreCount = telemetry.coreCount().takeIf { it > 0 } ?: CORE_COUNT
        state = state.copy(
            metrics = state.metrics.copy(
                cores = List(coreCount) { 600f },
                maxCoreMhz = telemetry.maxCoreMhz() ?: state.metrics.maxCoreMhz,
            ),
        )
        startTicker()
    }

    // ----- Telemetry loop (mock) --------------------------------------------------------------

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                tick()
                delay(280) // README: prototype updates every 280 ms
            }
        }
    }

    /**
     * One sample. CPU %, per-core MHz, memory and temperature (WIRE THIS #3–5) come from real
     * `/proc` + `sysfs` reads via [telemetry]; each falls back to the mock random walk when the
     * source is hidden (SELinux, emulator, offline core). GPU and throughput (#6–7) stay mock.
     */
    private fun tick() {
        val running = state.running
        val m = state.metrics

        // Real reads. cpuPercent() must run every tick to keep its delta baseline fresh.
        val cpuReal = telemetry.cpuPercent()
        val memReal = telemetry.memory()
        val tempReal = telemetry.temperature()
        val coresReal = telemetry.coreMhz(m.cores.size).takeIf { list -> list.any { it > 0f } }

        val mock = if (running) {
            m.copy(
                cpu = walk(m.cpu, 52f, 78f, 9f),
                temp = walk(m.temp, 49f, 61f, 2.5f),
                tps = walk(m.tps, 68f, 92f, 10f),
                pp = walk(m.pp, 190f, 320f, 40f),
                gpu = walk(m.gpu, 48f, 76f, 12f),
                gpuMemUsed = walk(m.gpuMemUsed, 2.6f, 4.4f, 0.4f),
                ramUsed = walk(m.ramUsed, 5200f, 5950f, 120f),
                cores = m.cores.map { walk(it, 900f, 2900f, 700f) },
            )
        } else {
            m.copy(
                cpu = ease(m.cpu, rnd(1f, 7f), 0.25f),
                temp = ease(m.temp, rnd(31f, 35f), 0.25f),
                tps = ease(m.tps, 0f, 0.5f),
                pp = ease(m.pp, 0f, 0.5f),
                gpu = ease(m.gpu, 0f, 0.4f),
                gpuMemUsed = ease(m.gpuMemUsed, 0.4f, 0.3f),
                ramUsed = ease(m.ramUsed, 3000f, 0.2f),
                cores = m.cores.map { ease(it, rnd(440f, 820f), 0.3f) },
            )
        }

        // Prefer real telemetry; fall back to the mock band when a source is unavailable.
        val next = mock.copy(
            cpu = cpuReal ?: mock.cpu,
            temp = tempReal ?: mock.temp,
            ramUsed = memReal?.usedMb ?: mock.ramUsed,
            ramTotal = memReal?.totalMb ?: mock.ramTotal,
            cores = coresReal ?: mock.cores,
        )

        val h = state.histories
        val histories = Histories(
            cpu = push(h.cpu, next.cpu / 100f),
            ram = push(h.ram, next.ramPct),
            temp = push(h.temp, ((next.temp - 25f) / 45f)),
            tps = push(h.tps, next.tps / 120f),
            pp = push(h.pp, next.pp / 360f),
        )

        var logs = state.logs
        // README: a new log line roughly every 3rd tick (~840 ms) while running.
        if (running && tickCount % 3 == 0) {
            logs = appendLog(logs, nextLogLine(next))
        }
        tickCount++

        state = state.copy(metrics = next, histories = histories, logs = logs)
    }

    // ----- Public actions ---------------------------------------------------------------------

    /** WIRE THIS (#1): launch / terminate the real llama-server process here. */
    fun toggleRunning() {
        val nowRunning = !state.running
        state = state.copy(running = nowRunning)
        if (nowRunning) {
            state = state.copy(
                logs = appendLog(state.logs, LogLine(now(), "main: server listening on http://0.0.0.0:${state.settings.port}")),
            )
        }
    }

    fun openMenu() { state = state.copy(panel = Panel.MENU) }
    fun closeMenu() { state = state.copy(panel = null) }
    fun navigate(panel: Panel) { state = state.copy(panel = panel) }
    fun backToMenu() { state = state.copy(panel = Panel.MENU) }

    // Settings (WIRE THIS #8: persist + apply as launch flags) --------------------------------
    fun updateSettings(transform: (ServerSettings) -> ServerSettings) {
        state = state.copy(settings = transform(state.settings))
    }

    // Update llama.cpp (WIRE THIS #9) ----------------------------------------------------------
    fun startDeploy() {
        if (state.release.state != ReleaseState.IDLE) return
        viewModelScope.launch {
            setRelease { it.copy(state = ReleaseState.DOWNLOADING, progress = 0f) }
            while (state.release.progress < 1f) {
                delay(220)
                setRelease { it.copy(progress = (it.progress + rnd(0.07f, 0.17f)).coerceAtMost(1f)) }
            }
            setRelease { it.copy(state = ReleaseState.DEPLOYING) }
            delay(1500)
            setRelease { it.copy(state = ReleaseState.DEPLOYED) }
        }
    }

    private fun setRelease(transform: (Release) -> Release) {
        state = state.copy(release = transform(state.release))
    }

    // Download model (WIRE THIS #10) -----------------------------------------------------------
    fun updateHfQuery(q: String) { state = state.copy(hfQuery = q) }

    fun downloadModel(id: String) {
        val target = state.models.firstOrNull { it.id == id } ?: return
        if (target.state != ModelState.IDLE) return
        viewModelScope.launch {
            updateModel(id) { it.copy(state = ModelState.DOWNLOADING, progress = 0f) }
            var progress = 0f
            while (progress < 1f) {
                delay(240)
                progress = (progress + rnd(0.04f, 0.12f)).coerceAtMost(1f)
                updateModel(id) { it.copy(progress = progress) }
            }
            updateModel(id) { it.copy(state = ModelState.INSTALLED) }
        }
    }

    private fun updateModel(id: String, transform: (ModelEntry) -> ModelEntry) {
        state = state.copy(models = state.models.map { if (it.id == id) transform(it) else it })
    }

    // ----- Helpers ----------------------------------------------------------------------------

    private fun push(history: List<Float>, value: Float): List<Float> =
        (history + value.coerceIn(0f, 1f)).takeLast(HISTORY_SIZE)

    private fun appendLog(logs: List<LogLine>, line: LogLine): List<LogLine> =
        (logs + line).takeLast(LOG_CAP)

    /** Random walk that drifts toward and stays inside [min, max]. */
    private fun walk(current: Float, min: Float, max: Float, step: Float): Float {
        val delta = rnd(-step, step)
        var v = current + delta
        if (v < min) v = min + rnd(0f, step)
        if (v > max) v = max - rnd(0f, step)
        return v
    }

    /** Exponential ease of [current] toward [target] by factor [k]. */
    private fun ease(current: Float, target: Float, k: Float): Float = current + (target - current) * k

    private fun rnd(min: Float, max: Float): Float = min + Random.nextFloat() * (max - min)

    private fun now(): String {
        val t = System.currentTimeMillis()
        val s = (t / 1000) % 86400
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", s / 3600, (s % 3600) / 60, s % 60, (t % 1000))
    }

    /** A plausible llama.cpp server log line. WIRE THIS (#2): replace with real captured lines. */
    private fun nextLogLine(m: Metrics): LogLine {
        val task = 300 + Random.nextInt(40)
        val nPast = 120 + Random.nextInt(80)
        val nTokens = 12 + Random.nextInt(40)
        val msPerTok = (1000f / m.tps.coerceAtLeast(1f))
        val body = when (Random.nextInt(5)) {
            0 -> "slot launch_slot_: id 0 | task $task | processing task"
            1 -> "slot update_slots: id 0 | task $task | new prompt, n_ctx_slot = ${state.settings.ctx}, n_keep = 0, n_prompt_tokens = $nTokens"
            2 -> "slot release: id 0 | task $task | stop processing: n_past = $nPast, truncated = 0"
            3 -> String.format(
                Locale.US,
                "print_timings: prompt eval time = %.2f ms / %d tokens (%.2f ms per token, %.2f tokens per second)",
                nTokens * (1000f / m.pp.coerceAtLeast(1f)), nTokens, 1000f / m.pp.coerceAtLeast(1f), m.pp,
            )
            else -> String.format(
                Locale.US,
                "print_timings: eval time = %.2f ms / %d runs (%.2f ms per token, %.2f tokens per second)",
                nTokens * msPerTok, nTokens, msPerTok, m.tps,
            )
        }
        return LogLine(now(), body)
    }

    private fun seedModels(): List<ModelEntry> = listOf(
        ModelEntry("1", "bartowski/Llama-3.1-8B-Instruct-GGUF", "Llama-3.1-8B-Instruct", "Q4_K_M", 4.9f, ModelState.INSTALLED),
        ModelEntry("2", "ggml-org/gemma-2-2b-it-GGUF", "Gemma-2-2B-it", "Q4_K_M", 1.7f),
        ModelEntry("3", "bartowski/Qwen2.5-7B-Instruct-GGUF", "Qwen2.5-7B-Instruct", "Q5_K_M", 5.4f),
        ModelEntry("4", "microsoft/Phi-3.5-mini-instruct-GGUF", "Phi-3.5-mini-instruct", "Q4_K_M", 2.4f),
        ModelEntry("5", "bartowski/Mistral-7B-Instruct-v0.3-GGUF", "Mistral-7B-Instruct-v0.3", "Q4_K_M", 4.4f),
        ModelEntry("6", "ggml-org/SmolLM2-1.7B-Instruct-GGUF", "SmolLM2-1.7B-Instruct", "Q8_0", 1.8f),
    )
}

/** Round a float to a whole number for display. */
fun Float.toIntDisplay(): Int = this.roundToInt()
