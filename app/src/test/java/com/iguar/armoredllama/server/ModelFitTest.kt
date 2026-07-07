package com.iguar.armoredllama.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFitTest {

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
