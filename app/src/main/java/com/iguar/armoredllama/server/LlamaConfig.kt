package com.iguar.armoredllama.server

/** Full llama-server launch configuration, persisted as config.ini. Pure (no Android deps). */
data class LlamaConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val ctx: Int = 32768,
    val threads: Int = 4,
    val noMmap: Boolean = true,
    val flashAttn: Boolean = true,   // --flash-attn on|off  (on shrinks the KV cache → bigger ctx fits)
    val contBatch: Boolean = true,   // --cont-batching / --no-cont-batching
    val mlock: Boolean = false,      // --mlock (lock model in RAM; off by default to leave headroom)
    val jinja: Boolean = true,       // --jinja / --no-jinja
    val reasoningBudget: Int = 2042, // --reasoning-budget
    val cacheTypeK: String = "q8_0", // --cache-type-k
    val cacheTypeV: String = "q8_0", // --cache-type-v
    // NOTE: --tools is intentionally not modeled — that server feature is experimental and unreliable.
    val specType: String = "draft-mtp",
    val specDraftNMax: Int = 4,
    val specDraftPMin: Float = 0.6f,
    val extraArgs: String = "",
    val repo: String = "unsloth/gemma-4-E2B-it-qat-mobile-GGUF",
    val modelFile: String = "gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf",
    val draftFile: String = "mtp-gemma-4-E2B-it.gguf",
    val mmprojFile: String = "mmproj-F16.gguf",
    // Feature gates (filenames above are kept either way, so toggling is reversible):
    val useDraft: Boolean = true,    // speculative decoding: emit --model-draft + --spec-*
    val useMmproj: Boolean = true,   // vision/multimodal: emit --mmproj (~1 GB; off for text-only)
    // Downloaded-file → repo bookkeeping (INI [library] section); display + switch-back metadata.
    val library: Map<String, String> = emptyMap(),
)
