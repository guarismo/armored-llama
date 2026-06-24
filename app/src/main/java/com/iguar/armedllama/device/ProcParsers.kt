package com.iguar.armedllama.device

/**
 * Pure parsers for the Linux `/proc` + `sysfs` text formats behind WIRE THIS #3–5
 * (CPU %, per-core MHz, memory, temperature). Kept free of any Android dependency
 * so they can be unit-tested on the host JVM; [DeviceTelemetry] does the file IO.
 */

/** Parsed `/proc/meminfo` figures, in kB (the unit the file reports). */
data class MemInfo(val totalKb: Long, val availableKb: Long)

/**
 * Parse `MemTotal` and `MemAvailable` from `/proc/meminfo`. Returns null when
 * `MemTotal` is absent. `MemAvailable` falls back to 0 if missing (very old kernels).
 */
fun parseMemInfo(text: String): MemInfo? {
    var total: Long? = null
    var available: Long? = null
    for (line in text.lineSequence()) {
        when {
            line.startsWith("MemTotal:") -> total = firstLong(line)
            line.startsWith("MemAvailable:") -> available = firstLong(line)
        }
    }
    val t = total ?: return null
    return MemInfo(totalKb = t, availableKb = available ?: 0L)
}

/** Aggregate CPU jiffies from the `cpu ` line of `/proc/stat`. */
data class CpuTimes(val idle: Long, val total: Long)

/**
 * Parse the aggregate `cpu ` line of `/proc/stat`. `idle` includes idle + iowait;
 * `total` is the sum of every field. Returns null if the aggregate line is absent.
 */
fun parseCpuTimes(text: String): CpuTimes? {
    val line = text.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
    val fields = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
    if (fields.size < 5) return null
    val idle = fields[3] + fields[4] // idle + iowait
    return CpuTimes(idle = idle, total = fields.sum())
}

/**
 * Busy CPU percentage over the interval between two [CpuTimes] samples.
 * Returns null when no time has elapsed (avoids divide-by-zero / first sample).
 */
fun cpuPercent(prev: CpuTimes, curr: CpuTimes): Float? {
    val totalDelta = curr.total - prev.total
    if (totalDelta <= 0L) return null
    val idleDelta = curr.idle - prev.idle
    val busy = (totalDelta - idleDelta).toFloat()
    return (busy / totalDelta * 100f).coerceIn(0f, 100f)
}

/**
 * Parse a thermal-zone `temp` reading. Values ≥ 1000 are treated as millidegrees
 * (the common case); smaller values are taken as whole °C. Null on garbage.
 */
fun parseMilliCelsius(text: String): Float? {
    val v = text.trim().toLongOrNull() ?: return null
    return if (v >= 1000L) v / 1000f else v.toFloat()
}

/** Convert a cpufreq kHz reading (`scaling_cur_freq`/`cpuinfo_max_freq`) to MHz. */
fun khzToMhz(text: String): Float? {
    val khz = text.trim().toLongOrNull() ?: return null
    return khz / 1000f
}

/** One thermal zone's reported `type` and temperature in °C. */
data class ThermalZone(val type: String, val celsius: Float)

/** °C range a device sensor must fall in to be trusted (filters out 0 / raw-millideg junk). */
private val PLAUSIBLE_C = 1f..150f

/** Zone `type` substrings that indicate a CPU/SoC sensor, preferred over battery/PMIC/etc. */
private val CPU_ZONE_HINTS = listOf("cpu", "soc", "tsens", "apc", "cluster")

/**
 * Pick the temperature most representative of the SoC: the hottest plausible CPU/SoC zone,
 * or — failing any — the hottest plausible zone of any kind. Null if nothing is plausible.
 */
fun selectTemperature(zones: List<ThermalZone>): Float? {
    val plausible = zones.filter { it.celsius in PLAUSIBLE_C }
    val cpu = plausible.filter { z -> CPU_ZONE_HINTS.any { z.type.lowercase().contains(it) } }
    return (cpu.ifEmpty { plausible }).maxByOrNull { it.celsius }?.celsius
}

private fun firstLong(line: String): Long? =
    Regex("\\d+").find(line)?.value?.toLongOrNull()
