package com.iguar.armoredllama.server

/** Local model library logic: which files are primary models, and how switching rewrites config. */

/** Vision projector companion (mmproj). */
fun isVisionFile(name: String): Boolean = "mmproj" in name.lowercase()

/** Draft/speculative companion: draft, mtp, or dspark (Bonsai's DSpark drafter). */
fun isDraftFile(name: String): Boolean {
    val n = name.lowercase()
    return "draft" in n || "mtp" in n || "dspark" in n
}

/** Companion (non-primary) GGUF: draft/mtp/dspark speculative models and mmproj vision projectors. */
fun isCompanionFile(name: String): Boolean = isVisionFile(name) || isDraftFile(name)

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

/**
 * The quant label from a GGUF filename. Longest/most-specific tokens first so `Q4_K_M` wins over
 * a bare `Q4` and `Q2_K_XL` over `Q2_K`. Unrecognized → "GGUF".
 */
fun quantFrom(name: String): String {
    val upper = name.uppercase()
    return QUANT_TOKENS.firstOrNull { it in upper } ?: "GGUF"
}

private val QUANT_TOKENS = listOf(
    "IQ4_XS", "IQ4_NL", "IQ3_XXS", "IQ3_XS", "IQ3_M", "IQ3_S",
    "IQ2_XXS", "IQ2_XS", "IQ2_M", "IQ2_S", "IQ1_M", "IQ1_S",
    "Q5_K_M", "Q5_K_S", "Q4_K_M", "Q4_K_S", "Q3_K_L", "Q3_K_M", "Q3_K_S",
    "Q2_K_XL", "Q2_K_S", "Q2_K",
    "Q8_0", "Q6_K", "Q5_1", "Q5_0", "Q4_1", "Q4_0", "Q1_0",
    "BF16", "FP16", "F16", "F32",
)
