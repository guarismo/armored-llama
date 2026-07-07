package com.iguar.armoredllama.server

/** Turn a [LlamaConfig] into the llama-server argv. Pure; flags omitted when their field is blank. */
fun buildArgs(config: LlamaConfig, binaryPath: String, modelsDir: String): List<String> {
    fun path(f: String) = "$modelsDir/$f"
    val args = mutableListOf(binaryPath)
    args += listOf("-m", path(config.modelFile))
    if (config.useDraft && config.draftFile.isNotBlank()) args += listOf("--model-draft", path(config.draftFile))
    if (config.useMmproj && config.mmprojFile.isNotBlank()) args += listOf("--mmproj", path(config.mmprojFile))
    if (config.useDraft && config.specType.isNotBlank()) {
        args += listOf("--spec-type", config.specType)
        args += listOf("--spec-draft-n-max", config.specDraftNMax.toString())
        args += listOf("--spec-draft-p-min", config.specDraftPMin.toString())
    }
    if (config.noMmap) args += "--no-mmap"
    args += listOf("--flash-attn", if (config.flashAttn) "on" else "off")
    args += if (config.contBatch) "--cont-batching" else "--no-cont-batching"
    if (config.mlock) args += "--mlock"
    args += if (config.jinja) "--jinja" else "--no-jinja"
    args += listOf("--reasoning-budget", config.reasoningBudget.toString())
    if (config.cacheTypeK.isNotBlank()) args += listOf("--cache-type-k", config.cacheTypeK)
    if (config.cacheTypeV.isNotBlank()) args += listOf("--cache-type-v", config.cacheTypeV)
    args += listOf("--host", config.host)
    args += listOf("--port", config.port.toString())
    args += listOf("-c", config.ctx.toString())
    args += listOf("-t", config.threads.toString())
    if (config.extraArgs.isNotBlank()) args += config.extraArgs.trim().split(Regex("\\s+"))
    return args
}
