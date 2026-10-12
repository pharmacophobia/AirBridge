package com.airbridge.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.airbridge.app.AirBridgeApplication
import com.airbridge.app.MainActivity
import com.airbridge.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MicStreamService : Service() {

    companion object {
        private const val TAG = "MicStreamService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.airbridge.action.START_MIC"
        const val ACTION_STOP = "com.airbridge.action.STOP_MIC"
        const val ACTION_TOGGLE_MUTE = "com.airbridge.action.TOGGLE_MUTE"

        const val EXTRA_HOST = "extra_host"
        const val EXTRA_UDP_PORT = "extra_udp_port"
        const val EXTRA_SAMPLE_RATE = "extra_sample_rate"

        private val _isStreaming = MutableStateFlow(false)
        val isStreaming = _isStreaming.asStateFlow()

        private val _isMuted = MutableStateFlow(false)
        val isMuted = _isMuted.asStateFlow()

        private val _vuLevel = MutableStateFlow(0f)
        val vuLevel = _vuLevel.asStateFlow()

        private val _gainMultiplier = MutableStateFlow(1.0f)
        val gainMultiplier = _gainMultiplier.asStateFlow()

        fun setGain(gain: Float) {
            _gainMultiplier.value = gain
        }

        fun toggleMute() {
            _isMuted.value = !_isMuted.value
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var udpSocket: DatagramSocket? = null

    private var targetHost: String = "127.0.0.1"
    private var targetPort: Int = 8766
    private var sampleRate: Int = 48000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
            ACTION_TOGGLE_MUTE -> {
                toggleMute()
                updateNotification()
            }
            ACTION_START -> {
                targetHost = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
                targetPort = intent.getIntExtra(EXTRA_UDP_PORT, 8766)
                sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 48000)

                startForegroundServiceWithNotification()
                startStreamingAudio()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MicStreamService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MicStreamService::class.java).apply { action = ACTION_TOGGLE_MUTE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteText = if (_isMuted.value) "Unmute" else "Mute"

        return NotificationCompat.Builder(this, AirBridgeApplication.CHANNEL_ID_MIC)
            .setContentTitle(getString(R.string.mic_active_title))
            .setContentText("Streaming low-latency audio to $targetHost:$targetPort (${if (_isMuted.value) "MUTED" else "ACTIVE"})")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, muteText, muteIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopIntent)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startStreamingAudio() {
        recordingJob?.cancel()
        recordingJob = serviceScope.launch {
            try {
                _isStreaming.value = true

                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                
                // Low latency buffer: ~20ms (960 samples @ 48kHz = 1920 bytes)
                val chunkSamples = (sampleRate * 0.02).toInt() // 20ms
                val bufferSize = max(minBufferSize, chunkSamples * 2 * 2)

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    // Fallback to standard MIC source
                    audioRecord?.release()
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                }

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord could not be initialized")
                    stopSelf()
                    return@launch
                }

                udpSocket = DatagramSocket()
                val targetAddress = InetAddress.getByName(targetHost)

                audioRecord?.startRecording()
                Log.i(TAG, "Started AudioRecord streaming to $targetHost:$targetPort ($sampleRate Hz)")

                val audioBuffer = ShortArray(chunkSamples)
                val byteBuffer = ByteArray(chunkSamples * 2)

                while (isActive && _isStreaming.value) {
                    val readCount = audioRecord?.read(audioBuffer, 0, chunkSamples) ?: -1
                    if (readCount <= 0) continue

                    val currentMuted = _isMuted.value
                    val currentGain = _gainMultiplier.value

                    var sumSquare = 0.0

                    // Convert short array to 16-bit PCM byte array (little-endian)
                    for (i in 0 until readCount) {
                        var sample = audioBuffer[i].toInt()

                        if (currentMuted) {
                            sample = 0
                        } else if (currentGain != 1.0f) {
                            sample = (sample * currentGain).toInt()
                            sample = max(-32768, min(32767, sample))
                        }

                        sumSquare += sample * sample

                        val byteIdx = i * 2
                        byteBuffer[byteIdx] = (sample and 0xFF).toByte()
                        byteBuffer[byteIdx + 1] = ((sample shr 8) and 0xFF).toByte()
                    }

                    // Calculate RMS VU level
                    if (readCount > 0 && !currentMuted) {
                        val rms = sqrt(sumSquare / readCount)
                        val db = if (rms > 0) 20 * log10(rms / 32767.0) else -100.0
                        // Normalize between -60dB (silence) and 0dB (peak) -> 0.0f to 1.0f
                        val normalized = ((db + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
                        _vuLevel.value = normalized
                    } else {
                        _vuLevel.value = 0f
                    }

                    // Transmit UDP packet
                    val packet = DatagramPacket(byteBuffer, readCount * 2, targetAddress, targetPort)
                    udpSocket?.send(packet)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during mic streaming", e)
            } finally {
                cleanupAudio()
            }
        }
    }

    private fun cleanupAudio() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null

        _isStreaming.value = false
        _vuLevel.value = 0f
    }

    private fun stopStreaming() {
        _isStreaming.value = false
        recordingJob?.cancel()
        cleanupAudio()
    }

    override fun onDestroy() {
        stopStreaming()
        serviceScope.cancel()
        super.onDestroy()
    }
}
