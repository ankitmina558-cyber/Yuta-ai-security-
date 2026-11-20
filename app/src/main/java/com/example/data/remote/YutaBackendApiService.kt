package com.example.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

interface YutaBackendApiService {

    @GET
    suspend fun getStatus(
        @Url url: String,
        @Header("X-Admin-Secret") adminSecret: String
    ): Response<BackendStatusResponse>

    @GET
    suspend fun getStats(
        @Url url: String,
        @Header("X-Admin-Secret") adminSecret: String
    ): Response<BackendStatsResponse>

    @GET
    suspend fun getLogs(
        @Url url: String,
        @Header("X-Admin-Secret") adminSecret: String
    ): Response<BackendLogsResponse>

    @GET
    suspend fun getSettings(
        @Url url: String,
        @Header("X-Admin-Secret") adminSecret: String
    ): Response<BackendSettingsDto>

    @POST
    suspend fun updateSettings(
        @Url url: String,
        @Header("X-Admin-Secret") adminSecret: String,
        @Body settings: BackendSettingsDto
    ): Response<BackendSettingsDto>

    @POST
    suspend fun scanMedia(
        @Url url: String,
        @Header("X-Admin-Secret") adminSecret: String,
        @Body body: BackendScanRequest
    ): Response<BackendLogItem>

    @GET
    suspend fun getStickerStats(
        @Url url: String,
        @Header("X-Admin-Secret") adminSecret: String
    ): Response<BackendStickerStatsDto>

    @POST
    suspend fun processStickerQueue(
        @Url url: String,
        @Header("X-Admin-Secret") adminSecret: String
    ): Response<BackendQueueProcessResponse>

    @POST
    suspend fun sendChat(
        @Url url: String,
        @Header("X-Admin-Secret") adminSecret: String,
        @Body body: BackendChatRequest
    ): Response<BackendChatResponse>

    @POST
    suspend fun setupWebhook(
        @Url url: String,
        @Header("X-Admin-Secret") adminSecret: String
    ): Response<BackendWebhookResponse>

    @POST
    suspend fun runConnectionTest(
        @Url url: String,
        @Header("X-Admin-Secret") adminSecret: String
    ): Response<BackendConnectionTestResponse>

    companion object {
        fun normalizeBaseUrl(rawUrl: String): String {
            return rawUrl.trim().trimEnd('/')
        }

        fun endpoint(baseUrl: String, path: String): String {
            return "${normalizeBaseUrl(baseUrl)}$path"
        }

        fun create(customClient: OkHttpClient? = null): YutaBackendApiService {
            val client = customClient ?: OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()

            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl("https://localhost/")
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(YutaBackendApiService::class.java)
        }
    }
}
