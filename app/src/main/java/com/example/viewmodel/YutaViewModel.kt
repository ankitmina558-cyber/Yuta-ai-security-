package com.example.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiRepository
import com.example.data.ai.AiServiceStatus
import com.example.data.ai.ChatStreamEvent
import com.example.data.ai.MediaKind
import com.example.data.ai.ModerationSensitivity
import com.example.data.ai.ModerationVerdict
import com.example.data.ai.SharedBackendAiRepository
import com.example.data.ai.SimulatedOutcome
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ModerationLogEntity
import com.example.data.local.ModerationStats
import com.example.data.local.StickerScannerStats
import com.example.data.local.YutaDao
import com.example.data.local.YutaSettings
import com.example.data.local.YutaSettingsRepository
import com.example.data.remote.BackendChatRequest
import com.example.data.remote.BackendScanRequest
import com.example.data.remote.BackendSettingsDto
import com.example.data.remote.YutaBackendApiService
import com.example.data.telegram.BotConnectionStatus
import com.example.data.telegram.BotRuntimeState
import com.example.data.telegram.MediaProcessor
import com.example.data.telegram.YutaBotEngine
import com.example.di.YutaAppContainer
import com.example.service.YutaStickerMonitorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class LogFilter(val label: String) {
    ALL("All"),
    VIOLATIONS("Violations"),
    UNCERTAIN("Uncertain"),
    SAFE("Safe"),
    FAILED("Failed")
}

data class YutaUiState(
    val settings: YutaSettings = YutaSettings(),
    val botRuntime: BotRuntimeState = BotRuntimeState(),
    val aiStatus: AiServiceStatus = AiServiceStatus(
        isAvailable = false,
        requiresFirebaseSetup = true,
        modelLabel = "Backend / Firebase Required",
        statusMessage = "Configure Shared HTTPS Backend in Settings for live Gemini AI."
    ),
    val stats: ModerationStats = ModerationStats(),
    val stickerStats: StickerScannerStats = StickerScannerStats(),
    val scannerUptimeSeconds: Long = 0L,
    val moderationLogs: List<ModerationLogEntity> = emptyList(),
    val selectedFilter: LogFilter = LogFilter.ALL,
    val chatMessages: List<ChatMessageEntity> = emptyList(),
    val streamingChatText: String? = null,
    val isChatLoading: Boolean = false,
    val commandPreviewOutput: String = "Tap any Telegram command chip (/start, /help, /status, /scan, /settings, /chat, /about) to preview Yuta AI's live bot output.",
    val isScanningLocalMedia: Boolean = false,
    val isDrainingQueue: Boolean = false,
    val latestScanFeedback: String? = null,
    val tokenVerificationFeedback: String? = null,
    val isVerifyingToken: Boolean = false,
    val sharedBackendConnected: Boolean = false,
    val sharedBackendFeedback: String? = null,
    val isVerifyingBackend: Boolean = false,
    val isTestingConnection: Boolean = false,
    val isRegisteringWebhook: Boolean = false,
    val webhookFeedback: String? = null
) {
    val filteredLogs: List<ModerationLogEntity>
        get() = when (selectedFilter) {
            LogFilter.ALL -> moderationLogs
            LogFilter.VIOLATIONS -> moderationLogs.filter { ModerationVerdict.isViolationName(it.verdict) }
            LogFilter.UNCERTAIN -> moderationLogs.filter { it.verdict == ModerationVerdict.UNCERTAIN.name }
            LogFilter.SAFE -> moderationLogs.filter { it.verdict == ModerationVerdict.SAFE.name }
            LogFilter.FAILED -> moderationLogs.filter { it.verdict == ModerationVerdict.SCAN_FAILED.name }
        }
}

private data class TransientUiFlags(
    val selectedFilter: LogFilter = LogFilter.ALL,
    val streamingChatText: String? = null,
    val isChatLoading: Boolean = false,
    val commandPreviewOutput: String = "Tap any Telegram command chip (/start, /help, /status, /scan, /settings, /chat, /about) to preview Yuta AI's live bot output.",
    val isScanningLocalMedia: Boolean = false,
    val isDrainingQueue: Boolean = false,
    val latestScanFeedback: String? = null,
    val tokenVerificationFeedback: String? = null,
    val isVerifyingToken: Boolean = false,
    val sharedBackendConnected: Boolean = false,
    val sharedBackendFeedback: String? = null,
    val sharedBackendError: String? = null,
    val isVerifyingBackend: Boolean = false,
    val isTestingConnection: Boolean = false,
    val isRegisteringWebhook: Boolean = false,
    val webhookFeedback: String? = null,
    val remoteStats: ModerationStats? = null,
    val remoteStickerStats: StickerScannerStats? = null,
    val remoteUptimeBaseSeconds: Long = 0L,
    val remoteUptimeFetchedAtMs: Long = 0L,
    val remoteLogs: List<ModerationLogEntity>? = null,
    val remoteAiStatus: AiServiceStatus? = null,
    val remoteBotUsername: String? = null,
    val remoteTelegramError: String? = null
)

class YutaViewModel(
    private val dao: YutaDao,
    private val settingsRepository: YutaSettingsRepository,
    private val aiRepository: AiRepository,
    private val botEngine: YutaBotEngine,
    private val backendApi: YutaBackendApiService
) : ViewModel() {

    private val transientFlags = MutableStateFlow(TransientUiFlags())
    private val uptimeTicker = MutableStateFlow(0L)

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<YutaUiState> = combine(
        settingsRepository.settingsFlow,
        botEngine.runtimeState,
        dao.observeStats(),
        dao.observeStickerScannerStats(),
        dao.observeModerationLogs(),
        dao.observeChatMessages(),
        transientFlags,
        uptimeTicker
    ) { values ->
        val settings = values[0] as YutaSettings
        val runtime = values[1] as BotRuntimeState
        val localStats = values[2] as ModerationStats
        val localStickerStats = values[3] as StickerScannerStats
        val localLogs = values[4] as List<ModerationLogEntity>
        val chat = values[5] as List<ChatMessageEntity>
        val flags = values[6] as TransientUiFlags
        val nowMs = values[7] as Long

        val effectiveRuntime = when {
            runtime.status != BotConnectionStatus.DISCONNECTED -> runtime
            flags.sharedBackendConnected && flags.remoteTelegramError == null && flags.remoteBotUsername != null -> runtime.copy(
                status = BotConnectionStatus.CONNECTED,
                botUsername = flags.remoteBotUsername,
                lastStatusNote = "Connected: @${flags.remoteBotUsername} via Cloudflare Worker"
            )
            flags.sharedBackendConnected && flags.remoteTelegramError != null -> runtime.copy(
                status = BotConnectionStatus.ERROR,
                botUsername = null,
                lastStatusNote = "Telegram Error: ${flags.remoteTelegramError}"
            )
            flags.sharedBackendError != null -> runtime.copy(
                status = BotConnectionStatus.ERROR,
                botUsername = null,
                lastStatusNote = "Backend Error: ${flags.sharedBackendError}"
            )
            else -> runtime
        }

        val computedUptimeSeconds = when {
            runtime.scannerStartedAtMs != null -> ((nowMs - runtime.scannerStartedAtMs) / 1000L).coerceAtLeast(0L)
            flags.sharedBackendConnected && flags.remoteUptimeFetchedAtMs > 0L ->
                flags.remoteUptimeBaseSeconds + ((nowMs - flags.remoteUptimeFetchedAtMs) / 1000L).coerceAtLeast(0L)
            else -> 0L
        }

        YutaUiState(
            settings = settings,
            botRuntime = effectiveRuntime,
            aiStatus = flags.remoteAiStatus ?: aiRepository.getServiceStatus(),
            stats = flags.remoteStats ?: localStats,
            stickerStats = flags.remoteStickerStats ?: localStickerStats,
            scannerUptimeSeconds = computedUptimeSeconds,
            moderationLogs = flags.remoteLogs ?: localLogs,
            selectedFilter = flags.selectedFilter,
            chatMessages = chat,
            streamingChatText = flags.streamingChatText,
            isChatLoading = flags.isChatLoading,
            commandPreviewOutput = flags.commandPreviewOutput,
            isScanningLocalMedia = flags.isScanningLocalMedia,
            isDrainingQueue = flags.isDrainingQueue,
            latestScanFeedback = flags.latestScanFeedback,
            tokenVerificationFeedback = flags.tokenVerificationFeedback,
            isVerifyingToken = flags.isVerifyingToken,
            sharedBackendConnected = flags.sharedBackendConnected,
            sharedBackendFeedback = flags.sharedBackendFeedback,
            isVerifyingBackend = flags.isVerifyingBackend,
            isTestingConnection = flags.isTestingConnection,
            isRegisteringWebhook = flags.isRegisteringWebhook,
            webhookFeedback = flags.webhookFeedback
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = YutaUiState(aiStatus = aiRepository.getServiceStatus())
    )

    init {
        viewModelScope.launch {
            while (isActive) {
                uptimeTicker.value = System.currentTimeMillis()
                delay(1000L)
            }
        }
        viewModelScope.launch {
            val snap = settingsRepository.getSnapshot()
            if (snap.isSharedBackendConfigured) {
                syncSharedBackendInternal(snap.sharedBackendUrl, snap.sharedAdminSecret)
            }
        }
    }

    fun toggleBotPolling() {
        val currentStatus = uiState.value.botRuntime.status
        if (currentStatus == BotConnectionStatus.POLLING || currentStatus == BotConnectionStatus.CONNECTING || currentStatus == BotConnectionStatus.RATE_LIMITED) {
            botEngine.stopPolling()
        } else {
            botEngine.startPolling()
        }
    }

    fun toggleBackgroundMonitor(context: Context) {
        if (botEngine.runtimeState.value.isForegroundServiceActive) {
            YutaStickerMonitorService.stop(context)
        } else {
            YutaStickerMonitorService.start(context)
        }
    }

    fun retryPendingStickerQueue() {
        viewModelScope.launch {
            transientFlags.value = transientFlags.value.copy(isDrainingQueue = true)
            try {
                val snap = settingsRepository.getSnapshot()
                if (snap.isSharedBackendConfigured) {
                    runCatching {
                        backendApi.processStickerQueue(
                            url = YutaBackendApiService.endpoint(snap.sharedBackendUrl, "/api/stickers/process-queue"),
                            adminSecret = snap.sharedAdminSecret
                        )
                    }
                    syncSharedBackendInternal(snap.sharedBackendUrl, snap.sharedAdminSecret)
                } else {
                    botEngine.retryFailedStickers()
                    botEngine.processNextStickerBatch()
                }
            } finally {
                transientFlags.value = transientFlags.value.copy(isDrainingQueue = false)
            }
        }
    }

    fun saveAndVerifySharedBackend(rawUrl: String, adminSecret: String) {
        viewModelScope.launch {
            val cleanUrl = YutaBackendApiService.normalizeBaseUrl(rawUrl)
            if (cleanUrl.isBlank()) {
                settingsRepository.saveSharedBackendConfig("", "")
                (aiRepository as? SharedBackendAiRepository)?.updateLiveStatus(
                    available = false,
                    modelLabel = "Shared Gemini Offline",
                    statusMessage = "Disconnected from Shared Backend."
                )
                transientFlags.value = transientFlags.value.copy(
                    sharedBackendConnected = false,
                    sharedBackendError = null,
                    remoteStats = null,
                    remoteStickerStats = null,
                    remoteUptimeBaseSeconds = 0L,
                    remoteUptimeFetchedAtMs = 0L,
                    remoteLogs = null,
                    remoteAiStatus = null,
                    remoteBotUsername = null,
                    remoteTelegramError = null,
                    webhookFeedback = null,
                    sharedBackendFeedback = "Disconnected from Shared Backend."
                )
                return@launch
            }
            if (!cleanUrl.startsWith("https://")) {
                transientFlags.value = transientFlags.value.copy(
                    sharedBackendConnected = false,
                    sharedBackendError = "HTTPS URL required",
                    sharedBackendFeedback = "Error: Secure HTTPS URL required (must start with https://)."
                )
                return@launch
            }
            settingsRepository.saveSharedBackendConfig(cleanUrl, adminSecret)
            syncSharedBackendInternal(cleanUrl, adminSecret.trim())
        }
    }

    fun runLiveConnectionTest() {
        viewModelScope.launch {
            val snap = settingsRepository.getSnapshot()
            if (!snap.isSharedBackendConfigured) {
                transientFlags.value = transientFlags.value.copy(
                    sharedBackendFeedback = "Disconnected: Enter your Cloudflare Worker HTTPS URL first."
                )
                return@launch
            }
            transientFlags.value = transientFlags.value.copy(
                isTestingConnection = true,
                sharedBackendFeedback = null
            )
            try {
                val resp = backendApi.runConnectionTest(
                    url = YutaBackendApiService.endpoint(snap.sharedBackendUrl, "/api/connection-test"),
                    adminSecret = snap.sharedAdminSecret
                )
                val body = resp.body()
                if (body == null) {
                    val err = "Connection test failed (HTTP ${resp.code()} — check URL or ADMIN_SECRET)."
                    transientFlags.value = transientFlags.value.copy(
                        isTestingConnection = false,
                        sharedBackendConnected = false,
                        sharedBackendError = err,
                        remoteAiStatus = AiServiceStatus(
                            isAvailable = false,
                            requiresFirebaseSetup = true,
                            modelLabel = "Backend Error",
                            statusMessage = err,
                            hasError = true
                        ),
                        sharedBackendFeedback = err
                    )
                    return@launch
                }

                val tgOk = body.telegram?.ok == true
                val tgErr = if (tgOk) null else (body.telegram?.error ?: "Telegram getMe failed")
                val gemOk = body.gemini?.ok == true
                val gemErr = body.gemini?.error.orEmpty()
                val webhookSummary = if (body.webhook?.ok == true) {
                    body.webhook.url.ifBlank { "Not registered yet" }
                } else {
                    "Error: ${body.webhook?.error ?: "check bot token"}"
                }

                val remoteAi = AiServiceStatus(
                    isAvailable = gemOk,
                    requiresFirebaseSetup = !gemOk,
                    modelLabel = if (gemOk) "Gemini (${body.gemini?.model})" else "Gemini Error",
                    statusMessage = if (gemOk) "Live reply: ${body.gemini?.reply}" else gemErr,
                    hasError = !gemOk
                )
                (aiRepository as? SharedBackendAiRepository)?.updateLiveStatus(
                    available = gemOk,
                    modelLabel = remoteAi.modelLabel,
                    statusMessage = remoteAi.statusMessage
                )

                val summary = buildString {
                    appendLine("• Telegram getMe: ${if (tgOk) "Connected (@${body.telegram?.username})" else "Error ($tgErr)"}")
                    appendLine("• Gemini Live Test: ${if (gemOk) "Connected (${body.gemini?.model} -> ${body.gemini?.reply})" else "Error ($gemErr)"}")
                    appendLine("• Webhook: $webhookSummary")
                    append("• D1 SQLite: ${if (body.database?.ok == true) "Connected" else "Not Bound"}")
                }

                transientFlags.value = transientFlags.value.copy(
                    isTestingConnection = false,
                    sharedBackendConnected = true,
                    sharedBackendError = null,
                    remoteBotUsername = body.telegram?.username,
                    remoteTelegramError = tgErr,
                    remoteAiStatus = remoteAi,
                    sharedBackendFeedback = summary
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = "Connection test network error: ${e.localizedMessage ?: "Check internet"}"
                transientFlags.value = transientFlags.value.copy(
                    isTestingConnection = false,
                    sharedBackendConnected = false,
                    sharedBackendError = err,
                    remoteAiStatus = AiServiceStatus(
                        isAvailable = false,
                        requiresFirebaseSetup = true,
                        modelLabel = "Connection Error",
                        statusMessage = err,
                        hasError = true
                    ),
                    sharedBackendFeedback = err
                )
            }
        }
    }

    fun setupSharedBackendWebhook() {
        viewModelScope.launch {
            val snap = settingsRepository.getSnapshot()
            if (!snap.isSharedBackendConfigured) {
                transientFlags.value = transientFlags.value.copy(
                    webhookFeedback = "Error: Save and connect a Shared HTTPS Backend URL first."
                )
                return@launch
            }
            transientFlags.value = transientFlags.value.copy(
                isRegisteringWebhook = true,
                webhookFeedback = null
            )
            try {
                val resp = backendApi.setupWebhook(
                    url = YutaBackendApiService.endpoint(snap.sharedBackendUrl, "/api/telegram/webhook-setup"),
                    adminSecret = snap.sharedAdminSecret
                )
                val body = resp.body()
                val msg = if (resp.isSuccessful && body?.ok == true) {
                    "24/7 Webhook Active: ${body.webhookUrl ?: "${snap.sharedBackendUrl}/telegram/webhook"}"
                } else {
                    "Webhook failed (HTTP ${resp.code()}): ${body?.description ?: body?.error ?: "Check TELEGRAM_BOT_TOKEN on Worker"}"
                }
                transientFlags.value = transientFlags.value.copy(
                    isRegisteringWebhook = false,
                    webhookFeedback = msg
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transientFlags.value = transientFlags.value.copy(
                    isRegisteringWebhook = false,
                    webhookFeedback = "Webhook setup error: ${e.localizedMessage ?: "network error"}"
                )
            }
        }
    }

    fun syncSharedBackendNow() {
        viewModelScope.launch {
            val snap = settingsRepository.getSnapshot()
            if (snap.isSharedBackendConfigured) {
                syncSharedBackendInternal(snap.sharedBackendUrl, snap.sharedAdminSecret)
            }
        }
    }

    private suspend fun syncSharedBackendInternal(baseUrl: String, adminSecret: String) {
        transientFlags.value = transientFlags.value.copy(
            isVerifyingBackend = true,
            sharedBackendFeedback = null
        )
        try {
            val statusResp = backendApi.getStatus(YutaBackendApiService.endpoint(baseUrl, "/api/status"), adminSecret)
            val statusBody = statusResp.body()
            if (!statusResp.isSuccessful || statusBody == null) {
                val errMsg = if (statusResp.code() == 401) {
                    "HTTP 401 Unauthorized — Check your ADMIN_SECRET passcode."
                } else {
                    "Backend HTTP ${statusResp.code()} error."
                }
                val errAi = AiServiceStatus(
                    isAvailable = false,
                    requiresFirebaseSetup = true,
                    modelLabel = "Backend Error",
                    statusMessage = errMsg,
                    hasError = true
                )
                (aiRepository as? SharedBackendAiRepository)?.updateLiveStatus(false, errAi.modelLabel, errMsg)
                transientFlags.value = transientFlags.value.copy(
                    isVerifyingBackend = false,
                    sharedBackendConnected = false,
                    sharedBackendError = errMsg,
                    remoteAiStatus = errAi,
                    sharedBackendFeedback = errMsg
                )
                return
            }

            val statsResp = backendApi.getStats(YutaBackendApiService.endpoint(baseUrl, "/api/stats"), adminSecret)
            val stickerStatsResp = backendApi.getStickerStats(YutaBackendApiService.endpoint(baseUrl, "/api/stickers/stats"), adminSecret)
            val logsResp = backendApi.getLogs(YutaBackendApiService.endpoint(baseUrl, "/api/logs"), adminSecret)
            val settingsResp = backendApi.getSettings(YutaBackendApiService.endpoint(baseUrl, "/api/settings"), adminSecret)

            settingsResp.body()?.let { remoteSettings ->
                val parsedSens = runCatching { ModerationSensitivity.valueOf(remoteSettings.sensitivity) }
                    .getOrDefault(ModerationSensitivity.BALANCED)
                settingsRepository.updateModerationToggles(
                    enableImage = remoteSettings.enableImageModeration,
                    enableSticker = remoteSettings.enableStickerModeration,
                    enableGif = remoteSettings.enableGifModeration,
                    enableAiChat = remoteSettings.enableAiChat,
                    enableAutoDelete = remoteSettings.enableAutoDelete,
                    enableAdminNotifications = remoteSettings.enableAdminNotifications,
                    disableMediaRetention = remoteSettings.disableMediaRetention,
                    sensitivity = parsedSens
                )
                if (remoteSettings.adminUserIds.isNotBlank() || remoteSettings.adminAlertChatId.isNotBlank()) {
                    settingsRepository.updateAdminContacts(
                        adminUserIdsRaw = remoteSettings.adminUserIds,
                        adminAlertChatId = remoteSettings.adminAlertChatId
                    )
                }
            }

            val mappedStats = statsResp.body()?.let { s ->
                ModerationStats(
                    totalImagesScanned = s.totalImagesScanned,
                    totalStickersScanned = s.totalStickersScanned,
                    totalVideosAndGifsScanned = s.totalVideosAndGifsScanned,
                    totalViolationsDetected = s.totalViolationsDetected,
                    totalMessagesDeleted = s.totalMessagesDeleted,
                    totalUncertainOrFailed = s.totalUncertainOrFailed
                )
            }

            val rawSticker = stickerStatsResp.body() ?: statusBody.stickerStats
            val mappedStickerStats = rawSticker?.let { st ->
                StickerScannerStats(
                    totalStickersReceived = st.totalStickersReceived,
                    totalStickersScanned = st.totalStickersScanned,
                    violationsDetected = st.violationsDetected,
                    messagesDeleted = st.messagesDeleted,
                    pendingScans = st.pendingScans,
                    failedScans = st.failedScans
                )
            }

            val mappedLogs = logsResp.body()?.logs?.map { item ->
                ModerationLogEntity(
                    id = item.id,
                    messageKey = item.messageKey,
                    chatId = item.chatId,
                    chatTitle = item.chatTitle,
                    senderName = item.senderName,
                    mediaKind = item.mediaKind,
                    verdict = item.verdict,
                    confidence = item.confidence,
                    actionTaken = item.actionTaken,
                    reason = item.reason,
                    isPlaceholderAi = false,
                    createdAt = item.createdAt
                )
            }

            val tgOk = statusBody.telegram?.ok == true
            val tgErr = if (tgOk) null else (statusBody.telegram?.error ?: "TELEGRAM_BOT_TOKEN secret missing or invalid.")
            val gemOk = statusBody.geminiConnected
            val gemHasError = statusBody.geminiConfigured && !gemOk
            val gemErr = statusBody.geminiError.ifBlank { if (!statusBody.geminiConfigured) "GEMINI_API_KEY secret missing." else "" }

            val remoteAi = AiServiceStatus(
                isAvailable = gemOk,
                requiresFirebaseSetup = !gemOk,
                modelLabel = when {
                    gemOk -> "Shared Gemini (${statusBody.geminiModel})"
                    gemHasError -> "Gemini Error"
                    else -> "Gemini Secret Missing"
                },
                statusMessage = if (gemOk) (statusBody.note ?: "Synced with Shared Backend") else gemErr,
                hasError = gemHasError
            )
            (aiRepository as? SharedBackendAiRepository)?.updateLiveStatus(
                available = remoteAi.isAvailable,
                modelLabel = remoteAi.modelLabel,
                statusMessage = remoteAi.statusMessage
            )

            val lastErrSuffix = statusBody.lastError.takeIf { it.isNotBlank() }?.let { " | Last Error: $it" }.orEmpty()

            transientFlags.value = transientFlags.value.copy(
                isVerifyingBackend = false,
                sharedBackendConnected = true,
                sharedBackendError = null,
                remoteStats = mappedStats,
                remoteStickerStats = mappedStickerStats,
                remoteUptimeBaseSeconds = stickerStatsResp.body()?.uptimeSeconds ?: statusBody.uptimeSeconds,
                remoteUptimeFetchedAtMs = System.currentTimeMillis(),
                remoteLogs = mappedLogs,
                remoteAiStatus = remoteAi,
                remoteBotUsername = statusBody.telegram?.username,
                remoteTelegramError = tgErr,
                sharedBackendFeedback = "Telegram: ${if (tgOk) "Connected (@${statusBody.telegram?.username})" else "Error ($tgErr)"} | Gemini: ${if (gemOk) "Connected (${statusBody.geminiModel})" else "Error ($gemErr)"} | D1 DB: ${if (statusBody.databaseBound) "Bound" else "Not Bound"}$lastErrSuffix"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Shared backend sync failed", e)
            val errMsg = e.localizedMessage ?: "Check Worker URL & internet"
            val errAi = AiServiceStatus(
                isAvailable = false,
                requiresFirebaseSetup = true,
                modelLabel = "Connection Error",
                statusMessage = errMsg,
                hasError = true
            )
            (aiRepository as? SharedBackendAiRepository)?.updateLiveStatus(
                available = false,
                modelLabel = errAi.modelLabel,
                statusMessage = errMsg
            )
            transientFlags.value = transientFlags.value.copy(
                isVerifyingBackend = false,
                sharedBackendConnected = false,
                sharedBackendError = errMsg,
                remoteAiStatus = errAi,
                sharedBackendFeedback = "Backend connection error: $errMsg"
            )
        }
    }

    fun runCommandPreview(command: String) {
        viewModelScope.launch {
            val output = botEngine.executeCommand(command)
            transientFlags.value = transientFlags.value.copy(commandPreviewOutput = output)
        }
    }

    fun setLogFilter(filter: LogFilter) {
        transientFlags.value = transientFlags.value.copy(selectedFilter = filter)
    }

    fun scanLocalMedia(
        context: Context,
        uri: Uri,
        mediaKind: MediaKind
    ) {
        viewModelScope.launch {
            transientFlags.value = transientFlags.value.copy(
                isScanningLocalMedia = true,
                latestScanFeedback = null
            )
            try {
                val rawBytes = MediaProcessor.readRawUriBytes(context, uri)
                if (rawBytes == null || rawBytes.isEmpty()) {
                    transientFlags.value = transientFlags.value.copy(
                        isScanningLocalMedia = false,
                        latestScanFeedback = "File exceeded 10MB safety limit or could not be read (NOT marked safe)."
                    )
                    return@launch
                }

                val resolverMime = context.contentResolver.getType(uri).orEmpty().lowercase()
                val pathHint = uri.lastPathSegment.orEmpty().lowercase()
                val effectiveMime = when {
                    mediaKind == MediaKind.STICKER_ANIMATED || pathHint.endsWith(".tgs") || resolverMime.contains("tgsticker") || resolverMime.contains("gzip") ->
                        "application/x-tgsticker"
                    mediaKind == MediaKind.STICKER_VIDEO || pathHint.endsWith(".webm") || resolverMime.contains("webm") ->
                        "video/webm"
                    mediaKind == MediaKind.STICKER_STATIC || pathHint.endsWith(".webp") ->
                        "image/webp"
                    resolverMime.isNotBlank() -> resolverMime
                    else -> "image/jpeg"
                }

                val snap = settingsRepository.getSnapshot()
                if (snap.isSharedBackendConfigured) {
                    val format = when (mediaKind) {
                        MediaKind.STICKER_STATIC -> "WEBP_STATIC"
                        MediaKind.STICKER_ANIMATED -> "TGS_ANIMATED"
                        MediaKind.STICKER_VIDEO -> "WEBM_VIDEO"
                        else -> ""
                    }
                    val frameBytes: ByteArray?
                    val sendMime: String
                    if (effectiveMime == "application/x-tgsticker") {
                        frameBytes = rawBytes
                        sendMime = "application/x-tgsticker"
                    } else if (format.isNotEmpty() || effectiveMime.startsWith("video/")) {
                        val prep = MediaProcessor.prepareStickerFrame(context, rawBytes, effectiveMime, format.ifEmpty { "WEBM_VIDEO" })
                        frameBytes = prep.jpegBytes
                        sendMime = "image/jpeg"
                    } else {
                        frameBytes = MediaProcessor.downscaleImageIfPossible(rawBytes).takeIf { it.isNotEmpty() }
                        sendMime = "image/jpeg"
                    }

                    if (frameBytes == null || frameBytes.isEmpty()) {
                        transientFlags.value = transientFlags.value.copy(
                            isScanningLocalMedia = false,
                            latestScanFeedback = "SCAN_FAILED: Could not convert sticker/media to frame. NEVER marked safe."
                        )
                        return@launch
                    }

                    val base64 = Base64.encodeToString(frameBytes, Base64.NO_WRAP)
                    val resp = backendApi.scanMedia(
                        url = YutaBackendApiService.endpoint(snap.sharedBackendUrl, "/api/scan"),
                        adminSecret = snap.sharedAdminSecret,
                        body = BackendScanRequest(
                            mediaKind = mediaKind.name,
                            mimeType = sendMime,
                            base64Data = base64
                        )
                    )
                    val item = resp.body()
                    if (resp.isSuccessful && item != null) {
                        transientFlags.value = transientFlags.value.copy(
                            isScanningLocalMedia = false,
                            latestScanFeedback = "${item.verdict} -> ${item.actionTaken}: ${item.reason}"
                        )
                        syncSharedBackendInternal(snap.sharedBackendUrl, snap.sharedAdminSecret)
                        return@launch
                    } else {
                        transientFlags.value = transientFlags.value.copy(
                            isScanningLocalMedia = false,
                            latestScanFeedback = "Backend scan error (HTTP ${resp.code()}). Media NOT marked safe."
                        )
                        return@launch
                    }
                }

                val result = botEngine.runTabletLocalScan(
                    bytes = rawBytes,
                    mimeType = effectiveMime,
                    mediaKind = mediaKind,
                    simulatedOutcome = SimulatedOutcome.AUTO_SAMPLE
                )
                transientFlags.value = transientFlags.value.copy(
                    isScanningLocalMedia = false,
                    latestScanFeedback = "${result.verdict} -> ${result.actionTaken}: ${result.reason}"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Media scan failed", e)
                transientFlags.value = transientFlags.value.copy(
                    isScanningLocalMedia = false,
                    latestScanFeedback = "Scan error: ${e.localizedMessage ?: "Unknown error"}"
                )
            }
        }
    }

    fun sendChatMessage(rawText: String) {
        val prompt = rawText.trim()
        if (prompt.isEmpty() || transientFlags.value.isChatLoading) return

        viewModelScope.launch {
            transientFlags.value = transientFlags.value.copy(
                isChatLoading = true,
                streamingChatText = ""
            )
            try {
                dao.insertChatMessage(
                    ChatMessageEntity(
                        role = "user",
                        content = prompt,
                        source = "APP",
                        isPlaceholderAi = false
                    )
                )

                val snap = settingsRepository.getSnapshot()
                if (snap.isSharedBackendConfigured) {
                    val resp = backendApi.sendChat(
                        url = YutaBackendApiService.endpoint(snap.sharedBackendUrl, "/api/chat"),
                        adminSecret = snap.sharedAdminSecret,
                        body = BackendChatRequest(prompt = prompt)
                    )
                    val replyText = resp.body()?.reply?.takeIf { it.isNotBlank() }
                        ?: "Shared backend returned HTTP ${resp.code()}."
                    dao.insertChatMessage(
                        ChatMessageEntity(
                            role = "model",
                            content = replyText,
                            source = "SHARED_BACKEND",
                            isPlaceholderAi = false
                        )
                    )
                    return@launch
                }

                var finalReply = ""
                var replyIsPlaceholder = false
                aiRepository.streamChatResponse(prompt).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Chunk -> {
                            transientFlags.value = transientFlags.value.copy(
                                streamingChatText = event.partialText
                            )
                        }
                        is ChatStreamEvent.Completed -> {
                            finalReply = event.fullText
                            replyIsPlaceholder = event.isPlaceholder
                        }
                        is ChatStreamEvent.Error -> {
                            finalReply = event.message
                            replyIsPlaceholder = false
                        }
                    }
                }

                if (finalReply.isNotBlank()) {
                    dao.insertChatMessage(
                        ChatMessageEntity(
                            role = "model",
                            content = finalReply,
                            source = "APP",
                            isPlaceholderAi = replyIsPlaceholder
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Chat stream failed", e)
                dao.insertChatMessage(
                    ChatMessageEntity(
                        role = "model",
                        content = "Error: ${e.localizedMessage ?: "Could not generate response."}",
                        source = "APP",
                        isPlaceholderAi = false
                    )
                )
            } finally {
                transientFlags.value = transientFlags.value.copy(
                    isChatLoading = false,
                    streamingChatText = null
                )
            }
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch { dao.clearChatMessages() }
    }

    fun clearModerationLogs() {
        viewModelScope.launch { dao.clearModerationLogs() }
    }

    fun saveAndVerifyTelegramConfig(
        token: String,
        adminUserIds: String,
        adminAlertChatId: String
    ) {
        viewModelScope.launch {
            transientFlags.value = transientFlags.value.copy(
                isVerifyingToken = true,
                tokenVerificationFeedback = null
            )
            settingsRepository.saveTelegramCredentials(token, adminUserIds, adminAlertChatId)
            val snap = settingsRepository.getSnapshot()
            if (snap.isSharedBackendConfigured) {
                runCatching {
                    backendApi.updateSettings(
                        url = YutaBackendApiService.endpoint(snap.sharedBackendUrl, "/api/settings"),
                        adminSecret = snap.sharedAdminSecret,
                        settings = BackendSettingsDto(
                            enableImageModeration = snap.enableImageModeration,
                            enableStickerModeration = snap.enableStickerModeration,
                            enableGifModeration = snap.enableGifModeration,
                            enableAiChat = snap.enableAiChat,
                            enableAutoDelete = snap.enableAutoDelete,
                            enableAdminNotifications = snap.enableAdminNotifications,
                            disableMediaRetention = snap.disableMediaRetention,
                            sensitivity = snap.sensitivity.name,
                            adminUserIds = snap.adminUserIdsRaw,
                            adminAlertChatId = snap.adminAlertChatId
                        )
                    )
                }
            }

            if (token.isBlank()) {
                botEngine.stopPolling()
                transientFlags.value = transientFlags.value.copy(
                    isVerifyingToken = false,
                    tokenVerificationFeedback = if (snap.isSharedBackendConfigured) {
                        "Admin IDs & Alert Chat ID saved and synced to Shared Backend (Bot Token stays in Cloudflare Secrets)."
                    } else {
                        "Credentials cleared."
                    }
                )
                return@launch
            }
            val result = botEngine.verifyToken(token)
            val feedback = result.fold(
                onSuccess = { user -> "Connected! Verified bot @${user.username ?: user.firstName} (ID: ${user.id})." },
                onFailure = { err -> err.message ?: "Verification failed." }
            )
            transientFlags.value = transientFlags.value.copy(
                isVerifyingToken = false,
                tokenVerificationFeedback = feedback
            )
        }
    }

    fun updateCustomLogoUri(uri: String?) {
        viewModelScope.launch { settingsRepository.updateCustomLogoUri(uri) }
    }

    fun updateImageModeration(enabled: Boolean) = updateToggleAndSync { settingsRepository.updateModerationToggles(enableImage = enabled) }
    fun updateStickerModeration(enabled: Boolean) = updateToggleAndSync { settingsRepository.updateModerationToggles(enableSticker = enabled) }
    fun updateGifModeration(enabled: Boolean) = updateToggleAndSync { settingsRepository.updateModerationToggles(enableGif = enabled) }
    fun updateVideoModeration(enabled: Boolean) = updateToggleAndSync { settingsRepository.updateModerationToggles(enableVideo = enabled) }
    fun updateAiChatEnabled(enabled: Boolean) = updateToggleAndSync { settingsRepository.updateModerationToggles(enableAiChat = enabled) }
    fun updateAutoDelete(enabled: Boolean) = updateToggleAndSync { settingsRepository.updateModerationToggles(enableAutoDelete = enabled) }
    fun updateAdminNotifications(enabled: Boolean) = updateToggleAndSync { settingsRepository.updateModerationToggles(enableAdminNotifications = enabled) }
    fun updateDisableMediaRetention(disabled: Boolean) = updateToggleAndSync { settingsRepository.updateModerationToggles(disableMediaRetention = disabled) }
    fun updateSensitivity(sensitivity: ModerationSensitivity) = updateToggleAndSync { settingsRepository.updateModerationToggles(sensitivity = sensitivity) }

    private fun updateToggleAndSync(localUpdate: suspend () -> Unit) {
        viewModelScope.launch {
            localUpdate()
            val s = settingsRepository.getSnapshot()
            if (s.isSharedBackendConfigured) {
                runCatching {
                    backendApi.updateSettings(
                        url = YutaBackendApiService.endpoint(s.sharedBackendUrl, "/api/settings"),
                        adminSecret = s.sharedAdminSecret,
                        settings = BackendSettingsDto(
                            enableImageModeration = s.enableImageModeration,
                            enableStickerModeration = s.enableStickerModeration,
                            enableGifModeration = s.enableGifModeration,
                            enableAiChat = s.enableAiChat,
                            enableAutoDelete = s.enableAutoDelete,
                            enableAdminNotifications = s.enableAdminNotifications,
                            disableMediaRetention = s.disableMediaRetention,
                            sensitivity = s.sensitivity.name,
                            adminUserIds = s.adminUserIdsRaw,
                            adminAlertChatId = s.adminAlertChatId
                        )
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "YutaViewModel"

        fun factory(container: YutaAppContainer): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return YutaViewModel(
                        dao = container.dao,
                        settingsRepository = container.settingsRepository,
                        aiRepository = container.aiRepository,
                        botEngine = container.botEngine,
                        backendApi = container.backendApiService
                    ) as T
                }
            }
        }
    }
}
