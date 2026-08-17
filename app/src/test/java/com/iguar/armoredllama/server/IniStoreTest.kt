package com.iguar.armoredllama.server

import org.junit.Assert.assertEquals
import org.junit.Test

class IniStoreTest {
    @Test fun parseIni_groupsKeysBySectionAndIgnoresCommentsAndBlanks() {
        val text = """
            ; a comment
            [server]
            host = 0.0.0.0
            port = 8080

            [model]
            repo = unsloth/gemma-4-E2B-it-qat-mobile-GGUF
        """.trimIndent()
        val ini = parseIni(text)
        assertEquals("0.0.0.0", ini["server"]!!["host"])
        assertEquals("8080", ini["server"]!!["port"])
        assertEquals("unsloth/gemma-4-E2B-it-qat-mobile-GGUF", ini["model"]!!["repo"])
    }

    @Test fun configRoundTripsThroughIni() {
        val config = LlamaConfig()
        val restored = llamaConfigFromIni(config.toIni())
        assertEquals(config, restored)
    }

    @Test fun optimizationFlagsRoundTripThroughIni() {
        val config = LlamaConfig(
            flashAttn = false,
            contBatch = false,
            mlock = true,
            jinja = false,
            reasoningBudget = 4096,
            cacheTypeK = "q5_1",
            cacheTypeV = "q4_0",
            ctx = 32768,
        )
        val restored = llamaConfigFromIni(config.toIni())
        assertEquals(false, restored.flashAttn)
        assertEquals(false, restored.contBatch)
        assertEquals(true, restored.mlock)
        assertEquals(false, restored.jinja)
        assertEquals(4096, restored.reasoningBudget)
        assertEquals("q5_1", restored.cacheTypeK)
        assertEquals("q4_0", restored.cacheTypeV)
        assertEquals(32768, restored.ctx)
    }

    @Test fun llamaConfigFromIni_acceptsHyphenatedRuntimeOptionAliases() {
        val cfg = llamaConfigFromIni(
            """
            [server]
            reasoning-budget = 2042
            cache-type-k = q8_0
            cache-type-v = q8_0
            """.trimIndent()
        )
        assertEquals(2042, cfg.reasoningBudget)
        assertEquals("q8_0", cfg.cacheTypeK)
        assertEquals("q8_0", cfg.cacheTypeV)
    }

    @Test fun modelFeatureTogglesRoundTripThroughIni() {
        val config = LlamaConfig(useMmproj = false, useDraft = false)
        val restored = llamaConfigFromIni(config.toIni())
        assertEquals(false, restored.useMmproj)
        assertEquals(false, restored.useDraft)
    }

    @Test fun llamaConfigFromIni_fallsBackToDefaultsForMissingKeys() {
        val cfg = llamaConfigFromIni("[server]\nport = 9090\n")
        assertEquals(9090, cfg.port)
        assertEquals(LlamaConfig().host, cfg.host)            // default kept
        assertEquals(LlamaConfig().modelFile, cfg.modelFile)  // default kept
    }

    @Test fun libraryRoundTrips() {
        val cfg = LlamaConfig(library = mapOf("Qwen3.5-4B-Q4_K_M.gguf" to "unsloth/Qwen3.5-4B-GGUF"))
        val restored = llamaConfigFromIni(cfg.toIni())
        assertEquals(cfg.library, restored.library)
    }

    @Test fun missingLibrarySectionYieldsEmptyMap() {
        val restored = llamaConfigFromIni(LlamaConfig().toIni())
        assertEquals(emptyMap<String, String>(), restored.library)
    }

    @Test fun drafters_roundTripThroughIni() {
        val cfg = LlamaConfig(drafters = mapOf(
            "HiFi.gguf" to "mtp-gemma-4-E2B-it.gguf",
            "Qwen.gguf" to "",
        ))
        val back = llamaConfigFromIni(cfg.toIni())
        assertEquals(cfg.drafters, back.drafters)
    }

    @Test fun drafters_emptyMapWritesNoSection_absentReadsEmpty() {
        val ini = LlamaConfig().toIni()
        assertEquals(false, ini.contains("[drafters]"))
        assertEquals(emptyMap<String, String>(), llamaConfigFromIni(ini).drafters)
    }
}
