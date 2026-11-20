package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.di.YutaAppContainer
import com.example.ui.YutaAppShell
import com.example.ui.components.YutaLogoAvatar
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.PureWhite
import com.example.ui.theme.YutaAiTheme
import com.example.viewmodel.YutaViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val appContainer: YutaAppContainer by lazy {
        YutaAppContainer.getInstance(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            YutaAiTheme {
                val yutaViewModel: YutaViewModel = viewModel(
                    factory = YutaViewModel.factory(appContainer)
                )
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(650L)
                    showSplash = false
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    YutaAppShell(viewModel = yutaViewModel)
                    AnimatedVisibility(
                        visible = showSplash,
                        exit = fadeOut(animationSpec = tween(350))
                    ) {
                        YutaSplashOverlay()
                    }
                }
            }
        }
    }
}

@Composable
private fun YutaSplashOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            YutaLogoAvatar(customLogoUri = null, size = 96.dp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "YUTA AI",
                style = MaterialTheme.typography.displaySmall,
                color = PureWhite
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Telegram Cyber Moderation Engine",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextSecondary
            )
        }
    }
}
