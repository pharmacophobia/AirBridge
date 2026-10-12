package com.airbridge.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbridge.app.model.TranscriptionItem
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AiTranscriptionScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val transcriptions by viewModel.transcriptions.collectAsState()
    val isRecordingVoiceNote by viewModel.isRecordingVoiceNote.collectAsState()

    var manualText by remember { mutableStateOf("") }
    var autoTypeOnPc by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // AI Voice Dictation Action Card
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
                    Column {
                        Text(
                            text = "AI SPEECH-TO-TEXT ENGINE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanAccent
                        )
                        Text(
                            text = "Powered by Local Whisper RTX GPU",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }

                    if (isRecordingVoiceNote) {
                        Surface(
                            color = CrimsonRed.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "RECORDING...",
                                color = CrimsonRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Voice Dictation Record Button
                Button(
                    onClick = {
                        if (isRecordingVoiceNote) {
                            viewModel.stopVoiceNoteRecording()
                        } else {
                            viewModel.startVoiceNoteRecording(context)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecordingVoiceNote) CrimsonRed else CyanAccent
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = if (isRecordingVoiceNote) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        tint = if (isRecordingVoiceNote) Color.White else BgDark
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRecordingVoiceNote) "Stop & Transcribe with AI" else "Record Voice Note for AI Transcription",
                        color = if (isRecordingVoiceNote) Color.White else BgDark,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Text Input & Direct PC Transfer
                OutlinedTextField(
                    value = manualText,
                    onValueChange = { manualText = it },
                    placeholder = { Text("Or type/paste text to send to PC...", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = BorderDark
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = autoTypeOnPc,
                            onCheckedChange = { autoTypeOnPc = it },
                            colors = CheckboxDefaults.colors(checkedColor = CyanAccent)
                        )
                        Text(
                            text = "Auto-type into active PC app",
                            fontSize = 12.sp,
                            color = TextPrimary
                        )
                    }

                    Button(
                        onClick = {
                            if (manualText.isNotBlank()) {
                                viewModel.sendTextTranscription(
                                    text = manualText,
                                    typeIntoWindow = autoTypeOnPc,
                                    copyClipboard = true
                                )
                                Toast.makeText(context, "Sent to PC!", Toast.LENGTH_SHORT).show()
                                manualText = ""
                            }
                        },
                        enabled = manualText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Send, contentDescription = null, tint = BgDark, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Send to PC", color = BgDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // Transcription History Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RECENT TRANSCRIPTIONS (${transcriptions.size})",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = CyanAccent
            )
        }

        if (transcriptions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.FormatQuote,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = BorderDark
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No transcriptions yet",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Record audio or type text to beam to your PC clipboard",
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(transcriptions, key = { it.id }) { item ->
                    TranscriptionCard(
                        item = item,
                        onSendToPc = {
                            viewModel.sendTextTranscription(item.text, typeIntoWindow = false, copyClipboard = true)
                            Toast.makeText(context, "Copied to PC Clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        onTypeIntoPc = {
                            viewModel.sendTextTranscription(item.text, typeIntoWindow = true, copyClipboard = true)
                            Toast.makeText(context, "Typing into active PC window...", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TranscriptionCard(
    item: TranscriptionItem,
    onSendToPc: () -> Unit,
    onTypeIntoPc: () -> Unit
) {
    val context = LocalContext.current
    val timeFormatted = remember(item.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timestamp))
    }

    Surface(
        color = CardDark,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = CyanAccent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = item.source,
                        color = CyanAccent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(text = timeFormatted, color = TextSecondary, fontSize = 11.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = item.text,
                color = TextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", item.text))
                        Toast.makeText(context, "Copied to phone clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy on phone", tint = TextSecondary, modifier = Modifier.size(16.dp))
                }

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = onTypeIntoPc,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(imageVector = Icons.Default.Keyboard, contentDescription = "Type in PC window", tint = AmberWarning, modifier = Modifier.size(16.dp))
                }

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = onSendToPc,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(imageVector = Icons.Default.Computer, contentDescription = "Send to PC clipboard", tint = EmeraldGreen, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
