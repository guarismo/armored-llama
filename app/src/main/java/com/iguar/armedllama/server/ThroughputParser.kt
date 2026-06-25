package com.iguar.armedllama.server

/**
 * Pure parser for llama-server (b9775) `print_timing` log lines. The server streams live
 * throughput during a request, so we read the live signals (preferred for a "now" reading)
 * and the final per-request snapshot. Returns non-null only for a recognised timing line; the
 * filled field (tps or pp) carries the value and the other stays null.
 *
 *   live gen:    "... n_decoded = N, tg = X t/s, tg_3s = Y t/s"          → tps = X (cumulative, stable)
 *   live prompt: "... prompt processing, ... t = S / X tokens per second" → pp  = X
 *   final gen:   "...        eval time = ... ( ..., X tokens per second)"  → tps = X
 *   final prompt:"... prompt eval time = ... ( ..., X tokens per second)"  → pp  = X
 */
data class ThroughputReading(val tps: Float? = null, val pp: Float? = null)

// Live generation rate. `tg` is the cumulative (stable) rate; `tg_3s` (3-second window) is noisy,
// so we deliberately read `tg` — \s*= after "tg" excludes the "tg_3s" token.
private val LIVE_TG_RE = Regex("""\btg\s*=\s*([\d.]+)\s*t/s""")
// Final per-request snapshots. Prompt line must be matched before the gen line (it also says
// "eval time"), so the gen regex uses a negative lookbehind to reject "prompt eval time".
private val PROMPT_EVAL_RE = Regex("""prompt eval time\s*=.+?([\d.]+)\s*tokens per second""")
private val GEN_EVAL_RE = Regex("""(?<!prompt )\beval time\s*=.+?([\d.]+)\s*tokens per second""")
// Live prompt-processing rate ("X tokens per second" on a "prompt processing" line).
private val TOKENS_PER_SEC_RE = Regex("""([\d.]+)\s*tokens per second""")

/**
 * True when the server reports it has finished all work and returned to idle. Callers zero the
 * live throughput readout on this. Keyed off "all slots are idle" (the all-done signal) rather
 * than a single slot's "stop processing", so parallel requests don't zero each other.
 */
fun isServerIdle(line: String): Boolean = line.contains("all slots are idle")

fun parseThroughput(line: String): ThroughputReading? {
    LIVE_TG_RE.find(line)?.let { return ThroughputReading(tps = it.groupValues[1].toFloatOrNull()) }
    if (line.contains("prompt processing")) {
        TOKENS_PER_SEC_RE.find(line)?.let { return ThroughputReading(pp = it.groupValues[1].toFloatOrNull()) }
    }
    PROMPT_EVAL_RE.find(line)?.let { return ThroughputReading(pp = it.groupValues[1].toFloatOrNull()) }
    GEN_EVAL_RE.find(line)?.let { return ThroughputReading(tps = it.groupValues[1].toFloatOrNull()) }
    return null
}
