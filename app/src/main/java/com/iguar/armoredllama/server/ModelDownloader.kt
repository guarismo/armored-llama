package com.iguar.armoredllama.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Resumable HF downloader writing into [modelsDir]. Reports (bytesWritten, totalBytes). */
class ModelDownloader(private val modelsDir: File) {

    fun localSize(file: String): Long = File(modelsDir, file).let { if (it.exists()) it.length() else 0L }

    suspend fun download(repo: String, file: String, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            modelsDir.mkdirs()
            val target = File(modelsDir, file)
            val existing = if (target.exists()) target.length() else 0L
            val conn = (URL(hfUrl(repo, file)).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 30_000
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
                connect()
            }
            try {
                val partial = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
                val len = conn.contentLengthLong.coerceAtLeast(0L)
                val total = if (partial) existing + len else len
                if (partial && total > 0L && isComplete(existing, total)) return@withContext target
                conn.inputStream.use { input ->
                    FileOutputStream(target, partial).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var written = if (partial) existing else 0L
                        var n = input.read(buf)
                        while (n >= 0) {
                            out.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                            n = input.read(buf)
                        }
                    }
                }
                target
            } finally {
                conn.disconnect()
            }
        }
}
