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

data class QuantFile(val file: String, val quant: String, val sizeGB: Float)

/** A downloadable vision projector (mmproj). Drafts are intentionally not surfaced (see [parseRepo]). */
data class CompanionFile(val file: String, val quant: String, val sizeGB: Float)

/** One Hugging Face repo's downloadable GGUFs: primary quants (smallest→largest) and vision companions. */
data class HfRepoResult(
    val repo: String,
    val name: String,
    val quants: List<QuantFile>,
    val companions: List<CompanionFile>,
)

object HfModels {
    private const val MAX_CONCURRENT_RESOLVES = 5

    suspend fun searchRepos(query: String, limit: Int = 8): List<HfRepoResult> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim().ifBlank { "GGUF" }, "UTF-8")
        val url = "https://huggingface.co/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=${limit * 2}"
        val repos = getJsonArray(url)
            .mapNotNull { it.optString("id").ifBlank { it.optString("modelId") }.ifBlank { null } }
            .distinct()
            .take(limit * 2)
        // Resolve repos concurrently (bounded); each repoResult() is a blocking round-trip. awaitAll
        // preserves the download-rank order.
        val gate = Semaphore(MAX_CONCURRENT_RESOLVES)
        val resolved = coroutineScope {
            repos.map { repo ->
                async { gate.withPermit { runCatching { repoResult(repo) }.getOrNull() } }
            }.awaitAll()
        }
        resolved.filterNotNull().take(limit)
    }

    private fun repoResult(repo: String): HfRepoResult? {
        // The tree endpoint carries per-file `size` for every GGUF in one fetch.
        val tree = get("https://huggingface.co/api/models/${repo.encodePath()}/tree/main?recursive=true")
        return parseRepo(repo, tree)
    }

    /** Pure: split a HF /tree/main payload into primary quants + companions. Null if no primary GGUF. */
    internal fun parseRepo(repo: String, treeJson: String): HfRepoResult? {
        val entries = JSONArray(treeJson)
        val ggufs = (0 until entries.length())
            .mapNotNull { entries.optJSONObject(it)?.toHfFile() }
            .filter { it.name.endsWith(".gguf", ignoreCase = true) }
        fun gb(f: HfFile): Float = f.sizeBytes?.let { it.toFloat() / (1024f * 1024f * 1024f) } ?: 0f

        val quants = ggufs.filterNot { isCompanionFile(it.name) }
            .map { QuantFile(it.name, quantFrom(it.name), gb(it)) }
            .sortedBy { it.sizeGB }
        if (quants.isEmpty()) return null

        // Vision (mmproj) companions only. Draft/speculative companions (draft/mtp/dspark) are excluded
        // from the quants above by isCompanionFile AND not surfaced here: the on-device CPU build gains
        // little from speculative decoding, and custom drafters (e.g. Bonsai's dspark) are an unknown
        // architecture that makes llama-server exit on load. The curated default's MTP draft is wired
        // separately in switchedConfig, not through this path.
        val companions = ggufs.filter { isVisionFile(it.name) }
            .map { f -> CompanionFile(f.name, quantFrom(f.name), gb(f)) }
            .sortedBy { it.sizeGB }

        return HfRepoResult(repo = repo, name = repo.substringAfterLast('/'), quants = quants, companions = companions)
    }

    private fun getJsonArray(url: String): List<JSONObject> {
        val arr = JSONArray(get(url))
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

    private data class HfFile(val name: String, val sizeBytes: Long?)

    private fun JSONObject.toHfFile(): HfFile? {
        val name = optString("path").ifBlank { return null }
        val size = optLong("size", 0L).takeIf { it > 0L }
        return HfFile(name, size)
    }
}
