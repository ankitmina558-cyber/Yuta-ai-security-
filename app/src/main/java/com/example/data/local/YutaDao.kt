package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface YutaDao {

    @Query("SELECT * FROM moderation_logs ORDER BY createdAt DESC LIMIT 100")
    fun observeModerationLogs(): Flow<List<ModerationLogEntity>>

    @Query(
        """
        SELECT 
            COALESCE(SUM(CASE WHEN mediaKind IN ('PHOTO', 'LOCAL_TEST') THEN 1 ELSE 0 END), 0) AS totalImagesScanned,
            COALESCE(SUM(CASE WHEN mediaKind IN ('STICKER_STATIC', 'STICKER_ANIMATED', 'STICKER_VIDEO') AND verdict != 'SCAN_FAILED' THEN 1 ELSE 0 END), 0) AS totalStickersScanned,
            COALESCE(SUM(CASE WHEN mediaKind IN ('GIF', 'VIDEO') THEN 1 ELSE 0 END), 0) AS totalVideosAndGifsScanned,
            COALESCE(SUM(CASE WHEN verdict IN ('VIOLATION', 'SEXUAL_CONTENT') THEN 1 ELSE 0 END), 0) AS totalViolationsDetected,
            COALESCE(SUM(CASE WHEN actionTaken = 'DELETED' THEN 1 ELSE 0 END), 0) AS totalMessagesDeleted,
            COALESCE(SUM(CASE WHEN verdict IN ('UNCERTAIN', 'SCAN_FAILED') THEN 1 ELSE 0 END), 0) AS totalUncertainOrFailed
        FROM moderation_logs
        """
    )
    fun observeStats(): Flow<ModerationStats>

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM (
                SELECT messageKey FROM sticker_queue
                UNION
                SELECT messageKey FROM moderation_logs WHERE mediaKind LIKE 'STICKER%'
            )) AS totalStickersReceived,
            (SELECT COALESCE(SUM(CASE WHEN mediaKind LIKE 'STICKER%' AND verdict != 'SCAN_FAILED' THEN 1 ELSE 0 END), 0) FROM moderation_logs) AS totalStickersScanned,
            (SELECT COALESCE(SUM(CASE WHEN mediaKind LIKE 'STICKER%' AND verdict IN ('VIOLATION', 'SEXUAL_CONTENT') THEN 1 ELSE 0 END), 0) FROM moderation_logs) AS violationsDetected,
            (SELECT COALESCE(SUM(CASE WHEN mediaKind LIKE 'STICKER%' AND actionTaken = 'DELETED' THEN 1 ELSE 0 END), 0) FROM moderation_logs) AS messagesDeleted,
            (SELECT COALESCE(SUM(CASE WHEN status IN ('PENDING', 'PROCESSING') THEN 1 ELSE 0 END), 0) FROM sticker_queue) AS pendingScans,
            (SELECT COUNT(*) FROM (
                SELECT messageKey FROM sticker_queue WHERE status = 'FAILED'
                UNION
                SELECT messageKey FROM moderation_logs WHERE mediaKind LIKE 'STICKER%' AND verdict = 'SCAN_FAILED'
            )) AS failedScans
        """
    )
    fun observeStickerScannerStats(): Flow<StickerScannerStats>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModerationLog(log: ModerationLogEntity)

    @Query("DELETE FROM moderation_logs")
    suspend fun clearModerationLogs()

    @Query("SELECT EXISTS(SELECT 1 FROM processed_messages WHERE messageKey = :messageKey)")
    suspend fun isMessageProcessed(messageKey: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProcessedMessage(entity: ProcessedMessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueSticker(job: StickerQueueEntity): Long

    @Query("SELECT * FROM sticker_queue WHERE status = 'PENDING' AND nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getRunnableStickerJobs(now: Long, limit: Int = 10): List<StickerQueueEntity>

    @Query("UPDATE sticker_queue SET status = 'PENDING', nextAttemptAt = :now, updatedAt = :now WHERE status = 'PROCESSING'")
    suspend fun recoverInterruptedStickerJobs(now: Long)

    @Query("UPDATE sticker_queue SET status = 'PENDING', retryCount = 0, lastError = '', nextAttemptAt = :now, updatedAt = :now WHERE status = 'FAILED'")
    suspend fun retryFailedStickerJobs(now: Long)

    @Query("UPDATE sticker_queue SET status = :status, retryCount = :retryCount, lastError = :lastError, nextAttemptAt = :nextAttemptAt, updatedAt = :updatedAt WHERE messageKey = :messageKey")
    suspend fun updateStickerJobState(
        messageKey: String,
        status: String,
        retryCount: Int,
        lastError: String,
        nextAttemptAt: Long,
        updatedAt: Long
    )

    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC")
    fun observeChatMessages(): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages")
    suspend fun clearChatMessages()
}
