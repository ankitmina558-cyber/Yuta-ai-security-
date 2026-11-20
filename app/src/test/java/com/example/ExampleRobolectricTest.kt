package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.ChatStreamEvent
import com.example.data.ai.MediaKind
import com.example.data.ai.ModerationSensitivity
import com.example.data.ai.ModerationVerdict
import com.example.data.ai.SharedBackendAiRepository
import com.example.data.local.YutaDatabase
import com.example.data.local.YutaSettingsRepository
import com.example.data.remote.YutaBackendApiService
import com.example.data.telegram.BotConnectionStatus
import com.example.data.telegram.TelegramApiService
import com.example.data.telegram.YutaBotEngine
import com.example.viewmodel.YutaViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Yuta AI", appName)
    }

    @Test
    fun `shared backend ai offline never fakes SAFE verdict or chat reply`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = YutaSettingsRepository(context)
        settingsRepo.saveSharedBackendConfig("", "")
        val repo = SharedBackendAiRepository(settingsRepo, YutaBackendApiService.create())

        val decision = repo.moderateMedia(
            mediaBytes = byteArrayOf(1, 2, 3, 4),
            mimeType = "image/jpeg",
            mediaKind = MediaKind.STICKER_STATIC,
            sensitivity = ModerationSensitivity.BALANCED
        )
        assertEquals(ModerationVerdict.SCAN_FAILED, decision.verdict)
        assertFalse(decision.isPlaceholder)
        assertTrue(decision.reason.contains("NEVER marked SAFE") || decision.reason.contains("NOT marked SAFE"))

        val events = repo.streamChatResponse("Hello Yuta").toList()
        assertEquals(1, events.size)
        assertTrue(events.first() is ChatStreamEvent.Error)
    }

    @Test
    fun `shared backend ai parses live scan and chat and fails safely on HTTP 500`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = YutaSettingsRepository(context)
        settingsRepo.saveSharedBackendConfig("https://yuta-worker.example.workers.dev", "secret_pass")

        var mode = "VIOLATION"
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            assertEquals("secret_pass", req.header("X-Admin-Secret"))
            val url = req.url.toString()
            when {
                url.endsWith("/api/scan") && mode == "VIOLATION" -> Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"verdict":"VIOLATION","confidence":0.97,"actionTaken":"DELETED","reason":"Explicit adult sticker"}""".toResponseBody("application/json".toMediaType()))
                    .build()
                url.endsWith("/api/scan") && mode == "ERROR" -> Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(500).message("Error")
                    .body("""{"error":"Gemini quota exceeded"}""".toResponseBody("application/json".toMediaType()))
                    .build()
                url.endsWith("/api/chat") -> Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"reply":"Namaste! Main Yuta AI hoon."}""".toResponseBody("application/json".toMediaType()))
                    .build()
                else -> Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }).build()

        val repo = SharedBackendAiRepository(settingsRepo, YutaBackendApiService.create(client))

        val violationDecision = repo.moderateMedia(
            mediaBytes = byteArrayOf(9, 8, 7),
            mimeType = "image/jpeg",
            mediaKind = MediaKind.STICKER_STATIC,
            sensitivity = ModerationSensitivity.BALANCED
        )
        assertEquals(ModerationVerdict.VIOLATION, violationDecision.verdict)
        assertFalse(violationDecision.isPlaceholder)

        mode = "ERROR"
        val errDecision = repo.moderateMedia(
            mediaBytes = byteArrayOf(9, 8, 7),
            mimeType = "image/jpeg",
            mediaKind = MediaKind.STICKER_STATIC,
            sensitivity = ModerationSensitivity.BALANCED
        )
        assertEquals(ModerationVerdict.SCAN_FAILED, errDecision.verdict)

        val chatEvents = repo.streamChatResponse("Kaise ho?").toList()
        assertTrue(chatEvents.first() is ChatStreamEvent.Completed)
        val completed = chatEvents.first() as ChatStreamEvent.Completed
        assertEquals("Namaste! Main Yuta AI hoon.", completed.fullText)
        assertFalse(completed.isPlaceholder)
    }

    @Test
    fun `connection test endpoint parses connected and error responses accurately`() = runTest {
        var simulateFailure = false
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            assertEquals("admin_secret", req.header("X-Admin-Secret"))
            val body = if (!simulateFailure) {
                """
                {
                  "ok": true,
                  "telegram": {"ok": true, "id": 123456, "username": "yuta_guard_bot"},
                  "gemini": {"ok": true, "model": "gemini-2.5-flash", "reply": "OK", "error": ""},
                  "webhook": {"ok": true, "url": "https://yuta.workers.dev/telegram/webhook", "pending_update_count": 0},
                  "database": {"ok": true}
                }
                """.trimIndent()
            } else {
                """
                {
                  "ok": false,
                  "telegram": {"ok": false, "error": "Unauthorized"},
                  "gemini": {"ok": false, "model": "gemini-2.5-flash", "reply": "", "error": "API key not valid"},
                  "webhook": {"ok": false, "url": "", "pending_update_count": 0, "error": "Unauthorized"},
                  "database": {"ok": false}
                }
                """.trimIndent()
            }
            Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1)
                .code(if (simulateFailure) 400 else 200)
                .message(if (simulateFailure) "Bad Request" else "OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()

        val api = YutaBackendApiService.create(client)
        val okResp = api.runConnectionTest("https://yuta.workers.dev/api/connection-test", "admin_secret")
        assertTrue(okResp.isSuccessful)
        assertTrue(okResp.body()?.ok == true)
        assertEquals("yuta_guard_bot", okResp.body()?.telegram?.username)
        assertEquals("OK", okResp.body()?.gemini?.reply)
        assertEquals("https://yuta.workers.dev/telegram/webhook", okResp.body()?.webhook?.url)
        assertTrue(okResp.body()?.database?.ok == true)

        simulateFailure = true
        val errResp = api.runConnectionTest("https://yuta.workers.dev/api/connection-test", "admin_secret")
        assertEquals(400, errResp.code())
        assertFalse(errResp.isSuccessful)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `viewmodel shows disconnected by default, connected on valid secrets, and error on invalid secrets`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, YutaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val settingsRepo = YutaSettingsRepository(context)
            settingsRepo.saveSharedBackendConfig("", "")

            var telegramOk = true
            var geminiOk = true
            val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                val json = when (path) {
                    "/api/status" -> """
                        {
                          "telegram": ${if (telegramOk) """{"ok":true,"id":777,"username":"yuta_live_bot"}""" else """{"ok":false,"error":"Unauthorized"}"""},
                          "geminiConfigured": true,
                          "geminiConnected": $geminiOk,
                          "geminiError": ${if (geminiOk) "\"\"" else "\"API key invalid\""},
                          "geminiModel": "gemini-2.5-flash",
                          "databaseBound": true,
                          "uptimeSeconds": 120
                        }
                    """.trimIndent()
                    "/api/stats" -> "{}"
                    "/api/stickers/stats" -> "{}"
                    "/api/logs" -> """{"logs":[]}"""
                    "/api/settings" -> "{}"
                    "/api/connection-test" -> """
                        {
                          "ok": ${telegramOk && geminiOk},
                          "telegram": ${if (telegramOk) """{"ok":true,"id":777,"username":"yuta_live_bot"}""" else """{"ok":false,"error":"Unauthorized"}"""},
                          "gemini": {"ok": $geminiOk, "model": "gemini-2.5-flash", "reply": "${if (geminiOk) "OK" else ""}", "error": "${if (geminiOk) "" else "API key invalid"}"},
                          "webhook": {"ok": $telegramOk, "url": "https://yuta.workers.dev/telegram/webhook", "pending_update_count": 0},
                          "database": {"ok": true}
                        }
                    """.trimIndent()
                    else -> "{}"
                }
                Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }).build()

            val backendApi = YutaBackendApiService.create(client)
            val aiRepo = SharedBackendAiRepository(settingsRepo, backendApi)
            val botEngine = YutaBotEngine(context, TelegramApiService.create(client), aiRepo, db.yutaDao(), settingsRepo)
            val vm = YutaViewModel(db.yutaDao(), settingsRepo, aiRepo, botEngine, backendApi)

            // Observe uiState so WhileSubscribed(5000) activates
            val initial = vm.uiState.value
            assertEquals(BotConnectionStatus.DISCONNECTED, initial.botRuntime.status)
            assertFalse(initial.aiStatus.isAvailable)

            // Configure backend with valid Telegram + Gemini -> Connected
            vm.saveAndVerifySharedBackend("https://yuta.workers.dev", "admin_secret")
            val connectedState = vm.uiState.first {
                it.botRuntime.status == BotConnectionStatus.CONNECTED && it.aiStatus.isAvailable
            }
            assertEquals("yuta_live_bot", connectedState.botRuntime.botUsername)
            assertFalse(connectedState.aiStatus.hasError)

            // Simulate broken Gemini & Telegram secrets during live connection test -> Error
            telegramOk = false
            geminiOk = false
            vm.runLiveConnectionTest()
            val errorState = vm.uiState.first {
                it.botRuntime.status == BotConnectionStatus.ERROR && it.aiStatus.hasError
            }
            assertFalse(errorState.aiStatus.isAvailable)
            assertTrue(errorState.sharedBackendFeedback.orEmpty().contains("Error"))
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}
