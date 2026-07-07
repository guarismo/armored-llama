package com.iguar.armoredllama.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HfModelsTest {

    /** The real bug: HF /tree/main carries per-file `size`; siblings did not. Size must populate. */
    @Test
    fun parseCandidate_readsSizeFromTree() {
        val tree = """
            [
              {"type":"file","path":"gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf","size":2186184768},
              {"type":"file","path":"mtp-gemma-4-E2B-it.gguf","size":59234176},
              {"type":"file","path":"mmproj-F16.gguf","size":985654080}
            ]
        """.trimIndent()

        val c = HfModels.parseCandidate("unsloth/gemma-4-E2B-it-qat-mobile-GGUF", tree)!!

        assertEquals("gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf", c.file)
        assertEquals("Q2_K", c.quant)
        assertEquals(2186184768f / (1024f * 1024f * 1024f), c.sizeGB, 0.01f)
    }

    /** Best-ranked quant wins; mmproj/draft/mtp companions are excluded from selection. */
    @Test
    fun parseCandidate_picksBestQuantSkippingCompanions() {
        val tree = """
            [
              {"type":"file","path":"model-Q2_K.gguf","size":1000000000},
              {"type":"file","path":"model-Q4_K_M.gguf","size":2000000000},
              {"type":"file","path":"mmproj-F16.gguf","size":500000000},
              {"type":"file","path":"README.md","size":1234}
            ]
        """.trimIndent()

        val c = HfModels.parseCandidate("acme/model-GGUF", tree)!!

        assertEquals("model-Q4_K_M.gguf", c.file)
        assertEquals("Q4_K_M", c.quant)
        assertEquals(2000000000f / (1024f * 1024f * 1024f), c.sizeGB, 0.01f)
    }

    /** A file with no size field stays selectable but reports 0 (→ UNKNOWN fit), not a crash. */
    @Test
    fun parseCandidate_missingSizeYieldsZero() {
        val tree = """[{"type":"file","path":"model-Q4_K_M.gguf"}]"""

        val c = HfModels.parseCandidate("acme/model-GGUF", tree)!!

        assertEquals("model-Q4_K_M.gguf", c.file)
        assertEquals(0f, c.sizeGB, 0.0001f)
    }

    @Test
    fun parseCandidate_returnsNullWhenNoGguf() {
        val tree = """[{"type":"file","path":"README.md","size":10}]"""

        assertNull(HfModels.parseCandidate("acme/x", tree))
    }
}
