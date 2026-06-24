package com.iguar.armedllama.server

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

    @Test fun llamaConfigFromIni_fallsBackToDefaultsForMissingKeys() {
        val cfg = llamaConfigFromIni("[server]\nport = 9090\n")
        assertEquals(9090, cfg.port)
        assertEquals(LlamaConfig().host, cfg.host)            // default kept
        assertEquals(LlamaConfig().modelFile, cfg.modelFile)  // default kept
    }
}
