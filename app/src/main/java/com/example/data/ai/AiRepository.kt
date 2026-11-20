package com.example.data.ai

import kotlinx.coroutines.flow.Flow

enum class MediaKind(val displayName: String) {
    PHOTO("Photo"),
    STICKER_STATIC("Static WEBP Sticker"),
    STICKER_ANIMATED("Animated TGS Sticker"),
    STICKER_VIDEO("Video WEBM Sticker"),
    GIF("GIF Animation"),
    VIDEO("Video"),
    LOCAL_TEST("Tablet Scan")
}

enum class ModerationVerdict(val label: String) {
    SAFE("SAFE"),
    VIOLATION("VIOLATION"),
    SEXUAL_CONTENT("SEXUAL_CONTENT"),
    UNCERTAIN("UNCERTAIN"),
    SCAN_FAILED("SCAN_FAILED");

    fun isViolation(): Boolean = this == VIOLATION || this == SEXUAL_CONTENT

    companion object {
        fun isViolationName(value: String): Boolean {
            return value == VIOLATION.name || value == SEXUAL_CONTENT.name
        }
    }
}

enum class ModerationSensitivity(val label: String, val hindiHint: String) {
    STRICT("Strict", "कड़ी सुरक्षा (अधिक सतर्क)"),
    BALANCED("Balanced", "संतुलित (सामान्य शिक्षा/मेडिकल सुरक्षित)"),
    RELAXED("Relaxed", "उदार (केवल स्पष्ट उल्लंघन)")
}

enum class SimulatedOutcome {
    AUTO_SAMPLE,
    FORCE_SEXUAL_CONTENT,
    FORCE_UNCERTAIN,
    FORCE_SAFE
}

data class ModerationDecision(
    val verdict: ModerationVerdict,
    val confidence: Float,
    val reason: String,
    val isPlaceholder: Boolean
)

sealed interface ChatStreamEvent {
    data class Chunk(val partialText: String) : ChatStreamEvent
    data class Completed(val fullText: String, val isPlaceholder: Boolean) : ChatStreamEvent
    data class Error(val message: String) : ChatStreamEvent
}

data class AiServiceStatus(
    val isAvailable: Boolean,
    val requiresFirebaseSetup: Boolean,
    val modelLabel: String,
    val statusMessage: String,
    val hasError: Boolean = false
)

interface AiRepository {
    fun getServiceStatus(): AiServiceStatus

    suspend fun moderateMedia(
        mediaBytes: ByteArray,
        mimeType: String,
        mediaKind: MediaKind,
        sensitivity: ModerationSensitivity,
        simulatedOutcome: SimulatedOutcome = SimulatedOutcome.AUTO_SAMPLE
    ): ModerationDecision

    fun streamChatResponse(
        userPrompt: String,
        recentHistoryPairs: List<Pair<String, String>> = emptyList()
    ): Flow<ChatStreamEvent>
}
