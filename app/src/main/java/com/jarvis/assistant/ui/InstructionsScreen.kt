package com.jarvis.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Standing instructions: how the user wants Jarvis to behave, pinned into
 * the system prompt on every backend (see `UserInstructions.promptBlock()`).
 *
 * What Jarvis *knows* about the user lives in `MemoryScreen` instead —
 * that's retrieved per-message rather than pinned, since it grows without
 * bound and only a few entries matter to any given turn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstructionsScreen(
    initialInstructions: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
) {
    var text by remember { mutableStateOf(initialInstructions) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Standing instructions") },
                    navigationIcon = {
                        IconButton(onClick = {
                            onSave(text)
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text("Standing instructions", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Anything you write here is added to every conversation, on top of the " +
                    "persona's own character. Use it to teach Jarvis how you want it to " +
                    "behave — tone, length, units, what you do for a living, whatever matters.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(200.dp),
                placeholder = {
                    Text(
                        "e.g. Keep answers under three sentences unless I ask for more. " +
                            "Use metric. I'm a paramedic, so medical shorthand is fine."
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onSave(text) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save instructions")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Looking for what Jarvis knows about you? That's under Memory — it's saved " +
                    "separately and recalled only when it's relevant to what you're asking.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
