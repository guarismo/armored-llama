package com.iguar.armoredllama

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.os.Build
import android.provider.Settings
import com.iguar.armoredllama.server.CompanionKind
import com.iguar.armoredllama.server.companionFilesForRepo
import com.iguar.armoredllama.server.ConfigRepository
import com.iguar.armoredllama.server.GithubReleases
import com.iguar.armoredllama.server.HfModels
import com.iguar.armoredllama.server.LatestResult
import com.iguar.armoredllama.server.LlamaConfig
import com.iguar.armoredllama.server.LlamaServerService
import com.iguar.armoredllama.server.LogBus
import com.iguar.armoredllama.server.ModelDownloader
import com.iguar.armoredllama.server.ModelFitLevel
import com.iguar.armoredllama.server.RuntimeBinaries
import com.iguar.armoredllama.server.UpdateDownloader
import com.iguar.armoredllama.server.companionsOf
import com.iguar.armoredllama.server.estimateModelFit
import com.iguar.armoredllama.server.isServerIdle
import com.iguar.armoredllama.server.isNewer
import com.iguar.armoredllama.server.parseThroughput
import com.iguar.armoredllama.server.primaryModels
import com.iguar.armoredllama.server.quantFrom
import com.iguar.armoredllama.server.switchedConfig
import kotlinx.coroutines.flow.collect
import com.iguar.armoredllama.device.DeviceTelemetry
import com.iguar.armoredllama.model.CompanionOption
import com.iguar.armoredllama.model.CORE_COUNT
import com.iguar.armoredllama.model.HISTORY_SIZE
import com.iguar.armoredllama.model.Histories
import com.iguar.armoredllama.model.ModelEntry
import com.iguar.armoredllama.model.ModelState
import com.iguar.armoredllama.model.MonitorUiState
import com.iguar.armoredllama.model.Panel
import com.iguar.armoredllama.model.pickHeadline
import com.iguar.armoredllama.model.QuantOption
import com.iguar.armoredllama.model.ServerSettings
import com.iguar.armoredllama.model.UpdateStatus
import com.iguar.armoredllama.model.UpdateUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Drives the dashboard. Server control ([toggleRunning]), model downloads ([downloadModel]) and the
 * log feed are wired to the real [com.iguar.armoredllama.server.LlamaServerService],
 * [com.iguar.armoredllama.server.ModelDownloader] and [com.iguar.armoredllama.server.LogBus]. Device
 * telemetry (CPU %, memory, temperature, per-core MHz) is read live in [tick]; throughput (tok/s and
 * prompt-processing) is parsed from the server log via [LogBus].
 */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val configRepo = ConfigRepository(app)
    private val downloader = ModelDownloader(configRepo.modelsDir())
    private val updateDownloader = UpdateDownloader(app.filesDir)
    private val runtimeBinaries = RuntimeBinaries(app.filesDir, File(app.applicationInfo.nativeLibraryDir))

    var state by mutableStateOf(
        MonitorUiState(models = localModels())
    )
        private set

    private var tickJob: Job? = null
    private var hfSearchJob: Job? = null
    private var pendingRestart = false
    private var tickCount = 0

    /** Real device telemetry. Reads fall back to mock values when unreadable. */
    private val telemetry = DeviceTelemetry()

    init {
        // Size the core grid to the real CPU and scale bars by the real max frequency,
        // both read once at startup. Falls back to the mock defaults when sysfs is hidden.
        val coreCount = telemetry.coreCount().takeIf { it > 0 } ?: CORE_COUNT
        val cfg = configRepo.load()
        val deviceName = runCatching {
            Settings.Global.getString(app.contentResolver, "device_name")
                .takeUnless { it.isNullOrBlank() }
        }.getOrNull() ?: Build.MODEL
        val modelName = cfg.modelFile.substringBeforeLast(".").takeUnless { it.isBlank() } ?: cfg.modelFile
        state = state.copy(
            host = deviceName,
            modelFile = modelName,
            metrics = state.metrics.copy(
                cores = List(coreCount) { 600f },
                maxCoreMhz = telemetry.maxCoreMhz() ?: state.metrics.maxCoreMhz,
            ),
            settings = state.settings.copy(
                ctx = cfg.ctx,
                threads = cfg.threads,
                port = cfg.port,
                flashAttn = cfg.flashAttn,
                contBatch = cfg.contBatch,
                mlock = cfg.mlock,
                jinja = cfg.jinja,
                reasoningBudget = cfg.reasoningBudget,
                cacheTypeK = cfg.cacheTypeK,
                cacheTypeV = cfg.cacheTypeV,
                useDraft = cfg.useDraft,
                useMmproj = cfg.useMmproj,
            ),
            update = state.update.copy(activeTag = runtimeBinaries.activeTag()),
            models = localModels(
                freeRamMB = state.metrics.ramFree,
                settings = state.settings.copy(
                    ctx = cfg.ctx,
                    threads = cfg.threads,
                    port = cfg.port,
                    flashAttn = cfg.flashAttn,
                    contBatch = cfg.contBatch,
                    mlock = cfg.mlock,
                    jinja = cfg.jinja,
                    reasoningBudget = cfg.reasoningBudget,
                    cacheTypeK = cfg.cacheTypeK,
                    cacheTypeV = cfg.cacheTypeV,
                    useDraft = cfg.useDraft,
                    useMmproj = cfg.useMmproj,
                ),
            ),
        )
        startTicker()
        // Real server logs replace the mock generator.
        viewModelScope.launch { LogBus.lines.collect { state = state.copy(logs = it) } }
        // Reflect service status into UI running state; reset throughput when stopped.
        viewModelScope.launch {
            LlamaServerService.status.collect { s ->
                val stopped = s == LlamaServerService.Status.STOPPED
                state = state.copy(
                    serverStatus = s,
                    running = s == LlamaServerService.Status.RUNNING || s == LlamaServerService.Status.STARTING,
                    metrics = if (stopped) state.metrics.copy(tps = 0f, pp = 0f) else state.metrics,
                )
                // Model switch requested while running: restart with the new config once stopped.
                if (stopped && pendingRestart) {
                    pendingRestart = false
                    toggleRunning()
                }
                if (s == LlamaServerService.Status.ERROR) pendingRestart = false
            }
        }
        // Parse throughput from the uncapped raw log stream (#7). Collecting LogBus.lines (capped
        // at LOG_CAP) would miss everything once the display buffer fills, which it does long before
        // inference starts.
        viewModelScope.launch {
            LogBus.raw.collect { body ->
                if (isServerIdle(body)) {
                    // Request finished — zero the live readout instead of holding the last value.
                    state = state.copy(metrics = state.metrics.copy(tps = 0f, pp = 0f))
                } else {
                    parseThroughput(body)?.let { r ->
                        state = state.copy(
                            metrics = state.metrics.copy(
                                tps = r.tps ?: state.metrics.tps,
                                pp = r.pp ?: state.metrics.pp,
                            )
                        )
                    }
                }
            }
        }
    }

    // ----- Telemetry loop ---------------------------------------------------------------------

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
     * One sample. CPU %, per-core MHz, memory and temperature come from `/proc` + `sysfs` reads via
     * [telemetry]; each falls back to the random walk when the source is hidden (SELinux, emulator,
     * offline core). Throughput is set by the LogBus collector.
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
                ramUsed = walk(m.ramUsed, 5200f, 5950f, 120f),
                cores = m.cores.map { walk(it, 900f, 2900f, 700f) },
            )
        } else {
            m.copy(
                cpu = ease(m.cpu, rnd(1f, 7f), 0.25f),
                temp = ease(m.temp, rnd(31f, 35f), 0.25f),
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
            // Throughput (tps/pp) is updated via the LogBus collector; tick() preserves current values.
            tps = state.metrics.tps,
            pp = state.metrics.pp,
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
        if (state.running) {
            LlamaServerService.stop(ctx)
        } else {
            // Flush settings synchronously first: updateSettings persists on a background dispatcher,
            // and the service reads config.ini at launch — without this, a quick Start can race the
            // pending write and boot with the previous settings (e.g. an old context size).
            persistSettings(state.settings)
            warnIfSelectedModelMayNotFit()
            LlamaServerService.start(ctx)
        }
    }

    fun openMenu() { state = state.copy(panel = Panel.MENU) }
    fun closeMenu() { state = state.copy(panel = null) }
    fun navigate(panel: Panel) { state = state.copy(panel = panel) }
    fun backToMenu() { state = state.copy(panel = Panel.MENU) }

    // Settings (persists to config.ini) -------------------------------------------------------
    fun updateSettings(transform: (ServerSettings) -> ServerSettings) {
        val newSettings = transform(state.settings)
        state = state.copy(settings = newSettings)
        // Rapid stepper taps persist off the main thread; the start path re-flushes synchronously.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { persistSettings(newSettings) }
    }

    /** Merge [settings] into config.ini and write it. Synchronous — callers choose the thread. */
    private fun persistSettings(settings: ServerSettings) {
        val cfg = configRepo.load().copy(
            ctx = settings.ctx,
            threads = settings.threads,
            port = settings.port,
            flashAttn = settings.flashAttn,
            contBatch = settings.contBatch,
            mlock = settings.mlock,
            jinja = settings.jinja,
            reasoningBudget = settings.reasoningBudget,
            cacheTypeK = settings.cacheTypeK,
            cacheTypeV = settings.cacheTypeV,
            useDraft = settings.useDraft,
            useMmproj = settings.useMmproj,
        )
        configRepo.save(cfg)
    }

    // Update llama.cpp -------------------------------------------------------------------------
    fun checkForUpdate() {
        if (state.update.status == UpdateStatus.CHECKING || state.update.status == UpdateStatus.DOWNLOADING) return
        val active = runtimeBinaries.activeTag()
        setUpdate { it.copy(status = UpdateStatus.CHECKING, activeTag = active, error = null) }
        viewModelScope.launch {
            when (val r = GithubReleases.latest()) {
                is LatestResult.Ok -> {
                    val info = r.info
                    val newer = isNewer(info.tag, active)
                    setUpdate {
                        when {
                            // A newer release with no android-arm64 asset is a surfaced error, not
                            // a silent "up to date" — the panel only shows `error` in the ERROR state.
                            newer && info.arm64AssetUrl == null ->
                                it.copy(status = UpdateStatus.ERROR, latest = info, error = "no arm64 build in ${info.tag}")
                            newer ->
                                it.copy(status = UpdateStatus.UPDATE_AVAILABLE, latest = info, error = null)
                            else ->
                                it.copy(status = UpdateStatus.UP_TO_DATE, latest = info, error = null)
                        }
                    }
                }
                is LatestResult.Err -> setUpdate { it.copy(status = UpdateStatus.ERROR, error = r.message) }
            }
        }
    }

    fun downloadUpdate() {
        val info = state.update.latest ?: return
        val url = info.arm64AssetUrl ?: return
        if (state.update.status == UpdateStatus.DOWNLOADING) return
        setUpdate { it.copy(status = UpdateStatus.DOWNLOADING, progress = 0f, error = null) }
        viewModelScope.launch {
            try {
                val file = updateDownloader.download(url) { written, total ->
                    val frac = if (total > 0) written.toFloat() / total else 0f
                    setUpdate { it.copy(progress = frac.coerceIn(0f, 1f)) }
                }
                withContext(Dispatchers.IO) {
                    file.inputStream().use { runtimeBinaries.install(info.tag, it) }
                    file.delete() // staged tarball is no longer needed once extracted
                }
                setUpdate { it.copy(status = UpdateStatus.INSTALLED, activeTag = info.tag, progress = 1f) }
                LogBus.append("installed llama.cpp ${info.tag} — restart server to apply")
            } catch (e: Exception) {
                setUpdate { it.copy(status = UpdateStatus.ERROR, error = "install failed: ${e.message}") }
            }
        }
    }

    fun removeDownloadedUpdate() {
        if (state.running || state.serverStatus == LlamaServerService.Status.STARTING) {
            setUpdate { it.copy(status = UpdateStatus.ERROR, error = "stop the server before removing the downloaded runtime") }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runtimeBinaries.resetToBundled() }
            setUpdate {
                it.copy(
                    status = UpdateStatus.IDLE,
                    activeTag = runtimeBinaries.activeTag(),
                    progress = 0f,
                    error = null,
                )
            }
            LogBus.append("removed downloaded llama.cpp runtime; using bundled ${RuntimeBinaries.BUNDLED_TAG}")
        }
    }

    private fun setUpdate(transform: (UpdateUi) -> UpdateUi) {
        state = state.copy(update = transform(state.update))
    }

    // Download model — real files from ConfigRepository ----------------------------------------
    fun updateHfQuery(q: String) {
        state = state.copy(hfQuery = q)
        hfSearchJob?.cancel()
        if (q.isBlank()) {
            state = state.copy(models = localModels(state.metrics.ramFree, state.settings), hfLoading = false, hfError = null)
            return
        }
        hfSearchJob = viewModelScope.launch {
            delay(350)
            state = state.copy(hfLoading = true, hfError = null)
            try {
                val freeRamMB = state.metrics.ramFree
                val settings = state.settings
                val results = HfModels.searchRepos(q).map { repo ->
                    val quantOptions = repo.quants.map { qf ->
                        QuantOption(
                            file = qf.file,
                            quant = qf.quant,
                            sizeGB = qf.sizeGB,
                            fit = estimateModelFit(
                                modelSizeGB = qf.sizeGB,
                                freeRamMB = freeRamMB,
                                ctx = settings.ctx,
                                cacheTypeK = settings.cacheTypeK,
                                cacheTypeV = settings.cacheTypeV,
                                flashAttn = settings.flashAttn,
                            ),
                        )
                    }
                    val companionOptions = repo.companions.map {
                        CompanionOption(it.file, it.kind, it.quant, it.sizeGB)
                    }
                    val headline = pickHeadline(quantOptions) ?: quantOptions.first()
                    val installed = downloader.localSize(headline.file) > 0L
                    ModelEntry(
                        id = repo.repo,
                        repo = repo.repo,
                        name = repo.name,
                        file = headline.file,
                        quant = headline.quant,
                        sizeGB = headline.sizeGB,
                        fit = headline.fit,
                        state = if (installed) ModelState.INSTALLED else ModelState.IDLE,
                        quants = quantOptions,
                        companions = companionOptions,
                    )
                }.sortedWith(compareBy<ModelEntry> { fitRank(it.fit.level) }.thenBy { it.sizeGB })
                state = state.copy(
                    models = results,
                    hfLoading = false,
                    hfError = if (results.isEmpty()) "No GGUF models found." else null,
                )
            } catch (e: Exception) {
                state = state.copy(
                    models = localModels(state.metrics.ramFree, state.settings),
                    hfLoading = false,
                    hfError = "Hugging Face search failed: ${e.message}",
                )
            }
        }
    }

    fun downloadModel(repo: String, file: String) {
        val entry = state.models.firstOrNull { it.id == repo }
        if (entry?.downloadingFile != null) return
        val d = LlamaConfig()
        // Curated default brings its bundled companions; any other repo derives them from [library].
        val (draft, mmproj) = if (repo == d.repo && file == d.modelFile) {
            d.draftFile to d.mmprojFile
        } else {
            val prevLib = configRepo.load().library
            companionFilesForRepo(prevLib, repo, file)
        }
        val prev = configRepo.load()
        val config = prev.copy(
            repo = repo,
            modelFile = file,
            draftFile = draft,
            mmprojFile = mmproj,
            useDraft = draft.isNotBlank(),
            useMmproj = mmproj.isNotBlank(),
            library = prev.library + (file to repo),
        )
        configRepo.save(config)
        state = state.copy(
            modelFile = file.substringBeforeLast(".").takeUnless { it.isBlank() } ?: file,
            settings = state.settings.copy(useDraft = config.useDraft, useMmproj = config.useMmproj),
        )
        // Only fetch files not already on disk (curated companions download; derived ones are present).
        val files = listOf(config.modelFile, config.draftFile, config.mmprojFile)
            .filter { it.isNotBlank() && downloader.localSize(it) <= 0L }
        val fit = entry?.quants?.firstOrNull { it.file == file }?.fit ?: entry?.fit
        if (fit != null && (fit.level == ModelFitLevel.TIGHT || fit.level == ModelFitLevel.TOO_LARGE)) {
            LogBus.append("RAM warning before download: ${fit.label.lowercase()} for $file; ${fit.detail}")
        }
        viewModelScope.launch {
            if (files.isEmpty()) {
                updateModel(repo) { it.copy(state = ModelState.INSTALLED, downloadingFile = null, progress = 1f) }
                return@launch
            }
            updateModel(repo) { it.copy(state = ModelState.DOWNLOADING, downloadingFile = file, progress = 0f) }
            try {
                files.forEachIndexed { idx, f ->
                    downloader.download(config.repo, f) { written, total ->
                        val frac = if (total > 0) written.toFloat() / total else 0f
                        val overall = (idx + frac) / files.size
                        updateModel(repo) { it.copy(progress = overall.coerceIn(0f, 1f)) }
                    }
                }
                updateModel(repo) { it.copy(state = ModelState.INSTALLED, downloadingFile = null, progress = 1f) }
                LogBus.append("download complete: ${config.repo}")
            } catch (e: Exception) {
                updateModel(repo) { it.copy(state = ModelState.IDLE, downloadingFile = null) }
                LogBus.append("download failed: ${e.message}")
            }
        }
    }

    // (`entry` above is `state.models.firstOrNull { it.id == repo }`, reused for the fit warning.)

    /** Download a vision/draft companion and record it in [library]; wire it if its repo is active. */
    fun downloadCompanion(repo: String, file: String, kind: CompanionKind) {
        val entry = state.models.firstOrNull { it.id == repo }
        if (entry?.downloadingFile != null) return
        viewModelScope.launch {
            updateModel(repo) { it.copy(downloadingFile = file, progress = 0f) }
            try {
                downloader.download(repo, file) { written, total ->
                    val frac = if (total > 0) written.toFloat() / total else 0f
                    updateModel(repo) { it.copy(progress = frac.coerceIn(0f, 1f)) }
                }
                val cfg = configRepo.load()
                var next = cfg.copy(library = cfg.library + (file to repo))
                // If this companion belongs to the currently active model's repo, wire it for next launch.
                if (repo == cfg.repo) {
                    next = when (kind) {
                        CompanionKind.VISION -> next.copy(mmprojFile = file, useMmproj = true)
                        CompanionKind.DRAFT -> next.copy(draftFile = file, useDraft = true)
                    }
                    LogBus.append("restart to apply ${if (kind == CompanionKind.VISION) "vision" else "draft"}")
                }
                configRepo.save(next)
                updateModel(repo) { it.copy(downloadingFile = null, progress = 1f) }
                if (repo == cfg.repo) {
                    state = state.copy(settings = state.settings.copy(useDraft = next.useDraft, useMmproj = next.useMmproj))
                }
                LogBus.append("companion downloaded: $file")
            } catch (e: Exception) {
                updateModel(repo) { it.copy(downloadingFile = null) }
                LogBus.append("companion download failed: ${e.message}")
            }
        }
    }

    private fun updateModel(id: String, transform: (ModelEntry) -> ModelEntry) {
        state = state.copy(models = state.models.map { if (it.id == id) transform(it) else it })
    }

    // Local model switching (blank-search list) ------------------------------------------------
    fun switchModel(file: String) {
        if (state.serverStatus == LlamaServerService.Status.STARTING) {
            LogBus.append("switch ignored: wait for the server to finish starting")
            return
        }
        val cfg = configRepo.load()
        if (file == cfg.modelFile) return
        val next = switchedConfig(cfg, file)
        configRepo.save(next) // synchronous: the restart below reads config.ini at launch
        state = state.copy(
            modelFile = next.modelFile.substringBeforeLast(".").takeUnless { it.isBlank() } ?: next.modelFile,
            settings = state.settings.copy(useDraft = next.useDraft, useMmproj = next.useMmproj),
            models = localModels(state.metrics.ramFree, state.settings),
        )
        if (state.running) {
            LogBus.append("switching model → $file")
            pendingRestart = true
            LlamaServerService.stop(getApplication())
        }
    }

    fun deleteModel(file: String) {
        val cfg = configRepo.load()
        if (file == cfg.modelFile) {
            LogBus.append("delete refused: $file is the active model — switch first")
            return
        }
        val dir = configRepo.modelsDir()
        val removed = (listOf(file) + companionsOf(file)).filter { File(dir, it).delete() }
        if (removed.isNotEmpty()) {
            LogBus.append("deleted: ${removed.joinToString()}")
            // Drop stale file→repo bookkeeping for the removed files.
            configRepo.save(cfg.copy(library = cfg.library - removed.toSet()))
        }
        state = state.copy(models = localModels(state.metrics.ramFree, state.settings))
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

    private fun seedModels(
        freeRamMB: Float = 0f,
        settings: ServerSettings = ServerSettings(),
    ): List<ModelEntry> {
        val cfg = configRepo.load()
        val files = listOf(cfg.modelFile, cfg.draftFile, cfg.mmprojFile).filter { it.isNotBlank() }
        val installed = files.isNotEmpty() && files.all { downloader.localSize(it) > 0L }
        val sizeGB = if (cfg.repo == LlamaConfig().repo) 3.2f else 0f
        return listOf(
            ModelEntry(
                id = cfg.repo,
                repo = cfg.repo,
                name = cfg.modelFile.substringBeforeLast(".").takeUnless { it.isBlank() } ?: cfg.modelFile,
                file = cfg.modelFile,
                quant = quantFrom(cfg.modelFile),
                sizeGB = sizeGB,
                draftFile = cfg.draftFile,
                mmprojFile = cfg.mmprojFile,
                fit = estimateModelFit(
                    modelSizeGB = sizeGB,
                    freeRamMB = freeRamMB,
                    ctx = settings.ctx,
                    hasDraft = cfg.draftFile.isNotBlank(),
                    hasMmproj = cfg.mmprojFile.isNotBlank(),
                    cacheTypeK = settings.cacheTypeK,
                    cacheTypeV = settings.cacheTypeV,
                    flashAttn = settings.flashAttn,
                ),
                state = if (installed) ModelState.INSTALLED else ModelState.IDLE,
            ),
        )
    }

    /**
     * Blank-search source: every primary GGUF on disk (active first). Falls back to the
     * configured-model seed entry when the configured model is not downloaded yet (fresh
     * install), so the curated default stays one tap away.
     */
    private fun localModels(
        freeRamMB: Float = 0f,
        settings: ServerSettings = ServerSettings(),
    ): List<ModelEntry> {
        val cfg = configRepo.load()
        val onDisk = configRepo.modelsDir().listFiles().orEmpty()
            .filter { it.isFile }
            .map { it.name to it.length() }
        val entries = primaryModels(onDisk).map { (name, sizeBytes) ->
            val sizeGB = sizeBytes.toFloat() / (1024f * 1024f * 1024f)
            val preview = switchedConfig(cfg, name) // what switching here would configure
            // Deleting also removes the model's on-disk companions (curated draft/mmproj).
            val companionBytes = companionsOf(name).sumOf { comp ->
                onDisk.firstOrNull { it.first == comp }?.second ?: 0L
            }
            ModelEntry(
                id = name,
                repo = preview.repo.ifBlank { "local file" },
                name = name.substringBeforeLast(".").takeUnless { it.isBlank() } ?: name,
                file = name,
                quant = quantFrom(name),
                sizeGB = sizeGB,
                draftFile = preview.draftFile,
                mmprojFile = preview.mmprojFile,
                fit = estimateModelFit(
                    modelSizeGB = sizeGB,
                    freeRamMB = freeRamMB,
                    ctx = settings.ctx,
                    hasDraft = preview.draftFile.isNotBlank(),
                    hasMmproj = preview.mmprojFile.isNotBlank(),
                    cacheTypeK = settings.cacheTypeK,
                    cacheTypeV = settings.cacheTypeV,
                    flashAttn = settings.flashAttn,
                ),
                state = if (name == cfg.modelFile) ModelState.ACTIVE else ModelState.INSTALLED,
                freedGB = sizeGB + companionBytes.toFloat() / (1024f * 1024f * 1024f),
            )
        }.sortedWith(
            compareByDescending<ModelEntry> { it.state == ModelState.ACTIVE }
                .thenBy { it.name.lowercase() }, // stable order: listFiles() is filesystem-ordered
        )
        // Fresh install / configured model not downloaded: show the seed entry so it can be Got.
        return if (entries.none { it.state == ModelState.ACTIVE }) {
            seedModels(freeRamMB, settings) + entries
        } else entries
    }

    private fun warnIfSelectedModelMayNotFit() {
        val cfg = configRepo.load()
        val modelSizeGB = downloader.localSize(cfg.modelFile).takeIf { it > 0L }
            ?.let { it.toFloat() / (1024f * 1024f * 1024f) }
            ?: state.models.firstOrNull { it.repo == cfg.repo && it.file == cfg.modelFile }?.sizeGB
            ?: 0f
        val fit = estimateModelFit(
            modelSizeGB = modelSizeGB,
            freeRamMB = state.metrics.ramFree,
            ctx = state.settings.ctx,
            hasDraft = cfg.useDraft && cfg.draftFile.isNotBlank(),
            hasMmproj = cfg.useMmproj && cfg.mmprojFile.isNotBlank(),
            cacheTypeK = cfg.cacheTypeK,
            cacheTypeV = cfg.cacheTypeV,
            flashAttn = cfg.flashAttn,
        )
        if (fit.level == ModelFitLevel.TOO_LARGE || fit.level == ModelFitLevel.TIGHT) {
            LogBus.append("RAM warning: ${fit.label.lowercase()} for ${cfg.modelFile}; ${fit.detail}")
        }
    }
}

/** Round a float to a whole number for display. */
fun Float.toIntDisplay(): Int = this.roundToInt()

private fun fitRank(level: ModelFitLevel): Int = when (level) {
    ModelFitLevel.FITS -> 0
    ModelFitLevel.TIGHT -> 1
    ModelFitLevel.UNKNOWN -> 2
    ModelFitLevel.TOO_LARGE -> 3
}
