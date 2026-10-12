package com.airbridge.app.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbridge.app.model.ConnectionState

@Composable
fun MicScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isStreaming by viewModel.isMicStreaming.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val vuLevel by viewModel.vuLevel.collectAsState()
    val gain by viewModel.gainMultiplier.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    val isConnected = connectionState is ConnectionState.Connected

    // Pulse animation based on live audio VU level
    val animatedScale by animateFloatAsState(
        targetValue = if (isStreaming && !isMuted) 1.0f + (vuLevel * 0.25f) else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "MicScale"
    )

    val buttonBgColor by animateColorAsState(
        targetValue = when {
            !isStreaming -> SurfaceDark
            isMuted -> CrimsonRed.copy(alpha = 0.8f)
            else -> EmeraldGreen
        },
        label = "ButtonBgColor"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Status header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(
                text = "PHONE AS PC MICROPHONE",
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Bold,
                color = CyanAccent
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    !isStreaming -> "Tap mic to stream to PC"
                    isMuted -> "Microphone MUTED"
                    else -> "Streaming Live Audio (48 kHz PCM)"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (isStreaming && !isMuted) EmeraldGreen else TextSecondary
            )
        }

        // Center Pulsing Mic Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(220.dp)
                .padding(16.dp)
        ) {
            // Outer dynamic sound wave ring
            if (isStreaming && !isMuted) {
                Box(
                    modifier = Modifier
                        .size(200.dp * animatedScale)
                        .clip(CircleShape)
                        .background(EmeraldGreen.copy(alpha = 0.15f))
                )
            }

            // Main clickable mic button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(buttonBgColor)
                    .border(
                        width = 3.dp,
                        color = if (isStreaming && !isMuted) CyanAccent else BorderDark,
                        shape = CircleShape
                    )
                    .clickable {
                        if (isStreaming) {
                            viewModel.stopMicStreaming(context)
                        } else {
                            viewModel.startMicStreaming(context)
                        }
                    }
            ) {
                Icon(
                    imageVector = when {
                        !isStreaming -> Icons.Default.MicOff
                        isMuted -> Icons.Default.MicOff
                        else -> Icons.Default.Mic
                    },
                    contentDescription = "Microphone Toggle",
                    modifier = Modifier.size(64.dp),
                    tint = if (isStreaming) Color.White else TextSecondary
                )
            }
        }

        // Audio VU Level Meter
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Input Level", fontSize = 12.sp, color = TextSecondary)
                Text(
                    text = if (isStreaming && !isMuted) "${(vuLevel * 100).toInt()}%" else "0%",
                    fontSize = 12.sp,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Custom VU bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(SurfaceDark)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (isStreaming && !isMuted) vuLevel else 0f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(EmeraldGreen, CyanAccent, CrimsonRed)
                            )
                        )
                )
            }
        }

        // Controls: Mute toggle & Gain booster
        Surface(
            color = CardDark,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = if (isMuted) CrimsonRed else TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Microphone Mute", color = TextPrimary, fontSize = 14.sp)
                    }

                    Switch(
                        checked = isMuted,
                        onCheckedChange = { viewModel.toggleMute() },
                        enabled = isStreaming,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CrimsonRed,
                            checkedTrackColor = CrimsonRed.copy(alpha = 0.5f)
                        )
                    )
                }

                HorizontalDivider(color = BorderDark)

                // Gain slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Mic Boost / Gain", color = TextSecondary, fontSize = 13.sp)
                        Text("${String.format("%.1f", gain)}x", color = CyanAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = gain,
                        onValueChange = { viewModel.setGain(it) },
                        valueRange = 0.5f..3.0f,
                        steps = 25,
                        colors = SliderDefaults.colors(
                            thumbColor = CyanAccent,
                            activeTrackColor = CyanAccent
                        )
                    )
                }
            }
        }

        // Informational footer
        Surface(
            color = SurfaceDark.copy(alpha = 0.6f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = CyanAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "PC recognized device: 'AirBridge Microphone'. Ready for Discord, Zoom, browser, and system apps.",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 14.sp
                )
            }
        }
    }
}
