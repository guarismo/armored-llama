package com.iguar.armoredllama.server

import org.json.JSONObject

/** Parsed GitHub `releases/latest` payload for ggml-org/llama.cpp. Pure; host-JVM tested. */
data class ReleaseInfo(
    val tag: String,
    val date: String,          // YYYY-MM-DD, from published_at
    val notes: String,
    val arm64AssetUrl: String?, // download URL of the android-arm64 tar.gz, or null if absent
)

/** Asset filename llama.cpp publishes for an android-arm64 build of a given tag. */
fun androidArm64AssetName(tag: String): String = "llama-$tag-bin-android-arm64.tar.gz"

/** The integer after the leading `b` in a llama.cpp tag (`"b9775" -> 9775`), or null. */
fun parseBuildNumber(tag: String): Int? = tag.trim().removePrefix("b").toIntOrNull()

/** True when [latestTag] has a higher build number than [currentTag]. */
fun isNewer(latestTag: String, currentTag: String): Boolean {
    val l = parseBuildNumber(latestTag) ?: return false
    val c = parseBuildNumber(currentTag) ?: return true
    return l > c
}

/** Parse a GitHub `releases/latest` JSON body. Returns null on malformed JSON or a missing tag. */
fun parseLatestRelease(json: String): ReleaseInfo? {
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val tag = obj.optString("tag_name").ifBlank { return null }
    val date = obj.optString("published_at").take(10)
    val notes = obj.optString("body")
    val wanted = androidArm64AssetName(tag)
    var url: String? = null
    val assets = obj.optJSONArray("assets")
    if (assets != null) {
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name") == wanted) {
                url = a.optString("browser_download_url").ifBlank { null }
                break
            }
        }
    }
    return ReleaseInfo(tag = tag, date = date, notes = notes, arm64AssetUrl = url)
}
