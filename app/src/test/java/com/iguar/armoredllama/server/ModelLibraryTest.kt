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

    @Test fun mmprojForRepo_findsVisionExcludingSelf() {
        val library = mapOf(
            "Bonsai-27B-Q1_0.gguf" to "prism-ml/Bonsai-27B-gguf",
            "Bonsai-27B-mmproj-Q8_0.gguf" to "prism-ml/Bonsai-27B-gguf",
            "Bonsai-27B-dspark-Q4_1.gguf" to "prism-ml/Bonsai-27B-gguf",
            "Other-Q4_K_M.gguf" to "acme/other-GGUF",
        )

        assertEquals(
            "Bonsai-27B-mmproj-Q8_0.gguf",
            mmprojForRepo(library, "prism-ml/Bonsai-27B-gguf", "Bonsai-27B-Q1_0.gguf"),
        )
    }

    @Test fun mmprojForRepo_blankWhenRepoBlankOrNoVision() {
        assertEquals("", mmprojForRepo(emptyMap(), "", "x.gguf"))
        val lib = mapOf("Other-Q4_K_M.gguf" to "acme/other-GGUF")
        assertEquals("", mmprojForRepo(lib, "acme/other-GGUF", "Other-Q4_K_M.gguf"))
    }

    @Test fun switchedConfig_wiresMmprojButNeverDraftForArbitrary() {
        // Even with a draft recorded in [library], switching to an arbitrary model wires ONLY the
        // vision projector — drafts are unsupported on-device and would crash the server.
        val cfg = LlamaConfig(
            library = mapOf(
                "Bonsai-27B-Q1_0.gguf" to "prism-ml/Bonsai-27B-gguf",
                "Bonsai-27B-mmproj-Q8_0.gguf" to "prism-ml/Bonsai-27B-gguf",
                "Bonsai-27B-dspark-Q4_1.gguf" to "prism-ml/Bonsai-27B-gguf",
            ),
        )

        val next = switchedConfig(cfg, "Bonsai-27B-Q1_0.gguf")

        assertEquals("prism-ml/Bonsai-27B-gguf", next.repo)
        assertEquals("Bonsai-27B-Q1_0.gguf", next.modelFile)
        assertEquals("", next.draftFile)
        assertEquals("Bonsai-27B-mmproj-Q8_0.gguf", next.mmprojFile)
        assertEquals(false, next.useDraft)
        assertEquals(true, next.useMmproj)
    }

    @Test fun quantFrom_recognizesLowBitAndKQuants() {
        assertEquals("Q1_0", quantFrom("Bonsai-27B-Q1_0.gguf"))
        assertEquals("IQ2_XXS", quantFrom("model-IQ2_XXS.gguf"))
        assertEquals("IQ1_S", quantFrom("model-IQ1_S.gguf"))
        assertEquals("Q2_K_S", quantFrom("model-Q2_K_S.gguf"))
        assertEquals("Q2_K_XL", quantFrom("gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf"))
        assertEquals("Q4_K_M", quantFrom("model-Q4_K_M.gguf"))
        assertEquals("BF16", quantFrom("model-mmproj-BF16.gguf"))
        assertEquals("GGUF", quantFrom("model-unknownquant.gguf"))
    }

    @Test fun quantFrom_recognizesKQuantLXlAndTernaryVariants() {
        // "_L"/"_XL" K-quant variants must beat their base token (e.g. Q6_K_L over Q6_K).
        assertEquals("Q6_K_L", quantFrom("model-Q6_K_L.gguf"))
        assertEquals("Q5_K_L", quantFrom("model-Q5_K_L.gguf"))
        assertEquals("Q4_K_XL", quantFrom("model-UD-Q4_K_XL.gguf"))
        assertEquals("Q3_K_XL", quantFrom("model-Q3_K_XL.gguf"))
        assertEquals("IQ4_XXS", quantFrom("model-IQ4_XXS.gguf"))
        assertEquals("TQ1_0", quantFrom("model-TQ1_0.gguf"))
        // Bases still resolve when no longer-variant is present.
        assertEquals("Q6_K", quantFrom("model-Q6_K.gguf"))
    }

    @Test fun draftForModel_explicitChoiceWins_emptyMeansNone() {
        val cfg = LlamaConfig(drafters = mapOf(
            "HiFi.gguf" to "mtp-gemma-4-E2B-it.gguf",
            LlamaConfig().modelFile to "",   // explicitly disable the curated model's draft
        ))
        assertEquals("mtp-gemma-4-E2B-it.gguf", draftForModel(cfg, "HiFi.gguf"))
        assertEquals("", draftForModel(cfg, LlamaConfig().modelFile))   // explicit none overrides curated
    }

    @Test fun draftForModel_curatedFallback_andArbitraryNone() {
        val cfg = LlamaConfig()  // no [drafters] entries
        assertEquals(LlamaConfig().draftFile, draftForModel(cfg, LlamaConfig().modelFile)) // curated default
        assertEquals("", draftForModel(cfg, "Qwen3.5-4B-Q4_K_M.gguf"))                     // arbitrary → none
    }

    @Test fun switchedConfig_wiresChosenDrafterForArbitraryModel() {
        val cfg = LlamaConfig(
            library = mapOf("HiFi.gguf" to "fraQtl/Gemma-4-E2B-it-Hi-Fi-GGUF"),
            drafters = mapOf("HiFi.gguf" to "mtp-gemma-4-E2B-it.gguf"),
        )
        val next = switchedConfig(cfg, "HiFi.gguf")
        assertEquals("HiFi.gguf", next.modelFile)
        assertEquals("mtp-gemma-4-E2B-it.gguf", next.draftFile)
        assertEquals(true, next.useDraft)
    }

    @Test fun switchedConfig_curatedDrafterCanBeDisabled() {
        val cfg = LlamaConfig(drafters = mapOf(LlamaConfig().modelFile to ""))
        val next = switchedConfig(cfg, LlamaConfig().modelFile)
        assertEquals("", next.draftFile)
        assertEquals(false, next.useDraft)
    }

    @Test fun draftersOnDisk_keepsOnlyDraftGgufs() {
        val files = listOf(
            "mtp-gemma-4-E2B-it.gguf" to 59_234_176L,     // draft (mtp)
            "Bonsai-27B-dspark-Q4_1.gguf" to 1_787_468_768L, // draft (dspark)
            "gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf" to 2_186_184_768L, // primary
            "mmproj-F16.gguf" to 985_654_080L,            // vision
            "notes.txt" to 10L,                           // not gguf
        )
        assertEquals(listOf(files[0], files[1]), draftersOnDisk(files))
    }

    @Test fun configAfterDrafterDelete_prunesMapsAndClearsActiveDraft() {
        val cfg = LlamaConfig(
            draftFile = "mtp-gemma-4-E2B-it.gguf", useDraft = true,
            library = mapOf("mtp-gemma-4-E2B-it.gguf" to "somerepo", "HiFi.gguf" to "repo2"),
            drafters = mapOf("HiFi.gguf" to "mtp-gemma-4-E2B-it.gguf", "Qwen.gguf" to ""),
        )
        val next = configAfterDrafterDelete(cfg, "mtp-gemma-4-E2B-it.gguf")
        assertEquals("", next.draftFile)                                   // active draft cleared
        assertEquals(false, next.useDraft)
        // HiFi→mtp mapping pruned; curated model gets an explicit "none" since the deleted file was its
        // own draft (mtp-gemma-4-E2B-it.gguf is LlamaConfig()'s default draftFile).
        assertEquals(mapOf("Qwen.gguf" to "", LlamaConfig().modelFile to ""), next.drafters)
        assertEquals(mapOf("HiFi.gguf" to "repo2"), next.library)          // library entry pruned
    }

    @Test fun configAfterDrafterDelete_suppressesCuratedFallbackWhenCuratedDraftDeleted() {
        val d = LlamaConfig()  // curated defaults: draftFile = the curated MTP draft, no [drafters] entries
        val next = configAfterDrafterDelete(d, d.draftFile)
        // curated model is now explicitly "none" — the fallback can't re-derive the deleted file
        assertEquals("", next.drafters[d.modelFile])
        assertEquals("", draftForModel(next, d.modelFile))
        assertEquals("", next.draftFile)
        assertEquals(false, next.useDraft)
    }
}
