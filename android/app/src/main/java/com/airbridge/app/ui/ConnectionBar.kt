package com.airbridge.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbridge.app.model.ConnectionState

@Composable
fun ConnectionBar(
    connectionState: ConnectionState,
    currentHost: String,
    currentHttpPort: Int,
    currentUdpPort: Int,
    onAutoDiscover: () -> Unit,
    onManualConnect: (String, Int, Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Surface(
        color = CardDark,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { showDialog = true }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (dotColor, statusText) = when (connectionState) {
                    is ConnectionState.Connected -> Pair(EmeraldGreen, "Connected: ${connectionState.serverName}")
                    is ConnectionState.Connecting -> Pair(AmberWarning, "Connecting to $currentHost...")
                    is ConnectionState.Searching -> Pair(CyanAccent, "Scanning Wi-Fi for PC...")
                    is ConnectionState.Error -> Pair(CrimsonRed, "Connection Failed")
                    is ConnectionState.Disconnected -> Pair(Color.Gray, "Disconnected (Tap to Setup)")
                }

                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = statusText,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (connectionState is ConnectionState.Connected) {
                        Text(
                            text = "${connectionState.host}:${connectionState.httpPort}",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            IconButton(onClick = onAutoDiscover) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Scan for PC",
                    tint = CyanAccent
                )
            }
        }
    }

    if (showDialog) {
        var hostInput by remember { mutableStateOf(currentHost) }
        var httpPortInput by remember { mutableStateOf(currentHttpPort.toString()) }
        var udpPortInput by remember { mutableStateOf(currentUdpPort.toString()) }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Connect to PC", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the IP address shown on your AirBridge Desktop screen:",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )

                    OutlinedTextField(
                        value = hostInput,
                        onValueChange = { hostInput = it },
                        label = { Text("PC IP Address") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = BorderDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = httpPortInput,
                            onValueChange = { httpPortInput = it },
                            label = { Text("HTTP Port") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = udpPortInput,
                            onValueChange = { udpPortInput = it },
                            label = { Text("Audio Port") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val hPort = httpPortInput.toIntOrNull() ?: 8765
                        val uPort = udpPortInput.toIntOrNull() ?: 8766
                        onManualConnect(hostInput.trim(), hPort, uPort)
                        showDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                ) {
                    Text("Connect", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}
