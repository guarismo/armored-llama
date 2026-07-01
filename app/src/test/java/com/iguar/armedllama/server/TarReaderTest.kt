package com.iguar.armedllama.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TarReaderTest {

    /** Build a minimal USTAR archive of (name -> bytes) regular-file entries. */
    private fun tarOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in files) {
            val h = ByteArray(512)
            name.toByteArray(Charsets.US_ASCII).copyInto(h, 0)
            val sizeOctal = data.size.toString(8).padStart(11, '0')
            sizeOctal.toByteArray(Charsets.US_ASCII).copyInto(h, 124)
            h[156] = '0'.code.toByte()                 // typeflag: regular file
            for (i in 148 until 156) h[i] = ' '.code.toByte() // checksum field (reader ignores it)
            out.write(h)
            out.write(data)
            val pad = (512 - data.size % 512) % 512
            out.write(ByteArray(pad))
        }
        out.write(ByteArray(1024)) // two zero blocks = end-of-archive
        return out.toByteArray()
    }

    @Test fun readTar_extractsRegularFilesWithBytes() {
        val a = "hello".toByteArray()
        val b = ByteArray(600) { (it % 7).toByte() } // spans two 512 blocks
        val tar = tarOf("llama-server" to a, "libggml.so" to b)

        val entries = readTar(ByteArrayInputStream(tar))

        assertEquals(2, entries.size)
        assertEquals("llama-server", entries[0].name)
        assertArrayEquals(a, entries[0].bytes)
        assertEquals("libggml.so", entries[1].name)
        assertArrayEquals(b, entries[1].bytes)
    }

    @Test fun readTar_emptyArchiveYieldsNoEntries() {
        assertEquals(0, readTar(ByteArrayInputStream(ByteArray(1024))).size)
    }
}
