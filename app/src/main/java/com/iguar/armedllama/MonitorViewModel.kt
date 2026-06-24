package com.iguar.armedllama

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iguar.armedllama.server.ConfigRepository
import com.iguar.armedllama.server.LlamaServerService
import com.iguar.armedllama.server.LogBus
import com.iguar.armedllama.server.ModelDownloader
import kotlinx.coroutines.flow.collect
import com.iguar.armedllama.device.DeviceTelemetry
import com.iguar.armedllama.model.CORE_COUNT
import com.iguar.armedllama.model.HISTORY_SIZE
import com.iguar.armedllama.model.Histories
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
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Drives the dashboard. Server control ([toggleRunning]), model downloads ([downloadModel]) and the
 * log feed are wired to the real [com.iguar.armedllama.server.LlamaServerService],
 * [com.iguar.armedllama.server.ModelDownloader] and [com.iguar.armedllama.server.LogBus]. Device
 * telemetry (CPU %, memory, temperature, per-core MHz) is read live in [tick]; GPU and throughput
 * metrics there are still a mock random walk pending real sources.
 */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val configRepo = ConfigRepository(app)
    private val downloader = ModelDownloader(configRepo.modelsDir())

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
        val cfg = configRepo.load()
        state = state.copy(
            metrics = state.metrics.copy(
                cores = List(coreCount) { 600f },
                maxCoreMhz = telemetry.maxCoreMhz() ?: state.metrics.maxCoreMhz,
            ),
            settings = state.settings.copy(ctx = cfg.ctx, threads = cfg.threads, port = cfg.port),
        )
        startTicker()
        // Real server logs replace the mock generator.
        viewModelScope.launch { LogBus.lines.collect { state = state.copy(logs = it) } }
        // Reflect service status into UI running state.
        viewModelScope.launch {
            LlamaServerService.status.collect { s ->
                state = state.copy(
                    serverStatus = s,
                    running = s == LlamaServerService.Status.RUNNING || s == LlamaServerService.Status.STARTING,
                )
            }
        }
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

        tickCount++
        state = state.copy(metrics = next, histories = histories)
    }

    // ----- Public actions ---------------------------------------------------------------------

    fun toggleRunning() {
        val ctx = getApplication<Application>()
        if (state.running) LlamaServerService.stop(ctx) else LlamaServerService.start(ctx)
    }

    fun openMenu() { state = state.copy(panel = Panel.MENU) }
    fun closeMenu() { state = state.copy(panel = null) }
    fun navigate(panel: Panel) { state = state.copy(panel = panel) }
    fun backToMenu() { state = state.copy(panel = Panel.MENU) }

    // Settings (persists ctx/threads/port to config.ini) --------------------------------------
    fun updateSettings(transform: (ServerSettings) -> ServerSettings) {
        val newSettings = transform(state.settings)
        state = state.copy(settings = newSettings)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cfg = configRepo.load().copy(
                ctx = newSettings.ctx,
                threads = newSettings.threads,
                port = newSettings.port,
            )
            configRepo.save(cfg)
        }
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

    // Download model — real files from ConfigRepository ----------------------------------------
    fun updateHfQuery(q: String) { state = state.copy(hfQuery = q) }

    fun downloadModel(id: String) {
        val target = state.models.firstOrNull { it.id == id } ?: return
        if (target.state == ModelState.DOWNLOADING) return
        val config = configRepo.load()
        val files = listOf(config.modelFile, config.draftFile, config.mmprojFile).filter { it.isNotBlank() }
        viewModelScope.launch {
            if (files.isEmpty()) {
                LogBus.append("download skipped: no model files configured")
                updateModel(id) { it.copy(state = ModelState.IDLE) }
                return@launch
            }
            updateModel(id) { it.copy(state = ModelState.DOWNLOADING, progress = 0f) }
            try {
                files.forEachIndexed { idx, file ->
                    downloader.download(config.repo, file) { written, total ->
                        val frac = if (total > 0) written.toFloat() / total else 0f
                        val overall = (idx + frac) / files.size
                        updateModel(id) { it.copy(progress = overall.coerceIn(0f, 1f)) }
                    }
                }
                updateModel(id) { it.copy(state = ModelState.INSTALLED, progress = 1f) }
                LogBus.append("download complete: ${config.repo}")
            } catch (e: Exception) {
                updateModel(id) { it.copy(state = ModelState.IDLE) }
                LogBus.append("download failed: ${e.message}")
            }
        }
    }

    private fun updateModel(id: String, transform: (ModelEntry) -> ModelEntry) {
        state = state.copy(models = state.models.map { if (it.id == id) transform(it) else it })
    }

    // ----- Helpers ----------------------------------------------------------------------------

    private fun push(history: List<Float>, value: Float): List<Float> =
        (history + value.coerceIn(0f, 1f)).takeLast(HISTORY_SIZE)

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

    private fun seedModels(): List<ModelEntry> {
        val cfg = configRepo.load()
        val files = listOf(cfg.modelFile, cfg.draftFile, cfg.mmprojFile).filter { it.isNotBlank() }
        val installed = files.isNotEmpty() && files.all { downloader.localSize(it) > 0L }
        return listOf(
            ModelEntry(
                id = "gemma-4",
                repo = cfg.repo,
                name = "gemma-4-E2B-it-qat",
                quant = "Q2_K_XL",
                sizeGB = 3.2f,
                state = if (installed) ModelState.INSTALLED else ModelState.IDLE,
            ),
        )
    }
}

/** Round a float to a whole number for display. */
fun Float.toIntDisplay(): Int = this.roundToInt()
