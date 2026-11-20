package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.AiRepository
import com.example.data.ai.AiServiceStatus
import com.example.data.ai.ChatStreamEvent
import com.example.data.ai.MediaKind
import com.example.data.ai.ModerationDecision
import com.example.data.ai.ModerationSensitivity
import com.example.data.ai.ModerationVerdict
import com.example.data.ai.SimulatedOutcome
import com.example.data.local.StickerQueueEntity
import com.example.data.local.YutaDatabase
import com.example.data.local.YutaSettings
import com.example.data.local.YutaSettingsRepository
import com.example.data.telegram.MediaProcessor
import com.example.data.telegram.TelegramApiService
import com.example.data.telegram.TgChat
import com.example.data.telegram.TgMessage
import com.example.data.telegram.TgSticker
import com.example.data.telegram.TgUser
import com.example.data.telegram.YutaBotEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StickerModerationTest {

    private lateinit var context: Context
    private lateinit var database: YutaDatabase
    private lateinit var settingsRepository: YutaSettingsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, YutaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsRepository = YutaSettingsRepository(context)
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createTinyValidPngBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    private fun createGzipBytes(content: String): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gzip ->
                gzip.write(content.toByteArray(Charsets.UTF_8))
            }
            baos.toByteArray()
        }
    }

    @Test
    fun `duplicate sticker message is deduplicated and not counted twice`() = runTest {
        val engine = YutaBotEngine(
            context = context,
            telegramApi = TelegramApiService.create(),
            aiRepository = object : AiRepository {
                override fun getServiceStatus() = AiServiceStatus(true, false, "Mock", "OK")
                override suspend fun moderateMedia(
                    mediaBytes: ByteArray,
                    mimeType: String,
                    mediaKind: MediaKind,
                    sensitivity: ModerationSensitivity,
                    simulatedOutcome: SimulatedOutcome
                ) = ModerationDecision(ModerationVerdict.SAFE, 0.99f, "Clean", false)
                override fun streamChatResponse(userPrompt: String, recentHistoryPairs: List<Pair<String, String>>): Flow<ChatStreamEvent> = flowOf()
            },
            dao = database.yutaDao(),
            settingsRepository = settingsRepository
        )

        val settings = YutaSettings(botToken = "123:ABC", enableStickerModeration = true)
        val msg = TgMessage(
            messageId = 42L,
            from = TgUser(id = 99L, firstName = "Alice"),
            chat = TgChat(id = -100123L, type = "supergroup", title = "Test Group"),
            sticker = TgSticker(fileId = "stk_file_1", isAnimated = false, isVideo = false)
        )

        // Send identical message twice
        engine.enqueueIncomingMessageForTest(msg, updateId = 1L, settings = settings, token = "123:ABC", botId = 1L)
        engine.enqueueIncomingMessageForTest(msg, updateId = 2L, settings = settings, token = "123:ABC", botId = 1L)

        val stats = database.yutaDao().observeStickerScannerStats().first()
        assertEquals(1, stats.totalStickersReceived)
        assertEquals(1, stats.pendingScans)
    }

    @Test
    fun `unscanned tgs without thumbnail is never marked SAFE and routes to UNCERTAIN`() = runTest {
        val fakeTgs = createGzipBytes("""{"v":"5.7.4","layers":[{"nm":"layer1"}]}""")
        val prep = MediaProcessor.prepareStickerFrame(
            context = context,
            rawBytes = fakeTgs,
            mimeType = "application/x-tgsticker",
            stickerFormat = StickerQueueEntity.FORMAT_TGS_ANIMATED
        )

        assertNull(prep.jpegBytes)
        assertTrue(prep.requiresManualReview)
        assertTrue(prep.inspectionNote.contains("UNCERTAIN"))

        val engine = YutaBotEngine(
            context = context,
            telegramApi = TelegramApiService.create(),
            aiRepository = object : AiRepository {
                override fun getServiceStatus() = AiServiceStatus(true, false, "Mock", "OK")
                override suspend fun moderateMedia(
                    mediaBytes: ByteArray,
                    mimeType: String,
                    mediaKind: MediaKind,
                    sensitivity: ModerationSensitivity,
                    simulatedOutcome: SimulatedOutcome
                ) = ModerationDecision(ModerationVerdict.SAFE, 1.0f, "Should not run", false)
                override fun streamChatResponse(userPrompt: String, recentHistoryPairs: List<Pair<String, String>>): Flow<ChatStreamEvent> = flowOf()
            },
            dao = database.yutaDao(),
            settingsRepository = settingsRepository
        )

        val log = engine.runTabletLocalScan(
            bytes = fakeTgs,
            mimeType = "application/x-tgsticker",
            mediaKind = MediaKind.STICKER_ANIMATED
        )
        assertNotEquals(ModerationVerdict.SAFE.name, log.verdict)
        assertEquals(ModerationVerdict.UNCERTAIN.name, log.verdict)
        assertEquals("FORWARDED_ADMIN", log.actionTaken)
    }

    @Test
    fun `static sticker violation checks delete permission and tracks stats cleanly`() = runTest {
        val validBytes = createTinyValidPngBytes()
        var deletedMessageId: Long? = null

        val mockClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url.toString()
            when {
                url.contains("/getMe") -> Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"ok":true,"result":{"id":1,"is_bot":true,"first_name":"Yuta","username":"yuta_bot"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
                url.contains("/getFile") -> Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"ok":true,"result":{"file_id":"stk_bad","file_path":"stickers/bad.webp","file_size":${validBytes.size}}}""".toResponseBody("application/json".toMediaType()))
                    .build()
                url.contains("/file/bot") -> Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(validBytes.toResponseBody("image/webp".toMediaType()))
                    .build()
                url.contains("/getChatMember") -> Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"ok":true,"result":{"status":"administrator","can_delete_messages":true}}""".toResponseBody("application/json".toMediaType()))
                    .build()
                url.contains("/deleteMessage") -> {
                    deletedMessageId = 777L
                    Response.Builder()
                        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"ok":true,"result":true}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                else -> Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"ok":true,"result":{}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }).build()

        settingsRepository.saveTelegramCredentials("123:ABC", "", "")
        settingsRepository.updateModerationToggles(enableSticker = true, enableAutoDelete = true)

        val engine = YutaBotEngine(
            context = context,
            telegramApi = TelegramApiService.create(mockClient),
            aiRepository = object : AiRepository {
                override fun getServiceStatus() = AiServiceStatus(true, false, "Gemini", "OK")
                override suspend fun moderateMedia(
                    mediaBytes: ByteArray,
                    mimeType: String,
                    mediaKind: MediaKind,
                    sensitivity: ModerationSensitivity,
                    simulatedOutcome: SimulatedOutcome
                ) = ModerationDecision(ModerationVerdict.VIOLATION, 0.98f, "Explicit sticker", false)
                override fun streamChatResponse(userPrompt: String, recentHistoryPairs: List<Pair<String, String>>): Flow<ChatStreamEvent> = flowOf()
            },
            dao = database.yutaDao(),
            settingsRepository = settingsRepository
        )

        database.yutaDao().enqueueSticker(
            StickerQueueEntity(
                messageKey = "-1001_777",
                chatId = -1001L,
                chatTitle = "Group A",
                senderName = "Bob",
                messageId = 777L,
                stickerFormat = StickerQueueEntity.FORMAT_WEBP_STATIC,
                fileId = "stk_bad",
                nextAttemptAt = 0L
            )
        )

        val processed = engine.processNextStickerBatch(now = System.currentTimeMillis() + 1000L)
        assertEquals(1, processed)
        assertEquals(777L, deletedMessageId)

        val stats = database.yutaDao().observeStickerScannerStats().first()
        assertEquals(1, stats.totalStickersReceived)
        assertEquals(1, stats.totalStickersScanned)
        assertEquals(1, stats.violationsDetected)
        assertEquals(1, stats.messagesDeleted)
        assertEquals(0, stats.pendingScans)
        assertEquals(0, stats.failedScans)
    }

    @Test
    fun `exhausted retries mark failed scan and NEVER mark unscanned sticker safe`() = runTest {
        val mockClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(500).message("Server Error")
                .body("""{"ok":false,"description":"Telegram temporary outage"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }).build()

        settingsRepository.saveTelegramCredentials("123:ABC", "", "")

        val engine = YutaBotEngine(
            context = context,
            telegramApi = TelegramApiService.create(mockClient),
            aiRepository = object : AiRepository {
                override fun getServiceStatus() = AiServiceStatus(true, false, "Gemini", "OK")
                override suspend fun moderateMedia(
                    mediaBytes: ByteArray,
                    mimeType: String,
                    mediaKind: MediaKind,
                    sensitivity: ModerationSensitivity,
                    simulatedOutcome: SimulatedOutcome
                ) = ModerationDecision(ModerationVerdict.SAFE, 1.0f, "Safe", false)
                override fun streamChatResponse(userPrompt: String, recentHistoryPairs: List<Pair<String, String>>): Flow<ChatStreamEvent> = flowOf()
            },
            dao = database.yutaDao(),
            settingsRepository = settingsRepository
        )

        database.yutaDao().enqueueSticker(
            StickerQueueEntity(
                messageKey = "-1002_888",
                chatId = -1002L,
                chatTitle = "Group B",
                senderName = "Eve",
                messageId = 888L,
                stickerFormat = StickerQueueEntity.FORMAT_WEBP_STATIC,
                fileId = "stk_broken",
                retryCount = 2,
                maxRetries = 3,
                nextAttemptAt = 0L
            )
        )

        engine.processNextStickerBatch(now = System.currentTimeMillis() + 1000L)

        val stats = database.yutaDao().observeStickerScannerStats().first()
        assertEquals(1, stats.totalStickersReceived)
        assertEquals(0, stats.totalStickersScanned)
        assertEquals(0, stats.pendingScans)
        assertEquals(1, stats.failedScans)

        val logs = database.yutaDao().observeModerationLogs().first()
        assertEquals(1, logs.size)
        assertEquals(ModerationVerdict.SCAN_FAILED.name, logs.first().verdict)
        assertNotEquals(ModerationVerdict.SAFE.name, logs.first().verdict)
    }

    @Test
    fun `missing delete permission logs PERMISSION_DENIED and skips deleteMessage`() = runTest {
        val validBytes = createTinyValidPngBytes()
        var deleteCalled = false

        val mockClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url.toString()
            when {
                url.contains("/getMe") -> Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"ok":true,"result":{"id":2,"is_bot":true,"first_name":"Yuta"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
                url.contains("/getFile") -> Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"ok":true,"result":{"file_id":"stk_noperm","file_path":"stickers/bad2.webp","file_size":${validBytes.size}}}""".toResponseBody("application/json".toMediaType()))
                    .build()
                url.contains("/file/bot") -> Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(validBytes.toResponseBody("image/webp".toMediaType()))
                    .build()
                url.contains("/getChatMember") -> Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"ok":true,"result":{"status":"member","can_delete_messages":false}}""".toResponseBody("application/json".toMediaType()))
                    .build()
                url.contains("/deleteMessage") -> {
                    deleteCalled = true
                    Response.Builder()
                        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"ok":true,"result":true}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                else -> Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"ok":true,"result":{}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }).build()

        settingsRepository.saveTelegramCredentials("123:ABC", "", "")
        settingsRepository.updateModerationToggles(enableSticker = true, enableAutoDelete = true)

        val engine = YutaBotEngine(
            context = context,
            telegramApi = TelegramApiService.create(mockClient),
            aiRepository = object : AiRepository {
                override fun getServiceStatus() = AiServiceStatus(true, false, "Gemini", "OK")
                override suspend fun moderateMedia(
                    mediaBytes: ByteArray,
                    mimeType: String,
                    mediaKind: MediaKind,
                    sensitivity: ModerationSensitivity,
                    simulatedOutcome: SimulatedOutcome
                ) = ModerationDecision(ModerationVerdict.VIOLATION, 0.99f, "Explicit sticker", false)
                override fun streamChatResponse(userPrompt: String, recentHistoryPairs: List<Pair<String, String>>): Flow<ChatStreamEvent> = flowOf()
            },
            dao = database.yutaDao(),
            settingsRepository = settingsRepository
        )

        database.yutaDao().enqueueSticker(
            StickerQueueEntity(
                messageKey = "-1003_999",
                chatId = -1003L,
                chatTitle = "Group C",
                senderName = "Carl",
                messageId = 999L,
                stickerFormat = StickerQueueEntity.FORMAT_WEBP_STATIC,
                fileId = "stk_noperm",
                nextAttemptAt = 0L
            )
        )

        engine.processNextStickerBatch(now = System.currentTimeMillis() + 1000L)
        assertEquals(false, deleteCalled)

        val logs = database.yutaDao().observeModerationLogs().first()
        assertEquals("PERMISSION_DENIED", logs.first().actionTaken)
        val stats = database.yutaDao().observeStickerScannerStats().first()
        assertEquals(0, stats.messagesDeleted)
        assertEquals(1, stats.violationsDetected)
    }

    @Test
    fun `transparent bitmap prepares clean JPEG and UNION counts local plus queued stickers`() = runTest {
        val rawPng = createTinyValidPngBytes()
        val prep = MediaProcessor.prepareStickerFrame(
            context = context,
            rawBytes = rawPng,
            mimeType = "image/webp",
            stickerFormat = StickerQueueEntity.FORMAT_WEBP_STATIC
        )
        val jpeg = prep.jpegBytes!!
        assertTrue(jpeg.size > 2)
        // Standard JPEG SOI marker FF D8
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])

        // Verify UNION count across sticker_queue + tablet local log + shared messageKey
        database.yutaDao().enqueueSticker(
            StickerQueueEntity(
                messageKey = "shared_key_1",
                chatId = -1004L,
                chatTitle = "Group D",
                senderName = "Dan",
                messageId = 10L,
                stickerFormat = StickerQueueEntity.FORMAT_WEBP_STATIC,
                fileId = "f1"
            )
        )
        database.yutaDao().insertModerationLog(
            com.example.data.local.ModerationLogEntity(
                messageKey = "shared_key_1",
                chatId = -1004L,
                chatTitle = "Group D",
                senderName = "Dan",
                mediaKind = MediaKind.STICKER_STATIC.name,
                verdict = ModerationVerdict.SAFE.name,
                confidence = 0.9f,
                actionTaken = "ALLOWED",
                reason = "Clean",
                isPlaceholderAi = false
            )
        )
        database.yutaDao().insertModerationLog(
            com.example.data.local.ModerationLogEntity(
                messageKey = "tablet_only_2",
                chatId = 0L,
                chatTitle = "Tablet Scanner",
                senderName = "Admin",
                mediaKind = MediaKind.STICKER_STATIC.name,
                verdict = ModerationVerdict.SAFE.name,
                confidence = 0.95f,
                actionTaken = "ALLOWED",
                reason = "Clean local sticker",
                isPlaceholderAi = false
            )
        )

        val stats = database.yutaDao().observeStickerScannerStats().first()
        assertEquals(2, stats.totalStickersReceived)
        assertEquals(2, stats.totalStickersScanned)
    }
}
