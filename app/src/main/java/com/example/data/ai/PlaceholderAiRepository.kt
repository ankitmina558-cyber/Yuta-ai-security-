package com.example.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fail-safe fallback repository when no AI backend is wired.
 * Strictly NEVER invents fake SAFE verdicts or fabricated chatbot replies.
 */
class PlaceholderAiRepository : AiRepository {

    override fun getServiceStatus(): AiServiceStatus {
        return AiServiceStatus(
            isAvailable = false,
            requiresFirebaseSetup = true,
            modelLabel = "Shared Gemini Offline",
            statusMessage = "No Shared HTTPS Backend connected. Unscanned media will NEVER be marked SAFE."
        )
    }

    override suspend fun moderateMedia(
        mediaBytes: ByteArray,
        mimeType: String,
        mediaKind: MediaKind,
        sensitivity: ModerationSensitivity,
        simulatedOutcome: SimulatedOutcome
    ): ModerationDecision {
        return ModerationDecision(
            verdict = ModerationVerdict.SCAN_FAILED,
            confidence = 0f,
            reason = "Live Gemini AI offline: Connect Shared HTTPS Backend in Settings. Media NOT marked SAFE.",
            isPlaceholder = false
        )
    }

    override fun streamChatResponse(
        userPrompt: String,
        recentHistoryPairs: List<Pair<String, String>>
    ): Flow<ChatStreamEvent> = flow {
        emit(
            ChatStreamEvent.Error(
                "Live Gemini AI offline: कृपया Settings में अपना Shared HTTPS Backend जोड़ें।"
            )
        )
    }
}
