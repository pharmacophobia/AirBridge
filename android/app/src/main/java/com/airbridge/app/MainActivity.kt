package com.airbridge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.airbridge.app.ui.*

enum class AirBridgeTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    MIC("Microphone", Icons.Default.Mic),
    IMAGES("Images", Icons.Default.Image),
    TRANSCRIPTION("AI Transcribe", Icons.Default.RecordVoiceOver)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var selectedTabState = mutableStateOf(AirBridgeTab.MIC)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordAudioGranted) {
            Toast.makeText(this, "Microphone permission required for Virtual Mic", Toast.LENGTH_LONG).show()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAppPermissions()
        handleIncomingShareIntent(intent)

        setContent {
            AirBridgeTheme {
                var selectedTab by selectedTabState
                val connectionState by viewModel.connectionState.collectAsState()
                val currentHost by viewModel.serverHost.collectAsState()
                val currentHttpPort by viewModel.httpPort.collectAsState()
                val currentUdpPort by viewModel.udpPort.collectAsState()

                Scaffold(
                    topBar = {
                        Column {
                            TopAppBar(
                                title = {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text(
                                            "AirBridge",
                                            fontWeight = FontWeight.Bold,
                                            color = CyanAccent,
                                            fontSize = 20.sp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "Companion",
                                            color = TextSecondary,
                                            fontSize = 14.sp
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = BgDark,
                                    titleContentColor = TextPrimary
                                )
                            )
                            ConnectionBar(
                                connectionState = connectionState,
                                currentHost = currentHost,
                                currentHttpPort = currentHttpPort,
                                currentUdpPort = currentUdpPort,
                                onAutoDiscover = { viewModel.autoDiscover() },
                                onManualConnect = { host, hPort, uPort ->
                                    viewModel.connectToServer(host, hPort, uPort)
                                }
                            )
                        }
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = SurfaceDark,
                            contentColor = TextPrimary
                        ) {
                            AirBridgeTab.values().forEach { tab ->
                                NavigationBarItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    icon = {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = tab.title,
                                            tint = if (selectedTab == tab) CyanAccent else TextSecondary
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.title,
                                            color = if (selectedTab == tab) CyanAccent else TextSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = CardDark
                                    )
                                )
                            }
                        }
                    },
                    containerColor = BgDark
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTab) {
                            AirBridgeTab.MIC -> MicScreen(viewModel = viewModel)
                            AirBridgeTab.IMAGES -> ImageTransferScreen(viewModel = viewModel)
                            AirBridgeTab.TRANSCRIPTION -> AiTranscriptionScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type?.startsWith("image/") == true) {
            val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
            }

            if (imageUri != null) {
                selectedTabState.value = AirBridgeTab.IMAGES
                viewModel.uploadSingleImage(imageUri, contentResolver)
                Toast.makeText(this, "Beaming shared photo to PC...", Toast.LENGTH_SHORT).show()
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action && type?.startsWith("image/") == true) {
            val imageUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }

            if (!imageUris.isNullOrEmpty()) {
                selectedTabState.value = AirBridgeTab.IMAGES
                viewModel.uploadImages(imageUris, contentResolver)
                Toast.makeText(this, "Beaming ${imageUris.size} shared photos to PC...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
