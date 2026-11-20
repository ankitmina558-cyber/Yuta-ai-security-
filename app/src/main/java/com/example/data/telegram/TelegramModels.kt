package com.example.data.telegram

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
    @Json(name = "error_code") val errorCode: Int? = null,
    val parameters: TelegramResponseParameters? = null
)

@JsonClass(generateAdapter = true)
data class TelegramResponseParameters(
    @Json(name = "retry_after") val retryAfter: Int? = null
)

@JsonClass(generateAdapter = true)
data class TgUser(
    val id: Long,
    @Json(name = "is_bot") val isBot: Boolean = false,
    @Json(name = "first_name") val firstName: String = "",
    val username: String? = null
)

@JsonClass(generateAdapter = true)
data class TgChat(
    val id: Long,
    val type: String = "private",
    val title: String? = null,
    val username: String? = null
)

@JsonClass(generateAdapter = true)
data class TgPhotoSize(
    @Json(name = "file_id") val fileId: String,
    @Json(name = "file_unique_id") val fileUniqueId: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @Json(name = "file_size") val fileSize: Long? = null
)

@JsonClass(generateAdapter = true)
data class TgSticker(
    @Json(name = "file_id") val fileId: String,
    @Json(name = "file_unique_id") val fileUniqueId: String = "",
    @Json(name = "is_animated") val isAnimated: Boolean = false,
    @Json(name = "is_video") val isVideo: Boolean = false,
    val thumbnail: TgPhotoSize? = null,
    @Json(name = "file_size") val fileSize: Long? = null,
    val emoji: String? = null,
    @Json(name = "set_name") val setName: String? = null
)

@JsonClass(generateAdapter = true)
data class TgAnimation(
    @Json(name = "file_id") val fileId: String,
    @Json(name = "file_unique_id") val fileUniqueId: String = "",
    @Json(name = "mime_type") val mimeType: String? = null,
    @Json(name = "file_size") val fileSize: Long? = null,
    val thumbnail: TgPhotoSize? = null
)

@JsonClass(generateAdapter = true)
data class TgVideo(
    @Json(name = "file_id") val fileId: String,
    @Json(name = "file_unique_id") val fileUniqueId: String = "",
    @Json(name = "mime_type") val mimeType: String? = null,
    @Json(name = "file_size") val fileSize: Long? = null,
    val thumbnail: TgPhotoSize? = null
)

@JsonClass(generateAdapter = true)
data class TgDocument(
    @Json(name = "file_id") val fileId: String,
    @Json(name = "file_unique_id") val fileUniqueId: String = "",
    @Json(name = "file_name") val fileName: String? = null,
    @Json(name = "mime_type") val mimeType: String? = null,
    @Json(name = "file_size") val fileSize: Long? = null,
    val thumbnail: TgPhotoSize? = null
)

@JsonClass(generateAdapter = true)
data class TgMessage(
    @Json(name = "message_id") val messageId: Long,
    val from: TgUser? = null,
    val chat: TgChat,
    val date: Long = 0,
    val text: String? = null,
    val caption: String? = null,
    val photo: List<TgPhotoSize>? = null,
    val sticker: TgSticker? = null,
    val animation: TgAnimation? = null,
    val video: TgVideo? = null,
    val document: TgDocument? = null
)

@JsonClass(generateAdapter = true)
data class TgUpdate(
    @Json(name = "update_id") val updateId: Long,
    val message: TgMessage? = null
)

@JsonClass(generateAdapter = true)
data class TgFile(
    @Json(name = "file_id") val fileId: String,
    @Json(name = "file_size") val fileSize: Long? = null,
    @Json(name = "file_path") val filePath: String? = null
)

@JsonClass(generateAdapter = true)
data class TgChatMember(
    val status: String = "member",
    @Json(name = "can_delete_messages") val canDeleteMessages: Boolean? = null
)
