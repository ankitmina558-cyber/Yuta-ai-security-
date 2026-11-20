package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.ChatMessageEntity
import com.example.ui.components.FirebaseNoticeBanner
import com.example.ui.components.YutaLogoAvatar
import com.example.ui.theme.CyberBorderSubtle
import com.example.ui.theme.CyberCardSurface
import com.example.ui.theme.CyberElevatedSurface
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SafeEmerald
import com.example.viewmodel.YutaUiState

@Composable
fun AiChatScreen(
    uiState: YutaUiState,
    onSendMessage: (String) -> Unit,
    onClearChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val quickPrompts = listOf(
        "Yuta AI kaise kaam karta hai?",
        "Free tier limits kya hain?",
        "Telegram group mein admin kaise banayein?",
        "Show security rules"
    )

    LaunchedEffect(uiState.chatMessages.size, uiState.streamingChatText) {
        val total = uiState.chatMessages.size + if (uiState.streamingChatText != null) 1 else 0
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                YutaLogoAvatar(customLogoUri = uiState.settings.customLogoUri, size = 40.dp)
                Column {
                    Text(
                        text = "Yuta AI Assistant",
                        style = MaterialTheme.typography.titleLarge,
                        color = PureWhite
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Isolated chat",
                            tint = SafeEmerald,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Hindi & English • Keys & Logs Isolated",
                            style = MaterialTheme.typography.labelSmall,
                            color = SafeEmerald
                        )
                    }
                }
            }

            if (uiState.chatMessages.isNotEmpty()) {
                TextButton(
                    onClick = onClearChat,
                    modifier = Modifier.testTag("clear_chat_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Clear chat",
                        tint = CyberTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear", color = CyberTextSecondary)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        if (!uiState.sharedBackendConnected) {
            FirebaseNoticeBanner()
            Spacer(modifier = Modifier.height(10.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickPrompts.forEach { prompt ->
                AssistChip(
                    onClick = { onSendMessage(prompt) },
                    label = { Text(prompt) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = CyberCardSurface,
                        labelColor = NeonCyan
                    ),
                    border = BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.5f))
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (uiState.chatMessages.isEmpty() && uiState.streamingChatText == null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = CyberCardSurface,
                        border = BorderStroke(1.dp, CyberBorderSubtle)
                    ) {
                        Text(
                            text = "नमस्ते! मैं Yuta AI हूँ। आप मुझसे Hindi या English में सवाल पूछ सकते हैं। Group moderation डेटा और Telegram Bot Token इस चैट से पूरी तरह अलग रखे जाते हैं।",
                            style = MaterialTheme.typography.bodyLarge,
                            color = CyberTextSecondary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            items(uiState.chatMessages, key = { it.id }) { msg ->
                ChatBubble(message = msg)
            }

            uiState.streamingChatText?.let { partial ->
                item(key = "streaming_bubble") {
                    ChatBubble(
                        message = ChatMessageEntity(
                            role = "model",
                            content = partial.ifEmpty { "Typing..." },
                            isPlaceholderAi = true
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        text = "Hindi या English में पूछें...",
                        color = CyberTextMuted
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = CyberBorderSubtle,
                    focusedTextColor = PureWhite,
                    unfocusedTextColor = PureWhite,
                    focusedContainerColor = CyberCardSurface,
                    unfocusedContainerColor = CyberCardSurface
                ),
                shape = RoundedCornerShape(16.dp),
                maxLines = 3,
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input_field")
            )

            IconButton(
                onClick = {
                    val toSend = inputText.trim()
                    if (toSend.isNotEmpty()) {
                        inputText = ""
                        onSendMessage(toSend)
                    }
                },
                enabled = !uiState.isChatLoading && inputText.isNotBlank(),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = NeonCyan,
                    contentColor = DeepBlack,
                    disabledContainerColor = CyberElevatedSurface,
                    disabledContentColor = CyberTextMuted
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("send_chat_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = "Send message"
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessageEntity) {
    val isUser = message.role == "user"
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            color = if (isUser) ElectricBlue.copy(alpha = 0.25f) else CyberCardSurface,
            border = BorderStroke(
                1.dp,
                if (isUser) NeonPurple.copy(alpha = 0.7f) else NeonCyan.copy(alpha = 0.55f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (isUser) "You (${message.source})" else "Yuta AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) NeonPurple else NeonCyan
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = PureWhite
                )
            }
        }
    }
}
