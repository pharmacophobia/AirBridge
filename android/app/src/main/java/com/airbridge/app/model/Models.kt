package com.airbridge.app.model

import android.net.Uri

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Searching : ConnectionState()
    data class Connecting(val host: String, val port: Int) : ConnectionState()
    data class Connected(
        val host: String,
        val httpPort: Int,
        val udpAudioPort: Int,
        val serverName: String
    ) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

data class DiscoveredServer(
    val host: String,
    val httpPort: Int,
    val udpAudioPort: Int,
    val serverName: String
)

enum class TransferStatus {
    QUEUED,
    UPLOADING,
    COMPLETED,
    FAILED
}

data class ImageTransferItem(
    val id: String,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val status: TransferStatus,
    val progress: Float = 0f,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class TranscriptionItem(
    val id: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "Mic",
    val durationSeconds: Float = 0f,
    val copiedToPc: Boolean = true
)
