package com.iguar.armedllama.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests against the REAL llama-server (b9775) log lines captured from a OnePlus 8T running
 * the gemma-4 model. The server streams live throughput during generation, not only a final
 * snapshot, so the parser handles four line shapes.
 */
class ThroughputParserTest {

    // --- Live generation: emitted every ~3s during decode. `tg` is the cumulative (stable) rate. ---

    @Test
    fun parseThroughput_readsLiveGenerationTg() {
        val line = "11.06.067.355 I slot print_timing: id  3 | task 868 | " +
            "n_decoded =    101, tg =   9.92 t/s, tg_3s =   9.92 t/s"
        val r = parseThroughput(line)!!
        assertEquals(9.92f, r.tps!!, 0.01f)
        assertNull(r.pp)
    }

    @Test
    fun parseThroughput_prefersCumulativeTgOverTg3s() {
        // tg (cumulative, stable) is the displayed number, not the noisy 3-second window.
        val line = "I slot print_timing: id 3 | task 868 | n_decoded = 135, tg = 10.21 t/s, tg_3s = 11.19 t/s"
        assertEquals(10.21f, parseThroughput(line)!!.tps!!, 0.01f)
    }

    // --- Live prompt processing: emitted as the prompt is ingested. ---

    @Test
    fun parseThroughput_readsLivePromptProcessing() {
        val line = "10.36.777.378 I slot print_timing: id  3 | task 868 | " +
            "prompt processing, n_tokens =    887, progress = 0.82, t =  26.14 s / 33.93 tokens per second"
        val r = parseThroughput(line)!!
        assertEquals(33.93f, r.pp!!, 0.01f)
        assertNull(r.tps)
    }

    // --- Final snapshot lines (appear once at request end). ---

    @Test
    fun parseThroughput_readsFinalPromptEval() {
        val line = "12.57.816.420 I slot print_timing: id  3 | task 868 | " +
            "prompt eval time =   45250.70 ms /  1403 tokens (   32.25 ms per token,    31.01 tokens per second)"
        val r = parseThroughput(line)!!
        assertEquals(31.01f, r.pp!!, 0.01f)
        assertNull(r.tps)
    }

    @Test
    fun parseThroughput_readsFinalEval() {
        val line = "12.57.816.454 I slot print_timing: id  3 | task 868 | " +
            "       eval time =  121927.14 ms /  1150 tokens (  106.02 ms per token,     9.43 tokens per second)"
        val r = parseThroughput(line)!!
        assertEquals(9.43f, r.tps!!, 0.01f)
        assertNull(r.pp)
    }

    @Test
    fun parseThroughput_finalEvalNotConfusedWithPromptEval() {
        val line = "I slot print_timing: prompt eval time = 100.0 ms / 50 tokens ( 2.0 ms per token, 500.0 tokens per second)"
        val r = parseThroughput(line)!!
        assertNull("a prompt eval line must not register as generation throughput", r.tps)
        assertEquals(500.0f, r.pp!!, 0.01f)
    }

    @Test
    fun parseThroughput_returnsNullForUnrelatedLine() {
        assertNull(parseThroughput("I main: server is listening on http://0.0.0.0:8080"))
    }

    // --- Idle detection: zero the live readout once the request finishes. ---

    @Test
    fun isServerIdle_detectsAllSlotsIdle() {
        assertTrue(isServerIdle("12.57.817.158 I srv  update_slots: all slots are idle"))
    }

    @Test
    fun isServerIdle_falseForActiveGeneration() {
        assertFalse(isServerIdle("I slot print_timing: id 3 | task 868 | n_decoded = 101, tg = 9.92 t/s, tg_3s = 9.92 t/s"))
    }

    @Test
    fun isServerIdle_falseForSingleSlotRelease() {
        // A single slot releasing must NOT zero the readout — other slots may still be generating.
        assertFalse(isServerIdle("I slot release: id 3 | task 868 | stop processing: n_tokens = 3950, truncated = 0"))
    }
}
