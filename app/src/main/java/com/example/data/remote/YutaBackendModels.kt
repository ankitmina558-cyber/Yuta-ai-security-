package com.example.data.remote

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BackendTelegramInfo(
    val ok: Boolean = false,
    val id: Long? = null,
    val username: String? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class BackendStickerStatsDto(
    val totalStickersReceived: Int = 0,
    val totalStickersScanned: Int = 0,
    val violationsDetected: Int = 0,
    val messagesDeleted: Int = 0,
    val pendingScans: Int = 0,
    val failedScans: Int = 0,
    val uptimeSeconds: Long = 0L
)

@JsonClass(generateAdapter = true)
data class BackendStatusResponse(
    val telegram: BackendTelegramInfo? = null,
    val geminiConfigured: Boolean = false,
    val geminiConnected: Boolean = false,
    val geminiError: String = "",
    val geminiModel: String = "",
    val databaseBound: Boolean = false,
    val uptimeSeconds: Long = 0L,
    val lastHeartbeatAt: Long = 0L,
    val lastError: String = "",
    val stickerStats: BackendStickerStatsDto? = null,
    val note: String? = null
)

@JsonClass(generateAdapter = true)
data class BackendGeminiTestInfo(
    val ok: Boolean = false,
    val model: String = "",
    val reply: String = "",
    val error: String = ""
)

@JsonClass(generateAdapter = true)
data class BackendWebhookTestInfo(
    val ok: Boolean = false,
    val url: String = "",
    @com.squareup.moshi.Json(name = "pending_update_count") val pendingUpdateCount: Int = 0,
    val error: String = ""
)

@JsonClass(generateAdapter = true)
data class BackendDbTestInfo(
    val ok: Boolean = false
)

@JsonClass(generateAdapter = true)
data class BackendConnectionTestResponse(
    val ok: Boolean = false,
    val telegram: BackendTelegramInfo? = null,
    val gemini: BackendGeminiTestInfo? = null,
    val webhook: BackendWebhookTestInfo? = null,
    val database: BackendDbTestInfo? = null
)

@JsonClass(generateAdapter = true)
data class BackendWebhookResponse(
    val ok: Boolean = false,
    val result: Boolean? = null,
    val description: String? = null,
    val webhookUrl: String? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class BackendStatsResponse(
    val totalImagesScanned: Int = 0,
    val totalStickersScanned: Int = 0,
    val totalVideosAndGifsScanned: Int = 0,
    val totalViolationsDetected: Int = 0,
    val totalMessagesDeleted: Int = 0,
    val totalUncertainOrFailed: Int = 0,
    val stickerStats: BackendStickerStatsDto? = null
)

@JsonClass(generateAdapter = true)
data class BackendQueueProcessResponse(
    val ok: Boolean = false,
    val processed: Int = 0,
    val stickerStats: BackendStickerStatsDto? = null
)

@JsonClass(generateAdapter = true)
data class BackendLogItem(
    val id: Long = 0,
    val messageKey: String = "",
    val chatId: Long = 0,
    val chatTitle: String = "",
    val senderName: String = "",
    val mediaKind: String = "PHOTO",
    val verdict: String = "UNCERTAIN",
    val confidence: Float = 0f,
    val actionTaken: String = "",
    val reason: String = "",
    val createdAt: Long = 0L
)

@JsonClass(generateAdapter = true)
data class BackendLogsResponse(
    val logs: List<BackendLogItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BackendSettingsDto(
    val enableImageModeration: Boolean = true,
    val enableStickerModeration: Boolean = true,
    val enableGifModeration: Boolean = true,
    val enableAiChat: Boolean = true,
    val enableAutoDelete: Boolean = true,
    val enableAdminNotifications: Boolean = true,
    val disableMediaRetention: Boolean = true,
    val sensitivity: String = "BALANCED",
    val adminUserIds: String = "",
    val adminAlertChatId: String = ""
)

@JsonClass(generateAdapter = true)
data class BackendScanRequest(
    val mediaKind: String,
    val mimeType: String,
    val base64Data: String
)

@JsonClass(generateAdapter = true)
data class BackendChatRequest(
    val prompt: String
)

@JsonClass(generateAdapter = true)
data class BackendChatResponse(
    val reply: String = ""
)
