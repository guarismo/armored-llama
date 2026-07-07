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
): ModelFit {
    if (modelSizeGB <= 0f || freeRamMB <= 0f) return ModelFit.UNKNOWN

    val freeGB = freeRamMB / 1024f
    // KV cache sized for the app's launch defaults: q8_0 K/V cache (~half of f16) with
    // flash-attn, and Gemma-family layer sharing — far below a naive f16 estimate.
    val kvGB = when {
        ctx <= 4096 -> 0.20f
        ctx <= 8192 -> 0.40f
        ctx <= 16384 -> 0.70f
        ctx <= 32768 -> 1.20f
        ctx <= 65536 -> 2.20f
        else -> 3.50f
    }
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
