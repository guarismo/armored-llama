package com.iguar.armedllama.server

/** Minimal INI parse/write + LlamaConfig mapping. Pure; unit-tested on the host JVM. */

fun parseIni(text: String): Map<String, Map<String, String>> {
    val out = LinkedHashMap<String, LinkedHashMap<String, String>>()
    var section = ""
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.substring(1, line.length - 1).trim()
            out.getOrPut(section) { LinkedHashMap() }
        } else {
            val idx = line.indexOf('=')
            if (idx > 0) {
                out.getOrPut(section) { LinkedHashMap() }[line.substring(0, idx).trim()] =
                    line.substring(idx + 1).trim()
            }
        }
    }
    return out
}

fun writeIni(data: Map<String, Map<String, String>>): String {
    val sb = StringBuilder()
    for ((section, entries) in data) {
        if (entries.isEmpty()) continue
        if (section.isNotEmpty()) sb.append('[').append(section).append("]\n")
        for ((k, v) in entries) sb.append(k).append(" = ").append(v).append('\n')
        sb.append('\n')
    }
    return sb.toString()
}

fun LlamaConfig.toIni(): String = writeIni(
    linkedMapOf(
        "server" to linkedMapOf(
            "host" to host,
            "port" to port.toString(),
            "ctx" to ctx.toString(),
            "threads" to threads.toString(),
            "no_mmap" to noMmap.toString(),
            "tools" to tools,
            "spec_type" to specType,
            "spec_draft_n_max" to specDraftNMax.toString(),
            "spec_draft_p_min" to specDraftPMin.toString(),
            "extra_args" to extraArgs,
        ),
        "model" to linkedMapOf(
            "repo" to repo,
            "model" to modelFile,
            "draft" to draftFile,
            "mmproj" to mmprojFile,
        ),
    ),
)

fun llamaConfigFromIni(text: String): LlamaConfig {
    val ini = parseIni(text)
    val s = ini["server"].orEmpty()
    val m = ini["model"].orEmpty()
    val d = LlamaConfig()
    return LlamaConfig(
        host = s["host"] ?: d.host,
        port = s["port"]?.toIntOrNull() ?: d.port,
        ctx = s["ctx"]?.toIntOrNull() ?: d.ctx,
        threads = s["threads"]?.toIntOrNull() ?: d.threads,
        noMmap = s["no_mmap"]?.toBooleanStrictOrNull() ?: d.noMmap,
        tools = s["tools"] ?: d.tools,
        specType = s["spec_type"] ?: d.specType,
        specDraftNMax = s["spec_draft_n_max"]?.toIntOrNull() ?: d.specDraftNMax,
        specDraftPMin = s["spec_draft_p_min"]?.toFloatOrNull() ?: d.specDraftPMin,
        extraArgs = s["extra_args"] ?: d.extraArgs,
        repo = m["repo"] ?: d.repo,
        modelFile = m["model"] ?: d.modelFile,
        draftFile = m["draft"] ?: d.draftFile,
        mmprojFile = m["mmproj"] ?: d.mmprojFile,
    )
}
