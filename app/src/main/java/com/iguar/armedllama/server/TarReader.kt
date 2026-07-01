package com.iguar.armedllama.server

import java.io.InputStream

/** One extracted tar entry: relative [name] and raw [bytes]. */
class TarEntry(val name: String, val bytes: ByteArray)

/**
 * Minimal USTAR reader — enough to extract a llama.cpp release tarball. Regular files only
 * (directories / other types skipped). Header layout: name@0(100), size@124(12, octal),
 * typeflag@156(1). Caller wraps a `GZIPInputStream` around the .tar.gz before calling.
 */
fun readTar(input: InputStream): List<TarEntry> {
    val out = ArrayList<TarEntry>()
    val header = ByteArray(512)
    while (readFully(input, header)) {
        if (header.all { it == 0.toByte() }) break // end-of-archive marker
        val name = cString(header, 0, 100)
        val size = octal(header, 124, 12)
        val typeflag = header[156].toInt().toChar()
        val data = ByteArray(size.toInt())
        if (size > 0 && !readFully(input, data)) error("truncated tar entry: $name")
        val pad = ((512 - (size % 512)) % 512).toInt()
        if (pad > 0) skipFully(input, pad)
        if (typeflag == '0' || typeflag == ' ' || typeflag.code == 0) out.add(TarEntry(name, data))
    }
    return out
}

private fun readFully(input: InputStream, buf: ByteArray): Boolean {
    var n = 0
    while (n < buf.size) {
        val r = input.read(buf, n, buf.size - n)
        if (r < 0) return false
        n += r
    }
    return true
}

private fun skipFully(input: InputStream, count: Int) {
    var remaining = count.toLong()
    val scratch = ByteArray(minOf(count, 512))
    while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped > 0) { remaining -= skipped; continue }
        val r = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
        if (r < 0) return
        remaining -= r
    }
}

private fun cString(buf: ByteArray, off: Int, len: Int): String {
    var end = off
    val limit = off + len
    while (end < limit && buf[end] != 0.toByte()) end++
    return String(buf, off, end - off, Charsets.US_ASCII)
}

private fun octal(buf: ByteArray, off: Int, len: Int): Long {
    val s = cString(buf, off, len).trim()
    return if (s.isEmpty()) 0L else s.toLong(8)
}
