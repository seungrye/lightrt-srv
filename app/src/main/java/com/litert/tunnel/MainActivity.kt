package com.litert.tunnel

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.litert.tunnel.repository.TunnelStatus
import com.litert.tunnel.ui.MainViewModel
import com.litert.tunnel.ui.screen.BUILT_IN_MODELS
import com.litert.tunnel.ui.screen.ChatScreen
import com.litert.tunnel.ui.screen.ModelsScreen
import com.litert.tunnel.ui.screen.MonitorScreen
import com.litert.tunnel.ui.screen.ServerScreen
import com.litert.tunnel.ui.strings.LocalStrings
import com.litert.tunnel.ui.strings.appStringsFor
import com.litert.tunnel.ui.theme.Background
import com.litert.tunnel.ui.theme.OnSurfaceMuted
import com.litert.tunnel.ui.theme.Primary
import com.litert.tunnel.ui.theme.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var activeModelPath by mutableStateOf("")

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val modelsDir = getExternalFilesDir(null) ?: filesDir

        // Preserve original extension so InferenceEngineFactory routes correctly
        val originalName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (idx >= 0) cursor.getString(idx) else null
        }
        val ext = originalName?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it == "gguf" || it == "litertlm" }
            ?: "litertlm"
        val destName = if (!originalName.isNullOrEmpty() &&
            (originalName.endsWith(".gguf", ignoreCase = true) ||
             originalName.endsWith(".litertlm", ignoreCase = true))
        ) originalName else "custom_${System.currentTimeMillis()}.$ext"
        val dest = File(modelsDir, destName)

        Toast.makeText(this, "Copying file…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                contentResolver.openInputStream(uri)?.use { ins ->
                    dest.outputStream().use { out -> ins.copyTo(out) }
                }
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    activeModelPath = dest.absolutePath
                    vm.scanCustomModels()
                    Toast.makeText(this@MainActivity, "Model loaded!", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Let Compose handle all window insets (required for imePadding to work)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        requestBatteryExemption()

        val modelsDir = (getExternalFilesDir(null) ?: filesDir).absolutePath
        if (activeModelPath.isEmpty()) {
            activeModelPath = "$modelsDir/${BUILT_IN_MODELS.first().filename}"
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val tunnelState by vm.tunnelState.collectAsState()
                val downloadState by vm.downloadState.collectAsState()
                val chatState by vm.chatState.collectAsState()
                val engineMetrics    by vm.engineMetrics.collectAsState()
                val resourceHistory  by vm.resourceHistory.collectAsState()
                val engineSettings  by vm.engineSettings.collectAsState()
                val language        by vm.language.collectAsState()
                val customModels    by vm.customModels.collectAsState()

                var selectedTab by rememberSaveable { mutableStateOf(0) }
                val s = appStringsFor(language)

                CompositionLocalProvider(LocalStrings provides s) {
                Scaffold(
                    containerColor = Background,
                    bottomBar = {
                        NavigationBar(containerColor = Surface) {
                            listOf(
                                Triple(s.tabServer,  Icons.Default.Cloud,    0),
                                Triple(s.tabChat,    Icons.Default.Chat,     1),
                                Triple(s.tabModels,  Icons.Default.Storage,  2),
                                Triple(s.tabMonitor, Icons.Default.Analytics,3),
                            ).forEach { (label, icon, idx) ->
                                NavigationBarItem(
                                    selected = selectedTab == idx,
                                    onClick = { selectedTab = idx },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Primary,
                                        unselectedIconColor = OnSurfaceMuted,
                                        selectedTextColor = Primary,
                                        unselectedTextColor = OnSurfaceMuted,
                                        indicatorColor = Surface,
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        Modifier
                            .padding(innerPadding)
                            .background(Background)
                    ) {
                        when (selectedTab) {
                            0 -> ServerScreen(
                                state = tunnelState,
                                onStart = { vm.startServer(activeModelPath) },
                                onStop = { vm.stopServer() },
                            )
                            1 -> ChatScreen(
                                messages = chatState.messages,
                                isGenerating = chatState.isGenerating,
                                engineReady = tunnelState.status == TunnelStatus.RUNNING,
                                onSend = { vm.sendMessage(it) },
                                onClear = { vm.clearChat() },
                            )
                            2 -> ModelsScreen(
                                modelsDir = (getExternalFilesDir(null) ?: filesDir).absolutePath,
                                downloadState = downloadState,
                                activeModelPath = activeModelPath,
                                customModels = customModels,
                                onDownload = { spec ->
                                    val dest = File(getExternalFilesDir(null) ?: filesDir, spec.filename)
                                    vm.downloadModel(spec.url, dest, spec.minValidBytes)
                                },
                                onSelect = { path -> activeModelPath = path },
                                onDelete = { spec ->
                                    File(getExternalFilesDir(null) ?: filesDir, spec.filename).delete()
                                    Toast.makeText(this@MainActivity, "Deleted", Toast.LENGTH_SHORT).show()
                                },
                                onDeleteCustom = { file ->
                                    vm.deleteCustomModel(file)
                                    if (activeModelPath == file.absolutePath) {
                                        activeModelPath = (getExternalFilesDir(null) ?: filesDir)
                                            .absolutePath + "/${BUILT_IN_MODELS.first().filename}"
                                    }
                                },
                                onPickFile = { filePicker.launch(arrayOf("*/*")) },
                            )
                            3 -> MonitorScreen(
                                metrics = engineMetrics,
                                resourceHistory = resourceHistory,
                                settings = engineSettings,
                                onSettingsChange = { vm.saveSettings(it) },
                                language = language,
                                onLanguageChange = { vm.saveLanguage(it) },
                            )
                        }
                    }
                }
                } // CompositionLocalProvider
            }
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }
    }
}
