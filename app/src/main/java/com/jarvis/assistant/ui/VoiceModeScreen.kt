package com.jarvis.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.JarvisBackground

/**
 * Full-screen ChatGPT/Gemini-style voice mode: a large [PlexusOrb] that
 * reacts to the current [orbPhase], a live caption (partial transcript
 * while listening, streamed reply while thinking/speaking), and mute/close
 * controls. All orchestration (auto-relisten loop, mute, mic-level
 * plumbing) lives in `ChatViewModel` — this composable is purely display.
 */
@Composable
fun VoiceModeScreen(
    personaName: String,
    orbColor: Color,
    orbPhase: OrbPhase,
    micLevel: Float,
    partialTranscript: String,
    latestReplyText: String,
    isMuted: Boolean,
    errorMessage: String?,
    screenViewEnabled: Boolean,
    onScreenViewToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(orbColor.copy(alpha = 0.12f), JarvisBackground),
                ),
            ),
    ) {
        // Subtle HUD chrome behind everything — quieter than the chat screen's
        // (lower glyph alpha, no scanline) so the orb stays the hero.
        HudBackdrop(
            color = orbColor,
            modifier = Modifier.fillMaxSize(),
            glyphAlpha = 0.06f,
            showScanline = false,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close voice mode", tint = Color.White)
                }
            }

            Spacer(Modifier.weight(1f))

            PlexusOrb(
                phase = orbPhase,
                micLevel = micLevel,
                color = orbColor,
                modifier = Modifier.size(280.dp),
            )

            Spacer(Modifier.height(28.dp))

            Text(personaName, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                stateLabel(orbPhase),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
            )

            Spacer(Modifier.height(20.dp))

            val caption = when (orbPhase) {
                OrbPhase.LISTENING -> partialTranscript
                OrbPhase.THINKING, OrbPhase.SPEAKING -> latestReplyText
                else -> ""
            }
            AnimatedContent(targetState = caption, label = "voiceModeCaption") { text ->
                Text(
                    text = text.ifBlank { " " },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            errorMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                IconButton(
                    onClick = onMuteToggle,
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.White.copy(alpha = 0.12f), CircleShape),
                ) {
                    Icon(
                        if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        tint = Color.White,
                    )
                }
                // Continuous screen view: while on, every turn carries a fresh
                // screenshot so Jarvis can answer about what you're looking at.
                IconButton(
                    onClick = onScreenViewToggle,
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            if (screenViewEnabled) orbColor.copy(alpha = 0.3f)
                            else Color.White.copy(alpha = 0.12f),
                            CircleShape,
                        ),
                ) {
                    Icon(
                        if (screenViewEnabled) Icons.Filled.ScreenShare else Icons.Filled.StopScreenShare,
                        contentDescription = if (screenViewEnabled) "Stop watching screen" else "Let Jarvis watch the screen",
                        tint = Color.White,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun stateLabel(phase: OrbPhase): String = when (phase) {
    OrbPhase.IDLE -> "Ready"
    OrbPhase.LISTENING -> "Listening…"
    OrbPhase.THINKING -> "Thinking…"
    OrbPhase.SPEAKING -> "Speaking…"
    OrbPhase.COOLDOWN -> "…"
    OrbPhase.MUTED -> "Muted"
}
