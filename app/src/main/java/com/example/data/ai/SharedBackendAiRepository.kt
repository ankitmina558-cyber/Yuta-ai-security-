package com.example.data.ai

import android.util.Base64
import com.example.data.local.YutaSettingsRepository
import com.example.data.remote.BackendChatRequest
import com.example.data.remote.BackendScanRequest
import com.example.data.remote.YutaBackendApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Production AI repository backed by the user's deployed Shared HTTPS Backend
 * (Cloudflare Worker + server-side Gemini Free Tier).
 * Zero API keys live inside the APK, and unscanned media is NEVER marked SAFE.
 */
class SharedBackendAiRepository(
    private val settingsRepository: YutaSettingsRepository,
    private val backendApi: YutaBackendApiService
) : AiRepository {

    @Volatile
    private var lastKnownAvailable: Boolean = false

    @Volatile
    private var lastKnownModel: String = "Shared Gemini Offline"

    @Volatile
    private var lastKnownMessage: String =
        "Connect your Shared HTTPS Backend in Settings to activate live Gemini AI."

    fun updateLiveStatus(available: Boolean, modelLabel: String, statusMessage: String) {
        lastKnownAvailable = available
        lastKnownModel = modelLabel
        lastKnownMessage = statusMessage
    }

    override fun getServiceStatus(): AiServiceStatus {
        return AiServiceStatus(
            isAvailable = lastKnownAvailable,
            requiresFirebaseSetup = !lastKnownAvailable,
            modelLabel = lastKnownModel,
            statusMessage = lastKnownMessage
        )
    }

    override suspend fun moderateMedia(
        mediaBytes: ByteArray,
        mimeType: String,
        mediaKind: MediaKind,
        sensitivity: ModerationSensitivity,
        simulatedOutcome: SimulatedOutcome
    ): ModerationDecision {
        if (mediaBytes.isEmpty()) {
            return ModerationDecision(
                verdict = ModerationVerdict.SCAN_FAILED,
                confidence = 0f,
                reason = "Empty media payload cannot be scanned; NEVER marked SAFE.",
                isPlaceholder = false
            )
        }

        val settings = settingsRepository.getSnapshot()
        if (!settings.isSharedBackendConfigured) {
            return ModerationDecision(
                verdict = ModerationVerdict.SCAN_FAILED,
                confidence = 0f,
                reason = "Live Gemini AI offline: Configure Shared HTTPS Backend in Settings. Media was NOT marked SAFE.",
                isPlaceholder = false
            )
        }

        return try {
            val base64 = Base64.encodeToString(mediaBytes, Base64.NO_WRAP)
            val response = backendApi.scanMedia(
                url = YutaBackendApiService.endpoint(settings.sharedBackendUrl, "/api/scan"),
                adminSecret = settings.sharedAdminSecret,
                body = BackendScanRequest(
                    mediaKind = mediaKind.name,
                    mimeType = mimeType,
                    base64Data = base64
                )
            )
            val item = response.body()
            if (response.isSuccessful && item != null) {
                val parsedVerdict = when (item.verdict.uppercase()) {
                    "SAFE" -> ModerationVerdict.SAFE
                    "VIOLATION" -> ModerationVerdict.VIOLATION
                    "SEXUAL_CONTENT" -> ModerationVerdict.SEXUAL_CONTENT
                    "SCAN_FAILED" -> ModerationVerdict.SCAN_FAILED
                    else -> ModerationVerdict.UNCERTAIN
                }
                if (parsedVerdict != ModerationVerdict.SCAN_FAILED) {
                    lastKnownAvailable = true
                }
                ModerationDecision(
                    verdict = parsedVerdict,
                    confidence = item.confidence,
                    reason = item.reason.ifBlank { "Analyzed via Shared Gemini Backend." },
                    isPlaceholder = false
                )
            } else {
                ModerationDecision(
                    verdict = ModerationVerdict.SCAN_FAILED,
                    confidence = 0f,
                    reason = "Shared Gemini Backend returned HTTP ${response.code()}. Media NOT marked SAFE.",
                    isPlaceholder = false
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ModerationDecision(
                verdict = ModerationVerdict.SCAN_FAILED,
                confidence = 0f,
                reason = "Shared Gemini Backend scan error (${e.localizedMessage ?: "network error"}). Media NOT marked SAFE.",
                isPlaceholder = false
            )
        }
    }

    override fun streamChatResponse(
        userPrompt: String,
        recentHistoryPairs: List<Pair<String, String>>
    ): Flow<ChatStreamEvent> = flow {
        val cleaned = userPrompt.trim()
        if (cleaned.isEmpty()) {
            emit(ChatStreamEvent.Error("कृपया कोई प्रश्न लिखें / Please enter a message."))
            return@flow
        }

        val settings = settingsRepository.getSnapshot()
        if (!settings.isSharedBackendConfigured) {
            emit(
                ChatStreamEvent.Error(
                    "Live Gemini AI offline: कृपया Settings में Shared HTTPS Backend URL और Passcode जोड़ें।"
                )
            )
            return@flow
        }

        try {
            val response = backendApi.sendChat(
                url = YutaBackendApiService.endpoint(settings.sharedBackendUrl, "/api/chat"),
                adminSecret = settings.sharedAdminSecret,
                body = BackendChatRequest(prompt = cleaned)
            )
            val reply = response.body()?.reply?.trim().orEmpty()
            if (response.isSuccessful && reply.isNotEmpty()) {
                lastKnownAvailable = true
                emit(ChatStreamEvent.Completed(reply, isPlaceholder = false))
            } else {
                emit(ChatStreamEvent.Error("Gemini Backend returned HTTP ${response.code()}."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(ChatStreamEvent.Error("Gemini Backend network error: ${e.localizedMessage ?: "unknown"}"))
        }
    }
}
