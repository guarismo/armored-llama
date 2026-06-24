package com.iguar.armedllama.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure /proc + sysfs parsers backing WIRE THIS #3–5
 * (CPU %, per-core MHz, memory, temperature). These run on the host JVM and
 * carry no Android dependencies.
 */
class ProcParsersTest {

    // ----- /proc/meminfo (#4) -----------------------------------------------------------------

    @Test
    fun parseMemInfo_readsTotalAndAvailable() {
        val text = """
            MemTotal:        7799808 kB
            MemFree:          512000 kB
            MemAvailable:    4096000 kB
            Buffers:           40000 kB
        """.trimIndent()

        val info = parseMemInfo(text)

        assertEquals(7799808L, info!!.totalKb)
        assertEquals(4096000L, info.availableKb)
    }

    @Test
    fun parseMemInfo_returnsNullWhenTotalMissing() {
        assertNull(parseMemInfo("MemFree: 512000 kB"))
    }

    // ----- /proc/stat (#3) --------------------------------------------------------------------

    @Test
    fun parseCpuTimes_sumsAllFieldsForTotalAndKeepsIdlePlusIowait() {
        // cpu  user nice system idle iowait irq softirq steal guest guest_nice
        val text = "cpu  100 20 30 700 40 5 5 0 0 0\ncpu0 50 10 15 350 20 2 3 0 0 0\n"

        val times = parseCpuTimes(text)!!

        // idle (700) + iowait (40) = 740
        assertEquals(740L, times.idle)
        // sum of all ten = 100+20+30+700+40+5+5+0+0+0 = 900
        assertEquals(900L, times.total)
    }

    @Test
    fun parseCpuTimes_returnsNullWithoutAggregateLine() {
        assertNull(parseCpuTimes("cpu0 50 10 15 350 20 2 3 0 0 0\n"))
    }

    @Test
    fun cpuPercent_isBusyShareOfDelta() {
        val prev = CpuTimes(idle = 100, total = 200)
        val curr = CpuTimes(idle = 150, total = 300)
        // total delta = 100, idle delta = 50 → busy = 50 → 50%
        assertEquals(50f, cpuPercent(prev, curr)!!, 0.001f)
    }

    @Test
    fun cpuPercent_returnsNullWhenNoElapsedTime() {
        val same = CpuTimes(idle = 100, total = 200)
        assertNull(cpuPercent(same, same))
    }

    // ----- thermal_zone*/temp (#5) ------------------------------------------------------------

    @Test
    fun parseMilliCelsius_convertsMillidegrees() {
        assertEquals(48.2f, parseMilliCelsius("48200")!!, 0.001f)
    }

    @Test
    fun parseMilliCelsius_passesThroughPlainCelsius() {
        // Some zones already report whole degrees.
        assertEquals(42f, parseMilliCelsius("42")!!, 0.001f)
    }

    @Test
    fun parseMilliCelsius_returnsNullForGarbage() {
        assertNull(parseMilliCelsius("n/a"))
    }

    // ----- scaling_cur_freq / cpuinfo_max_freq (#3) -------------------------------------------

    @Test
    fun khzToMhz_convertsKilohertz() {
        assertEquals(1804.8f, khzToMhz("1804800")!!, 0.001f)
    }

    @Test
    fun khzToMhz_returnsNullForGarbage() {
        assertNull(khzToMhz(""))
    }

    // ----- thermal-zone selection (#5) --------------------------------------------------------

    @Test
    fun selectTemperature_prefersCpuZoneOverBattery() {
        val zones = listOf(
            ThermalZone(type = "battery", celsius = 31f),
            ThermalZone(type = "cpu-0-0-usr", celsius = 52f),
            ThermalZone(type = "gpu", celsius = 47f),
        )
        assertEquals(52f, selectTemperature(zones)!!, 0.001f)
    }

    @Test
    fun selectTemperature_takesHottestCpuZoneWhenSeveralMatch() {
        val zones = listOf(
            ThermalZone(type = "cpu-0", celsius = 48f),
            ThermalZone(type = "cpuss-1", celsius = 55f),
        )
        assertEquals(55f, selectTemperature(zones)!!, 0.001f)
    }

    @Test
    fun selectTemperature_fallsBackToHottestPlausibleWhenNoCpuZone() {
        val zones = listOf(
            ThermalZone(type = "battery", celsius = 30f),
            ThermalZone(type = "pmic", celsius = 44f),
        )
        assertEquals(44f, selectTemperature(zones)!!, 0.001f)
    }

    @Test
    fun selectTemperature_ignoresImplausibleReadingsAndReturnsNullWhenNoneValid() {
        val zones = listOf(
            ThermalZone(type = "cpu", celsius = 0f),
            ThermalZone(type = "soc", celsius = 900f),
        )
        assertNull(selectTemperature(zones))
    }
}
