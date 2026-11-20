package com.example.di

import android.content.Context
import com.example.data.ai.AiRepository
import com.example.data.ai.SharedBackendAiRepository
import com.example.data.local.YutaDao
import com.example.data.local.YutaDatabase
import com.example.data.local.YutaSettingsRepository
import com.example.data.remote.YutaBackendApiService
import com.example.data.telegram.TelegramApiService
import com.example.data.telegram.YutaBotEngine

class YutaAppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: YutaDatabase by lazy {
        YutaDatabase.getInstance(appContext)
    }

    val dao: YutaDao by lazy {
        database.yutaDao()
    }

    val settingsRepository: YutaSettingsRepository by lazy {
        YutaSettingsRepository(appContext)
    }

    val telegramApiService: TelegramApiService by lazy {
        TelegramApiService.create()
    }

    val backendApiService: YutaBackendApiService by lazy {
        YutaBackendApiService.create()
    }

    val sharedAiRepository: SharedBackendAiRepository by lazy {
        SharedBackendAiRepository(
            settingsRepository = settingsRepository,
            backendApi = backendApiService
        )
    }

    val aiRepository: AiRepository
        get() = sharedAiRepository

    val botEngine: YutaBotEngine by lazy {
        YutaBotEngine(
            context = appContext,
            telegramApi = telegramApiService,
            aiRepository = aiRepository,
            dao = dao,
            settingsRepository = settingsRepository
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: YutaAppContainer? = null

        fun getInstance(context: Context): YutaAppContainer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: YutaAppContainer(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
