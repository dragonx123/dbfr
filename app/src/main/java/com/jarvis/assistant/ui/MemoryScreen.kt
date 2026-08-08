package com.jarvis.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.memory.Memory
import com.jarvis.assistant.memory.MemoryKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything Jarvis has remembered about the user, with a search box and
 * per-item delete. Memories arrive three ways — the model saving one on
 * request, the pattern-based extractor catching a durable statement, or the
 * user adding one here by hand — so this screen is also the place to check
 * that nothing wrong got stored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    memories: List<Memory>,
    onAdd: (String) -> Unit,
    onForget: (String) -> Unit,
    onForgetAll: () -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var newMemory by remember { mutableStateOf("") }

    val visible = remember(memories, query) {
        val sorted = memories.sortedByDescending { it.createdAt }
        if (query.isBlank()) sorted
        else sorted.filter { it.text.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            Column {
                androidx.compose.material3.TopAppBar(
                    title = { Text("Memory") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = onForgetAll, enabled = memories.isNotEmpty()) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Forget everything")
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "${memories.size} ${if (memories.size == 1) "memory" else "memories"}. " +
                        "Jarvis saves these automatically when you tell it something lasting, " +
                        "and pulls the relevant ones into a conversation on its own.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = newMemory,
                    onValueChange = { newMemory = it },
                    placeholder = { Text("Teach Jarvis something…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (newMemory.isNotBlank()) {
                                    onAdd(newMemory.trim())
                                    newMemory = ""
                                }
                            },
                            enabled = newMemory.isNotBlank(),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add memory")
                        }
                    },
                )
                if (memories.size > 6) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search memories") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (visible.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (memories.isEmpty()) {
                            "Nothing remembered yet.\n\nTry saying \"remember that I take my coffee black\" " +
                                "in a conversation, or add something above."
                        } else {
                            "No memories match \"$query\"."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible, key = { it.id }) { memory ->
                        MemoryRow(memory = memory, onForget = { onForget(memory.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryRow(memory: Memory, onForget: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(memory.text, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(
                            when (memory.kind) {
                                MemoryKind.PREFERENCE -> "Preference"
                                MemoryKind.EVENT -> "Event"
                                MemoryKind.FACT -> "Fact"
                            }
                        )
                        append(" · ")
                        append(DATE_FORMAT.format(Date(memory.createdAt)))
                        if (memory.useCount > 0) append(" · recalled ${memory.useCount}×")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onForget) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Forget this",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val DATE_FORMAT = SimpleDateFormat("MMM d", Locale.getDefault())
