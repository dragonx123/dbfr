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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ai.OllamaChatBackend
import com.jarvis.assistant.model.BackendConfig
import com.jarvis.assistant.model.BackendSettings
import com.jarvis.assistant.model.BackendType
import com.jarvis.assistant.model.CloudProvider
import com.jarvis.assistant.model.Persona
import com.jarvis.assistant.model.Personas
import com.jarvis.assistant.model.VoiceGender
import kotlinx.coroutines.launch

private fun providerLabel(provider: CloudProvider): String = when (provider) {
    CloudProvider.GEMINI -> "Google Gemini"
    CloudProvider.ANTHROPIC -> "Anthropic Claude"
    CloudProvider.OPENAI_COMPAT -> "OpenAI-compatible"
}

/**
 * Switches provider, and swaps the model/baseUrl fields to the new
 * provider's defaults — but only when the user hadn't customized them (i.e.
 * they still equal some provider's default), so a hand-entered model name
 * never gets clobbered by tapping through providers.
 */
private fun switchProvider(
    new: CloudProvider,
    old: CloudProvider,
    setModel: (String) -> Unit,
    setBaseUrl: (String) -> Unit,
    currentModel: String,
): CloudProvider {
    if (new == old) return old
    val allDefaults = CloudProvider.entries.map { BackendSettings.defaultModelFor(it) }
    if (currentModel.isBlank() || currentModel in allDefaults) {
        setModel(BackendSettings.defaultModelFor(new))
    }
    setBaseUrl(BackendSettings.defaultBaseUrlFor(new))
    return new
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentBackendType: BackendType,
    currentOllamaUrl: String,
    currentOllamaModel: String,
    currentCloudProvider: CloudProvider,
    currentCloudApiKey: String,
    currentCloudModel: String,
    currentCloudBaseUrl: String,
    currentPersona: Persona,
    onSave: (BackendConfig) -> Unit,
    onPreviewVoice: (Persona) -> Unit,
    availableVoices: List<String>,
    maleVoiceName: String,
    femaleVoiceName: String,
    onChooseVoice: (VoiceGender, String) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenInstructions: () -> Unit,
    onOpenMemory: () -> Unit,
    memoryCount: Int,
    onOpenAccessibilitySettings: () -> Unit,
    screenControlEnabled: Boolean,
    onBack: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(currentBackendType) }
    var ollamaUrl by remember { mutableStateOf(currentOllamaUrl.ifBlank { "http://" }) }
    var ollamaModel by remember { mutableStateOf(currentOllamaModel) }
    var cloudProvider by remember { mutableStateOf(currentCloudProvider) }
    var cloudApiKey by remember { mutableStateOf(currentCloudApiKey) }
    var cloudModel by remember {
        mutableStateOf(currentCloudModel.ifBlank { BackendSettings.defaultModelFor(currentCloudProvider) })
    }
    var cloudBaseUrl by remember {
        mutableStateOf(currentCloudBaseUrl.ifBlank { BackendSettings.defaultBaseUrlFor(currentCloudProvider) })
    }
    var selectedPersona by remember { mutableStateOf(currentPersona) }

    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
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

            if (availableVoices.size > 1) {
                Spacer(Modifier.height(20.dp))
                Text("Which system voice to use", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Android doesn't say which of your installed voices are male or female — " +
                        "the names are codes like \"en-us-x-iob-local\". Pick one for each here " +
                        "and Jarvis will use it; tapping a voice plays a sample. Leave them " +
                        "unset and pitch alone distinguishes the personas.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                VoicePickerRow(
                    label = "Male personas (Jarvis, Vision, Ultron)",
                    voices = availableVoices,
                    selected = maleVoiceName,
                    onPick = { onChooseVoice(VoiceGender.MALE, it) },
                )
                Spacer(Modifier.height(12.dp))
                VoicePickerRow(
                    label = "Female personas (Friday, Edith)",
                    voices = availableVoices,
                    selected = femaleVoiceName,
                    onPick = { onChooseVoice(VoiceGender.FEMALE, it) },
                )
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
            Spacer(Modifier.height(8.dp))
            BackendOption(
                title = "Cloud API (your key)",
                description = "Google Gemini, Anthropic Claude, or any OpenAI-compatible API with your own key. Best quality, web browsing, and (later) screen understanding. Needs internet.",
                selected = selectedType == BackendType.CLOUD_API,
                onSelect = { selectedType = BackendType.CLOUD_API },
            )

            if (selectedType == BackendType.CLOUD_API) {
                Spacer(Modifier.height(20.dp))
                Text("Provider", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderChip("Gemini", cloudProvider == CloudProvider.GEMINI) {
                        cloudProvider = switchProvider(CloudProvider.GEMINI, cloudProvider, { cloudModel = it }, { cloudBaseUrl = it }, cloudModel)
                    }
                    ProviderChip("Claude", cloudProvider == CloudProvider.ANTHROPIC) {
                        cloudProvider = switchProvider(CloudProvider.ANTHROPIC, cloudProvider, { cloudModel = it }, { cloudBaseUrl = it }, cloudModel)
                    }
                    ProviderChip("OpenAI+", cloudProvider == CloudProvider.OPENAI_COMPAT) {
                        cloudProvider = switchProvider(CloudProvider.OPENAI_COMPAT, cloudProvider, { cloudModel = it }, { cloudBaseUrl = it }, cloudModel)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = cloudApiKey,
                    onValueChange = { cloudApiKey = it },
                    label = { Text("API key") },
                    placeholder = { Text("Paste your ${providerLabel(cloudProvider)} API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cloudModel,
                    onValueChange = { cloudModel = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (cloudProvider == CloudProvider.OPENAI_COMPAT) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cloudBaseUrl,
                        onValueChange = { cloudBaseUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.openai.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Works with any OpenAI-compatible server: Groq, Mistral, LM Studio, …",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

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
                    onSave(
                        BackendConfig(
                            type = selectedType,
                            ollamaBaseUrl = ollamaUrl,
                            ollamaModel = ollamaModel,
                            cloudProvider = cloudProvider,
                            cloudApiKey = cloudApiKey,
                            cloudModel = cloudModel,
                            cloudBaseUrl = cloudBaseUrl,
                            persona = selectedPersona,
                        )
                    )
                    onBack()
                },
                enabled = when (selectedType) {
                    BackendType.ON_DEVICE -> true
                    BackendType.OLLAMA -> ollamaUrl.isNotBlank() && ollamaModel.isNotBlank()
                    BackendType.CLOUD_API -> cloudApiKey.isNotBlank() && cloudModel.isNotBlank()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }

            Spacer(Modifier.height(24.dp))
            Text("Teaching Jarvis", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Standing instructions you write, plus anything Jarvis has saved when you " +
                    "said \"remember that…\". Both apply on every backend.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinkCard(
                icon = Icons.Filled.School,
                label = "Standing instructions",
                onClick = onOpenInstructions,
            )
            Spacer(Modifier.height(8.dp))
            LinkCard(
                icon = Icons.Filled.Psychology,
                label = if (memoryCount == 0) "Memory" else "Memory · $memoryCount remembered",
                onClick = onOpenMemory,
            )

            Spacer(Modifier.height(24.dp))
            Text("Screen control", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                if (screenControlEnabled) {
                    "Enabled. Jarvis can read what's on your screen and operate it — going " +
                        "back or home, scrolling, and tapping buttons you name. Tap below to " +
                        "review or turn it off."
                } else {
                    "Off. Turning this on lets Jarvis read the current screen and operate it " +
                        "for you. Android requires you to enable it yourself in Accessibility " +
                        "settings — find \"Jarvis screen control\" in the list."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinkCard(
                icon = Icons.Filled.Accessibility,
                label = if (screenControlEnabled) "Screen control: on" else "Enable screen control",
                onClick = onOpenAccessibilitySettings,
                highlighted = screenControlEnabled,
            )

            Spacer(Modifier.height(24.dp))
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "See what Jarvis has been doing under the hood — backend connections, " +
                    "tool calls, speech/TTS state, and any errors — useful for troubleshooting " +
                    "without needing a computer plugged in.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinkCard(icon = Icons.Filled.Terminal, label = "View logs", onClick = onOpenLogs)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A tappable settings row that navigates somewhere: icon, label, chevron. */
@Composable
private fun LinkCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(12.dp),
    ) {
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

/**
 * Horizontal list of the device's usable TTS voices. Names are opaque engine
 * codes, so they're shown verbatim and shortened only at the ends — guessing
 * a friendly label would be inventing information the system doesn't give us.
 */
@Composable
private fun VoicePickerRow(
    label: String,
    voices: List<String>,
    selected: String,
    onPick: (String) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(6.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(voices) { voice ->
            ProviderChip(
                label = voice.removePrefix("en-").removeSuffix("-local"),
                selected = voice == selected,
                onClick = { onPick(voice) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun GenderBadge(gender: VoiceGender) {
    Surface(
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = selected, onClick = onSelect)
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
