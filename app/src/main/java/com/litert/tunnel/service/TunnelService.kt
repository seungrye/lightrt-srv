package com.litert.tunnel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.litert.tunnel.MainActivity
import com.litert.tunnel.engine.EngineConfig
import com.litert.tunnel.engine.InferenceEngine
import com.litert.tunnel.engine.InferenceEngineFactory
import com.litert.tunnel.engine.ResourceMonitor
import com.litert.tunnel.repository.SettingsRepository
import com.litert.tunnel.repository.TunnelRepository
import com.litert.tunnel.server.TunnelServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TunnelService : Service() {

    companion object {
        private const val TAG = "TunnelService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tunnel_server"
        const val EXTRA_MODEL_PATH = "model_path"
        const val ACTION_STOP = "com.litert.tunnel.ACTION_STOP"

        /** Shared repository — set by [TunnelApplication], read here. */
        lateinit var repository: TunnelRepository
        lateinit var settingsRepository: SettingsRepository
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var llmEngine: InferenceEngine? = null
    private var apiServer: TunnelServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH) ?: run {
            repository.setError("No model path provided")
            return START_NOT_STICKY
        }

        startForegroundWithNotification("LiteRT Tunnel — Loading model…")
        repository.setLoading()

        scope.launch {
            try {
                val engine = InferenceEngineFactory.create(modelPath, applicationContext)
                llmEngine = engine

                val currentSettings = settingsRepository.settings.value
                engine.applySettings(currentSettings)

                val ok = engine.initialize(EngineConfig(
                    modelPath = modelPath,
                    backendOrder = currentSettings.backendOrder,
                ))
                if (!ok) {
                    repository.setError("Failed to initialize engine for: $modelPath")
                    stopSelf()
                    return@launch
                }

                val server = TunnelServer(engine) { log ->
                    repository.appendLog(log)
                }
                val port = server.start()
                apiServer = server

                updateNotification("LiteRT Tunnel — Running on :$port (${engine.backendName})")
                repository.setRunning(
                    port = port,
                    backendName = engine.backendName,
                    modelName = engine.modelName,
                    localAddresses = getLocalIpAddresses(),
                )

                // Forward engine metrics to the repository so the UI can observe them
                scope.launch {
                    engine.metrics.collect { repository.updateEngineMetrics(it) }
                }

                // Collect CPU/RAM samples every 2 seconds
                scope.launch(Dispatchers.IO) {
                    val monitor = ResourceMonitor(applicationContext)
                    while (true) {
                        delay(2_000)
                        repository.appendResourceSample(monitor.sample())
                    }
                }

                // Apply settings changes live — no server restart needed
                scope.launch {
                    settingsRepository.settings.collect { settings ->
                        engine.applySettings(settings)
                        server.updateCorsOrigins(settings.corsOrigins)
                    }
                }

                Log.i(TAG, "Server running on port $port")
            } catch (e: Exception) {
                Log.e(TAG, "Service error", e)
                repository.setError(e.message ?: "Unknown error")
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        apiServer?.stop()
        llmEngine?.shutdown()
        scope.cancel()
        repository.setStopped()
        repository.resetEngineMetrics()
        repository.resetResourceHistory()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ─────────────────────────────────────────────────────

    private fun startForegroundWithNotification(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TunnelService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LiteRT Tunnel")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_delete),
                    "Stop",
                    stopIntent,
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun getLocalIpAddresses(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.mapNotNull { it.hostAddress }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LiteRT Tunnel Server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "LLM inference + HTTP API server" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
