package com.litert.tunnel.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.litert.tunnel.download.DownloadProgress
import com.litert.tunnel.ui.strings.LocalStrings
import com.litert.tunnel.ui.theme.Background
import com.litert.tunnel.ui.theme.Error
import com.litert.tunnel.ui.theme.OnSurface
import com.litert.tunnel.ui.theme.OnSurfaceMuted
import com.litert.tunnel.ui.theme.Primary
import com.litert.tunnel.ui.theme.Success
import com.litert.tunnel.ui.theme.Surface

data class ModelSpec(
    val displayName: String,
    val description: String,
    val sizeGb: Float,
    val filename: String,
    val url: String,
    val minValidBytes: Long,
)

val BUILT_IN_MODELS = listOf(
    ModelSpec(
        displayName = "Gemma 4 E2B",
        description = "2B MoE · multimodal · 128K context",
        sizeGb = 2.58f,
        filename = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        minValidBytes = 2_400_000_000L,
    ),
    ModelSpec(
        displayName = "Gemma 4 E4B",
        description = "4B MoE · multimodal · 128K context",
        sizeGb = 3.65f,
        filename = "gemma-4-E4B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        minValidBytes = 3_500_000_000L,
    ),
)

@Composable
fun ModelsScreen(
    modelsDir: String,
    downloadState: com.litert.tunnel.ui.DownloadState,
    activeModelPath: String,
    customModels: List<java.io.File>,
    onDownload: (ModelSpec) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (ModelSpec) -> Unit,
    onDeleteCustom: (java.io.File) -> Unit,
    onPickFile: () -> Unit,
) {
    val s = LocalStrings.current
    val builtInFilenames = BUILT_IN_MODELS.map { it.filename }.toSet()
    val customModelCards = customModels.filter { it.name !in builtInFilenames }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(16.dp)
    ) {
        Text(s.modelsTitle, color = OnSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(s.storedIn + modelsDir, color = OnSurfaceMuted, fontSize = 11.sp)
        Spacer(Modifier.height(16.dp))

        BUILT_IN_MODELS.forEach { spec ->
            val destPath = "$modelsDir/${spec.filename}"
            val exists = java.io.File(destPath).let { it.exists() && it.length() >= spec.minValidBytes }
            val isActive = activeModelPath == destPath

            val cardDownloadState = if (!exists && downloadState.downloadingFilename == spec.filename)
                downloadState else null

            ModelCard(
                spec = spec,
                exists = exists,
                isActive = isActive,
                downloadState = cardDownloadState,
                onDownload = { onDownload(spec) },
                onSelect = { onSelect(destPath) },
                onDelete = { onDelete(spec) },
            )
            Spacer(Modifier.height(12.dp))
        }

        if (customModelCards.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(s.customModelsTitle, color = OnSurfaceMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            customModelCards.forEach { file ->
                CustomModelCard(
                    file = file,
                    isActive = activeModelPath == file.absolutePath,
                    onSelect = { onSelect(file.absolutePath) },
                    onDelete = { onDeleteCustom(file) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Text(s.loadCustom, color = Primary)
        }
    }
}

@Composable
private fun CustomModelCard(
    file: java.io.File,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = LocalStrings.current
    val formatBadge = if (file.extension.lowercase() == "gguf") "GGUF" else "LiteRT"
    val sizeMb = file.length() / 1_048_576f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(file.nameWithoutExtension, color = OnSurface, fontWeight = FontWeight.SemiBold)
                Text("$formatBadge  •  %.1f GB".format(sizeMb / 1024f), color = OnSurfaceMuted, fontSize = 12.sp)
            }
            if (isActive) Text(s.active, color = Success, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row {
            if (!isActive) {
                Button(
                    onClick = onSelect,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                ) { Text(s.use, color = Background) }
                Spacer(Modifier.padding(4.dp))
            }
            OutlinedButton(onClick = onDelete) { Text(s.delete, color = Error) }
        }
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    exists: Boolean,
    isActive: Boolean,
    downloadState: com.litert.tunnel.ui.DownloadState?,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(spec.displayName, color = OnSurface, fontWeight = FontWeight.SemiBold)
                Text(spec.description, color = OnSurfaceMuted, fontSize = 12.sp)
                Text("${spec.sizeGb} GB", color = OnSurfaceMuted, fontSize = 12.sp)
            }
            if (isActive) Text(s.active, color = Success, fontSize = 12.sp)
        }

        Spacer(Modifier.height(8.dp))

        if (exists) {
            Row {
                if (!isActive) {
                    Button(
                        onClick = onSelect,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    ) { Text(s.use, color = Background) }
                    Spacer(Modifier.padding(4.dp))
                }
                OutlinedButton(onClick = onDelete) { Text(s.delete, color = Error) }
            }
        } else {
            downloadState?.let { dl ->
                val prog = dl.progress
                if (dl.isDownloading && prog != null && prog.error == null) {
                    LinearProgressIndicator(
                        progress = { prog.progressFraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = Primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    val dlMb = prog.downloadedBytes / 1_048_576f
                    val totMb = prog.totalBytes / 1_048_576f
                    val speedMB = prog.speedBps / 1_048_576f
                    Text(
                        "%.0f / %.0f MB  •  %.1f MB/s".format(dlMb, totMb, speedMB),
                        color = OnSurfaceMuted, fontSize = 11.sp,
                    )
                } else {
                    prog?.error?.let { err ->
                        Text(err, color = Error, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !dl.isDownloading,
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    ) { Text(if (dl.isDownloading) s.downloading else s.download, color = Background) }
                }
            } ?: Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
            ) { Text(s.download, color = Background) }
        }
    }
}
