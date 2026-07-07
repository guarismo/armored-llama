package com.iguar.armoredllama.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Result of a releases/latest lookup: parsed info or a user-facing error message. */
sealed interface LatestResult {
    data class Ok(val info: ReleaseInfo) : LatestResult
    data class Err(val message: String) : LatestResult
}

/** Fetches the latest ggml-org/llama.cpp release from the GitHub API. */
object GithubReleases {
    private const val URL_LATEST = "https://api.github.com/repos/ggml-org/llama.cpp/releases/latest"

    suspend fun latest(): LatestResult = withContext(Dispatchers.IO) {
        val conn = runCatching {
            (URL(URL_LATEST).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "ArmoredLlama")
                setRequestProperty("Accept", "application/vnd.github+json")
                connect()
            }
        }.getOrElse {
            return@withContext LatestResult.Err("couldn't reach GitHub: ${it.message}")
        }

        try {
            when (conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().readText()
                    val info = parseLatestRelease(body)
                        ?: return@withContext LatestResult.Err("couldn't parse GitHub response")
                    LatestResult.Ok(info)
                }
                403 -> LatestResult.Err("GitHub rate-limited, try again later")
                else -> LatestResult.Err("GitHub returned HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            LatestResult.Err("update check failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }
}
