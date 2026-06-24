package com.iguar.armedllama.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ArgsBuilderTest {
    @Test fun buildArgs_producesFullGemmaCommandLine() {
        val args = buildArgs(LlamaConfig(), "/lib/libllamaserver.so", "/models")
        assertEquals(
            listOf(
                "/lib/libllamaserver.so",
                "-m", "/models/gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf",
                "--model-draft", "/models/mtp-gemma-4-E2B-it.gguf",
                "--mmproj", "/models/mmproj-F16.gguf",
                "--spec-type", "draft-mtp",
                "--spec-draft-n-max", "4",
                "--spec-draft-p-min", "0.6",
                "--no-mmap",
                "--host", "0.0.0.0",
                "--port", "8080",
                "-c", "8192",
                "-t", "4",
                "--tools", "all",
            ),
            args,
        )
    }

    @Test fun buildArgs_omitsEmptyOptionalFieldsAndAppendsExtraArgs() {
        val cfg = LlamaConfig(draftFile = "", mmprojFile = "", specType = "", tools = "", noMmap = false, extraArgs = "--verbose --flash-attn")
        val args = buildArgs(cfg, "/b", "/m")
        assertEquals(false, args.contains("--model-draft"))
        assertEquals(false, args.contains("--mmproj"))
        assertEquals(false, args.contains("--spec-type"))
        assertEquals(false, args.contains("--tools"))
        assertEquals(false, args.contains("--no-mmap"))
        assertEquals(listOf("--verbose", "--flash-attn"), args.takeLast(2))
    }
}
