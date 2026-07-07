package com.iguar.armoredllama.server

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
                "--flash-attn", "on",
                "--cont-batching",
                "--jinja",
                "--reasoning-budget", "2042",
                "--cache-type-k", "q8_0",
                "--cache-type-v", "q8_0",
                "--host", "0.0.0.0",
                "--port", "8080",
                "-c", "32768",
                "-t", "4",
            ),
            args,
        )
    }

    @Test fun buildArgs_emitsOptimizationFlagsFromConfig() {
        // flash-attn off, continuous batching off, mlock on, explicit Jinja off.
        val cfg = LlamaConfig(flashAttn = false, contBatch = false, mlock = true, jinja = false)
        val args = buildArgs(cfg, "/b", "/m")
        assertEquals(true, args.windowed(2).contains(listOf("--flash-attn", "off")))
        assertEquals(true, args.contains("--no-cont-batching"))
        assertEquals(false, args.contains("--cont-batching"))
        assertEquals(true, args.contains("--mlock"))
        assertEquals(true, args.contains("--no-jinja"))
        assertEquals(false, args.contains("--jinja"))
    }

    @Test fun buildArgs_emitsReasoningBudgetAndKvCacheTypesFromConfig() {
        val cfg = LlamaConfig(reasoningBudget = 4096, cacheTypeK = "q5_1", cacheTypeV = "q4_0")
        val args = buildArgs(cfg, "/b", "/m")
        assertEquals(true, args.windowed(2).contains(listOf("--reasoning-budget", "4096")))
        assertEquals(true, args.windowed(2).contains(listOf("--cache-type-k", "q5_1")))
        assertEquals(true, args.windowed(2).contains(listOf("--cache-type-v", "q4_0")))
    }

    @Test fun buildArgs_mlockOmittedByDefault() {
        // default mlock = false → no --mlock flag
        assertEquals(false, buildArgs(LlamaConfig(), "/b", "/m").contains("--mlock"))
    }

    @Test fun buildArgs_omitsMmprojWhenVisionDisabled() {
        // Vision off → no --mmproj, but the filename is preserved in config and speculative stays on.
        val args = buildArgs(LlamaConfig(useMmproj = false), "/b", "/m")
        assertEquals(false, args.contains("--mmproj"))
        assertEquals(true, args.contains("--model-draft"))
        assertEquals(true, args.contains("--spec-type"))
    }

    @Test fun buildArgs_omitsDraftAndSpecWhenSpeculativeDisabled() {
        // Speculative off → no --model-draft and no --spec-* flags, but --mmproj stays.
        val args = buildArgs(LlamaConfig(useDraft = false), "/b", "/m")
        assertEquals(false, args.contains("--model-draft"))
        assertEquals(false, args.contains("--spec-type"))
        assertEquals(false, args.contains("--spec-draft-n-max"))
        assertEquals(true, args.contains("--mmproj"))
    }

    @Test fun buildArgs_omitsEmptyOptionalFieldsAndAppendsExtraArgs() {
        val cfg = LlamaConfig(draftFile = "", mmprojFile = "", specType = "", noMmap = false, extraArgs = "--verbose --no-warmup")
        val args = buildArgs(cfg, "/b", "/m")
        assertEquals(false, args.contains("--model-draft"))
        assertEquals(false, args.contains("--mmproj"))
        assertEquals(false, args.contains("--spec-type"))
        assertEquals(false, args.contains("--tools"))
        assertEquals(false, args.contains("--no-mmap"))
        assertEquals(listOf("--verbose", "--no-warmup"), args.takeLast(2))
    }
}
