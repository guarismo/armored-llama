package com.iguar.armoredllama.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HfModelsTest {

    private val bonsai = """
        [
          {"type":"file","path":"Bonsai-27B-F16.gguf","size":53808280640},
          {"type":"file","path":"Bonsai-27B-Q1_0.gguf","size":3803452480},
          {"type":"file","path":"Bonsai-27B-dspark-Q4_1.gguf","size":1787468768},
          {"type":"file","path":"Bonsai-27B-dspark-bf16.gguf","size":7291885792},
          {"type":"file","path":"Bonsai-27B-mmproj-BF16.gguf","size":931145760},
          {"type":"file","path":"Bonsai-27B-mmproj-Q8_0.gguf","size":629246880},
          {"type":"file","path":"README.md","size":21877}
        ]
    """.trimIndent()

    @Test fun parseRepo_returnsAllPrimaryQuantsSmallestFirst() {
        val r = HfModels.parseRepo("prism-ml/Bonsai-27B-gguf", bonsai)!!

        assertEquals(listOf("Bonsai-27B-Q1_0.gguf", "Bonsai-27B-F16.gguf"), r.quants.map { it.file })
        assertEquals("Q1_0", r.quants[0].quant)
        assertEquals(3803452480f / (1024f * 1024f * 1024f), r.quants[0].sizeGB, 0.01f)
    }

    @Test fun parseRepo_companionsAreVisionOnly_draftsExcludedEverywhere() {
        val r = HfModels.parseRepo("prism-ml/Bonsai-27B-gguf", bonsai)!!

        // drafts (dspark) are not primary quants...
        assertTrue(r.quants.none { isCompanionFile(it.file) })
        // ...and are NOT surfaced as companions either — vision (mmproj) only, smallest first.
        // (dspark drafts are unsupported by the on-device build and would crash the server.)
        assertEquals(
            listOf("Bonsai-27B-mmproj-Q8_0.gguf", "Bonsai-27B-mmproj-BF16.gguf"),
            r.companions.map { it.file },
        )
        assertTrue(r.companions.none { isDraftFile(it.file) })
    }

    @Test fun parseRepo_singleGgufHasOneQuantNoCompanions() {
        val tree = """[{"type":"file","path":"model-Q4_K_M.gguf","size":2000000000}]"""

        val r = HfModels.parseRepo("acme/model-GGUF", tree)!!

        assertEquals(1, r.quants.size)
        assertEquals("model-Q4_K_M.gguf", r.quants[0].file)
        assertTrue(r.companions.isEmpty())
    }

    @Test fun parseRepo_nullWhenNoPrimaryQuant() {
        val tree = """
            [
              {"type":"file","path":"mmproj-F16.gguf","size":500000000},
              {"type":"file","path":"README.md","size":10}
            ]
        """.trimIndent()

        assertNull(HfModels.parseRepo("acme/x", tree))
    }
}
