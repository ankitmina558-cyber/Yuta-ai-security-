package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FaceRetouchingNatural
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.ai.MediaKind
import com.example.ui.components.CyberGlowingCard
import com.example.ui.components.FirebaseNoticeBanner
import com.example.ui.theme.CyberBorderSubtle
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SafeEmerald
import com.example.ui.theme.ViolationCrimson
import com.example.viewmodel.LogFilter
import com.example.viewmodel.YutaUiState

@Composable
fun ModerationScreen(
    uiState: YutaUiState,
    onSelectFilter: (LogFilter) -> Unit,
    onScanLocalMedia: (android.net.Uri, MediaKind) -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedMediaKind by rememberSaveable { mutableStateOf(MediaKind.STICKER_STATIC) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onScanLocalMedia(uri, selectedMediaKind)
        }
    }

    // Zero-permission SAF picker for .webp, .tgs, .webm sticker test files
    val stickerDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onScanLocalMedia(uri, selectedMediaKind)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!uiState.sharedBackendConnected) {
            item {
                FirebaseNoticeBanner()
            }
        }

        item {
            CyberGlowingCard(
                glowStart = NeonCyan,
                glowEnd = NeonPurple
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = "Sticker scanner",
                        tint = NeonCyan
                    )
                    Column {
                        Text(
                            text = if (uiState.sharedBackendConnected) "Live Gemini Sticker & Media Scanner" else "Tablet Sticker & Media Scanner",
                            style = MaterialTheme.typography.titleLarge,
                            color = PureWhite
                        )
                        Text(
                            text = "Static .webp, Video .webm (फ्रेम निकालकर) और Animated .tgs (सुरक्षित UNCERTAIN जांच) टेस्ट करें। कोई भी अनस्कैन स्टिकर SAFE नहीं माना जाता।",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Sticker / Media Format:",
                    style = MaterialTheme.typography.labelLarge,
                    color = PureWhite
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        MediaKind.STICKER_STATIC,
                        MediaKind.STICKER_VIDEO,
                        MediaKind.STICKER_ANIMATED,
                        MediaKind.PHOTO,
                        MediaKind.GIF
                    ).forEach { kind ->
                        FilterChip(
                            selected = selectedMediaKind == kind,
                            onClick = { selectedMediaKind = kind },
                            label = { Text(kind.displayName) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricBlue.copy(alpha = 0.25f),
                                selectedLabelColor = NeonCyan,
                                labelColor = CyberTextSecondary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (selectedMediaKind == MediaKind.STICKER_VIDEO || selectedMediaKind == MediaKind.STICKER_ANIMATED) {
                                stickerDocLauncher.launch(
                                    arrayOf(
                                        "video/webm",
                                        "application/x-tgsticker",
                                        "application/gzip",
                                        "application/octet-stream",
                                        "image/*"
                                    )
                                )
                            } else {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        },
                        enabled = !uiState.isScanningLocalMedia,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = DeepBlack
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("pick_media_scan_button")
                    ) {
                        if (uiState.isScanningLocalMedia) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = DeepBlack,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scanning...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(imageVector = Icons.Rounded.PhotoLibrary, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when (selectedMediaKind) {
                                    MediaKind.STICKER_VIDEO -> "Pick .WEBM"
                                    MediaKind.STICKER_ANIMATED -> "Pick .TGS"
                                    else -> "Photo / WEBP"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            stickerDocLauncher.launch(
                                arrayOf(
                                    "image/*",
                                    "video/webm",
                                    "application/x-tgsticker",
                                    "application/gzip",
                                    "application/octet-stream"
                                )
                            )
                        },
                        enabled = !uiState.isScanningLocalMedia,
                        border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.8f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("pick_sticker_file_button")
                    ) {
                        Icon(imageVector = Icons.Rounded.FaceRetouchingNatural, contentDescription = null, tint = NeonPurple)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pick .TGS/.WEBM")
                    }
                }

                uiState.latestScanFeedback?.let { feedback ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = DeepBlack,
                        border = BorderStroke(1.dp, SafeEmerald.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = feedback,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PureWhite,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
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
                    text = "Moderation Audit Logs (${uiState.filteredLogs.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = PureWhite
                )
                if (uiState.moderationLogs.isNotEmpty() && !uiState.sharedBackendConnected) {
                    OutlinedButton(
                        onClick = onClearLogs,
                        border = BorderStroke(1.dp, ViolationCrimson.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ViolationCrimson),
                        modifier = Modifier.testTag("clear_logs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Clear logs",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LogFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = uiState.selectedFilter == filter,
                        onClick = { onSelectFilter(filter) },
                        label = { Text(filter.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NeonCyan.copy(alpha = 0.2f),
                            selectedLabelColor = NeonCyan,
                            labelColor = CyberTextSecondary
                        )
                    )
                }
            }
        }

        if (uiState.filteredLogs.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = DeepBlack,
                    border = BorderStroke(1.dp, CyberBorderSubtle)
                ) {
                    Text(
                        text = "No moderation records recorded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextSecondary,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }
        } else {
            items(uiState.filteredLogs, key = { "${it.id}_${it.messageKey}" }) { log ->
                ModerationActivityRow(log = log)
            }
        }
    }
}
