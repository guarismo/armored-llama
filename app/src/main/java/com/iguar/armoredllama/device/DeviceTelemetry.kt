package com.iguar.armoredllama.device

import java.io.File

/**
 * Abstraction over reading `/proc` + `sysfs`, so [DeviceTelemetry] can be unit-tested
 * with an in-memory fake. [RealFileSource] is the production implementation.
 */
interface FileSource {
    /** File contents, or null if missing / unreadable (SELinux, offline core, …). */
    fun read(path: String): String?
    /** Names of the entries directly under [path]; empty if not a readable directory. */
    fun list(path: String): List<String>
}

/** Reads the real filesystem. All reads are best-effort: failures return null/empty. */
object RealFileSource : FileSource {
    override fun read(path: String): String? =
        runCatching { File(path).readText() }.getOrNull()

    override fun list(path: String): List<String> =
        runCatching { File(path).list()?.toList() }.getOrNull().orEmpty()
}

/** A memory reading in megabytes. */
data class MemoryReading(val usedMb: Float, val totalMb: Float)

/**
 * Real device telemetry: CPU %, per-core MHz, memory and temperature read from `/proc` and
 * `sysfs`. Every accessor returns null/0 when the source is unreadable so callers can fall back to
 * mock data. CPU % is stateful (needs two samples).
 */
class DeviceTelemetry(private val src: FileSource = RealFileSource) {

    private var prevCpu: CpuTimes? = null

    /** Number of `cpuN` entries under `/sys/devices/system/cpu` (0 if unreadable). */
    fun coreCount(): Int =
        src.list(CPU_ROOT).count { it.matches(Regex("cpu\\d+")) }

    /** Max core frequency in MHz from `cpu0/cpufreq/cpuinfo_max_freq`, or null. */
    fun maxCoreMhz(): Float? =
        src.read("$CPU_ROOT/cpu0/cpufreq/cpuinfo_max_freq")?.let { khzToMhz(it) }

    /** Per-core current MHz; offline / unreadable cores report 0. */
    fun coreMhz(count: Int): List<Float> =
        (0 until count).map { i ->
            src.read("$CPU_ROOT/cpu$i/cpufreq/scaling_cur_freq")?.let { khzToMhz(it) } ?: 0f
        }

    /** Used + total RAM in MB from `/proc/meminfo`, or null if unreadable. */
    fun memory(): MemoryReading? {
        val info = src.read("/proc/meminfo")?.let { parseMemInfo(it) } ?: return null
        val usedKb = (info.totalKb - info.availableKb).coerceAtLeast(0L)
        return MemoryReading(usedMb = usedKb / 1024f, totalMb = info.totalKb / 1024f)
    }

    /** Busy CPU % over the interval since the previous call; null on the first call. */
    fun cpuPercent(): Float? {
        val curr = src.read("/proc/stat")?.let { parseCpuTimes(it) } ?: return null
        val prev = prevCpu
        prevCpu = curr
        return prev?.let { cpuPercent(it, curr) }
    }

    /** Representative SoC temperature in °C from `/sys/class/thermal`, or null. */
    fun temperature(): Float? {
        val zones = src.list(THERMAL_ROOT)
            .filter { it.startsWith("thermal_zone") }
            .mapNotNull { zone ->
                val type = src.read("$THERMAL_ROOT/$zone/type")?.trim() ?: return@mapNotNull null
                val celsius = src.read("$THERMAL_ROOT/$zone/temp")?.let { parseMilliCelsius(it) }
                    ?: return@mapNotNull null
                ThermalZone(type = type, celsius = celsius)
            }
        return selectTemperature(zones)
    }

    private companion object {
        const val CPU_ROOT = "/sys/devices/system/cpu"
        const val THERMAL_ROOT = "/sys/class/thermal"
    }
}
