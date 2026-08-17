package com.iguar.armoredllama.model

import com.iguar.armoredllama.server.LlamaServerService
import com.iguar.armoredllama.server.ModelFit
import com.iguar.armoredllama.server.ModelFitLevel

/**
 * The state model from the README ("State Management"). Everything the UI renders lives here;
 * the ViewModel owns instances and swaps them immutably so Compose recomposes.
 */

/** Fallback CPU core count used until real device topology is read. */
const val CORE_COUNT = 8

/** Samples retained per metric for the sparklines. */
const val HISTORY_SIZE = 26

/** Max log lines kept in memory. */
const val LOG_CAP = 90

/** Which menu surface is showing over the dashboard (null = dashboard only). */
enum class Panel { MENU, CHAT, SETTINGS, RELEASE, HF }

/** Live telemetry read from device sources and server logs, with fallback defaults. */
data class Metrics(
    val cpu: Float = 3f,          // %  (/proc/stat)
    val temp: Float = 33f,        // °C (thermal_zone*/temp)
    val tps: Float = 0f,          // generation tok/s (parsed from server print_timing log)
    val pp: Float = 0f,           // prompt-processing tok/s (parsed from server print_timing log)
    val ramUsed: Float = 3000f,   // MB (/proc/meminfo)
    val ramTotal: Float = 7616f,  // MB (placeholder per README)
    val cores: List<Float> = List(CORE_COUNT) { 600f }, // MHz (cpufreq/scaling_cur_freq)
    val maxCoreMhz: Float = 2900f, // MHz, scales the core bars (cpuinfo_max_freq)
) {
    val ramPct: Float get() = if (ramTotal <= 0f) 0f else (ramUsed / ramTotal).coerceIn(0f, 1f)
    val ramFree: Float get() = (ramTotal - ramUsed).coerceAtLeast(0f)
    val avgCoreMhz: Int get() = if (cores.isEmpty()) 0 else cores.average().toInt()
}

/** Histories for sparklines; oldest → newest, each value already normalised to 0..1. */
data class Histories(
    val cpu: List<Float> = emptyList(),
    val ram: List<Float> = emptyList(),
    val temp: List<Float> = emptyList(),
    val tps: List<Float> = emptyList(),
    val pp: List<Float> = emptyList(),
)

/** One streamed server-log line from the running llama-server process. */
data class LogLine(val time: String, val body: String)

data class ServerSettings(
    val ctx: Int = 32768,         // -c / --ctx-size  (…/16384/32768/65536/131072)
    val threads: Int = 6,         // --threads (1..16)
    val port: Int = 8080,         // --port (1024..65535)
    val flashAttn: Boolean = true,    // --flash-attn on|off
    val contBatch: Boolean = true,    // --cont-batching / --no-cont-batching
    val mlock: Boolean = false,       // --mlock
    val jinja: Boolean = true,        // --jinja / --no-jinja
    val reasoningBudget: Int = 2042,  // --reasoning-budget
    val cacheTypeK: String = "q8_0",  // --cache-type-k
    val cacheTypeV: String = "q8_0",  // --cache-type-v
    val useDraft: Boolean = true,     // speculative decoding (--model-draft + --spec-*)
    val useMmproj: Boolean = true,    // vision/multimodal (--mmproj)
    // No -ngl: the bundled llama.cpp build is CPU-only, so GPU layers would be a no-op.
)

enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, UPDATE_AVAILABLE, DOWNLOADING, INSTALLED, ERROR }

/** Update-llama.cpp screen state. */
data class UpdateUi(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val activeTag: String = "b9775",
    val latest: com.iguar.armoredllama.server.ReleaseInfo? = null,
    val progress: Float = 0f, // 0..1 during download
    val error: String? = null,
)

enum class ModelState { IDLE, DOWNLOADING, INSTALLED, ACTIVE }

/** One downloadable quant of a repo, with its per-file RAM fit. */
data class QuantOption(val file: String, val quant: String, val sizeGB: Float, val fit: ModelFit)

/** One downloadable vision projector (mmproj) of a repo. Size only — fit is about the primary. */
data class CompanionOption(val file: String, val quant: String, val sizeGB: Float)

/** A draft/speculative GGUF present on disk, for the drafter chooser + management list. */
data class DrafterFile(val file: String, val sizeGB: Float)

/**
 * The recommended quant to headline: the largest whose fit is FITS/TIGHT, else the smallest.
 * Null when [quants] is empty.
 */
fun pickHeadline(quants: List<QuantOption>): QuantOption? =
    quants.filter { it.fit.level == ModelFitLevel.FITS || it.fit.level == ModelFitLevel.TIGHT }
        .maxByOrNull { it.sizeGB }
        ?: quants.minByOrNull { it.sizeGB }

/** A Hugging Face GGUF entry; INSTALLED means the selected file is present on disk. */
data class ModelEntry(
    val id: String,
    val repo: String,
    val name: String,
    val file: String,
    val quant: String,
    val sizeGB: Float,
    val draftFile: String = "",
    val mmprojFile: String = "",
    val fit: ModelFit = ModelFit.UNKNOWN,
    val state: ModelState = ModelState.IDLE,
    val progress: Float = 0f, // 0..1
    val downloadingFile: String? = null, // which file the DOWNLOADING state/progress refers to (null = headline)
    val freedGB: Float = 0f,  // on-disk GB a delete would free (primary + companions)
    val quants: List<QuantOption> = emptyList(),      // repo's quants (empty for local rows)
    val companions: List<CompanionOption> = emptyList(),
)

/** The full UI state, parent-owned (per README). */
data class MonitorUiState(
    val running: Boolean = false,
    val serverStatus: LlamaServerService.Status =
        LlamaServerService.Status.STOPPED,
    val serverEpoch: Int = 0,                    // bumped on each transition into RUNNING (chat reload)
    val localDrafters: List<DrafterFile> = emptyList(),
    val host: String = "shockwave",
    val modelFile: String = "Llama-3.1-8B-Q4_K_M",
    val metrics: Metrics = Metrics(),
    val histories: Histories = Histories(),
    val logs: List<LogLine> = emptyList(),
    val panel: Panel? = null,
    val settings: ServerSettings = ServerSettings(),
    val update: UpdateUi = UpdateUi(),
    val models: List<ModelEntry> = emptyList(),
    val hfQuery: String = "",
    val hfLoading: Boolean = false,
    val hfError: String? = null,
) {
    /** `<pp> pp · <gen> gen` shown in the log header. */
    val ppLabel: Int get() = metrics.pp.toInt()
    val genLabel: Int get() = metrics.tps.toInt()

    /** Models filtered by the search field (client-side, per README). */
    val visibleModels: List<ModelEntry>
        get() = if (hfQuery.isBlank()) models else models.filter {
            it.name.contains(hfQuery, true) || it.repo.contains(hfQuery, true) || it.quant.contains(hfQuery, true)
        }
}
