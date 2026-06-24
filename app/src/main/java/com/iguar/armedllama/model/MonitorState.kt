package com.iguar.armedllama.model

import com.iguar.armedllama.server.LlamaServerService

/**
 * The state model from the README ("State Management"). Everything the UI renders lives here;
 * the ViewModel owns instances and swaps them immutably so Compose recomposes.
 */

/** Number of CPU cores the dashboard renders. WIRE THIS (#3): derive from the real core count. */
const val CORE_COUNT = 8

/** Samples retained per metric for the sparklines. */
const val HISTORY_SIZE = 26

/** Max log lines kept in memory. */
const val LOG_CAP = 90

/** Which menu surface is showing over the dashboard (null = dashboard only). */
enum class Panel { MENU, SETTINGS, RELEASE, HF }

/** Live telemetry. WIRE THIS #3–7 replace each field with a real OS source. */
data class Metrics(
    val cpu: Float = 3f,          // %  (/proc/stat)
    val temp: Float = 33f,        // °C (thermal_zone*/temp)
    val tps: Float = 0f,          // generation tok/s (server timings/metrics)
    val pp: Float = 0f,           // prompt-processing tok/s
    val gpu: Float = 0f,          // %  (vendor sysfs, e.g. kgsl gpubusy)
    val gpuMemUsed: Float = 0.4f, // GB
    val gpuMemTotal: Float = 5.8f,// GB
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

/** One streamed server-log line. WIRE THIS (#2): real stdout/stderr lines. */
data class LogLine(val time: String, val body: String)

data class ServerSettings(
    val ctx: Int = 4096,          // -c / --ctx-size  (2048/4096/8192/16384/32768)
    val threads: Int = 6,         // --threads (1..16)
    val gpuLayers: Int = 33,      // -ngl (0..99)
    val port: Int = 8080,         // --port (1024..65535)
    val flashAttn: Boolean = true,    // -fa
    val contBatch: Boolean = true,    // --cont-batching
    val mlock: Boolean = false,       // --mlock
)

enum class ReleaseState { IDLE, DOWNLOADING, DEPLOYING, DEPLOYED }

/** Update-llama.cpp card. WIRE THIS (#9): from the GitHub Releases API for ggml-org/llama.cpp. */
data class Release(
    val tag: String = "b4789",
    val repo: String = "ggml-org/llama.cpp",
    val date: String = "2026-06-21",
    val asset: String = "llama-android-arm64.zip",
    val sizeMB: Int = 28,
    val whatsNew: List<String> = listOf(
        "Faster prompt processing on Adreno via improved batching",
        "Fix mlock on Android 15 (scoped storage)",
        "Add --cont-batching default for server",
    ),
    val state: ReleaseState = ReleaseState.IDLE,
    val progress: Float = 0f, // 0..1
)

enum class ModelState { IDLE, DOWNLOADING, INSTALLED }

/** A Hugging Face GGUF entry. WIRE THIS (#10): from the HF API; INSTALLED = present on disk. */
data class ModelEntry(
    val id: String,
    val repo: String,
    val name: String,
    val quant: String,
    val sizeGB: Float,
    val state: ModelState = ModelState.IDLE,
    val progress: Float = 0f, // 0..1
)

/** The full UI state, parent-owned (per README). */
data class MonitorUiState(
    val running: Boolean = false,
    val serverStatus: LlamaServerService.Status =
        LlamaServerService.Status.STOPPED,
    val host: String = "shockwave",
    val modelFile: String = "Llama-3.1-8B-Q4_K_M",
    val metrics: Metrics = Metrics(),
    val histories: Histories = Histories(),
    val logs: List<LogLine> = emptyList(),
    val panel: Panel? = null,
    val settings: ServerSettings = ServerSettings(),
    val release: Release = Release(),
    val models: List<ModelEntry> = emptyList(),
    val hfQuery: String = "",
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
