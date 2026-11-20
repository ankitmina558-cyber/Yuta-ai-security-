package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.ai.ModerationSensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.yutaDataStore: DataStore<Preferences> by preferencesDataStore(name = "yuta_ai_settings")

data class YutaSettings(
    val botToken: String = "",
    val adminUserIdsRaw: String = "",
    val adminAlertChatId: String = "",
    val sharedBackendUrl: String = "",
    val sharedAdminSecret: String = "",
    val customLogoUri: String? = null,
    val enableImageModeration: Boolean = true,
    val enableStickerModeration: Boolean = true,
    val enableGifModeration: Boolean = true,
    val enableVideoModeration: Boolean = true,
    val enableAiChat: Boolean = true,
    val enableAutoDelete: Boolean = true,
    val enableAdminNotifications: Boolean = true,
    val disableMediaRetention: Boolean = true,
    val sensitivity: ModerationSensitivity = ModerationSensitivity.BALANCED,
    val lastUpdateOffset: Long = 0L
) {
    val authorizedAdminIds: Set<Long>
        get() = adminUserIdsRaw.split(",", " ", ";")
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()

    val alertChatIdOrNull: Long?
        get() = adminAlertChatId.trim().toLongOrNull()

    val isSharedBackendConfigured: Boolean
        get() = sharedBackendUrl.trim().startsWith("https://")
}

class YutaSettingsRepository(private val context: Context) {

    private object Keys {
        val BOT_TOKEN = stringPreferencesKey("bot_token")
        val ADMIN_USER_IDS = stringPreferencesKey("admin_user_ids")
        val ADMIN_ALERT_CHAT_ID = stringPreferencesKey("admin_alert_chat_id")
        val SHARED_BACKEND_URL = stringPreferencesKey("shared_backend_url")
        val SHARED_ADMIN_SECRET = stringPreferencesKey("shared_admin_secret")
        val CUSTOM_LOGO_URI = stringPreferencesKey("custom_logo_uri")
        val ENABLE_IMAGE_MOD = booleanPreferencesKey("enable_image_mod")
        val ENABLE_STICKER_MOD = booleanPreferencesKey("enable_sticker_mod")
        val ENABLE_GIF_MOD = booleanPreferencesKey("enable_gif_mod")
        val ENABLE_VIDEO_MOD = booleanPreferencesKey("enable_video_mod")
        val ENABLE_AI_CHAT = booleanPreferencesKey("enable_ai_chat")
        val ENABLE_AUTO_DELETE = booleanPreferencesKey("enable_auto_delete")
        val ENABLE_ADMIN_NOTIFY = booleanPreferencesKey("enable_admin_notify")
        val DISABLE_MEDIA_RETENTION = booleanPreferencesKey("disable_media_retention")
        val SENSITIVITY = stringPreferencesKey("sensitivity")
        val LAST_UPDATE_OFFSET = longPreferencesKey("last_update_offset")
    }

    val settingsFlow: Flow<YutaSettings> = context.yutaDataStore.data.map { prefs ->
        val sensitivityName = prefs[Keys.SENSITIVITY] ?: ModerationSensitivity.BALANCED.name
        val parsedSensitivity = runCatching { ModerationSensitivity.valueOf(sensitivityName) }
            .getOrDefault(ModerationSensitivity.BALANCED)
        val rawStoredToken = prefs[Keys.BOT_TOKEN].orEmpty()
        val rawStoredSecret = prefs[Keys.SHARED_ADMIN_SECRET].orEmpty()

        YutaSettings(
            botToken = TokenCrypto.decrypt(rawStoredToken),
            adminUserIdsRaw = prefs[Keys.ADMIN_USER_IDS].orEmpty(),
            adminAlertChatId = prefs[Keys.ADMIN_ALERT_CHAT_ID].orEmpty(),
            sharedBackendUrl = prefs[Keys.SHARED_BACKEND_URL].orEmpty(),
            sharedAdminSecret = TokenCrypto.decrypt(rawStoredSecret),
            customLogoUri = prefs[Keys.CUSTOM_LOGO_URI],
            enableImageModeration = prefs[Keys.ENABLE_IMAGE_MOD] ?: true,
            enableStickerModeration = prefs[Keys.ENABLE_STICKER_MOD] ?: true,
            enableGifModeration = prefs[Keys.ENABLE_GIF_MOD] ?: true,
            enableVideoModeration = prefs[Keys.ENABLE_VIDEO_MOD] ?: true,
            enableAiChat = prefs[Keys.ENABLE_AI_CHAT] ?: true,
            enableAutoDelete = prefs[Keys.ENABLE_AUTO_DELETE] ?: true,
            enableAdminNotifications = prefs[Keys.ENABLE_ADMIN_NOTIFY] ?: true,
            disableMediaRetention = prefs[Keys.DISABLE_MEDIA_RETENTION] ?: true,
            sensitivity = parsedSensitivity,
            lastUpdateOffset = prefs[Keys.LAST_UPDATE_OFFSET] ?: 0L
        )
    }

    suspend fun getSnapshot(): YutaSettings = settingsFlow.first()

    suspend fun saveTelegramCredentials(
        botToken: String,
        adminUserIdsRaw: String,
        adminAlertChatId: String
    ) {
        val trimmedToken = botToken.trim()
        context.yutaDataStore.edit { prefs ->
            prefs[Keys.BOT_TOKEN] = if (trimmedToken.isEmpty()) "" else TokenCrypto.encrypt(trimmedToken)
            prefs[Keys.ADMIN_USER_IDS] = adminUserIdsRaw.trim()
            prefs[Keys.ADMIN_ALERT_CHAT_ID] = adminAlertChatId.trim()
        }
    }

    suspend fun updateAdminContacts(
        adminUserIdsRaw: String,
        adminAlertChatId: String
    ) {
        context.yutaDataStore.edit { prefs ->
            prefs[Keys.ADMIN_USER_IDS] = adminUserIdsRaw.trim()
            prefs[Keys.ADMIN_ALERT_CHAT_ID] = adminAlertChatId.trim()
        }
    }

    suspend fun saveSharedBackendConfig(
        backendUrl: String,
        adminSecret: String
    ) {
        val cleanUrl = backendUrl.trim().trimEnd('/')
        val cleanSecret = adminSecret.trim()
        context.yutaDataStore.edit { prefs ->
            prefs[Keys.SHARED_BACKEND_URL] = cleanUrl
            prefs[Keys.SHARED_ADMIN_SECRET] = if (cleanSecret.isEmpty()) "" else TokenCrypto.encrypt(cleanSecret)
        }
    }

    suspend fun updateCustomLogoUri(uri: String?) {
        context.yutaDataStore.edit { prefs ->
            if (uri.isNullOrBlank()) {
                prefs.remove(Keys.CUSTOM_LOGO_URI)
            } else {
                prefs[Keys.CUSTOM_LOGO_URI] = uri
            }
        }
    }

    suspend fun updateModerationToggles(
        enableImage: Boolean? = null,
        enableSticker: Boolean? = null,
        enableGif: Boolean? = null,
        enableVideo: Boolean? = null,
        enableAiChat: Boolean? = null,
        enableAutoDelete: Boolean? = null,
        enableAdminNotifications: Boolean? = null,
        disableMediaRetention: Boolean? = null,
        sensitivity: ModerationSensitivity? = null
    ) {
        context.yutaDataStore.edit { prefs ->
            enableImage?.let { prefs[Keys.ENABLE_IMAGE_MOD] = it }
            enableSticker?.let { prefs[Keys.ENABLE_STICKER_MOD] = it }
            enableGif?.let { prefs[Keys.ENABLE_GIF_MOD] = it }
            enableVideo?.let { prefs[Keys.ENABLE_VIDEO_MOD] = it }
            enableAiChat?.let { prefs[Keys.ENABLE_AI_CHAT] = it }
            enableAutoDelete?.let { prefs[Keys.ENABLE_AUTO_DELETE] = it }
            enableAdminNotifications?.let { prefs[Keys.ENABLE_ADMIN_NOTIFY] = it }
            disableMediaRetention?.let { prefs[Keys.DISABLE_MEDIA_RETENTION] = it }
            sensitivity?.let { prefs[Keys.SENSITIVITY] = it.name }
        }
    }

    suspend fun updateLastOffset(offset: Long) {
        context.yutaDataStore.edit { prefs ->
            prefs[Keys.LAST_UPDATE_OFFSET] = offset
        }
    }
}
