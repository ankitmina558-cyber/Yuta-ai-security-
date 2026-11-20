package com.example.data.telegram

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

interface TelegramApiService {

    @GET
    suspend fun getMe(@Url url: String): Response<TelegramResponse<TgUser>>

    @POST
    suspend fun deleteWebhook(
        @Url url: String,
        @Query("drop_pending_updates") dropPendingUpdates: Boolean = false
    ): Response<TelegramResponse<Boolean>>

    @GET
    suspend fun getUpdates(
        @Url url: String,
        @Query("offset") offset: Long,
        @Query("timeout") timeout: Int = 20,
        @Query("allowed_updates") allowedUpdates: String = "[\"message\"]"
    ): Response<TelegramResponse<List<TgUpdate>>>

    @GET
    suspend fun getFile(
        @Url url: String,
        @Query("file_id") fileId: String
    ): Response<TelegramResponse<TgFile>>

    @FormUrlEncoded
    @POST
    suspend fun sendMessage(
        @Url url: String,
        @Field("chat_id") chatId: Long,
        @Field("text") text: String,
        @Field("reply_to_message_id") replyToMessageId: Long? = null
    ): Response<TelegramResponse<TgMessage>>

    @FormUrlEncoded
    @POST
    suspend fun deleteMessage(
        @Url url: String,
        @Field("chat_id") chatId: Long,
        @Field("message_id") messageId: Long
    ): Response<TelegramResponse<Boolean>>

    @FormUrlEncoded
    @POST
    suspend fun forwardMessage(
        @Url url: String,
        @Field("chat_id") chatId: Long,
        @Field("from_chat_id") fromChatId: Long,
        @Field("message_id") messageId: Long
    ): Response<TelegramResponse<TgMessage>>

    @GET
    suspend fun getChatMember(
        @Url url: String,
        @Query("chat_id") chatId: Long,
        @Query("user_id") userId: Long
    ): Response<TelegramResponse<TgChatMember>>

    @Streaming
    @GET
    suspend fun downloadFile(@Url fileUrl: String): Response<ResponseBody>

    companion object {
        private const val BASE_HOST = "https://api.telegram.org/"

        private val moshi: Moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        private val errorAdapter = moshi.adapter<TelegramResponse<Any>>(
            Types.newParameterizedType(TelegramResponse::class.java, Any::class.java)
        )

        fun botEndpoint(token: String, method: String): String {
            return "${BASE_HOST}bot${token.trim()}/$method"
        }

        fun fileDownloadUrl(token: String, filePath: String): String {
            return "${BASE_HOST}file/bot${token.trim()}/$filePath"
        }

        fun parseErrorBody(errorBody: ResponseBody?): TelegramResponse<Any>? {
            val raw = runCatching { errorBody?.string() }.getOrNull() ?: return null
            return runCatching { errorAdapter.fromJson(raw) }.getOrNull()
        }

        fun create(customClient: OkHttpClient? = null): TelegramApiService {
            // Never attach URL/body logging interceptors so bot tokens cannot appear in logcat.
            val client = customClient ?: OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_HOST)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(TelegramApiService::class.java)
        }
    }
}
