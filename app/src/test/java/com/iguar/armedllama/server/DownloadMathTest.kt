package com.iguar.armedllama.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadMathTest {
    @Test fun hfUrl_buildsResolveMainUrl() {
        assertEquals(
            "https://huggingface.co/unsloth/gemma-4-E2B-it-qat-mobile-GGUF/resolve/main/mmproj-F16.gguf",
            hfUrl("unsloth/gemma-4-E2B-it-qat-mobile-GGUF", "mmproj-F16.gguf"),
        )
    }
    @Test fun resumeOffset_zeroWhenNothingDownloaded() = assertEquals(0L, resumeOffset(0, 100))
    @Test fun resumeOffset_partialReturnsExistingBytes() = assertEquals(40L, resumeOffset(40, 100))
    @Test fun resumeOffset_nullWhenAlreadyComplete() = assertNull(resumeOffset(100, 100))
    @Test fun isComplete_trueWhenSizesMatch() = assertEquals(true, isComplete(100, 100))
    @Test fun isComplete_falseWhenShortOrUnknownTotal() {
        assertEquals(false, isComplete(40, 100))
        assertEquals(false, isComplete(100, 0))
    }
}
