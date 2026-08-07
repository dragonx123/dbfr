package com.jarvis.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ai.OllamaChatBackend
import com.jarvis.assistant.model.BackendType
import com.jarvis.assistant.model.Persona
import com.jarvis.assistant.model.Personas
import com.jarvis.assistant.model.VoiceGender
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentBackendType: BackendType,
    currentOllamaUrl: String,
    currentOllamaModel: String,
    currentPersona: Persona,
    onSave: (BackendType, String, String, Persona) -> Unit,
    onPreviewVoice: (Persona) -> Unit,
    onBack: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(currentBackendType) }
    var ollamaUrl by remember { mutableStateOf(currentOllamaUrl.ifBlank { "http://" }) }
    var ollamaModel by remember { mutableStateOf(currentOllamaModel) }
    var selectedPersona by remember { mutableStateOf(currentPersona) }

    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
        ) {
            Text("Assistant voice", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Pick a name and voice for your assistant.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Personas.all.forEach { persona ->
                PersonaOption(
                    persona = persona,
                    selected = selectedPersona.id == persona.id,
                    onSelect = { selectedPersona = persona },
                    onPreview = { onPreviewVoice(persona) },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(16.dp))
            Text("AI backend", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Choose whether Jarvis runs a model on this phone, or talks to an Ollama server on your network.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            BackendOption(
                title = "On-device (offline)",
                description = "Runs a .litertlm model locally via LiteRT-LM. Fully offline; supports device-control actions (alarms, apps, etc).",
                selected = selectedType == BackendType.ON_DEVICE,
                onSelect = { selectedType = BackendType.ON_DEVICE },
            )
            Spacer(Modifier.height(8.dp))
            BackendOption(
                title = "Ollama server",
                description = "Sends chat requests to an Ollama instance (e.g. running on your PC) over the network. Faster/better models, but needs a reachable server and no device-control actions.",
                selected = selectedType == BackendType.OLLAMA,
                onSelect = { selectedType = BackendType.OLLAMA },
            )

            if (selectedType == BackendType.OLLAMA) {
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = ollamaUrl,
                    onValueChange = { ollamaUrl = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://192.168.1.50:11434") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ollamaModel,
                    onValueChange = { ollamaModel = it },
                    label = { Text("Model name") },
                    placeholder = { Text("llama3.2") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        fetchError = null
                        isFetchingModels = true
                        scope.launch {
                            OllamaChatBackend.listModels(ollamaUrl)
                                .onSuccess { models ->
                                    availableModels = models
                                    if (models.isEmpty()) fetchError = "Server reachable but has no models pulled yet."
                                }
                                .onFailure { fetchError = it.message ?: "Couldn't reach server" }
                            isFetchingModels = false
                        }
                    },
                    enabled = ollamaUrl.isNotBlank() && !isFetchingModels,
                ) {
                    Text(if (isFetchingModels) "Checking…" else "Fetch models from server")
                }
                if (isFetchingModels) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                }
                fetchError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (availableModels.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(160.dp)) {
                        items(availableModels) { name ->
                            TextButton(onClick = { ollamaModel = name }) {
                                Text(name)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    onSave(selectedType, ollamaUrl, ollamaModel, selectedPersona)
                    onBack()
                },
                enabled = selectedType == BackendType.ON_DEVICE ||
                    (ollamaUrl.isNotBlank() && ollamaModel.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun PersonaOption(
    persona: Persona,
    selected: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    Card(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(persona.displayName, style = MaterialTheme.typography.bodyLarge)
                    GenderBadge(persona.gender)
                }
                Text(
                    persona.tagline,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPreview) {
                Icon(Icons.Filled.VolumeUp, contentDescription = "Preview ${persona.displayName}'s voice")
            }
        }
    }
}

@Composable
private fun GenderBadge(gender: VoiceGender) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = if (gender == VoiceGender.FEMALE) "Female voice" else "Male voice",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun BackendOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = selected, onClick = onSelect)
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
