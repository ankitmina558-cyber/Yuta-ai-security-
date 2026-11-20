package com.example.data.telegram

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.ai.AiRepository
import com.example.data.ai.ChatStreamEvent
import com.example.data.ai.MediaKind
import com.example.data.ai.ModerationVerdict
import com.example.data.ai.SimulatedOutcome
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ModerationLogEntity
import com.example.data.local.ProcessedMessageEntity
import com.example.data.local.StickerQueueEntity
import com.example.data.local.TokenCrypto
import com.example.data.local.YutaDao
import com.example.data.local.YutaSettings
import com.example.data.local.YutaSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.math.pow

enum class BotConnectionStatus(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    POLLING("Live Polling"),
    RATE_LIMITED("Rate Limited"),
    ERROR("Error")
}

data class BotRuntimeState(
    val status: BotConnectionStatus = BotConnectionStatus.DISCONNECTED,
    val botUsername: String? = null,
    val botId: Long? = null,
    val queuedMediaCount: Int = 0,
    val scannerStartedAtMs: Long? = null,
    val isForegroundServiceActive: Boolean = false,
    val lastStatusNote: String = "Bot idle. Configure Shared Backend (24/7 Cloud) or @BotFather token in Settings."
)

private data class MediaQueueTask(
    val message: TgMessage,
    val mediaKind: MediaKind,
    val fileId: String,
    val declaredFileSize: Long?,
    val mimeType: String,
    val fallbackNote: String? = null
)

class YutaBotEngine(
    private val context: Context,
    private val telegramApi: TelegramApiService,
    private val aiRepository: AiRepository,
    private val dao: YutaDao,
    private val settingsRepository: YutaSettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var nonStickerWorkerJob: Job? = null
    private var stickerWorkerJob: Job? = null
    private val mediaQueue = Channel<MediaQueueTask>(capacity = 64)
    private val stickerWakeSignal = Channel<Unit>(capacity = Channel.CONFLATED)

    private val _runtimeState = MutableStateFlow(BotRuntimeState())
    val runtimeState: StateFlow<BotRuntimeState> = _runtimeState.asStateFlow()

    fun setForegroundServiceActive(active: Boolean) {
        _runtimeState.value = _runtimeState.value.copy(isForegroundServiceActive = active)
    }

    suspend fun verifyToken(token: String): Result<TgUser> {
        val trimmed = token.trim()
        if (trimmed.isBlank() || !trimmed.contains(":")) {
            return Result.failure(IllegalArgumentException("Invalid Telegram Bot Token format. Get one free from @BotFather."))
        }
        return try {
            val response = telegramApi.getMe(TelegramApiService.botEndpoint(trimmed, "getMe"))
            val body = response.body()
            if (response.isSuccessful && body?.ok == true && body.result != null) {
                _runtimeState.value = _runtimeState.value.copy(
                    botUsername = body.result.username,
                    botId = body.result.id,
                    lastStatusNote = "Verified @${body.result.username ?: body.result.firstName}"
                )
                Result.success(body.result)
            } else {
                val parsedErr = body?.description ?: TelegramApiService.parseErrorBody(response.errorBody())?.description
                val msg = TokenCrypto.redactSecrets(
                    parsedErr ?: "Telegram rejected token (HTTP ${response.code()}).",
                    trimmed
                )
                Result.failure(IllegalStateException(msg))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val safeError = TokenCrypto.redactSecrets(e.localizedMessage ?: "Check internet", trimmed)
            Result.failure(IllegalStateException("Network error verifying token: $safeError"))
        }
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        ensureWorkersRunning()
        pollingJob = scope.launch {
            val initialSettings = settingsRepository.getSnapshot()
            val token = initialSettings.botToken.trim()
            if (token.isBlank()) {
                _runtimeState.value = _runtimeState.value.copy(
                    status = BotConnectionStatus.ERROR,
                    scannerStartedAtMs = null,
                    lastStatusNote = "No Telegram Bot Token saved. Enter your @BotFather token in Settings."
                )
                return@launch
            }

            if (!hasNetwork()) {
                _runtimeState.value = _runtimeState.value.copy(
                    status = BotConnectionStatus.ERROR,
                    scannerStartedAtMs = null,
                    lastStatusNote = "No active internet connection detected on tablet."
                )
                return@launch
            }

            _runtimeState.value = _runtimeState.value.copy(
                status = BotConnectionStatus.CONNECTING,
                lastStatusNote = "Connecting to Telegram Bot API (getMe)..."
            )

            val meResult = verifyToken(token)
            if (meResult.isFailure) {
                _runtimeState.value = _runtimeState.value.copy(
                    status = BotConnectionStatus.ERROR,
                    scannerStartedAtMs = null,
                    lastStatusNote = meResult.exceptionOrNull()?.message ?: "Telegram authentication failed."
                )
                return@launch
            }

            val me = meResult.getOrThrow()
            // Clear any leftover webhook cleanly before starting long polling
            runCatching {
                telegramApi.deleteWebhook(
                    url = TelegramApiService.botEndpoint(token, "deleteWebhook"),
                    dropPendingUpdates = false
                )
            }

            dao.recoverInterruptedStickerJobs(System.currentTimeMillis())
            stickerWakeSignal.trySend(Unit)

            var offset = initialSettings.lastUpdateOffset
            var backoffMs = 2000L

            _runtimeState.value = _runtimeState.value.copy(
                status = BotConnectionStatus.POLLING,
                botUsername = me.username,
                botId = me.id,
                scannerStartedAtMs = _runtimeState.value.scannerStartedAtMs ?: System.currentTimeMillis(),
                lastStatusNote = "Live polling + sticker queue active as @${me.username ?: me.firstName}"
            )

            while (isActive) {
                try {
                    val currentSettings = settingsRepository.getSnapshot()
                    val activeToken = currentSettings.botToken.trim()
                    if (activeToken.isBlank()) {
                        _runtimeState.value = _runtimeState.value.copy(
                            status = BotConnectionStatus.DISCONNECTED,
                            scannerStartedAtMs = null,
                            lastStatusNote = "Polling stopped because Bot Token was cleared."
                        )
                        break
                    }

                    val resp = telegramApi.getUpdates(
                        url = TelegramApiService.botEndpoint(activeToken, "getUpdates"),
                        offset = offset,
                        timeout = 20
                    )
                    val body = resp.body()
                    if (resp.isSuccessful && body?.ok == true) {
                        backoffMs = 2000L
                        val updates = body.result.orEmpty()
                        for (update in updates) {
                            offset = maxOf(offset, update.updateId + 1)
                            settingsRepository.updateLastOffset(offset)
                            update.message?.let { msg ->
                                handleIncomingMessage(msg, update.updateId, currentSettings, activeToken, me.id)
                            }
                        }
                    } else {
                        val errObj = if (body != null) null else TelegramApiService.parseErrorBody(resp.errorBody())
                        val retryAfter = body?.parameters?.retryAfter ?: errObj?.parameters?.retryAfter
                        val errDesc = TokenCrypto.redactSecrets(
                            body?.description ?: errObj?.description ?: "Telegram API HTTP ${resp.code()}",
                            activeToken
                        )

                        when (resp.code()) {
                            429 -> {
                                val retrySec = (retryAfter ?: 5).coerceIn(2, 60)
                                _runtimeState.value = _runtimeState.value.copy(
                                    status = BotConnectionStatus.RATE_LIMITED,
                                    lastStatusNote = "Telegram rate limit (429). Waiting ${retrySec}s..."
                                )
                                delay(retrySec * 1000L)
                                _runtimeState.value = _runtimeState.value.copy(
                                    status = BotConnectionStatus.POLLING,
                                    lastStatusNote = "Resumed polling after rate-limit cooldown."
                                )
                            }
                            409 -> {
                                _runtimeState.value = _runtimeState.value.copy(
                                    status = BotConnectionStatus.CONNECTING,
                                    lastStatusNote = "Webhook conflict detected (409). Resetting webhook for polling..."
                                )
                                runCatching {
                                    telegramApi.deleteWebhook(
                                        url = TelegramApiService.botEndpoint(activeToken, "deleteWebhook"),
                                        dropPendingUpdates = false
                                    )
                                }
                                delay(2000L)
                            }
                            401 -> {
                                _runtimeState.value = _runtimeState.value.copy(
                                    status = BotConnectionStatus.ERROR,
                                    scannerStartedAtMs = null,
                                    lastStatusNote = "Unauthorized Bot Token (401): $errDesc"
                                )
                                break
                            }
                            else -> {
                                _runtimeState.value = _runtimeState.value.copy(
                                    status = BotConnectionStatus.ERROR,
                                    lastStatusNote = "$errDesc — retrying in ${backoffMs / 1000}s"
                                )
                                delay(backoffMs)
                                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    val safeMsg = TokenCrypto.redactSecrets(e.localizedMessage ?: "retrying", token)
                    _runtimeState.value = _runtimeState.value.copy(
                        status = BotConnectionStatus.ERROR,
                        lastStatusNote = "Network hiccup: $safeMsg"
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                } catch (e: Exception) {
                    val safeMsg = TokenCrypto.redactSecrets(e.localizedMessage ?: "unknown", token)
                    _runtimeState.value = _runtimeState.value.copy(
                        status = BotConnectionStatus.ERROR,
                        lastStatusNote = "Polling error: $safeMsg"
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _runtimeState.value = _runtimeState.value.copy(
            status = BotConnectionStatus.DISCONNECTED,
            scannerStartedAtMs = null,
            lastStatusNote = "Polling paused by admin on tablet."
        )
    }

    suspend fun retryFailedStickers() {
        ensureWorkersRunning()
        dao.retryFailedStickerJobs(System.currentTimeMillis())
        stickerWakeSignal.trySend(Unit)
    }

    /**
     * Publicly exposed for unit/Robolectric tests and internal queue draining.
     */
    suspend fun processNextStickerBatch(now: Long = System.currentTimeMillis()): Int {
        val jobs = dao.getRunnableStickerJobs(now = now, limit = 8)
        var processedCount = 0
        for (job in jobs) {
            dao.updateStickerJobState(
                messageKey = job.messageKey,
                status = StickerQueueEntity.STATUS_PROCESSING,
                retryCount = job.retryCount,
                lastError = job.lastError,
                nextAttemptAt = job.nextAttemptAt,
                updatedAt = System.currentTimeMillis()
            )
            val result = processQueuedStickerJob(job)
            if (result.isSuccess) {
                dao.updateStickerJobState(
                    messageKey = job.messageKey,
                    status = StickerQueueEntity.STATUS_DONE,
                    retryCount = job.retryCount,
                    lastError = "",
                    nextAttemptAt = 0L,
                    updatedAt = System.currentTimeMillis()
                )
                processedCount++
            } else {
                val errMsg = result.exceptionOrNull()?.message ?: "Sticker processing error"
                val nextRetry = job.retryCount + 1
                if (result.retryable && nextRetry < job.maxRetries) {
                    val backoffMs = (2.0.pow(nextRetry.toDouble()).toLong() * 2000L).coerceAtMost(30_000L)
                    dao.updateStickerJobState(
                        messageKey = job.messageKey,
                        status = StickerQueueEntity.STATUS_PENDING,
                        retryCount = nextRetry,
                        lastError = errMsg,
                        nextAttemptAt = System.currentTimeMillis() + backoffMs,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    dao.updateStickerJobState(
                        messageKey = job.messageKey,
                        status = StickerQueueEntity.STATUS_FAILED,
                        retryCount = nextRetry,
                        lastError = errMsg,
                        nextAttemptAt = 0L,
                        updatedAt = System.currentTimeMillis()
                    )
                    val settings = settingsRepository.getSnapshot()
                    logAndNotifyStickerFailure(job, "Failed after retries: $errMsg", settings, settings.botToken.trim())
                }
            }
        }
        return processedCount
    }

    private fun ensureWorkersRunning() {
        if (nonStickerWorkerJob?.isActive != true) {
            nonStickerWorkerJob = scope.launch {
                for (task in mediaQueue) {
                    try {
                        _runtimeState.value = _runtimeState.value.copy(
                            queuedMediaCount = (_runtimeState.value.queuedMediaCount - 1).coerceAtLeast(0)
                        )
                        processNonStickerTask(task)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _runtimeState.value = _runtimeState.value.copy(
                            lastStatusNote = "Media worker error: ${e.localizedMessage ?: "unknown"}"
                        )
                    }
                }
            }
        }

        if (stickerWorkerJob?.isActive != true) {
            stickerWorkerJob = scope.launch {
                while (isActive) {
                    try {
                        processNextStickerBatch()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _runtimeState.value = _runtimeState.value.copy(
                            lastStatusNote = "Sticker queue worker error: ${e.localizedMessage ?: "unknown"}"
                        )
                    }
                    // Wait for either immediate new sticker signal OR 5s backoff retry check
                    withTimeoutOrNull(5000L) {
                        stickerWakeSignal.receive()
                    }
                }
            }
        }
    }

    suspend fun enqueueIncomingMessageForTest(
        message: TgMessage,
        updateId: Long,
        settings: YutaSettings,
        token: String,
        botId: Long?
    ) = handleIncomingMessage(message, updateId, settings, token, botId)

    private suspend fun handleIncomingMessage(
        message: TgMessage,
        updateId: Long,
        settings: YutaSettings,
        token: String,
        botId: Long?
    ) {
        val messageKey = "${message.chat.id}_${message.messageId}"
        if (dao.isMessageProcessed(messageKey)) {
            return // Strict deduplication: never scan the same message twice
        }
        dao.insertProcessedMessage(ProcessedMessageEntity(messageKey = messageKey, updateId = updateId))

        val text = message.text?.trim().orEmpty()
        if (text.startsWith("/")) {
            val reply = executeCommand(
                rawCommand = text,
                senderUserId = message.from?.id,
                chatId = message.chat.id,
                chatType = message.chat.type,
                token = token,
                settings = settings,
                botId = botId
            )
            sendTelegramText(token, message.chat.id, reply, message.messageId)
            return
        }

        // Primary 24/7 Feature: Asynchronous persistent Sticker Queue
        message.sticker?.let { sticker ->
            if (!settings.enableStickerModeration) return
            val format = when {
                sticker.isVideo -> StickerQueueEntity.FORMAT_WEBM_VIDEO
                sticker.isAnimated -> StickerQueueEntity.FORMAT_TGS_ANIMATED
                else -> StickerQueueEntity.FORMAT_WEBP_STATIC
            }
            val mimeType = when (format) {
                StickerQueueEntity.FORMAT_WEBM_VIDEO -> "video/webm"
                StickerQueueEntity.FORMAT_TGS_ANIMATED -> "application/x-tgsticker"
                else -> "image/webp"
            }
            val chatTitle = message.chat.title ?: message.chat.username ?: "Chat ${message.chat.id}"
            val senderName = message.from?.let { u ->
                listOfNotNull(u.firstName.takeIf { it.isNotBlank() }, u.username?.let { "@$it" }).joinToString(" ")
            } ?: "Unknown"

            val entity = StickerQueueEntity(
                messageKey = messageKey,
                chatId = message.chat.id,
                chatTitle = chatTitle,
                senderName = senderName,
                messageId = message.messageId,
                stickerFormat = format,
                fileId = sticker.fileId,
                thumbnailFileId = sticker.thumbnail?.fileId.orEmpty(),
                stickerSetName = sticker.setName.orEmpty(),
                stickerEmoji = sticker.emoji.orEmpty(),
                declaredFileSize = sticker.fileSize,
                mimeType = mimeType,
                status = StickerQueueEntity.STATUS_PENDING,
                nextAttemptAt = System.currentTimeMillis()
            )
            dao.enqueueSticker(entity)
            ensureWorkersRunning()
            stickerWakeSignal.trySend(Unit)
            return
        }

        // Non-sticker media detection
        val mediaTask = detectNonStickerMedia(message, settings)
        if (mediaTask != null) {
            val sent = mediaQueue.trySend(mediaTask)
            if (sent.isSuccess) {
                _runtimeState.value = _runtimeState.value.copy(
                    queuedMediaCount = _runtimeState.value.queuedMediaCount + 1
                )
            } else {
                logAndNotifyFailure(
                    message = message,
                    mediaKind = mediaTask.mediaKind,
                    reason = "Moderation queue full (64 items). Kept unscanned as safe fallback.",
                    settings = settings,
                    token = token
                )
            }
        }
    }

    private data class QueueStepOutcome(
        val isSuccess: Boolean,
        val retryable: Boolean = false,
        val error: String? = null
    ) {
        fun exceptionOrNull(): Throwable? = error?.let { IllegalStateException(it) }
    }

    private suspend fun processQueuedStickerJob(job: StickerQueueEntity): QueueStepOutcome {
        val settings = settingsRepository.getSnapshot()
        val token = settings.botToken.trim()
        val mediaKind = when (job.stickerFormat) {
            StickerQueueEntity.FORMAT_WEBM_VIDEO -> MediaKind.STICKER_VIDEO
            StickerQueueEntity.FORMAT_TGS_ANIMATED -> MediaKind.STICKER_ANIMATED
            else -> MediaKind.STICKER_STATIC
        }

        if (job.declaredFileSize != null && job.declaredFileSize > MediaProcessor.MAX_DOWNLOAD_BYTES) {
            logAndNotifyStickerFailure(
                job = job,
                reason = "Sticker (${job.declaredFileSize / (1024 * 1024)}MB) exceeds 10MB cap. Not marked safe.",
                settings = settings,
                token = token
            )
            return QueueStepOutcome(isSuccess = true) // Non-retryable terminal fallback
        }

        // Determine download strategy per sticker format
        var preparedBytes: ByteArray? = null
        var inspectionNote = ""
        var forceUncertainReview = false

        when (job.stickerFormat) {
            StickerQueueEntity.FORMAT_WEBP_STATIC -> {
                val dl = downloadTelegramFileBytes(token, job.fileId)
                    ?: return QueueStepOutcome(isSuccess = false, retryable = true, error = "Failed downloading WEBP sticker from Telegram.")
                val prep = MediaProcessor.prepareStickerFrame(context, dl, "image/webp", job.stickerFormat)
                preparedBytes = prep.jpegBytes
                inspectionNote = prep.inspectionNote
            }

            StickerQueueEntity.FORMAT_WEBM_VIDEO -> {
                val fullBytes = downloadTelegramFileBytes(token, job.fileId)
                if (fullBytes != null) {
                    val prep = MediaProcessor.prepareStickerFrame(context, fullBytes, "video/webm", job.stickerFormat)
                    preparedBytes = prep.jpegBytes
                    inspectionNote = prep.inspectionNote
                }
                // Fallback to Telegram preview thumbnail if device MediaMetadataRetriever failed
                if ((preparedBytes == null || preparedBytes.isEmpty()) && job.thumbnailFileId.isNotBlank()) {
                    val thumbBytes = downloadTelegramFileBytes(token, job.thumbnailFileId)
                        ?: return QueueStepOutcome(isSuccess = false, retryable = true, error = "Failed downloading WEBM thumbnail.")
                    val prep = MediaProcessor.prepareStickerFrame(context, thumbBytes, "image/jpeg", StickerQueueEntity.FORMAT_WEBP_STATIC)
                    preparedBytes = prep.jpegBytes
                    inspectionNote = "WEBM fallback thumbnail frame | ${prep.inspectionNote}"
                }
                if (preparedBytes == null || preparedBytes.isEmpty()) {
                    return QueueStepOutcome(isSuccess = false, retryable = true, error = "Could not extract frame or thumbnail from WEBM sticker.")
                }
            }

            StickerQueueEntity.FORMAT_TGS_ANIMATED -> {
                if (job.thumbnailFileId.isNotBlank()) {
                    val thumbBytes = downloadTelegramFileBytes(token, job.thumbnailFileId)
                        ?: return QueueStepOutcome(isSuccess = false, retryable = true, error = "Failed downloading .tgs preview thumbnail.")
                    val prep = MediaProcessor.prepareStickerFrame(context, thumbBytes, "image/jpeg", StickerQueueEntity.FORMAT_WEBP_STATIC)
                    preparedBytes = prep.jpegBytes
                    inspectionNote = "Animated .tgs preview frame | ${prep.inspectionNote}"
                } else {
                    // Inspect .tgs gzipped Lottie safely — never mark unscanned .tgs SAFE!
                    val tgsBytes = if (job.fileId.isNotBlank()) downloadTelegramFileBytes(token, job.fileId) else null
                    inspectionNote = if (tgsBytes != null) {
                        MediaProcessor.inspectTgsMetadataSafe(tgsBytes)
                    } else {
                        "Animated .tgs sticker has no preview thumbnail; forwarded for admin review (never marked SAFE)."
                    }
                    forceUncertainReview = true
                }
            }
        }

        if (forceUncertainReview) {
            forwardStickerUncertainToAdmin(token, settings, job, inspectionNote)
            dao.insertModerationLog(
                ModerationLogEntity(
                    messageKey = job.messageKey,
                    chatId = job.chatId,
                    chatTitle = job.chatTitle,
                    senderName = job.senderName,
                    mediaKind = mediaKind.name,
                    verdict = ModerationVerdict.UNCERTAIN.name,
                    confidence = 0.4f,
                    actionTaken = "FORWARDED_ADMIN",
                    reason = inspectionNote,
                    isPlaceholderAi = aiRepository.getServiceStatus().requiresFirebaseSetup
                )
            )
            return QueueStepOutcome(isSuccess = true)
        }

        val scanPayload = preparedBytes
            ?: return QueueStepOutcome(isSuccess = false, retryable = false, error = "Empty frame after sticker conversion.")

        val decision = aiRepository.moderateMedia(
            mediaBytes = scanPayload,
            mimeType = "image/jpeg",
            mediaKind = mediaKind,
            sensitivity = settings.sensitivity
        )

        if (decision.verdict == ModerationVerdict.SCAN_FAILED) {
            return QueueStepOutcome(isSuccess = false, retryable = true, error = decision.reason)
        }

        val finalVerdict = if (decision.verdict.isViolation()) ModerationVerdict.VIOLATION else decision.verdict
        val setMeta = listOfNotNull(
            job.stickerEmoji.takeIf { it.isNotBlank() }?.let { "Emoji:$it" },
            job.stickerSetName.takeIf { it.isNotBlank() }?.let { "Set:$it" }
        ).joinToString(" ")
        val finalReason = listOfNotNull(
            inspectionNote.takeIf { it.isNotBlank() },
            setMeta.takeIf { it.isNotBlank() },
            decision.reason
        ).joinToString(" | ")

        var actionTaken = when (finalVerdict) {
            ModerationVerdict.SAFE -> "ALLOWED"
            ModerationVerdict.UNCERTAIN -> "FORWARDED_ADMIN"
            ModerationVerdict.VIOLATION, ModerationVerdict.SEXUAL_CONTENT -> if (settings.enableAutoDelete) "DELETED" else "FORWARDED_ADMIN"
            ModerationVerdict.SCAN_FAILED -> "FAILED_SAFE_FALLBACK"
        }

        if (finalVerdict.isViolation() && settings.enableAutoDelete) {
            if (job.chatId > 0L) {
                actionTaken = "FORWARDED_ADMIN"
                forwardStickerUncertainToAdmin(token, settings, job, finalReason)
            } else {
                val canDelete = checkBotCanDelete(token, job.chatId, "group", _runtimeState.value.botId)
                val deleteResult = if (!canDelete) {
                    Result.failure(IllegalStateException("Yuta AI lacks Administrator 'Delete Messages' permission in ${job.chatTitle}."))
                } else {
                    deleteTelegramMessage(token, job.chatId, job.messageId)
                }

                if (deleteResult.isFailure) {
                    actionTaken = "PERMISSION_DENIED"
                    val explanation = deleteResult.exceptionOrNull()?.message
                        ?: "Yuta AI lacks Admin 'Delete Messages' permission."
                    sendTelegramText(
                        token = token,
                        chatId = job.chatId,
                        text = "⚠️ Yuta AI detected a violating sticker, lekin is group mein message delete karne ki Admin permission ('Delete Messages') nahi mili hai.",
                        replyToMessageId = job.messageId
                    )
                    notifyAdmins(token, settings, "Permission Needed in ${job.chatTitle}: $explanation")
                } else if (settings.enableAdminNotifications) {
                    notifyAdmins(token, settings, "🗑 Deleted violating ${mediaKind.displayName} in ${job.chatTitle} from ${job.senderName}.\nReason: $finalReason")
                }
            }
        } else if (finalVerdict == ModerationVerdict.UNCERTAIN) {
            forwardStickerUncertainToAdmin(token, settings, job, finalReason)
        }

        dao.insertModerationLog(
            ModerationLogEntity(
                messageKey = job.messageKey,
                chatId = job.chatId,
                chatTitle = job.chatTitle,
                senderName = job.senderName,
                mediaKind = mediaKind.name,
                verdict = finalVerdict.name,
                confidence = decision.confidence,
                actionTaken = actionTaken,
                reason = finalReason,
                isPlaceholderAi = decision.isPlaceholder
            )
        )
        return QueueStepOutcome(isSuccess = true)
    }

    private suspend fun forwardStickerUncertainToAdmin(
        token: String,
        settings: YutaSettings,
        job: StickerQueueEntity,
        reason: String
    ) {
        if (!settings.enableAdminNotifications) return
        val alertTarget = settings.alertChatIdOrNull ?: settings.authorizedAdminIds.firstOrNull() ?: return
        if (token.isNotBlank() && job.chatId != 0L && job.messageId != 0L) {
            runCatching {
                telegramApi.forwardMessage(
                    url = TelegramApiService.botEndpoint(token, "forwardMessage"),
                    chatId = alertTarget,
                    fromChatId = job.chatId,
                    messageId = job.messageId
                )
            }
        }
        sendTelegramText(
            token = token,
            chatId = alertTarget,
            text = "🧐 Uncertain Sticker Review\n• Group: ${job.chatTitle}\n• Sender: ${job.senderName}\n• Format: ${job.stickerFormat}\n• Reason: $reason"
        )
    }

    private fun detectNonStickerMedia(message: TgMessage, settings: YutaSettings): MediaQueueTask? {
        message.photo?.lastOrNull()?.let { largestPhoto ->
            if (!settings.enableImageModeration) return null
            return MediaQueueTask(
                message = message,
                mediaKind = MediaKind.PHOTO,
                fileId = largestPhoto.fileId,
                declaredFileSize = largestPhoto.fileSize,
                mimeType = "image/jpeg"
            )
        }

        message.document?.let { doc ->
            val mime = doc.mimeType.orEmpty().lowercase()
            if (mime.startsWith("image/") && settings.enableImageModeration) {
                return MediaQueueTask(
                    message = message,
                    mediaKind = MediaKind.PHOTO,
                    fileId = doc.fileId,
                    declaredFileSize = doc.fileSize,
                    mimeType = mime
                )
            }
        }

        message.animation?.let { gif ->
            if (!settings.enableGifModeration) return null
            val targetFileId = gif.thumbnail?.fileId ?: gif.fileId
            val targetSize = gif.thumbnail?.fileSize ?: gif.fileSize
            return MediaQueueTask(
                message = message,
                mediaKind = MediaKind.GIF,
                fileId = targetFileId,
                declaredFileSize = targetSize,
                mimeType = if (gif.thumbnail != null) "image/jpeg" else (gif.mimeType ?: "video/mp4")
            )
        }

        message.video?.let { video ->
            if (!settings.enableVideoModeration) return null
            return if (video.thumbnail != null) {
                MediaQueueTask(
                    message = message,
                    mediaKind = MediaKind.VIDEO,
                    fileId = video.thumbnail.fileId,
                    declaredFileSize = video.thumbnail.fileSize,
                    mimeType = "image/jpeg",
                    fallbackNote = "Analyzed video preview thumbnail (free-tier limit avoids full video upload)"
                )
            } else {
                MediaQueueTask(
                    message = message,
                    mediaKind = MediaKind.VIDEO,
                    fileId = "",
                    declaredFileSize = video.fileSize,
                    mimeType = video.mimeType ?: "video/mp4",
                    fallbackNote = "Video has no thumbnail and exceeds zero-budget full-stream processing."
                )
            }
        }

        return null
    }

    private suspend fun processNonStickerTask(task: MediaQueueTask) {
        val settings = settingsRepository.getSnapshot()
        val token = settings.botToken.trim()
        val message = task.message
        val chatTitle = message.chat.title ?: message.chat.username ?: "Chat ${message.chat.id}"
        val senderName = message.from?.let { u ->
            listOfNotNull(u.firstName.takeIf { it.isNotBlank() }, u.username?.let { "@$it" }).joinToString(" ")
        } ?: "Unknown"

        if (task.fileId.isBlank()) {
            logAndNotifyFailure(message, task.mediaKind, task.fallbackNote ?: "No scannable thumbnail available.", settings, token)
            return
        }

        if (task.declaredFileSize != null && task.declaredFileSize > MediaProcessor.MAX_DOWNLOAD_BYTES) {
            logAndNotifyFailure(message, task.mediaKind, "File exceeds 10MB cap. Not marked safe.", settings, token)
            return
        }

        val downloadedBytes = downloadTelegramFileBytes(token, task.fileId)
        if (downloadedBytes == null) {
            logAndNotifyFailure(message, task.mediaKind, "Failed to download media safely from Telegram.", settings, token)
            return
        }

        val preparedBytes = if (task.mimeType.startsWith("video/")) {
            val ext = if (task.mimeType.contains("webm")) "webm" else "mp4"
            MediaProcessor.extractFrameFromVideoBytes(context, downloadedBytes, ext)
        } else {
            MediaProcessor.downscaleImageIfPossible(downloadedBytes)
        }

        if (preparedBytes == null || preparedBytes.isEmpty()) {
            logAndNotifyFailure(message, task.mediaKind, "Could not decode media/frame. Kept unscanned.", settings, token)
            return
        }

        val decision = aiRepository.moderateMedia(
            mediaBytes = preparedBytes,
            mimeType = "image/jpeg",
            mediaKind = task.mediaKind,
            sensitivity = settings.sensitivity
        )

        val finalVerdict = if (decision.verdict.isViolation()) ModerationVerdict.VIOLATION else decision.verdict
        val finalReason = listOfNotNull(task.fallbackNote, decision.reason).joinToString(" | ")
        var actionTaken = when (finalVerdict) {
            ModerationVerdict.SAFE -> "ALLOWED"
            ModerationVerdict.UNCERTAIN -> "FORWARDED_ADMIN"
            ModerationVerdict.SCAN_FAILED -> "FAILED_SAFE_FALLBACK"
            ModerationVerdict.VIOLATION, ModerationVerdict.SEXUAL_CONTENT -> if (settings.enableAutoDelete) "DELETED" else "FORWARDED_ADMIN"
        }

        if (finalVerdict.isViolation() && settings.enableAutoDelete) {
            val canDelete = checkBotCanDelete(token, message.chat.id, message.chat.type, _runtimeState.value.botId)
            val deleteResult = if (!canDelete) {
                Result.failure(IllegalStateException("Yuta AI lacks Admin 'Delete Messages' permission."))
            } else {
                deleteTelegramMessage(token, message.chat.id, message.messageId)
            }
            if (deleteResult.isFailure) {
                actionTaken = "PERMISSION_DENIED"
                sendTelegramText(token, message.chat.id, "⚠️ Yuta AI lacks Admin 'Delete Messages' permission in this group.", message.messageId)
            } else if (settings.enableAdminNotifications) {
                notifyAdmins(token, settings, "🗑 Deleted ${task.mediaKind.displayName} in $chatTitle from $senderName.\nReason: $finalReason")
            }
        } else if (finalVerdict == ModerationVerdict.UNCERTAIN && settings.enableAdminNotifications) {
            notifyAdmins(token, settings, "🧐 Review Requested in $chatTitle\nSender: $senderName\nMedia: ${task.mediaKind.displayName}\nReason: $finalReason")
        }

        dao.insertModerationLog(
            ModerationLogEntity(
                messageKey = "${message.chat.id}_${message.messageId}",
                chatId = message.chat.id,
                chatTitle = chatTitle,
                senderName = senderName,
                mediaKind = task.mediaKind.name,
                verdict = finalVerdict.name,
                confidence = decision.confidence,
                actionTaken = actionTaken,
                reason = finalReason,
                isPlaceholderAi = decision.isPlaceholder
            )
        )
    }

    suspend fun runTabletLocalScan(
        bytes: ByteArray,
        mimeType: String,
        mediaKind: MediaKind,
        simulatedOutcome: SimulatedOutcome = SimulatedOutcome.AUTO_SAMPLE
    ): ModerationLogEntity {
        val settings = settingsRepository.getSnapshot()
        val format = when (mediaKind) {
            MediaKind.STICKER_STATIC -> StickerQueueEntity.FORMAT_WEBP_STATIC
            MediaKind.STICKER_ANIMATED -> StickerQueueEntity.FORMAT_TGS_ANIMATED
            MediaKind.STICKER_VIDEO -> StickerQueueEntity.FORMAT_WEBM_VIDEO
            else -> ""
        }

        val preparedBytes: ByteArray?
        val prepNote: String
        var forceUncertain = false

        if (format.isNotEmpty()) {
            val prep = MediaProcessor.prepareStickerFrame(context, bytes, mimeType, format)
            preparedBytes = prep.jpegBytes
            prepNote = prep.inspectionNote
            forceUncertain = prep.requiresManualReview
        } else {
            val resized = MediaProcessor.downscaleImageIfPossible(bytes)
            preparedBytes = resized.takeIf { it.isNotEmpty() }
            prepNote = if (preparedBytes != null) "Image prepared for scan." else "Image decode failed."
        }

        val entry = when {
            forceUncertain -> ModerationLogEntity(
                messageKey = "tablet_scan_${System.currentTimeMillis()}",
                chatId = 0L,
                chatTitle = "Tablet Sticker Test",
                senderName = "Admin Tablet",
                mediaKind = mediaKind.name,
                verdict = ModerationVerdict.UNCERTAIN.name,
                confidence = 0.4f,
                actionTaken = "FORWARDED_ADMIN",
                reason = prepNote,
                isPlaceholderAi = aiRepository.getServiceStatus().requiresFirebaseSetup
            )
            preparedBytes == null || preparedBytes.isEmpty() -> ModerationLogEntity(
                messageKey = "tablet_scan_${System.currentTimeMillis()}",
                chatId = 0L,
                chatTitle = "Tablet Sticker Test",
                senderName = "Admin Tablet",
                mediaKind = mediaKind.name,
                verdict = ModerationVerdict.SCAN_FAILED.name,
                confidence = 0f,
                actionTaken = "FAILED_SAFE_FALLBACK",
                reason = "$prepNote (Kept unscanned; NEVER marked SAFE).",
                isPlaceholderAi = aiRepository.getServiceStatus().requiresFirebaseSetup
            )
            else -> {
                val decision = aiRepository.moderateMedia(
                    mediaBytes = preparedBytes,
                    mimeType = "image/jpeg",
                    mediaKind = mediaKind,
                    sensitivity = settings.sensitivity,
                    simulatedOutcome = simulatedOutcome
                )
                val finalVerdict = if (decision.verdict.isViolation()) ModerationVerdict.VIOLATION else decision.verdict
                val actionTaken = when (finalVerdict) {
                    ModerationVerdict.SAFE -> "ALLOWED"
                    ModerationVerdict.VIOLATION, ModerationVerdict.SEXUAL_CONTENT -> if (settings.enableAutoDelete) "DELETED" else "FORWARDED_ADMIN"
                    ModerationVerdict.UNCERTAIN -> "FORWARDED_ADMIN"
                    ModerationVerdict.SCAN_FAILED -> "FAILED_SAFE_FALLBACK"
                }
                ModerationLogEntity(
                    messageKey = "tablet_scan_${System.currentTimeMillis()}",
                    chatId = 0L,
                    chatTitle = "Tablet Scanner",
                    senderName = "Admin Tablet",
                    mediaKind = mediaKind.name,
                    verdict = finalVerdict.name,
                    confidence = decision.confidence,
                    actionTaken = actionTaken,
                    reason = listOf(prepNote, decision.reason).joinToString(" | "),
                    isPlaceholderAi = decision.isPlaceholder
                )
            }
        }
        dao.insertModerationLog(entry)
        return entry
    }

    suspend fun executeCommand(
        rawCommand: String,
        senderUserId: Long? = null,
        chatId: Long? = null,
        chatType: String = "private",
        token: String = "",
        settings: YutaSettings? = null,
        botId: Long? = _runtimeState.value.botId
    ): String {
        val activeSettings = settings ?: settingsRepository.getSnapshot()
        val commandParts = rawCommand.trim().split("\\s+".toRegex(), limit = 2)
        val cmd = commandParts[0].substringBefore("@").lowercase()
        val arg = commandParts.getOrNull(1).orEmpty().trim()
        val stats = dao.observeStats().first()
        val stickerStats = dao.observeStickerScannerStats().first()
        val aiStatus = aiRepository.getServiceStatus()

        return when (cmd) {
            "/start" -> buildString {
                appendLine("🤖 Yuta AI Active (24/7 Sticker Guard)!")
                appendLine("Supported Stickers: Static WEBP, Animated TGS (Preview), Video WEBM.")
                appendLine("Commands ke liye /help bhejein.")
            }.trim()

            "/help" -> buildString {
                appendLine("📋 Yuta AI Commands:")
                appendLine("/start - Bot start karein")
                appendLine("/help - Sabhi commands dekhein")
                appendLine("/status - Bot aur 24/7 Sticker Scanner status")
                appendLine("/scan - Sticker aur media statistics dekhein")
                appendLine("/settings - Moderation settings (Admin only)")
                appendLine("/chat <sawal> - Hindi/English AI se baat karein")
                appendLine("/about - Yuta AI aur free-tier limits jaanein")
            }.trim()

            "/status" -> {
                val groupDeleteStatus = if (chatId != null && token.isNotBlank() && (chatType == "group" || chatType == "supergroup")) {
                    val canDel = checkBotCanDelete(token, chatId, chatType, botId)
                    if (canDel) "Granted (Admin)" else "MISSING ('Delete Messages' required)"
                } else {
                    "N/A (Private/Local)"
                }
                buildString {
                    appendLine("📡 Yuta AI Live Status:")
                    appendLine("• Telegram Connection: ${_runtimeState.value.status.label}")
                    appendLine("• Pending Stickers in Queue: ${stickerStats.pendingScans}")
                    appendLine("• Failed Sticker Scans: ${stickerStats.failedScans}")
                    appendLine("• Group Delete Permission: $groupDeleteStatus")
                    appendLine("• AI Engine: ${aiStatus.modelLabel}")
                    append("• Auto-Delete: ${if (activeSettings.enableAutoDelete) "ON" else "OFF"}")
                }.trim()
            }

            "/scan" -> buildString {
                appendLine("📊 24/7 Sticker Scanner Stats:")
                appendLine("• Stickers Received: ${stickerStats.totalStickersReceived}")
                appendLine("• Stickers Scanned: ${stickerStats.totalStickersScanned}")
                appendLine("• Sticker Violations: ${stickerStats.violationsDetected}")
                appendLine("• Stickers Deleted: ${stickerStats.messagesDeleted}")
                appendLine("• Pending / Failed: ${stickerStats.pendingScans} / ${stickerStats.failedScans}")
                append("• Total Photos & GIFs: ${stats.totalImagesScanned + stats.totalVideosAndGifsScanned}")
            }.trim()

            "/settings" -> {
                val isAuthorized = isAuthorizedAdmin(senderUserId, chatId, chatType, token, activeSettings)
                if (!isAuthorized) {
                    "🔒 Unauthorized: Keval authorized group admins hi /settings dekh aur badal sakte hain."
                } else {
                    buildString {
                        appendLine("⚙️ Yuta AI Moderation Settings:")
                        appendLine("• Sticker Moderation: ${onOff(activeSettings.enableStickerModeration)}")
                        appendLine("• Image Moderation: ${onOff(activeSettings.enableImageModeration)}")
                        appendLine("• GIF Moderation: ${onOff(activeSettings.enableGifModeration)}")
                        appendLine("• AI Chat (/chat): ${onOff(activeSettings.enableAiChat)}")
                        appendLine("• Auto-Delete Violations: ${onOff(activeSettings.enableAutoDelete)}")
                        append("• Sensitivity: ${activeSettings.sensitivity.label}")
                    }.trim()
                }
            }

            "/chat" -> {
                if (!activeSettings.enableAiChat) {
                    "🚫 AI Chat abhi Admin dwara band kiya gaya hai."
                } else if (arg.isBlank()) {
                    "💬 Kripya command ke baad apna sawal likhein."
                } else {
                    var finalReply = ""
                    var isPlaceholder = true
                    aiRepository.streamChatResponse(arg).collect { event ->
                        if (event is ChatStreamEvent.Completed) {
                            finalReply = event.fullText
                            isPlaceholder = event.isPlaceholder
                        } else if (event is ChatStreamEvent.Error) {
                            finalReply = event.message
                        }
                    }
                    dao.insertChatMessage(ChatMessageEntity(role = "user", content = arg, source = "TELEGRAM", isPlaceholderAi = false))
                    dao.insertChatMessage(ChatMessageEntity(role = "model", content = finalReply, source = "TELEGRAM", isPlaceholderAi = isPlaceholder))
                    finalReply
                }
            }

            "/about" -> buildString {
                appendLine("🛡 Yuta AI — Zero-Budget 24/7 Sticker Moderation Bot")
                appendLine("• Supports Static WEBP, Animated TGS (Preview), Video WEBM.")
                appendLine("• Free limits: Max 10MB; .tgs without thumbnail routes to UNCERTAIN Admin Review.")
            }.trim()

            else -> "❓ Unknown command. Available commands ke liye /help bhejein."
        }
    }

    private suspend fun checkBotCanDelete(
        token: String,
        chatId: Long,
        chatType: String,
        botId: Long?
    ): Boolean {
        if (chatType == "private" || chatId > 0L) return false
        if (token.isBlank()) return false
        val resolvedBotId = botId ?: runCatching {
            val meResp = telegramApi.getMe(TelegramApiService.botEndpoint(token, "getMe"))
            val me = meResp.body()?.result
            if (me != null) {
                _runtimeState.value = _runtimeState.value.copy(
                    botId = me.id,
                    botUsername = me.username ?: _runtimeState.value.botUsername
                )
            }
            me?.id
        }.getOrNull() ?: return false

        return runCatching {
            val resp = telegramApi.getChatMember(
                url = TelegramApiService.botEndpoint(token, "getChatMember"),
                chatId = chatId,
                userId = resolvedBotId
            )
            if (!resp.isSuccessful || resp.body()?.ok != true) return@runCatching false
            val member = resp.body()?.result
            member?.status == "creator" || (member?.status == "administrator" && member.canDeleteMessages == true)
        }.getOrDefault(false)
    }

    private suspend fun isAuthorizedAdmin(
        senderUserId: Long?,
        chatId: Long?,
        chatType: String,
        token: String,
        settings: YutaSettings
    ): Boolean {
        if (senderUserId == null) return true
        if (senderUserId in settings.authorizedAdminIds) return true
        if (chatType == "private" && settings.authorizedAdminIds.isEmpty()) return true
        if (chatId != null && token.isNotBlank() && (chatType == "group" || chatType == "supergroup")) {
            return runCatching {
                val resp = telegramApi.getChatMember(
                    url = TelegramApiService.botEndpoint(token, "getChatMember"),
                    chatId = chatId,
                    userId = senderUserId
                )
                val status = resp.body()?.result?.status
                status == "creator" || status == "administrator"
            }.getOrDefault(false)
        }
        return false
    }

    private suspend fun downloadTelegramFileBytes(token: String, fileId: String): ByteArray? {
        return try {
            val fileResp = telegramApi.getFile(TelegramApiService.botEndpoint(token, "getFile"), fileId)
            val tgFile = fileResp.body()?.result ?: return null
            val path = tgFile.filePath ?: return null
            if ((tgFile.fileSize ?: 0L) > MediaProcessor.MAX_DOWNLOAD_BYTES) return null

            val dlResp = telegramApi.downloadFile(TelegramApiService.fileDownloadUrl(token, path))
            val body = dlResp.body() ?: return null
            body.byteStream().use { stream ->
                MediaProcessor.readStreamWithLimit(stream, MediaProcessor.MAX_DOWNLOAD_BYTES)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun sendTelegramText(
        token: String,
        chatId: Long,
        text: String,
        replyToMessageId: Long? = null
    ) {
        if (token.isBlank()) return
        runCatching {
            telegramApi.sendMessage(
                url = TelegramApiService.botEndpoint(token, "sendMessage"),
                chatId = chatId,
                text = text.take(4000),
                replyToMessageId = replyToMessageId
            )
        }
    }

    private suspend fun deleteTelegramMessage(
        token: String,
        chatId: Long,
        messageId: Long
    ): Result<Unit> {
        return try {
            val resp = telegramApi.deleteMessage(
                url = TelegramApiService.botEndpoint(token, "deleteMessage"),
                chatId = chatId,
                messageId = messageId
            )
            val body = resp.body()
            if (resp.isSuccessful && body?.ok == true && body.result == true) {
                Result.success(Unit)
            } else {
                val errDesc = body?.description ?: TelegramApiService.parseErrorBody(resp.errorBody())?.description
                val desc = TokenCrypto.redactSecrets(
                    errDesc ?: "Telegram refused deletion (HTTP ${resp.code()}).",
                    token
                )
                Result.failure(IllegalStateException(desc))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException(TokenCrypto.redactSecrets(e.localizedMessage, token)))
        }
    }

    private suspend fun notifyAdmins(token: String, settings: YutaSettings, message: String) {
        if (!settings.enableAdminNotifications) return
        val alertTarget = settings.alertChatIdOrNull ?: settings.authorizedAdminIds.firstOrNull() ?: return
        sendTelegramText(token, alertTarget, message)
    }

    private suspend fun logAndNotifyStickerFailure(
        job: StickerQueueEntity,
        reason: String,
        settings: YutaSettings,
        token: String
    ) {
        val mediaKind = when (job.stickerFormat) {
            StickerQueueEntity.FORMAT_WEBM_VIDEO -> MediaKind.STICKER_VIDEO
            StickerQueueEntity.FORMAT_TGS_ANIMATED -> MediaKind.STICKER_ANIMATED
            else -> MediaKind.STICKER_STATIC
        }
        notifyAdmins(token, settings, "⚠️ Sticker Scan Failed in ${job.chatTitle} (${job.stickerFormat}). NEVER marked safe.\nReason: $reason")
        dao.insertModerationLog(
            ModerationLogEntity(
                messageKey = job.messageKey,
                chatId = job.chatId,
                chatTitle = job.chatTitle,
                senderName = job.senderName,
                mediaKind = mediaKind.name,
                verdict = ModerationVerdict.SCAN_FAILED.name,
                confidence = 0f,
                actionTaken = "FAILED_SAFE_FALLBACK",
                reason = reason,
                isPlaceholderAi = aiRepository.getServiceStatus().requiresFirebaseSetup
            )
        )
    }

    private suspend fun logAndNotifyFailure(
        message: TgMessage,
        mediaKind: MediaKind,
        reason: String,
        settings: YutaSettings,
        token: String
    ) {
        val chatTitle = message.chat.title ?: message.chat.username ?: "Chat ${message.chat.id}"
        val senderName = message.from?.firstName ?: "Unknown"
        notifyAdmins(token, settings, "⚠️ Yuta AI Safe Fallback in $chatTitle: $reason")
        dao.insertModerationLog(
            ModerationLogEntity(
                messageKey = "${message.chat.id}_${message.messageId}",
                chatId = message.chat.id,
                chatTitle = chatTitle,
                senderName = senderName,
                mediaKind = mediaKind.name,
                verdict = ModerationVerdict.SCAN_FAILED.name,
                confidence = 0f,
                actionTaken = "FAILED_SAFE_FALLBACK",
                reason = reason,
                isPlaceholderAi = aiRepository.getServiceStatus().requiresFirebaseSetup
            )
        )
    }

    private fun hasNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun onOff(enabled: Boolean): String = if (enabled) "ENABLED" else "DISABLED"
}
