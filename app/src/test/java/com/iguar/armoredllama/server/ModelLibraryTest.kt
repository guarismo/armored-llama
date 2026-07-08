package com.iguar.armoredllama.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLibraryTest {

    @Test fun primaryModels_filtersCompanionsAndNonGguf() {
        val files = listOf(
            "gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf" to 2_186_184_768L,
            "Qwen3.5-4B-Q4_K_M.GGUF" to 2_740_937_888L, // case-insensitive extension
            "mtp-gemma-4-E2B-it.gguf" to 59_234_176L,   // companion: mtp
            "mmproj-F16.gguf" to 985_654_080L,          // companion: mmproj
            "notes.txt" to 10L,                         // not a gguf
        )

        assertEquals(listOf(files[0], files[1]), primaryModels(files))
    }

    @Test fun switchedConfig_restoresCuratedProfile() {
        val d = LlamaConfig()
        val qwen = d.copy(
            repo = "unsloth/Qwen3.5-4B-GGUF", modelFile = "Qwen3.5-4B-Q4_K_M.gguf",
            draftFile = "", mmprojFile = "", useDraft = false, useMmproj = false, ctx = 8192,
        )

        val back = switchedConfig(qwen, d.modelFile)

        assertEquals(d.repo, back.repo)
        assertEquals(d.modelFile, back.modelFile)
        assertEquals(d.draftFile, back.draftFile)
        assertEquals(d.mmprojFile, back.mmprojFile)
        assertEquals(true, back.useDraft)
        assertEquals(true, back.useMmproj)
        assertEquals(8192, back.ctx) // server settings pass through untouched
    }

    @Test fun switchedConfig_clearsCompanionsForOtherModels() {
        val next = switchedConfig(LlamaConfig(), "Qwen3.5-4B-Q4_K_M.gguf")

        assertEquals("Qwen3.5-4B-Q4_K_M.gguf", next.modelFile)
        assertEquals("", next.draftFile)
        assertEquals("", next.mmprojFile)
        assertEquals(false, next.useDraft)
        assertEquals(false, next.useMmproj)
    }

    @Test fun companionsOf_curatedTakesItsCompanions_othersNone() {
        val d = LlamaConfig()
        assertEquals(listOf(d.draftFile, d.mmprojFile), companionsOf(d.modelFile))
        assertEquals(emptyList<String>(), companionsOf("Qwen3.5-4B-Q4_K_M.gguf"))
    }

    @Test fun switchedConfig_looksUpRepoInLibrary() {
        val cfg = LlamaConfig(library = mapOf("Qwen3.5-4B-Q4_K_M.gguf" to "unsloth/Qwen3.5-4B-GGUF"))

        val next = switchedConfig(cfg, "Qwen3.5-4B-Q4_K_M.gguf")

        assertEquals("unsloth/Qwen3.5-4B-GGUF", next.repo)
        assertEquals(cfg.library, next.library) // library passes through
    }
}
