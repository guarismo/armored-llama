package com.iguar.armedllama.server

/** Full llama-server launch configuration, persisted as config.ini. Pure (no Android deps). */
data class LlamaConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val ctx: Int = 8192,
    val threads: Int = 4,
    val noMmap: Boolean = true,
    val tools: String = "all",
    val specType: String = "draft-mtp",
    val specDraftNMax: Int = 4,
    val specDraftPMin: Float = 0.6f,
    val extraArgs: String = "",
    val repo: String = "unsloth/gemma-4-E2B-it-qat-mobile-GGUF",
    val modelFile: String = "gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf",
    val draftFile: String = "mtp-gemma-4-E2B-it.gguf",
    val mmprojFile: String = "mmproj-F16.gguf",
)
