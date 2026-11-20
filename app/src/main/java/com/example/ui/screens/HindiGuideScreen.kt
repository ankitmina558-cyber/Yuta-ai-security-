package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.components.CyberGlowingCard
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.PureWhite
import com.example.ui.theme.WarningAmber

private data class GuideStage(
    val number: String,
    val title: String,
    val description: String
)

@Composable
fun HindiGuideScreen(
    modifier: Modifier = Modifier
) {
    val stages = listOf(
        GuideStage(
            "Step 1 — 24/7 Shared Backend ($0)",
            "Cloudflare Worker + D1 Sticker Queue (Tablet Browser)",
            "टैबलेट ब्राउज़र में dash.cloudflare.com खोलें (कोई क्रेडिट कार्ड नहीं लगता)। Workers & Pages -> Create Worker में backend-worker/worker.js पेस्ट करें। D1 SQLite में schema.sql (sticker_queue + scanner_meta सहित) चलाएं और Settings -> Secrets में TELEGRAM_BOT_TOKEN, GEMINI_API_KEY और ADMIN_SECRET सेव करें।"
        ),
        GuideStage(
            "Step 2 — Sticker Formats & Frame Conversion",
            "WEBP, TGS और WEBM स्टिकर्स की जांच",
            "• Static .webp: सीधे स्कैन होता है।\n• Video .webm: Android ऐप MediaMetadataRetriever से फ्रेम निकालता है और Worker Telegram thumbnail फ्रेम उपयोग करता है।\n• Animated .tgs: यह Lottie JSON होता है — Telegram thumbnail स्कैन होता है; thumbnail न होने पर इसे कभी SAFE नहीं माना जाता बल्कि UNCERTAIN मानकर Admin को forward किया जाता है।"
        ),
        GuideStage(
            "Step 3 — 24/7 Tablet Monitor Service (Optional)",
            "बिना क्लाउड के सीधे टैबलेट से स्कैनिंग",
            "यदि आप बिना Cloudflare के सीधे टैबलेट पर बॉट चलाना चाहते हैं, तो Dashboard के '24/7 Sticker Scanner' में 'Run 24/7 on Tablet' दबाएं। यह Android Foreground Service (dataSync) और Room SQLite sticker_queue को चालू रखेगा।"
        ),
        GuideStage(
            "Step 4 — Web PWA + Android APK (com.yuta.ai)",
            "दोनों वर्ज़न एक ही डेटाबेस से सिंक",
            "web-pwa/ को GitHub Pages या Cloudflare Pages पर डालें और Chrome से Install करें। फिर Web PWA और Android APK दोनों के Settings में एक ही Cloudflare Worker HTTPS URL डालें — सभी Sticker Stats, Uptime और Logs लाइव सिंक रहेंगे।"
        ),
        GuideStage(
            "Step 5 — Easy Updates",
            "Web और APK अपडेट करना",
            "Web PWA अपडेट करने के लिए केवल GitHub/Cloudflare में नई फ़ाइलें अपलोड करें — Service Worker नया वर्ज़न अपने-आप अपडेट कर देगा। Android APK अपडेट करने के लिए नया APK डाउनलोड करके ऊपर से इंस्टॉल करें (Package ID: com.yuta.ai)।"
        )
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DeepBlack),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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
                        imageVector = Icons.Rounded.AccountTree,
                        contentDescription = "Architecture",
                        tint = NeonCyan
                    )
                    Text(
                        text = "Yuta AI 24/7 Sticker Guard Architecture",
                        style = MaterialTheme.typography.titleLarge,
                        color = PureWhite
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Async Queue: हर आने वाला स्टिकर पहले Room/D1 sticker_queue में जुड़ता है और बैकग्राउंड में 3 retries के साथ स्कैन होता है ताकि ग्रुप चैट स्लो न हो।\n" +
                        "• Strict Safety: कोई भी अनस्कैन, corrupted या बिना फ्रेम वाला स्टिकर कभी SAFE नहीं माना जाता — या तो UNCERTAIN (Admin review) या SCAN_FAILED रहता है।\n" +
                        "• Security: Bot Token और Gemini Key कभी PWA JS या APK में नहीं डाले जाते।",
                    style = MaterialTheme.typography.bodyLarge,
                    color = CyberTextSecondary
                )
            }
        }

        item {
            CyberGlowingCard(
                glowStart = WarningAmber,
                glowEnd = ElectricBlue
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = "Honest limitations",
                        tint = WarningAmber
                    )
                    Text(
                        text = "ईमानदार सीमाएँ (Free-Tier Limitations)",
                        style = MaterialTheme.typography.titleMedium,
                        color = WarningAmber
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "• True 24/7 uptime के लिए Cloudflare Worker Webhook सबसे अच्छा है क्योंकि Android OS बैटरी बचाने के लिए बैकग्राउंड टैबलेट ऐप को सुला (Doze) सकता है।\n" +
                        "• Cloudflare Free में 100,000 req/day और Gemini Free Tier में प्रति-मिनट सीमा (RPM/TPM) होती है।\n" +
                        "• Cloudflare Worker में FFmpeg नहीं होता, इसलिए बिना thumbnail वाले .webm/.tgs स्टिकर Admin review (UNCERTAIN) पर जाते हैं।",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextSecondary
                )
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                    contentDescription = "Step by step tablet guide",
                    tint = NeonCyan
                )
                Text(
                    text = "Tablet Browser Guide (सरल हिंदी में)",
                    style = MaterialTheme.typography.titleLarge,
                    color = PureWhite
                )
            }
        }

        items(stages, key = { it.number }) { stage ->
            CyberGlowingCard(
                glowStart = ElectricBlue,
                glowEnd = NeonCyan
            ) {
                Text(
                    text = "${stage.number}: ${stage.title}",
                    style = MaterialTheme.typography.titleMedium,
                    color = NeonCyan
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stage.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextSecondary
                )
            }
        }
    }
}
