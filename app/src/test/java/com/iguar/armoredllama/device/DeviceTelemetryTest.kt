package com.iguar.armoredllama.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the IO orchestration in [DeviceTelemetry] against an in-memory [FileSource],
 * so no real device files are touched. Parsing rules themselves live in ProcParsersTest.
 */
class DeviceTelemetryTest {

    private class FakeSource(
        private val files: Map<String, String> = emptyMap(),
        private val dirs: Map<String, List<String>> = emptyMap(),
    ) : FileSource {
        override fun read(path: String): String? = files[path]
        override fun list(path: String): List<String> = dirs[path] ?: emptyList()
    }

    @Test
    fun coreCount_countsCpuNDirectoriesOnly() {
        val src = FakeSource(
            dirs = mapOf(
                "/sys/devices/system/cpu" to listOf("cpu0", "cpu1", "cpu2", "cpufreq", "cpuidle", "kernel_max"),
            ),
        )
        assertEquals(3, DeviceTelemetry(src).coreCount())
    }

    @Test
    fun coreMhz_readsEachCoreAndZerosOfflineOnes() {
        val src = FakeSource(
            files = mapOf(
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq" to "1804800",
                // cpu1 offline → file absent
                "/sys/devices/system/cpu/cpu2/cpufreq/scaling_cur_freq" to "2400000",
            ),
        )
        assertEquals(listOf(1804.8f, 0f, 2400f), DeviceTelemetry(src).coreMhz(3))
    }

    @Test
    fun memory_reportsUsedAndTotalInMegabytes() {
        val src = FakeSource(
            files = mapOf(
                "/proc/meminfo" to "MemTotal: 8388608 kB\nMemAvailable: 2097152 kB\n",
            ),
        )
        val mem = DeviceTelemetry(src).memory()!!
        // total 8388608 kB / 1024 = 8192 MB; used (8388608-2097152)/1024 = 6144 MB
        assertEquals(8192f, mem.totalMb, 0.001f)
        assertEquals(6144f, mem.usedMb, 0.001f)
    }

    @Test
    fun memory_returnsNullWhenMeminfoUnreadable() {
        assertNull(DeviceTelemetry(FakeSource()).memory())
    }

    @Test
    fun cpuPercent_isNullOnFirstSampleThenComputesDelta() {
        // /proc/stat changes between the two reads; the fake returns a fresh value each call.
        val readings = ArrayDeque(
            listOf(
                "cpu  100 0 0 100 0 0 0 0 0 0\n", // total 200, idle 100
                "cpu  150 0 0 150 0 0 0 0 0 0\n", // total 300, idle 150 → busy 50%
            ),
        )
        val src = object : FileSource {
            override fun read(path: String): String? =
                if (path == "/proc/stat") readings.removeFirst() else null
            override fun list(path: String): List<String> = emptyList()
        }
        val telemetry = DeviceTelemetry(src)
        assertNull(telemetry.cpuPercent())                 // first sample: no baseline
        assertEquals(50f, telemetry.cpuPercent()!!, 0.001f) // second: 50% busy
    }
}
