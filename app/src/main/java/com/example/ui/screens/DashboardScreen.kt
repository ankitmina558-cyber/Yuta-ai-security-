package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FaceRetouchingNatural
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.MarkChatRead
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.ai.ModerationVerdict
import com.example.data.local.ModerationLogEntity
import com.example.data.telegram.BotConnectionStatus
import com.example.ui.components.CyberGlowingCard
import com.example.ui.components.FirebaseNoticeBanner
import com.example.ui.components.MetricTile
import com.example.ui.components.NeonStatusPill
import com.example.ui.components.YutaLogoAvatar
import com.example.ui.theme.CyberBorderSubtle
import com.example.ui.theme.CyberElevatedSurface
import com.example.ui.theme.CyberTextMuted
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.InfoSky
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SafeEmerald
import com.example.ui.theme.ViolationCrimson
import com.example.ui.theme.WarningAmber
import com.example.viewmodel.YutaUiState

@Composable
fun DashboardScreen(
    uiState: YutaUiState,
    onTogglePolling: () -> Unit,
    onToggleBackgroundMonitor: () -> Unit,
    onRetryStickerQueue: () -> Unit,
    onSyncSharedBackend: () -> Unit,
    onRunCommandPreview: (String) -> Unit,
    onOpenModeration: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onToggleBackgroundMonitor()
    }

    val isPolling = uiState.botRuntime.status == BotConnectionStatus.POLLING ||
        uiState.botRuntime.status == BotConnectionStatus.CONNECTING ||
        uiState.botRuntime.status == BotConnectionStatus.RATE_LIMITED

    val connectionColor = when (uiState.botRuntime.status) {
        BotConnectionStatus.CONNECTED, BotConnectionStatus.POLLING -> SafeEmerald
        BotConnectionStatus.CONNECTING -> InfoSky
        BotConnectionStatus.RATE_LIMITED -> WarningAmber
        BotConnectionStatus.ERROR -> ViolationCrimson
        BotConnectionStatus.DISCONNECTED -> CyberTextMuted
    }

    var selectedCommand by rememberSaveable { mutableStateOf("/start") }
    val commands = listOf("/start", "/help", "/status", "/scan", "/settings", "/chat hello", "/about")

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            HeroCommandCard(
                uiState = uiState,
                isPolling = isPolling,
                connectionColor = connectionColor,
                onTogglePolling = if (uiState.sharedBackendConnected) onSyncSharedBackend else onTogglePolling,
                onOpenSettings = onOpenSettings
            )
        }

        if (!uiState.sharedBackendConnected) {
            item {
                FirebaseNoticeBanner()
            }
        }

        // DEDICATED 24/7 STICKER SCANNER SECTION
        item {
            StickerScannerSection(
                uiState = uiState,
                onToggle247Service = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !uiState.botRuntime.isForegroundServiceActive &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onToggleBackgroundMonitor()
                    }
                },
                onRetryQueue = onRetryStickerQueue
            )
        }

        item {
            Text(
                text = "Overall Moderation Telemetry (SQLite)",
                style = MaterialTheme.typography.titleLarge,
                color = PureWhite
            )
            Spacer(modifier = Modifier.height(10.dp))
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= 700.dp) 3 else 2
                val metrics = listOf(
                    Triple("IMAGES SCANNED", uiState.stats.totalImagesScanned.toString(), "Photos & docs") to Pair(Icons.Rounded.ImageSearch, NeonCyan),
                    Triple("STICKERS SCANNED", uiState.stats.totalStickersScanned.toString(), "WEBP / TGS / WEBM") to Pair(Icons.Rounded.FaceRetouchingNatural, ElectricBlue),
                    Triple("GIFS & VIDEOS", uiState.stats.totalVideosAndGifsScanned.toString(), "Thumbnails & GIFs") to Pair(Icons.Rounded.VideoFile, NeonPurple),
                    Triple("VIOLATIONS", uiState.stats.totalViolationsDetected.toString(), "Confirmed") to Pair(Icons.Rounded.ReportProblem, ViolationCrimson),
                    Triple("AUTO DELETED", uiState.stats.totalMessagesDeleted.toString(), "Group cleanup") to Pair(Icons.Rounded.DeleteSweep, SafeEmerald),
                    Triple("UNCERTAIN / FAIL", uiState.stats.totalUncertainOrFailed.toString(), "Admin review queue") to Pair(Icons.Rounded.WarningAmber, WarningAmber)
                )

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    metrics.chunked(columns).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowItems.forEach { (textTriple, iconPair) ->
                                MetricTile(
                                    title = textTriple.first,
                                    value = textTriple.second,
                                    subtitle = textTriple.third,
                                    icon = iconPair.first,
                                    accentColor = iconPair.second,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (rowItems.size < columns) {
                                repeat(columns - rowItems.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            CyberGlowingCard(
                glowStart = ElectricBlue,
                glowEnd = NeonCyan
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = "Command simulator",
                        tint = NeonCyan
                    )
                    Text(
                        text = "Telegram Command Tester",
                        style = MaterialTheme.typography.titleMedium,
                        color = PureWhite
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "टैबलेट पर ही सभी 7 बॉट कमांड्स का लाइव आउटपुट जांचें:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    commands.forEach { cmd ->
                        FilterChip(
                            selected = selectedCommand == cmd,
                            onClick = {
                                selectedCommand = cmd
                                onRunCommandPreview(cmd)
                            },
                            label = {
                                Text(
                                    text = cmd.substringBefore(" "),
                                    fontFamily = JetBrainsMonoFontFamily
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonCyan.copy(alpha = 0.2f),
                                selectedLabelColor = NeonCyan,
                                labelColor = CyberTextSecondary
                            ),
                            modifier = Modifier.testTag("cmd_chip_${cmd.removePrefix("/").substringBefore(" ")}")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = DeepBlack,
                    border = BorderStroke(1.dp, CyberBorderSubtle)
                ) {
                    Text(
                        text = uiState.commandPreviewOutput,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeonCyan,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Moderation Activity",
                    style = MaterialTheme.typography.titleLarge,
                    color = PureWhite
                )
                Button(
                    onClick = onOpenModeration,
                    colors = ButtonDefaults.textButtonColors(contentColor = NeonCyan),
                    modifier = Modifier.testTag("view_all_logs_button")
                ) {
                    Text("Open Scanner")
                }
            }
        }

        if (uiState.moderationLogs.isEmpty()) {
            item {
                CyberGlowingCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = "No scans yet",
                            tint = NeonCyan,
                            modifier = Modifier.size(32.dp)
                        )
                        Column {
                            Text(
                                text = "No moderation events recorded yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = PureWhite
                            )
                            Text(
                                text = "Telegram Bot Token जोड़कर 24/7 Sticker Monitor शुरू करें या Scanner टैब से .webp/.tgs/.webm स्टिकर टेस्ट करें।",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberTextSecondary
                            )
                        }
                    }
                }
            }
        } else {
            items(uiState.moderationLogs.take(5), key = { "${it.id}_${it.messageKey}" }) { log ->
                ModerationActivityRow(log = log)
            }
        }
    }
}

@Composable
private fun StickerScannerSection(
    uiState: YutaUiState,
    onToggle247Service: () -> Unit,
    onRetryQueue: () -> Unit
) {
    val st = uiState.stickerStats
    val uptimeFormatted = formatSecondsHhMmSs(uiState.scannerUptimeSeconds)
    val aiLabelShort = when {
        uiState.aiStatus.isAvailable -> "Connected"
        uiState.aiStatus.hasError -> "Error"
        else -> "Disconnected"
    }
    val aiTileColor = when {
        uiState.aiStatus.isAvailable -> SafeEmerald
        uiState.aiStatus.hasError -> ViolationCrimson
        else -> WarningAmber
    }
    val fgsActive = uiState.botRuntime.isForegroundServiceActive

    CyberGlowingCard(
        glowStart = NeonPurple,
        glowEnd = NeonCyan,
        modifier = Modifier.testTag("sticker_scanner_section")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            YutaLogoAvatar(
                customLogoUri = uiState.settings.customLogoUri,
                size = 40.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "24/7 Sticker Scanner",
                    style = MaterialTheme.typography.titleLarge,
                    color = PureWhite
                )
                Text(
                    text = "WEBP Static • TGS Preview • WEBM Frame Queue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!uiState.sharedBackendConnected) {
                Button(
                    onClick = onToggle247Service,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (fgsActive) ViolationCrimson else ElectricBlue,
                        contentColor = PureWhite
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("toggle_247_service_button")
                ) {
                    Text(
                        text = if (fgsActive) "Stop 24/7 Guard" else "Run 24/7 on Tablet",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            OutlinedButton(
                onClick = onRetryQueue,
                enabled = !uiState.isDrainingQueue,
                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.7f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("retry_sticker_queue_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Drain queue",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (uiState.isDrainingQueue) "Processing..." else "Retry Queue")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cols = if (maxWidth >= 700.dp) 4 else 2
            val stickerMetrics = listOf(
                Triple("STICKERS RECEIVED", st.totalStickersReceived.toString(), "Unique messages") to Pair(Icons.Rounded.MarkChatRead, NeonCyan),
                Triple("STICKERS SCANNED", st.totalStickersScanned.toString(), "Frames verified") to Pair(Icons.Rounded.FaceRetouchingNatural, ElectricBlue),
                Triple("VIOLATIONS", st.violationsDetected.toString(), "Confirmed") to Pair(Icons.Rounded.ReportProblem, ViolationCrimson),
                Triple("DELETED", st.messagesDeleted.toString(), "Bot admin check") to Pair(Icons.Rounded.DeleteSweep, SafeEmerald),
                Triple("PENDING SCANS", st.pendingScans.toString(), "Async queue") to Pair(Icons.Rounded.HourglassTop, NeonPurple),
                Triple("FAILED SCANS", st.failedScans.toString(), "Never marked safe") to Pair(Icons.Rounded.ErrorOutline, WarningAmber),
                Triple("SCANNER UPTIME", uptimeFormatted, if (uiState.sharedBackendConnected) "24/7 Cloud" else if (fgsActive) "Foreground Active" else "Local Session") to Pair(Icons.Rounded.Timer, NeonCyan),
                Triple("AI SERVICE", aiLabelShort, uiState.aiStatus.modelLabel) to Pair(Icons.Rounded.SmartToy, aiTileColor)
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                stickerMetrics.chunked(cols).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { (textTriple, iconPair) ->
                            MetricTile(
                                title = textTriple.first,
                                value = textTriple.second,
                                subtitle = textTriple.third,
                                icon = iconPair.first,
                                accentColor = iconPair.second,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSecondsHhMmSs(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val h = safe / 3600L
    val m = (safe % 3600L) / 60L
    val s = safe % 60L
    return "%02d:%02d:%02d".format(h, m, s)
}

@Composable
private fun HeroCommandCard(
    uiState: YutaUiState,
    isPolling: Boolean,
    connectionColor: Color,
    onTogglePolling: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = CyberElevatedSurface,
        border = BorderStroke(
            1.4.dp,
            Brush.linearGradient(listOf(NeonCyan, ElectricBlue, NeonPurple))
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.img_yuta_hero_banner),
                contentDescription = stringResource(R.string.cd_hero_banner),
                contentScale = ContentScale.Crop,
                alpha = 0.34f,
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                DeepBlack.copy(alpha = 0.45f),
                                DeepBlack.copy(alpha = 0.88f)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    YutaLogoAvatar(
                        customLogoUri = uiState.settings.customLogoUri,
                        size = 62.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "YUTA AI",
                            style = MaterialTheme.typography.headlineMedium,
                            color = PureWhite
                        )
                        Text(
                            text = uiState.botRuntime.botUsername?.let { "@$it • 24/7 Sticker Guard" }
                                ?: "Telegram Group Moderation & Assistant",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NeonStatusPill(
                        label = "TG: ${uiState.botRuntime.status.label}",
                        accentColor = connectionColor,
                        isPulsing = isPolling,
                        modifier = Modifier.weight(1f)
                    )
                    NeonStatusPill(
                        label = when {
                            uiState.aiStatus.isAvailable -> "AI: Connected"
                            uiState.aiStatus.hasError -> "AI: Error"
                            else -> "AI: Disconnected"
                        },
                        accentColor = when {
                            uiState.aiStatus.isAvailable -> SafeEmerald
                            uiState.aiStatus.hasError -> ViolationCrimson
                            else -> WarningAmber
                        },
                        isPulsing = uiState.aiStatus.isAvailable,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = uiState.botRuntime.lastStatusNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onTogglePolling,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.sharedBackendConnected) SafeEmerald else if (isPolling) ViolationCrimson else NeonCyan,
                            contentColor = if (uiState.sharedBackendConnected || !isPolling) DeepBlack else PureWhite
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("toggle_polling_button")
                    ) {
                        Icon(
                            imageVector = if (uiState.sharedBackendConnected) Icons.Rounded.Refresh else if (isPolling) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.sharedBackendConnected) "Sync Cloud" else if (isPolling) "Stop Bot" else "Start Bot",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite),
                        border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("hero_bot_setup_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = NeonPurple
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Bot Setup")
                    }
                }
            }
        }
    }
}

@Composable
fun ModerationActivityRow(log: ModerationLogEntity) {
    val badgeColor = when {
        ModerationVerdict.isViolationName(log.verdict) -> ViolationCrimson
        log.verdict == "UNCERTAIN" -> WarningAmber
        log.verdict == "SCAN_FAILED" -> NeonPurple
        else -> SafeEmerald
    }
    CyberGlowingCard(
        glowStart = badgeColor,
        glowEnd = ElectricBlue
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${log.mediaKind} • ${log.chatTitle}",
                    style = MaterialTheme.typography.titleMedium,
                    color = PureWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "By ${log.senderName} • Action: ${log.actionTaken}",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberTextSecondary
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = badgeColor.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.55f))
            ) {
                Text(
                    text = log.verdict,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = log.reason,
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextSecondary
        )
    }
}
