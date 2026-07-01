package com.iguar.armedllama.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class RuntimeBinariesTest {
    @get:Rule val tmp = TemporaryFolder()

    // Reuses the tar builder shape from TarReaderTest, gzipped (install() expects a .tar.gz stream).
    private fun tarGz(vararg files: Pair<String, ByteArray>): ByteArray {
        val raw = ByteArrayOutputStream()
        for ((name, data) in files) {
            val h = ByteArray(512)
            name.toByteArray(Charsets.US_ASCII).copyInto(h, 0)
            data.size.toString(8).padStart(11, '0').toByteArray(Charsets.US_ASCII).copyInto(h, 124)
            h[156] = '0'.code.toByte()
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            raw.write(h); raw.write(data); raw.write(ByteArray((512 - data.size % 512) % 512))
        }
        raw.write(ByteArray(1024))
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(raw.toByteArray()) }
        return gz.toByteArray()
    }

    private fun newBinaries(): RuntimeBinaries {
        val files = tmp.newFolder("files")
        val bundled = tmp.newFolder("lib")
        java.io.File(bundled, RuntimeBinaries.BUNDLED_EXEC).writeText("bundled")
        // no-op chmod so the test doesn't touch android.system.Os
        return RuntimeBinaries(files, bundled, chmod = { _, _ -> })
    }

    @Test fun activeExecutable_defaultsToBundledWhenNothingInstalled() {
        val rb = newBinaries()
        assertEquals(RuntimeBinaries.BUNDLED_TAG, rb.activeTag())
        assertTrue(rb.activeExecutable().execPath.endsWith(RuntimeBinaries.BUNDLED_EXEC))
    }

    @Test fun install_extractsExecAndSoAndBecomesActive() {
        val rb = newBinaries()
        rb.install("b9999", ByteArrayInputStream(tarGz(
            "llama-b9999-bin-android-arm64/llama-server" to "exe".toByteArray(),
            "llama-b9999-bin-android-arm64/libggml.so" to "so".toByteArray(),
        )))
        assertEquals("b9999", rb.activeTag())
        val target = rb.activeExecutable()
        assertTrue(target.execPath.endsWith("/b9999/llama-server"))
        assertEquals("exe", java.io.File(target.execPath).readText())
        assertEquals("so", java.io.File(target.libDir, "libggml.so").readText())
    }

    @Test fun install_prunesPreviousDownloadedVersion() {
        val rb = newBinaries()
        rb.install("b9000", ByteArrayInputStream(tarGz("x/llama-server" to "old".toByteArray())))
        rb.install("b9999", ByteArrayInputStream(tarGz("x/llama-server" to "new".toByteArray())))
        assertEquals("b9999", rb.activeTag())
        val activeDir = java.io.File(rb.activeExecutable().libDir)     // …/llama/b9999
        assertFalse(java.io.File(activeDir.parentFile, "b9000").exists()) // …/llama/b9000 pruned
    }
}
