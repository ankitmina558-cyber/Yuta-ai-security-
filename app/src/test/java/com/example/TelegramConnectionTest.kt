package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.PlaceholderAiRepository
import com.example.data.local.TokenCrypto
import com.example.data.local.YutaDatabase
import com.example.data.local.YutaSettingsRepository
import com.example.data.telegram.TelegramApiService
import com.example.data.telegram.YutaBotEngine
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TelegramConnectionTest {

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

    private fun createMockEngine(handler: (String) -> Pair<Int, String>): YutaBotEngine {
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val (code, json) = handler(request.url.toString())
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Mock")
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val api = TelegramApiService.create(client)
        return YutaBotEngine(
            context = context,
            telegramApi = api,
            aiRepository = PlaceholderAiRepository(),
            dao = database.yutaDao(),
            settingsRepository = settingsRepository
        )
    }

    @Test
    fun `verifyToken succeeds on valid getMe response`() = runTest {
        val engine = createMockEngine { url ->
            assertTrue(url.endsWith("/getMe"))
            200 to """{"ok":true,"result":{"id":777001,"is_bot":true,"first_name":"Yuta AI","username":"yuta_guard_bot"}}"""
        }

        val result = engine.verifyToken("123456:AAFakeSecretTokenValue")
        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals(777001L, user.id)
        assertEquals("yuta_guard_bot", user.username)
        assertEquals("yuta_guard_bot", engine.runtimeState.value.botUsername)
    }

    @Test
    fun `verifyToken parses Telegram 401 errorBody without leaking token`() = runTest {
        val secretToken = "998877:AAH_SUPER_SECRET_BOT_TOKEN"
        val engine = createMockEngine { _ ->
            401 to """{"ok":false,"error_code":401,"description":"Unauthorized: bot$secretToken rejected"}"""
        }

        val result = engine.verifyToken(secretToken)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("Unauthorized"))
        assertFalse(message.contains(secretToken))
        assertTrue(message.contains("[REDACTED_TOKEN]"))
    }

    @Test
    fun `TokenCrypto redacts telegram bot URLs cleanly`() {
        val token = "555111:AAExampleToken12345"
        val raw = "Failed calling https://api.telegram.org/bot$token/getUpdates"
        val redacted = TokenCrypto.redactSecrets(raw, token)
        assertFalse(redacted.contains(token))
        assertTrue(redacted.contains("bot[REDACTED_TOKEN]"))
    }
}
