package com.iguar.armoredllama.server

import android.content.Context
import java.io.File

/** Loads/saves config.ini (seeding defaults on first run) and resolves app storage dirs. */
class ConfigRepository(private val context: Context) {
    private fun base(): File = context.getExternalFilesDir(null) ?: context.filesDir
    private fun configFile(): File = File(base(), "config.ini")

    fun load(): LlamaConfig {
        val f = configFile()
        if (!f.exists()) {
            val def = LlamaConfig()
            save(def)
            return def
        }
        val text = f.readText()
        val config = llamaConfigFromIni(text)
        val server = parseIni(text)["server"].orEmpty()
        if (listOf("jinja", "reasoning_budget", "cache_type_k", "cache_type_v").any { it !in server }) {
            save(config)
        }
        return config
    }

    fun save(config: LlamaConfig) {
        val f = configFile()
        f.parentFile?.mkdirs()
        f.writeText(config.toIni())
    }

    fun modelsDir(): File = File(base(), "models").apply { mkdirs() }
    fun logFile(): File = File(base(), "logs/server.log").also { it.parentFile?.mkdirs() }
}
