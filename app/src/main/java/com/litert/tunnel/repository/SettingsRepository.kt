package com.litert.tunnel.repository

import android.content.Context
import com.litert.tunnel.engine.Backend
import com.litert.tunnel.engine.EngineSettings
import com.litert.tunnel.ui.strings.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val PREFS_NAME           = "engine_settings"
private const val KEY_MAX_TURNS        = "max_conversation_turns"
private const val KEY_MAX_CHARS        = "max_input_chars"
private const val KEY_REPLAY_TURNS     = "context_replay_turns"
private const val KEY_LANGUAGE         = "app_language"
private const val KEY_CORS_ORIGINS     = "cors_origins"
private const val KEY_BACKEND_ORDER    = "backend_order"
private const val SEPARATOR            = ","

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<EngineSettings> = _settings.asStateFlow()

    private val _language = MutableStateFlow(loadLanguage())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun save(settings: EngineSettings) {
        prefs.edit()
            .putInt(KEY_MAX_TURNS,     settings.maxConversationTurns)
            .putInt(KEY_MAX_CHARS,     settings.maxInputChars)
            .putInt(KEY_REPLAY_TURNS,  settings.contextReplayTurns)
            .putString(KEY_CORS_ORIGINS,  settings.corsOrigins.joinToString(SEPARATOR))
            .putString(KEY_BACKEND_ORDER, settings.backendOrder.joinToString(SEPARATOR) { it.name })
            .apply()
        _settings.update { settings }
    }

    fun saveLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.name).apply()
        _language.update { language }
    }

    private fun load() = EngineSettings(
        maxConversationTurns = prefs.getInt(KEY_MAX_TURNS,    EngineSettings.DEFAULT_MAX_TURNS),
        maxInputChars        = prefs.getInt(KEY_MAX_CHARS,    EngineSettings.DEFAULT_MAX_INPUT_CHARS),
        contextReplayTurns   = prefs.getInt(KEY_REPLAY_TURNS, EngineSettings.DEFAULT_CONTEXT_REPLAY_TURNS),
        corsOrigins          = prefs.getString(KEY_CORS_ORIGINS, null)
            ?.split(SEPARATOR)?.filter { it.isNotBlank() }
            ?: EngineSettings.DEFAULT_CORS_ORIGINS,
        backendOrder         = prefs.getString(KEY_BACKEND_ORDER, null)
            ?.split(SEPARATOR)
            ?.mapNotNull { runCatching { Backend.valueOf(it) }.getOrNull() }
            ?.ifEmpty { null }
            ?: EngineSettings.DEFAULT_BACKEND_ORDER,
    )

    private fun loadLanguage(): AppLanguage =
        prefs.getString(KEY_LANGUAGE, null)
            ?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
            ?: AppLanguage.EN
}
