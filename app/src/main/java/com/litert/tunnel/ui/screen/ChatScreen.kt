package com.litert.tunnel.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.litert.tunnel.ui.strings.LocalStrings
import com.litert.tunnel.ui.theme.Background
import com.litert.tunnel.ui.theme.BubbleAssistant
import com.litert.tunnel.ui.theme.BubbleUser
import com.litert.tunnel.ui.theme.Error
import com.litert.tunnel.ui.theme.OnSurface
import com.litert.tunnel.ui.theme.OnSurfaceMuted
import com.litert.tunnel.ui.theme.Primary
import com.litert.tunnel.ui.theme.Surface
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,   // "user" | "assistant"
    val content: String,
    val isStreaming: Boolean = false,
)

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    engineReady: Boolean,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
) {
    val s = LocalStrings.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) scope.launch {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .imePadding()   // push content above the keyboard when it appears
    ) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(s.chatTitle, color = OnSurface, fontSize = 16.sp)
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Delete, contentDescription = "Clear", tint = OnSurfaceMuted)
            }
        }

        if (!engineReady) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.engineNotReady, color = OnSurfaceMuted)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
                if (isGenerating) {
                    item {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .height(16.dp)
                                    .widthIn(max = 16.dp),
                                color = Primary,
                                strokeWidth = 2.dp,
                            )
                            Text(s.generating, color = OnSurfaceMuted, fontSize = 13.sp, fontStyle = FontStyle.Italic)
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }

        // Input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(s.messagePlaceholder, color = OnSurfaceMuted) },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = OnSurfaceMuted,
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    cursorColor = Primary,
                ),
                maxLines = 5,
            )
            IconButton(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty() && !isGenerating) {
                        onSend(text)
                        input = ""
                    }
                },
                enabled = input.trim().isNotEmpty() && !isGenerating && engineReady,
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = Primary)
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    if (isUser) BubbleUser else BubbleAssistant,
                    RoundedCornerShape(
                        topStart = 12.dp, topEnd = 12.dp,
                        bottomStart = if (isUser) 12.dp else 2.dp,
                        bottomEnd = if (isUser) 2.dp else 12.dp,
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            SelectionContainer {
                Text(
                    text = if (msg.isStreaming && msg.content.isEmpty()) "…" else msg.content,
                    color = if (msg.content.startsWith("Error:")) Error else OnSurface,
                    fontSize = 14.sp,
                    fontStyle = if (msg.isStreaming && msg.content.isEmpty()) FontStyle.Italic else FontStyle.Normal,
                )
            }
        }
    }
}
