package com.iguar.armoredllama.server

enum class ModelFitLevel { FITS, TIGHT, TOO_LARGE, UNKNOWN }

data class ModelFit(
    val level: ModelFitLevel,
    val requiredGB: Float,
    val freeGB: Float,
) {
    val label: String
        get() = when (level) {
            ModelFitLevel.FITS -> "Fits"
            ModelFitLevel.TIGHT -> "Tight"
            ModelFitLevel.TOO_LARGE -> "Too large"
            ModelFitLevel.UNKNOWN -> "Unknown fit"
        }

    val detail: String
        get() = if (level == ModelFitLevel.UNKNOWN) {
            "size unavailable"
        } else {
            "needs ~${requiredGB.oneDecimal()} GB, free ~${freeGB.oneDecimal()} GB"
        }

    companion object {
        val UNKNOWN = ModelFit(ModelFitLevel.UNKNOWN, 0f, 0f)
    }
}

fun estimateModelFit(
    modelSizeGB: Float,
    freeRamMB: Float,
    ctx: Int,
    hasDraft: Boolean = false,
    hasMmproj: Boolean = false,
    cacheTypeK: String = "q8_0",
    cacheTypeV: String = "q8_0",
    flashAttn: Boolean = true,
): ModelFit {
    if (modelSizeGB <= 0f || freeRamMB <= 0f) return ModelFit.UNKNOWN

    val freeGB = freeRamMB / 1024f
    // Base KV table is sized for the q8_0 K/V cache + flash-attn defaults (with Gemma-family
    // layer sharing) — far below a naive f16 estimate.
    val baseKvGB = when {
        ctx <= 4096 -> 0.20f
        ctx <= 8192 -> 0.40f
        ctx <= 16384 -> 0.70f
        ctx <= 32768 -> 1.20f
        ctx <= 65536 -> 2.20f
        else -> 3.50f
    }
    // Scale KV for the user's actual cache type. Without flash-attn the quantized-KV saving is
    // forfeited, so treat each cache as at least f16-sized. Baseline is q8_0 (~8.5 bits).
    fun kvBits(type: String) = when (type.trim().lowercase()) {
        "f32" -> 32f
        "f16", "bf16" -> 16f
        "q8_0" -> 8.5f
        "q5_0", "q5_1" -> 6f
        "q4_0", "q4_1" -> 4.5f
        else -> 8.5f
    }
    val effK = if (flashAttn) kvBits(cacheTypeK) else maxOf(kvBits(cacheTypeK), 16f)
    val effV = if (flashAttn) kvBits(cacheTypeV) else maxOf(kvBits(cacheTypeV), 16f)
    val kvGB = baseKvGB * (((effK + effV) / 2f) / 8.5f)
    // Draft models are small (the MTP draft is ~60 MB); mmproj is ~1 GB.
    val companionGB = (if (hasDraft) 0.25f else 0f) + (if (hasMmproj) 1.00f else 0f)
    // --no-mmap keeps the model resident at roughly file size; +0.6 GB fixed runtime slack.
    val requiredGB = modelSizeGB * 1.10f + kvGB + companionGB + 0.60f
    val level = when {
        requiredGB > freeGB -> ModelFitLevel.TOO_LARGE
        freeGB - requiredGB < 0.75f -> ModelFitLevel.TIGHT
        requiredGB / freeGB > 0.85f -> ModelFitLevel.TIGHT
        else -> ModelFitLevel.FITS
    }
    return ModelFit(level = level, requiredGB = requiredGB, freeGB = freeGB)
}

private fun Float.oneDecimal(): String = "%.1f".format(this)
