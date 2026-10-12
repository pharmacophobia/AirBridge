package com.airbridge.app.ui

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airbridge.app.model.ConnectionState
import com.airbridge.app.model.DiscoveredServer
import com.airbridge.app.model.ImageTransferItem
import com.airbridge.app.model.TranscriptionItem
import com.airbridge.app.model.TransferStatus
import com.airbridge.app.network.ApiClient
import com.airbridge.app.network.DiscoveryClient
import com.airbridge.app.service.MicStreamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MainViewModel"

    private val discoveryClient = DiscoveryClient(application)
    private val apiClient = ApiClient()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers = _discoveredServers.asStateFlow()

    private val _serverHost = MutableStateFlow("192.168.1.100")
    val serverHost = _serverHost.asStateFlow()

    private val _httpPort = MutableStateFlow(8765)
    val httpPort = _httpPort.asStateFlow()

    private val _udpPort = MutableStateFlow(8766)
    val udpPort = _udpPort.asStateFlow()

    // Transfers
    private val _imageTransfers = MutableStateFlow<List<ImageTransferItem>>(emptyList())
    val imageTransfers = _imageTransfers.asStateFlow()

    // Transcriptions
    private val _transcriptions = MutableStateFlow<List<TranscriptionItem>>(emptyList())
    val transcriptions = _transcriptions.asStateFlow()

    private val _isRecordingVoiceNote = MutableStateFlow(false)
    val isRecordingVoiceNote = _isRecordingVoiceNote.asStateFlow()

    // Mic streaming states forwarded from Service
    val isMicStreaming = MicStreamService.isStreaming
    val isMuted = MicStreamService.isMuted
    val vuLevel = MicStreamService.vuLevel
    val gainMultiplier = MicStreamService.gainMultiplier

    init {
        // Attempt immediate discovery on startup
        autoDiscover()
    }

    fun setServerHost(host: String) {
        _serverHost.value = host.trim()
    }

    fun setHttpPort(port: Int) {
        _httpPort.value = port
    }

    fun setUdpPort(port: Int) {
        _udpPort.value = port
    }

    fun autoDiscover() {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Searching
            val found = discoveryClient.discoverServers(timeoutMs = 2000)
            _discoveredServers.value = found

            if (found.isNotEmpty()) {
                val server = found.first()
                connectToServer(server.host, server.httpPort, server.udpAudioPort)
            } else {
                // If not found via broadcast, test current configured IP
                testCurrentConnection()
            }
        }
    }

    private suspend fun testCurrentConnection() {
        val host = _serverHost.value
        val port = _httpPort.value
        val pingResult = apiClient.ping(host, port)
        if (pingResult != null) {
            val serverName = pingResult.optString("server_name", "Linux PC")
            _connectionState.value = ConnectionState.Connected(
                host = host,
                httpPort = port,
                udpAudioPort = _udpPort.value,
                serverName = serverName
            )
        } else {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    fun connectToServer(host: String, httpPort: Int = 8765, udpAudioPort: Int = 8766) {
        viewModelScope.launch {
            _serverHost.value = host
            _httpPort.value = httpPort
            _udpPort.value = udpAudioPort
            _connectionState.value = ConnectionState.Connecting(host, httpPort)

            val pingResult = apiClient.ping(host, httpPort)
            if (pingResult != null) {
                val serverName = pingResult.optString("server_name", "AirBridge Desktop")
                _connectionState.value = ConnectionState.Connected(
                    host = host,
                    httpPort = httpPort,
                    udpAudioPort = udpAudioPort,
                    serverName = serverName
                )
            } else {
                _connectionState.value = ConnectionState.Error("Failed to reach $host:$httpPort")
            }
        }
    }

    // --- Mic Controls ---

    fun startMicStreaming(context: Context) {
        val intent = Intent(context, MicStreamService::class.java).apply {
            action = MicStreamService.ACTION_START
            putExtra(MicStreamService.EXTRA_HOST, _serverHost.value)
            putExtra(MicStreamService.EXTRA_UDP_PORT, _udpPort.value)
            putExtra(MicStreamService.EXTRA_SAMPLE_RATE, 48000)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopMicStreaming(context: Context) {
        val intent = Intent(context, MicStreamService::class.java).apply {
            action = MicStreamService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun toggleMute() {
        MicStreamService.toggleMute()
    }

    fun setGain(gain: Float) {
        MicStreamService.setGain(gain)
    }

    // --- Image Transfers ---

    fun uploadImages(uris: List<Uri>, contentResolver: ContentResolver) {
        for (uri in uris) {
            uploadSingleImage(uri, contentResolver)
        }
    }

    fun uploadSingleImage(uri: Uri, contentResolver: ContentResolver) {
        val id = UUID.randomUUID().toString()
        var fileName = "photo_${System.currentTimeMillis()}.jpg"
        var fileSize = 0L

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }

        val item = ImageTransferItem(
            id = id,
            uri = uri,
            name = fileName,
            sizeBytes = fileSize,
            status = TransferStatus.UPLOADING,
            progress = 0.1f
        )

        _imageTransfers.value = listOf(item) + _imageTransfers.value

        viewModelScope.launch {
            val success = apiClient.uploadImage(
                host = _serverHost.value,
                port = _httpPort.value,
                uri = uri,
                contentResolver = contentResolver,
                onProgress = { prog ->
                    updateItemProgress(id, prog)
                }
            )

            updateItemStatus(id, if (success) TransferStatus.COMPLETED else TransferStatus.FAILED)
        }
    }

    private fun updateItemProgress(id: String, progress: Float) {
        _imageTransfers.value = _imageTransfers.value.map {
            if (it.id == id) it.copy(progress = progress) else it
        }
    }

    private fun updateItemStatus(id: String, status: TransferStatus) {
        _imageTransfers.value = _imageTransfers.value.map {
            if (it.id == id) it.copy(status = status, progress = if (status == TransferStatus.COMPLETED) 1f else it.progress) else it
        }
    }

    // --- AI Transcriptions ---

    fun sendTextTranscription(text: String, typeIntoWindow: Boolean = false, copyClipboard: Boolean = true) {
        if (text.isBlank()) return
        val id = UUID.randomUUID().toString()
        val item = TranscriptionItem(
            id = id,
            text = text,
            source = "Dictation",
            copiedToPc = copyClipboard
        )
        _transcriptions.value = listOf(item) + _transcriptions.value

        viewModelScope.launch {
            apiClient.sendTranscription(
                host = _serverHost.value,
                port = _httpPort.value,
                text = text,
                typeIntoWindow = typeIntoWindow,
                copyClipboard = copyClipboard
            )
        }
    }

    private var voiceNoteRecorder: AudioRecord? = null
    private var voiceNoteFile: File? = null

    fun startVoiceNoteRecording(context: Context) {
        if (_isRecordingVoiceNote.value) return
        _isRecordingVoiceNote.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 16000
                val minBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                voiceNoteFile = File.createTempFile("airbridge_voice_note_", ".wav", context.cacheDir)
                val rawPcmFile = File.createTempFile("airbridge_raw_", ".pcm", context.cacheDir)

                voiceNoteRecorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize * 2
                )

                voiceNoteRecorder?.startRecording()
                val buffer = ByteArray(2048)

                FileOutputStream(rawPcmFile).use { out ->
                    while (_isRecordingVoiceNote.value) {
                        val read = voiceNoteRecorder?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            out.write(buffer, 0, read)
                        }
                    }
                }

                voiceNoteRecorder?.stop()
                voiceNoteRecorder?.release()
                voiceNoteRecorder = null

                // Convert raw PCM to WAV format with 44-byte header
                rawPcmToWav(rawPcmFile, voiceNoteFile!!, sampleRate, 1, 16)
                rawPcmFile.delete()

                // Transcribe on PC via Whisper GPU
                val transcription = apiClient.transcribeAudioFile(
                    host = _serverHost.value,
                    port = _httpPort.value,
                    audioFile = voiceNoteFile!!
                )

                voiceNoteFile?.delete()

                if (!transcription.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        val item = TranscriptionItem(
                            id = UUID.randomUUID().toString(),
                            text = transcription,
                            source = "Whisper AI (RTX)"
                        )
                        _transcriptions.value = listOf(item) + _transcriptions.value
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice note recording failed", e)
                _isRecordingVoiceNote.value = false
            }
        }
    }

    fun stopVoiceNoteRecording() {
        _isRecordingVoiceNote.value = false
    }

    private fun rawPcmToWav(rawFile: File, wavFile: File, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val rawData = rawFile.readBytes()
        val totalAudioLen = rawData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // 16 for PCM
        header[20] = 1; header[21] = 0 // format = 1 (PCM)
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        FileOutputStream(wavFile).use { out ->
            out.write(header)
            out.write(rawData)
        }
    }
}
