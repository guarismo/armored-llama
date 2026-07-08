package com.iguar.armoredllama.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class HfModelCandidate(
    val repo: String,
    val name: String,
    val file: String,
    val quant: String,
    val sizeGB: Float,
)

object HfModels {
    private const val MAX_CONCURRENT_RESOLVES = 5

    suspend fun search(query: String, limit: Int = 8): List<HfModelCandidate> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim().ifBlank { "GGUF" }, "UTF-8")
        val url = "https://huggingface.co/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=${limit * 2}"
        val repos = getJsonArray(url)
            .mapNotNull { it.optString("id").ifBlank { it.optString("modelId") }.ifBlank { null } }
            .distinct()
            .take(limit * 2)
        // Resolve repos concurrently (bounded) — each candidate() is its own blocking round-trip;
        // sequentially this was up to 16 fetches. awaitAll preserves the download-rank order.
        val gate = Semaphore(MAX_CONCURRENT_RESOLVES)
        val resolved = coroutineScope {
            repos.map { repo ->
                async { gate.withPermit { runCatching { candidate(repo) }.getOrNull() } }
            }.awaitAll()
        }
        resolved.filterNotNull().take(limit)
    }

    private fun candidate(repo: String): HfModelCandidate? {
        // The tree endpoint carries per-file `size`; the /api/models siblings array does not.
        val tree = get("https://huggingface.co/api/models/${repo.encodePath()}/tree/main?recursive=true")
        return parseCandidate(repo, tree)
    }

    /** Pure: pick the best primary GGUF (skipping companions) from a HF /tree/main payload. */
    internal fun parseCandidate(repo: String, treeJson: String): HfModelCandidate? {
        val entries = JSONArray(treeJson)
        val files = (0 until entries.length())
            .mapNotNull { entries.optJSONObject(it)?.toHfFile() }
            .filter { it.name.endsWith(".gguf", ignoreCase = true) }
            .filterNot { isCompanionFile(it.name) }
        val selected = files.minWithOrNull(compareBy<HfFile> { fileRank(it.name) }.thenBy { it.name.lowercase() })
            ?: return null
        val size = selected.sizeBytes?.let { it.toFloat() / (1024f * 1024f * 1024f) } ?: 0f
        return HfModelCandidate(
            repo = repo,
            name = selected.name.substringBeforeLast("."),
            file = selected.name,
            quant = quantFrom(selected.name),
            sizeGB = size,
        )
    }

    private fun getJsonArray(url: String): List<JSONObject> {
        val text = get(url)
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "ArmoredLlama")
            setRequestProperty("Accept", "application/json")
            connect()
        }
        try {
            if (conn.responseCode != 200) error("Hugging Face returned HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun String.encodePath(): String =
        split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }

    private fun fileRank(name: String): Int {
        val n = name.uppercase()
        return when {
            "Q4_K_M" in n -> 0
            "Q4_K_S" in n -> 1
            "Q5_K_M" in n -> 2
            "Q3_K_M" in n -> 3
            "Q2_K" in n -> 4
            "Q8_0" in n -> 5
            "F16" in n || "FP16" in n -> 6
            else -> 10
        }
    }

    private fun quantFrom(name: String): String {
        val upper = name.uppercase()
        val candidates = listOf("Q4_K_M", "Q4_K_S", "Q5_K_M", "Q3_K_M", "Q2_K", "Q8_0", "F16", "FP16")
        return candidates.firstOrNull { it in upper } ?: "GGUF"
    }

    private data class HfFile(val name: String, val sizeBytes: Long?)

    private fun JSONObject.toHfFile(): HfFile? {
        val name = optString("path").ifBlank { return null }
        val size = optLong("size", 0L).takeIf { it > 0L }
        return HfFile(name, size)
    }
}
