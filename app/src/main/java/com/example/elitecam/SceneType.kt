package com.example.elitecam

enum class SceneType(val label: String) {
    LOW_LIGHT("Low light"),
    BACKLIT("High contrast"),
    BRIGHT("Bright"),
    NORMAL("Balanced");

    companion object {
        /** meanY is 0..255, fractions are 0..1 of sampled pixels. */
        fun classify(meanY: Float, darkFrac: Float, brightFrac: Float): SceneType = when {
            meanY < 70f -> LOW_LIGHT
            darkFrac > 0.25f && brightFrac > 0.08f -> BACKLIT
            meanY > 165f -> BRIGHT
            else -> NORMAL
        }
    }
}

enum class CastType(val label: String) { WARM("warm cast"), COOL("cool cast"), NEUTRAL("neutral") }

data class SceneInfo(val scene: SceneType, val cast: CastType)
