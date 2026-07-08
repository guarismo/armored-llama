package com.iguar.armoredllama.server

/** Local model library logic: which files are primary models, and how switching rewrites config. */

/** Companion (non-primary) GGUF: draft/mtp speculative models and mmproj vision projectors. */
fun isCompanionFile(name: String): Boolean {
    val n = name.lowercase()
    return "mmproj" in n || "draft" in n || "mtp" in n
}

/** Filter (fileName, sizeBytes) pairs down to primary .gguf models. */
fun primaryModels(files: List<Pair<String, Long>>): List<Pair<String, Long>> =
    files.filter { (name, _) -> name.endsWith(".gguf", ignoreCase = true) && !isCompanionFile(name) }

/**
 * The config after switching to [file]. The curated default model restores its full profile
 * (repo + draft + mmproj from [LlamaConfig] defaults); any other file clears the companions.
 * Feature toggles follow the files; server settings pass through untouched.
 */
fun switchedConfig(cfg: LlamaConfig, file: String): LlamaConfig {
    val d = LlamaConfig()
    val next = if (file == d.modelFile) {
        cfg.copy(repo = d.repo, modelFile = d.modelFile, draftFile = d.draftFile, mmprojFile = d.mmprojFile)
    } else {
        cfg.copy(repo = cfg.library[file] ?: "", modelFile = file, draftFile = "", mmprojFile = "")
    }
    return next.copy(useDraft = next.draftFile.isNotBlank(), useMmproj = next.mmprojFile.isNotBlank())
}

/** Files deleted along with [file]: the curated default takes its draft+mmproj; others nothing. */
fun companionsOf(file: String): List<String> {
    val d = LlamaConfig()
    return if (file == d.modelFile) listOf(d.draftFile, d.mmprojFile).filter { it.isNotBlank() } else emptyList()
}
