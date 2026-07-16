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

/**
 * Cleans a GitHub release body for display.
 *
 * 1. Extracts content between `<details>` tags (concatenated with blank-line separator).
 * 2. Strips remaining HTML tags.
 * 3. Strips Markdown formatting (bold, links, headings, bullets).
 * 4. Collapses runs of 3+ newlines to 2; trims leading/trailing whitespace.
 * 5. Falls back to stripping the full body when no `<details>` block is found.
 */
internal fun sanitizeNotes(raw: String): String {
    if (raw.isBlank()) return ""

    val detailsRegex = Regex("""<details[^>]*>(.*?)</details>""", RegexOption.DOT_MATCHES_ALL)
    val matches = detailsRegex.findAll(raw).toList()

    val text = if (matches.isNotEmpty()) {
        matches.joinToString("\n\n") { it.groupValues[1] }
    } else {
        raw
    }

    return text
        // Strip HTML tags
        .replace(Regex("""<[^>]+>"""), "")
        // **bold** → bold
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        // [text](url) → text
        .replace(Regex("""\[([^\]]*)\]\([^)]*\)"""), "$1")
        // # heading markers at line starts
        .replace(Regex("""(?m)^#+\s*"""), "")
        // * list bullets → - bullets
        .replace(Regex("""(?m)^\*\s"""), "- ")
        // Collapse 3+ newlines → 2
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

/** Parse a GitHub `releases/latest` JSON body. Returns null on malformed JSON or a missing tag. */
fun parseLatestRelease(json: String): ReleaseInfo? {
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val tag = obj.optString("tag_name").ifBlank { return null }
    val date = obj.optString("published_at").take(10)
    val notes = sanitizeNotes(obj.optString("body"))
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
