package com.iguar.armoredllama.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFitTest {

    /** f16 KV cache roughly doubles KV memory vs the q8_0 default → higher required RAM. */
    @Test
    fun estimateModelFit_f16CacheNeedsMoreThanQ8() {
        val q8 = estimateModelFit(modelSizeGB = 3f, freeRamMB = 8_192f, ctx = 32768)
        val f16 = estimateModelFit(
            modelSizeGB = 3f, freeRamMB = 8_192f, ctx = 32768,
            cacheTypeK = "f16", cacheTypeV = "f16",
        )

        assertTrue("f16 cache must require more RAM than q8_0", f16.requiredGB > q8.requiredGB + 0.5f)
    }

    /** Disabling flash-attn forfeits the quantized-KV saving → treated as ≥ f16 sized. */
    @Test
    fun estimateModelFit_noFlashAttnNeedsMoreThanWithIt() {
        val withFa = estimateModelFit(modelSizeGB = 3f, freeRamMB = 8_192f, ctx = 32768)
        val noFa = estimateModelFit(
            modelSizeGB = 3f, freeRamMB = 8_192f, ctx = 32768, flashAttn = false,
        )

        assertTrue("no flash-attn must require more RAM", noFa.requiredGB > withFa.requiredGB + 0.5f)
    }

    /** The OOM hole: a model that FITS under q8_0 must not still read FITS under f16. */
    @Test
    fun estimateModelFit_f16CacheFlipsFitToTight() {
        val q8 = estimateModelFit(modelSizeGB = 2.8f, freeRamMB = 6_144f, ctx = 32768)
        val f16 = estimateModelFit(
            modelSizeGB = 2.8f, freeRamMB = 6_144f, ctx = 32768,
            cacheTypeK = "f16", cacheTypeV = "f16",
        )

        assertEquals(ModelFitLevel.FITS, q8.level)
        assertTrue("f16 must no longer read FITS", f16.level != ModelFitLevel.FITS)
    }

    @Test
    fun estimateModelFit_returnsUnknownWhenSizeIsMissing() {
        val fit = estimateModelFit(modelSizeGB = 0f, freeRamMB = 6_000f, ctx = 8192)

        assertEquals(ModelFitLevel.UNKNOWN, fit.level)
    }

    @Test
    fun estimateModelFit_marksSmallModelAsFits() {
        val fit = estimateModelFit(modelSizeGB = 2f, freeRamMB = 8_192f, ctx = 8192)

        assertEquals(ModelFitLevel.FITS, fit.level)
        assertTrue(fit.requiredGB < fit.freeGB)
    }

    @Test
    fun estimateModelFit_marksNearLimitAsTight() {
        val fit = estimateModelFit(modelSizeGB = 3.1f, freeRamMB = 4_608f, ctx = 8192)

        assertEquals(ModelFitLevel.TIGHT, fit.level)
    }

    @Test
    fun estimateModelFit_marksOversizedModelAsTooLarge() {
        val fit = estimateModelFit(modelSizeGB = 6f, freeRamMB = 5_120f, ctx = 32768)

        assertEquals(ModelFitLevel.TOO_LARGE, fit.level)
    }

    /**
     * Regression: the curated Gemma launch (2 GiB model, 32k ctx, draft + mmproj) on the
     * OnePlus 8T with ~7.6 GB free actually used ~3.4 GB. The estimate must not cry "Tight"
     * with ~4 GB to spare — it should report FITS.
     */
    @Test
    fun estimateModelFit_curatedGemmaLaunchFitsWithHeadroom() {
        val fit = estimateModelFit(
            modelSizeGB = 2.04f,
            freeRamMB = 7_782f, // ~7.6 GB free at launch
            ctx = 32768,
            hasDraft = true,
            hasMmproj = true,
        )

        assertEquals(ModelFitLevel.FITS, fit.level)
        assertTrue("estimate should stay under actual free RAM", fit.requiredGB < fit.freeGB)
        assertTrue("estimate should be far below the old ~7.1 GB", fit.requiredGB < 6.0f)
    }
}
