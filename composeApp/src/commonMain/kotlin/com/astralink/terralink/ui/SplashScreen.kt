package com.astralink.terralink.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import terralink.composeapp.generated.resources.Res
import terralink.composeapp.generated.resources.upv_logo

private const val SPLASH_DURATION_MS = 1_500L

// "Savia" = plant sap -> a deep green wordmark, fixed so it reads on white in any theme.
private val SaviaGreen = Color(0xFF1B5E20)

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(SPLASH_DURATION_MS)
        onTimeout()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(Res.drawable.upv_logo),
                contentDescription = "UPV",
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(160.dp),
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = "SAVIA",
                color = SaviaGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp,
                letterSpacing = 8.sp,
            )
        }
    }
}
