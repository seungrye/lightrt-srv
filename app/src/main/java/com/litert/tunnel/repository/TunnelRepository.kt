package com.litert.tunnel.repository

import com.litert.tunnel.engine.EngineMetrics
import com.litert.tunnel.engine.ResourceSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TunnelStatus { IDLE, LOADING, RUNNING, ERROR, STOPPED }

data class RequestLog(
    val timestamp: Long = System.currentTimeMillis(),
    val endpoint: String,
    val statusCode: Int,
    val responseTimeMs: Long,
)

data class TunnelState(
    val status: TunnelStatus = TunnelStatus.IDLE,
    val port: Int = 8080,
    val backendName: String = "",
    val modelName: String = "",
    val error: String? = null,
    val requestCount: Long = 0L,
    val recentLogs: List<RequestLog> = emptyList(),
    /** Non-loopback IPv4 addresses of this device — populated when server starts. */
    val localAddresses: List<String> = emptyList(),
)

/**
 * Singleton state hub that bridges [TunnelService] (writer) and [MainViewModel] (reader).
 * All mutations are thread-safe via [MutableStateFlow.update].
 */
class TunnelRepository {

    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _engineMetrics = MutableStateFlow(EngineMetrics())
    val engineMetrics: StateFlow<EngineMetrics> = _engineMetrics.asStateFlow()

    fun updateEngineMetrics(metrics: EngineMetrics) = _engineMetrics.update { metrics }
    fun resetEngineMetrics() = _engineMetrics.update { EngineMetrics() }

    private val _resourceHistory = MutableStateFlow<List<ResourceSample>>(emptyList())
    val resourceHistory: StateFlow<List<ResourceSample>> = _resourceHistory.asStateFlow()

    fun appendResourceSample(sample: ResourceSample) =
        _resourceHistory.update { (it + sample).takeLast(60) }

    fun resetResourceHistory() = _resourceHistory.update { emptyList() }

    fun setLoading() = _state.update { it.copy(status = TunnelStatus.LOADING, error = null) }

    fun setRunning(port: Int, backendName: String, modelName: String, localAddresses: List<String> = emptyList()) =
        _state.update {
            it.copy(
                status = TunnelStatus.RUNNING,
                port = port,
                backendName = backendName,
                modelName = modelName,
                error = null,
                localAddresses = localAddresses,
            )
        }

    fun setError(message: String) =
        _state.update { it.copy(status = TunnelStatus.ERROR, error = message) }

    fun setStopped() = _state.update { it.copy(status = TunnelStatus.STOPPED) }

    fun appendLog(log: RequestLog) =
        _state.update { state ->
            val updated = (state.recentLogs + log).takeLast(100)
            state.copy(
                requestCount = state.requestCount + 1,
                recentLogs = updated,
            )
        }

    /** Resets to initial state (used when re-launching the service after a stop). */
    fun reset() = _state.update { TunnelState() }
}
