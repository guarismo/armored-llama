package com.iguar.armedllama.server

/** Turn a [LlamaConfig] into the llama-server argv. Pure; flags omitted when their field is blank. */
fun buildArgs(config: LlamaConfig, binaryPath: String, modelsDir: String): List<String> {
    fun path(f: String) = "$modelsDir/$f"
    val args = mutableListOf(binaryPath)
    args += listOf("-m", path(config.modelFile))
    if (config.draftFile.isNotBlank()) args += listOf("--model-draft", path(config.draftFile))
    if (config.mmprojFile.isNotBlank()) args += listOf("--mmproj", path(config.mmprojFile))
    if (config.specType.isNotBlank()) {
        args += listOf("--spec-type", config.specType)
        args += listOf("--spec-draft-n-max", config.specDraftNMax.toString())
        args += listOf("--spec-draft-p-min", config.specDraftPMin.toString())
    }
    if (config.noMmap) args += "--no-mmap"
    args += listOf("--host", config.host)
    args += listOf("--port", config.port.toString())
    args += listOf("-c", config.ctx.toString())
    args += listOf("-t", config.threads.toString())
    if (config.tools.isNotBlank()) args += listOf("--tools", config.tools)
    if (config.extraArgs.isNotBlank()) args += config.extraArgs.trim().split(Regex("\\s+"))
    return args
}
