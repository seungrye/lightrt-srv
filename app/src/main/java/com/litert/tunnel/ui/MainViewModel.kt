package com.litert.tunnel.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.litert.tunnel.TunnelApplication
import com.litert.tunnel.client.ChatClient
import com.litert.tunnel.download.DownloadProgress
import com.litert.tunnel.download.ModelDownloader
import com.litert.tunnel.engine.EngineMetrics
import com.litert.tunnel.engine.EngineSettings
import com.litert.tunnel.engine.Message
import com.litert.tunnel.ui.strings.AppLanguage
import com.litert.tunnel.repository.TunnelState
import com.litert.tunnel.repository.TunnelStatus
import com.litert.tunnel.service.TunnelService
import com.litert.tunnel.ui.screen.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class DownloadState(
    val isDownloading: Boolean = false,
    val downloadingFilename: String? = null,
    val progress: DownloadProgress? = null,
)

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo         = (app as TunnelApplication).repository
    private val settingsRepo = (app as TunnelApplication).settingsRepository

    val tunnelState: StateFlow<TunnelState> = repo.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.state.value)

    val engineMetrics: StateFlow<EngineMetrics> = repo.engineMetrics
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.engineMetrics.value)

    val resourceHistory: StateFlow<List<com.litert.tunnel.engine.ResourceSample>> = repo.resourceHistory
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val engineSettings: StateFlow<EngineSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepo.settings.value)

    fun saveSettings(settings: EngineSettings) = settingsRepo.save(settings)

    val language: StateFlow<AppLanguage> = settingsRepo.language
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepo.language.value)

    fun saveLanguage(lang: AppLanguage) = settingsRepo.saveLanguage(lang)

    private val modelsDir: File
        get() = getApplication<Application>().run { getExternalFilesDir(null) ?: filesDir }

    private val _customModels = MutableStateFlow<List<File>>(emptyList())
    val customModels: StateFlow<List<File>> = _customModels.asStateFlow()

    init { scanCustomModels() }

    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _chatState = MutableStateFlow(ChatState())
    val chatState: StateFlow<ChatState> = _chatState.asStateFlow()

    private val downloader = ModelDownloader()

    fun scanCustomModels() {
        _customModels.value = (modelsDir.listFiles { f ->
            val ext = f.extension.lowercase()
            ext == "gguf" || ext == "litertlm"
        } ?: emptyArray()).sortedByDescending { it.lastModified() }
    }

    fun deleteCustomModel(file: File) {
        file.delete()
        scanCustomModels()
    }
    private var generatingJob: Job? = null

    // ── Server control ────────────────────────────────────────────────────

    fun startServer(modelPath: String) {
        if (tunnelState.value.status == TunnelStatus.LOADING ||
            tunnelState.value.status == TunnelStatus.RUNNING) return

        val intent = Intent(getApplication(), TunnelService::class.java).apply {
            putExtra(TunnelService.EXTRA_MODEL_PATH, modelPath)
        }
        getApplication<TunnelApplication>().startForegroundService(intent)
    }

    fun stopServer() {
        getApplication<TunnelApplication>()
            .stopService(Intent(getApplication(), TunnelService::class.java))
    }

    // ── In-app chat ───────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        if (_chatState.value.isGenerating) return
        val port = tunnelState.value.port

        val userMsg = ChatMessage(role = "user", content = text)
        val assistantMsg = ChatMessage(role = "assistant", content = "", isStreaming = true)

        _chatState.update {
            it.copy(
                messages = it.messages + userMsg + assistantMsg,
                isGenerating = true,
                error = null,
            )
        }

        val assistantId = assistantMsg.id

        // Build full message history for context (exclude the empty assistant placeholder)
        val history = _chatState.value.messages
            .filter { it.id != assistantId }
            .map { Message(it.role, it.content) }

        val client = ChatClient(port)

        generatingJob = viewModelScope.launch(Dispatchers.IO) {
            client.streamMessage(history)
                .onCompletion { err ->
                    _chatState.update { state ->
                        val updated = state.messages.map { msg ->
                            if (msg.id == assistantId) msg.copy(isStreaming = false) else msg
                        }
                        state.copy(
                            messages = updated,
                            isGenerating = false,
                            error = err?.message,
                        )
                    }
                }
                .catch { e ->
                    _chatState.update { state ->
                        val updated = state.messages.map { msg ->
                            if (msg.id == assistantId)
                                msg.copy(content = "Error: ${e.message}", isStreaming = false)
                            else msg
                        }
                        state.copy(messages = updated, isGenerating = false)
                    }
                }
                .collect { token ->
                    _chatState.update { state ->
                        val updated = state.messages.map { msg ->
                            if (msg.id == assistantId)
                                msg.copy(content = msg.content + token)
                            else msg
                        }
                        state.copy(messages = updated)
                    }
                }
        }
    }

    fun clearChat() {
        generatingJob?.cancel()
        _chatState.update { ChatState() }
    }

    // ── Model download ────────────────────────────────────────────────────

    fun downloadModel(url: String, destFile: File, minValidBytes: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _downloadState.update {
                it.copy(isDownloading = true, downloadingFilename = destFile.name, progress = null)
            }
            downloader.download(url, destFile, minValidBytes)
                .catch { e ->
                    _downloadState.update {
                        it.copy(
                            isDownloading = false,
                            progress = DownloadProgress(0f, 0L, 0L, error = e.message),
                        )
                    }
                }
                .collect { progress ->
                    _downloadState.update { it.copy(progress = progress) }
                    if (progress.isDone || progress.error != null) {
                        _downloadState.update { it.copy(isDownloading = false) }
                    }
                }
        }
    }
}
