package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.ai.ModerationSensitivity
import com.example.ui.components.CyberGlowingCard
import com.example.ui.components.YutaLogoAvatar
import com.example.ui.theme.CyberBorderSubtle
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SafeEmerald
import com.example.viewmodel.YutaUiState

@Composable
fun SettingsScreen(
    uiState: YutaUiState,
    onSaveSharedBackend: (String, String) -> Unit,
    onTestConnection: () -> Unit,
    onSetupWebhook: () -> Unit,
    onSaveTelegramConfig: (String, String, String) -> Unit,
    onUpdateCustomLogoUri: (String?) -> Unit,
    onToggleImageMod: (Boolean) -> Unit,
    onToggleStickerMod: (Boolean) -> Unit,
    onToggleGifMod: (Boolean) -> Unit,
    onToggleVideoMod: (Boolean) -> Unit,
    onToggleAiChat: (Boolean) -> Unit,
    onToggleAutoDelete: (Boolean) -> Unit,
    onToggleAdminNotify: (Boolean) -> Unit,
    onToggleDisableMediaRetention: (Boolean) -> Unit,
    onSelectSensitivity: (ModerationSensitivity) -> Unit,
    modifier: Modifier = Modifier
) {
    var backendUrlInput by rememberSaveable { mutableStateOf(uiState.settings.sharedBackendUrl) }
    var backendSecretInput by rememberSaveable { mutableStateOf(uiState.settings.sharedAdminSecret) }
    var showSecret by rememberSaveable { mutableStateOf(false) }

    var botTokenInput by rememberSaveable { mutableStateOf(uiState.settings.botToken) }
    var adminIdsInput by rememberSaveable { mutableStateOf(uiState.settings.adminUserIdsRaw) }
    var alertChatIdInput by rememberSaveable { mutableStateOf(uiState.settings.adminAlertChatId) }
    var showToken by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(
        uiState.settings.sharedBackendUrl,
        uiState.settings.sharedAdminSecret,
        uiState.settings.botToken,
        uiState.settings.adminUserIdsRaw,
        uiState.settings.adminAlertChatId
    ) {
        if (backendUrlInput.isBlank() && uiState.settings.sharedBackendUrl.isNotBlank()) {
            backendUrlInput = uiState.settings.sharedBackendUrl
        }
        if (backendSecretInput.isBlank() && uiState.settings.sharedAdminSecret.isNotBlank()) {
            backendSecretInput = uiState.settings.sharedAdminSecret
        }
        if (botTokenInput.isBlank() && uiState.settings.botToken.isNotBlank()) {
            botTokenInput = uiState.settings.botToken
        }
        if (adminIdsInput.isBlank() && uiState.settings.adminUserIdsRaw.isNotBlank()) {
            adminIdsInput = uiState.settings.adminUserIdsRaw
        }
        if (alertChatIdInput.isBlank() && uiState.settings.adminAlertChatId.isNotBlank()) {
            alertChatIdInput = uiState.settings.adminAlertChatId
        }
    }

    val logoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onUpdateCustomLogoUri(uri.toString())
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            CyberGlowingCard(
                glowStart = SafeEmerald,
                glowEnd = NeonCyan
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDone,
                        contentDescription = "Shared Backend",
                        tint = SafeEmerald
                    )
                    Column {
                        Text(
                            text = "Shared HTTPS Backend (Web PWA + Android APK)",
                            style = MaterialTheme.typography.titleLarge,
                            color = PureWhite
                        )
                        Text(
                            text = "अपना Cloudflare Worker HTTPS URL डालें ताकि PWA और APK दोनों एक ही Gemini AI, Telegram Bot और D1 डेटाबेस से जुड़ें (कोई key APK में नहीं रहती)।",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = backendUrlInput,
                    onValueChange = { backendUrlInput = it },
                    label = { Text("Shared Backend HTTPS URL") },
                    placeholder = { Text("https://yuta-ai-worker.yourname.workers.dev", color = CyberTextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SafeEmerald,
                        unfocusedBorderColor = CyberBorderSubtle,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shared_backend_url_input")
                )

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = backendSecretInput,
                    onValueChange = { backendSecretInput = it },
                    label = { Text("Admin Access Passcode (Encrypted on device)") },
                    singleLine = true,
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(
                                imageVector = if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showSecret) "Hide passcode" else "Show passcode",
                                tint = CyberTextSecondary
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SafeEmerald,
                        unfocusedBorderColor = CyberBorderSubtle,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shared_backend_secret_input")
                )

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = { onSaveSharedBackend(backendUrlInput, backendSecretInput) },
                    enabled = !uiState.isVerifyingBackend,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SafeEmerald,
                        contentColor = DeepBlack
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_shared_backend_button")
                ) {
                    if (uiState.isVerifyingBackend) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = DeepBlack, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Syncing Shared Backend...", fontWeight = FontWeight.Bold)
                    } else {
                        Text("Connect & Sync Shared Backend", fontWeight = FontWeight.Bold)
                    }
                }

                uiState.sharedBackendFeedback?.let { feedback ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = DeepBlack,
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = feedback,
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PureWhite,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                if (uiState.sharedBackendConnected || uiState.settings.isSharedBackendConfigured) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onTestConnection,
                        enabled = !uiState.isTestingConnection,
                        border = BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.8f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("test_connection_button")
                    ) {
                        if (uiState.isTestingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NeonCyan, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing Telegram & Gemini...")
                        } else {
                            Text("Test Telegram (getMe) & Gemini")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onSetupWebhook,
                        enabled = !uiState.isRegisteringWebhook,
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("setup_webhook_button")
                    ) {
                        if (uiState.isRegisteringWebhook) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NeonCyan, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Registering Webhook...")
                        } else {
                            Text("Register 24/7 Telegram Webhook")
                        }
                    }

                    uiState.webhookFeedback?.let { wFeedback ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = DeepBlack,
                            border = BorderStroke(1.dp, SafeEmerald.copy(alpha = 0.55f))
                        ) {
                            Text(
                                text = wFeedback,
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.bodyMedium,
                                color = PureWhite,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            CyberGlowingCard(
                glowStart = NeonCyan,
                glowEnd = ElectricBlue
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Key,
                        contentDescription = "Telegram credentials",
                        tint = NeonCyan
                    )
                    Column {
                        Text(
                            text = "Direct Tablet Bot Mode (Optional)",
                            style = MaterialTheme.typography.titleLarge,
                            color = PureWhite
                        )
                        Text(
                            text = "यदि आप बिना Shared Backend के सीधे टैबलेट से Polling चलाना चाहते हैं, तो @BotFather टोकन Android Keystore (AES-256) से एन्क्रिप्ट होकर सेव होगा।",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = botTokenInput,
                    onValueChange = { botTokenInput = it },
                    label = { Text("Telegram Bot Token (@BotFather)") },
                    placeholder = { Text("123456789:AAH...", color = CyberTextMuted) },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                imageVector = if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showToken) "Hide token" else "Show token",
                                tint = CyberTextSecondary
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CyberBorderSubtle,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bot_token_input")
                )

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = adminIdsInput,
                    onValueChange = { adminIdsInput = it },
                    label = { Text("Authorized Admin User IDs (comma-separated)") },
                    placeholder = { Text("e.g. 987654321, 112233445", color = CyberTextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CyberBorderSubtle,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("admin_ids_input")
                )

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = alertChatIdInput,
                    onValueChange = { alertChatIdInput = it },
                    label = { Text("Admin Alert Chat ID (for UNCERTAIN reviews)") },
                    placeholder = { Text("Your Telegram User ID or Admin Group ID", color = CyberTextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CyberBorderSubtle,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("alert_chat_id_input")
                )

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        onSaveTelegramConfig(botTokenInput, adminIdsInput, alertChatIdInput)
                    },
                    enabled = !uiState.isVerifyingToken,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = DeepBlack
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_verify_token_button")
                ) {
                    if (uiState.isVerifyingToken) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = DeepBlack,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verifying with Telegram...", fontWeight = FontWeight.Bold)
                    } else {
                        Text("Save & Verify Bot Token", fontWeight = FontWeight.Bold)
                    }
                }

                uiState.tokenVerificationFeedback?.let { feedback ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = DeepBlack,
                        border = BorderStroke(1.dp, SafeEmerald.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = feedback,
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PureWhite,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }

        item {
            CyberGlowingCard(
                glowStart = NeonPurple,
                glowEnd = ElectricBlue
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    YutaLogoAvatar(customLogoUri = uiState.settings.customLogoUri, size = 56.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Official Yuta AI Logo",
                            style = MaterialTheme.typography.titleMedium,
                            color = PureWhite
                        )
                        Text(
                            text = "अपना अपलोड किया हुआ Yuta लोगो सीधे टैबलेट गैलरी से सेट करें।",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberTextSecondary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            logoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("pick_custom_logo_button")
                    ) {
                        Icon(imageVector = Icons.Rounded.Image, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Choose Logo")
                    }
                    if (!uiState.settings.customLogoUri.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = { onUpdateCustomLogoUri(null) },
                            border = BorderStroke(1.dp, CyberBorderSubtle),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberTextSecondary),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Reset")
                        }
                    }
                }
            }
        }

        item {
            CyberGlowingCard(
                glowStart = ElectricBlue,
                glowEnd = NeonPurple
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AdminPanelSettings,
                        contentDescription = "Moderation controls",
                        tint = NeonPurple
                    )
                    Text(
                        text = "Moderation & Security Controls",
                        style = MaterialTheme.typography.titleLarge,
                        color = PureWhite
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                SettingSwitchRow(
                    title = "Image Moderation",
                    subtitle = "Scan Telegram photos & image documents",
                    checked = uiState.settings.enableImageModeration,
                    onCheckedChange = onToggleImageMod,
                    tag = "switch_image_mod"
                )
                SettingSwitchRow(
                    title = "Sticker Moderation",
                    subtitle = "Scan static stickers & animated/video sticker thumbnails",
                    checked = uiState.settings.enableStickerModeration,
                    onCheckedChange = onToggleStickerMod,
                    tag = "switch_sticker_mod"
                )
                SettingSwitchRow(
                    title = "GIF Moderation",
                    subtitle = "Scan animated GIF files & previews",
                    checked = uiState.settings.enableGifModeration,
                    onCheckedChange = onToggleGifMod,
                    tag = "switch_gif_mod"
                )
                SettingSwitchRow(
                    title = "Video Thumbnail Moderation",
                    subtitle = "Inspect video preview thumbnails within 10MB cap",
                    checked = uiState.settings.enableVideoModeration,
                    onCheckedChange = onToggleVideoMod,
                    tag = "switch_video_mod"
                )
                SettingSwitchRow(
                    title = "AI Chat (/chat)",
                    subtitle = "Allow bilingual Hindi/English assistant replies",
                    checked = uiState.settings.enableAiChat,
                    onCheckedChange = onToggleAiChat,
                    tag = "switch_ai_chat"
                )
                SettingSwitchRow(
                    title = "Auto-Delete Violations",
                    subtitle = "Delete only confirmed SEXUAL_CONTENT when bot is Admin",
                    checked = uiState.settings.enableAutoDelete,
                    onCheckedChange = onToggleAutoDelete,
                    tag = "switch_auto_delete"
                )
                SettingSwitchRow(
                    title = "Admin Notifications",
                    subtitle = "Forward UNCERTAIN media & failures to Admin Chat ID",
                    checked = uiState.settings.enableAdminNotifications,
                    onCheckedChange = onToggleAdminNotify,
                    tag = "switch_admin_notify"
                )
                SettingSwitchRow(
                    title = "Disable Media Retention",
                    subtitle = "Zero-disk privacy: never save user photos on tablet storage",
                    checked = uiState.settings.disableMediaRetention,
                    onCheckedChange = onToggleDisableMediaRetention,
                    tag = "switch_zero_retention"
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = "Sensitivity",
                        tint = NeonCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Moderation Sensitivity",
                        style = MaterialTheme.typography.titleMedium,
                        color = PureWhite
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ModerationSensitivity.entries.forEach { level ->
                        FilterChip(
                            selected = uiState.settings.sensitivity == level,
                            onClick = { onSelectSensitivity(level) },
                            label = { Text(level.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonCyan.copy(alpha = 0.22f),
                                selectedLabelColor = NeonCyan,
                                labelColor = CyberTextSecondary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = uiState.settings.sensitivity.hindiHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextSecondary
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = PureWhite
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DeepBlack,
                checkedTrackColor = NeonCyan,
                uncheckedThumbColor = CyberTextSecondary,
                uncheckedTrackColor = DeepBlack
            ),
            modifier = Modifier.testTag(tag)
        )
    }
}
