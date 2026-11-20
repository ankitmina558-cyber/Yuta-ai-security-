package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "moderation_logs")
data class ModerationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageKey: String,
    val chatId: Long,
    val chatTitle: String,
    val senderName: String,
    val mediaKind: String,
    val verdict: String,
    val confidence: Float,
    val actionTaken: String,
    val reason: String,
    val isPlaceholderAi: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "processed_messages")
data class ProcessedMessageEntity(
    @PrimaryKey val messageKey: String,
    val updateId: Long,
    val processedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sticker_queue")
data class StickerQueueEntity(
    @PrimaryKey val messageKey: String,
    val chatId: Long,
    val chatTitle: String,
    val senderName: String,
    val messageId: Long,
    val stickerFormat: String, // WEBP_STATIC | TGS_ANIMATED | WEBM_VIDEO
    val fileId: String,
    val thumbnailFileId: String = "",
    val stickerSetName: String = "",
    val stickerEmoji: String = "",
    val declaredFileSize: Long? = null,
    val mimeType: String = "image/webp",
    val fallbackNote: String? = null,
    val status: String = STATUS_PENDING, // PENDING | PROCESSING | DONE | FAILED
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val lastError: String = "",
    val nextAttemptAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_PROCESSING = "PROCESSING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"

        const val FORMAT_WEBP_STATIC = "WEBP_STATIC"
        const val FORMAT_TGS_ANIMATED = "TGS_ANIMATED"
        const val FORMAT_WEBM_VIDEO = "WEBM_VIDEO"
    }
}

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user" or "model"
    val content: String,
    val source: String = "APP", // "APP" or "TELEGRAM"
    val isPlaceholderAi: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

data class ModerationStats(
    val totalImagesScanned: Int = 0,
    val totalStickersScanned: Int = 0,
    val totalVideosAndGifsScanned: Int = 0,
    val totalViolationsDetected: Int = 0,
    val totalMessagesDeleted: Int = 0,
    val totalUncertainOrFailed: Int = 0
)

data class StickerScannerStats(
    val totalStickersReceived: Int = 0,
    val totalStickersScanned: Int = 0,
    val violationsDetected: Int = 0,
    val messagesDeleted: Int = 0,
    val pendingScans: Int = 0,
    val failedScans: Int = 0
)
