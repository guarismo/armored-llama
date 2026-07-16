package com.iguar.armoredllama.model

import com.iguar.armoredllama.server.ModelFit
import com.iguar.armoredllama.server.ModelFitLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadlineTest {

    private fun q(file: String, sizeGB: Float, level: ModelFitLevel) =
        QuantOption(file, "Q", sizeGB, ModelFit(level, sizeGB, 8f))

    @Test fun pickHeadline_largestThatFits() {
        val quants = listOf(
            q("q1", 3f, ModelFitLevel.FITS),
            q("q2", 5f, ModelFitLevel.TIGHT),
            q("q3", 50f, ModelFitLevel.TOO_LARGE),
        )
        assertEquals("q2", pickHeadline(quants)!!.file)
    }

    @Test fun pickHeadline_fallsBackToSmallestWhenNoneFit() {
        val quants = listOf(
            q("big", 50f, ModelFitLevel.TOO_LARGE),
            q("small", 20f, ModelFitLevel.TOO_LARGE),
        )
        assertEquals("small", pickHeadline(quants)!!.file)
    }

    @Test fun pickHeadline_nullOnEmpty() {
        assertNull(pickHeadline(emptyList()))
    }
}
