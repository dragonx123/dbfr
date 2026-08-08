package com.jarvis.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The "teach / instruct" surface: free-text standing instructions the user
 * writes themselves, plus the list of facts Jarvis has saved via its
 * rememberFact tool when the user says "remember that…". Both are appended
 * to the persona's system prompt on every backend — see
 * `UserInstructions.promptBlock()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstructionsScreen(
    initialInstructions: String,
    facts: List<String>,
    onSave: (String) -> Unit,
    onDeleteFact: (String) -> Unit,
    onClearFacts: () -> Unit,
    onBack: () -> Unit,
) {
    var text by remember { mutableStateOf(initialInstructions) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Instructions & memory") },
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

            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("What Jarvis has learned", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (facts.isNotEmpty()) {
                    androidx.compose.material3.TextButton(onClick = onClearFacts) { Text("Clear all") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (facts.isEmpty()) {
                    "Nothing yet. Say \"remember that…\" in a conversation and Jarvis will " +
                        "save it here and use it from then on."
                } else {
                    "Saved from your conversations. These are treated as true and used when relevant."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            facts.forEach { fact ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(fact, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onDeleteFact(fact) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Forget this",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
