package com.litert.tunnel.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.litert.tunnel.repository.RequestLog
import com.litert.tunnel.repository.TunnelState
import com.litert.tunnel.repository.TunnelStatus
import com.litert.tunnel.ui.strings.LocalStrings
import com.litert.tunnel.ui.theme.Background
import com.litert.tunnel.ui.theme.Error
import com.litert.tunnel.ui.theme.OnSurface
import com.litert.tunnel.ui.theme.OnSurfaceMuted
import com.litert.tunnel.ui.theme.Primary
import com.litert.tunnel.ui.theme.Success
import com.litert.tunnel.ui.theme.Surface
import com.litert.tunnel.ui.theme.SurfaceVariant
import com.litert.tunnel.ui.theme.Warning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ServerScreen(
    state: TunnelState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(16.dp)
    ) {
        StatusCard(state)

        Spacer(Modifier.height(16.dp))

        when (state.status) {
            TunnelStatus.IDLE, TunnelStatus.STOPPED, TunnelStatus.ERROR -> {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                ) {
                    Text(s.startServer, color = Background, fontWeight = FontWeight.Bold)
                }
            }
            TunnelStatus.RUNNING -> {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(s.stopServer, color = Error)
                }
            }
            TunnelStatus.LOADING -> {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(s.loadingBtn)
                }
            }
        }

        if (state.status == TunnelStatus.RUNNING) {
            Spacer(Modifier.height(8.dp))
            ConnectionInfo(state)
        }

        state.error?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(err, color = Error, fontSize = 13.sp, modifier = Modifier.padding(4.dp))
        }

        Spacer(Modifier.height(16.dp))

        if (state.recentLogs.isNotEmpty()) {
            Text(s.recentRequests, color = OnSurfaceMuted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            LogList(logs = state.recentLogs.reversed())
        }
    }
}

@Composable
private fun StatusCard(state: TunnelState) {
    val s = LocalStrings.current
    val (dotColor, label) = when (state.status) {
        TunnelStatus.RUNNING -> Pair(Success,       s.statusRunning)
        TunnelStatus.LOADING -> Pair(Warning,       s.statusLoading)
        TunnelStatus.ERROR   -> Pair(Error,         s.statusError)
        TunnelStatus.STOPPED -> Pair(OnSurfaceMuted,s.statusStopped)
        TunnelStatus.IDLE    -> Pair(OnSurfaceMuted,s.statusIdle)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Text(
                    " $label",
                    color = dotColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
            if (state.status == TunnelStatus.RUNNING) {
                Spacer(Modifier.height(4.dp))
                Text(state.modelName, color = OnSurfaceMuted, fontSize = 12.sp)
                Text(s.backendLabel + state.backendName, color = OnSurfaceMuted, fontSize = 12.sp)
            }
        }
        if (state.status == TunnelStatus.RUNNING) {
            Text(s.reqCount(state.requestCount), color = OnSurfaceMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ConnectionInfo(state: TunnelState) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AddressRow(
            label = s.onDevice,
            url = "http://localhost:${state.port}",
            copiedLabel = s.copied,
            onCopy = { clipboard.setText(AnnotatedString(it)) },
        )

        if (state.localAddresses.isEmpty()) {
            Text(s.noLanAddress, color = OnSurfaceMuted, fontSize = 12.sp)
        } else {
            state.localAddresses.forEach { ip ->
                AddressRow(
                    label = s.lan,
                    url = "http://$ip:${state.port}",
                    copiedLabel = s.copied,
                    onCopy = { clipboard.setText(AnnotatedString(it)) },
                )
            }
        }

        Text(s.tapToCopy, color = OnSurfaceMuted, fontSize = 11.sp)
    }
}

@Composable
private fun AddressRow(label: String, url: String, copiedLabel: String, onCopy: (String) -> Unit) {
    var copied by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = OnSurfaceMuted, fontSize = 11.sp, modifier = Modifier.widthIn(min = 110.dp))
        Text(
            if (copied) copiedLabel else url,
            fontFamily = FontFamily.Monospace,
            color = if (copied) Success else Primary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f).clickable {
                onCopy(url)
                copied = true
            },
        )
    }
}

@Composable
private fun LogList(logs: List<RequestLog>) {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    LazyColumn {
        items(logs.take(50)) { log ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${fmt.format(Date(log.timestamp))} ${log.endpoint}",
                    color = OnSurfaceMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "${log.statusCode} ${log.responseTimeMs}ms",
                    color = if (log.statusCode < 400) Success else Error,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
