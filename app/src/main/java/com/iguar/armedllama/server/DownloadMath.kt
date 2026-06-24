package com.iguar.armedllama.server

/** Pure helpers for resumable Hugging Face downloads. */

fun hfUrl(repo: String, file: String): String =
    "https://huggingface.co/$repo/resolve/main/$file"

/** Offset to resume from, or null if the file is already complete. */
fun resumeOffset(existingBytes: Long, totalBytes: Long): Long? = when {
    existingBytes <= 0L -> 0L
    isComplete(existingBytes, totalBytes) -> null
    else -> existingBytes
}

fun isComplete(existingBytes: Long, totalBytes: Long): Boolean =
    totalBytes > 0L && existingBytes >= totalBytes
