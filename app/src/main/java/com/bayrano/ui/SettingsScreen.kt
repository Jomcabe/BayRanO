package com.bayrano.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bayrano.core.BatteryOptimization
import com.bayrano.gemini.MediaResolution
import com.bayrano.gemini.ThinkingLevel
import com.bayrano.wake.WakeTrigger

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var draft by remember { mutableStateOf(viewModel.currentKey()) }
    var reveal by remember { mutableStateOf(false) }
    var elevenDraft by remember { mutableStateOf(viewModel.currentElevenLabsKey()) }
    var elevenReveal by remember { mutableStateOf(false) }

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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Gemini API key", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Stored encrypted on-device (EncryptedSharedPreferences). " +
                    "Current: ${state.keyPreview}" +
                    if (!state.hasUserKey) " — using build default" else "",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation =
                    if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = { reveal = !reveal }) {
                    Text(if (reveal) "Hide" else "Reveal")
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        viewModel.clear()
                        draft = viewModel.currentKey()
                    },
                ) { Text("Reset") }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.save(draft) },
                enabled = draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save key") }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Speed vs. quality", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Lower is faster and cheaper; higher gives more visual detail and reasoning.",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(16.dp))
            Text("Image resolution", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaResolution.entries.forEach { option ->
                    FilterChip(
                        selected = state.mediaResolution == option,
                        onClick = { viewModel.setMediaResolution(option) },
                        label = { Text(option.label) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Thinking level", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThinkingLevel.entries.forEach { option ->
                    FilterChip(
                        selected = state.thinkingLevel == option,
                        onClick = { viewModel.setThinkingLevel(option) },
                        label = { Text(option.label) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Voice (ElevenLabs)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Optional. Add your ElevenLabs API key and pick a voice to speak " +
                    "answers with it instead of the device's built-in voice. " +
                    "Stored encrypted on-device. Current key: ${state.elevenLabsKeyPreview}",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = elevenDraft,
                onValueChange = { elevenDraft = it },
                label = { Text("ElevenLabs API key") },
                singleLine = true,
                visualTransformation =
                    if (elevenReveal) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = { elevenReveal = !elevenReveal }) {
                    Text(if (elevenReveal) "Hide" else "Reveal")
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        viewModel.clearElevenLabs()
                        elevenDraft = viewModel.currentElevenLabsKey()
                    },
                ) { Text("Reset") }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.saveElevenLabsKey(elevenDraft) },
                    enabled = elevenDraft.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Save key") }
                OutlinedButton(
                    onClick = { viewModel.loadVoices() },
                    enabled = state.hasElevenLabsKey && !state.voicesLoading,
                    modifier = Modifier.weight(1f),
                ) { Text("Load voices") }
            }

            if (state.voicesLoading) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Text("   Loading voices…", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.voicesError?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠️ $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.voices.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Choose a voice", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.voices.forEach { voice ->
                        FilterChip(
                            selected = state.selectedVoiceId == voice.id,
                            onClick = { viewModel.selectVoice(voice) },
                            label = { Text(voice.name) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                if (state.selectedVoiceName != null && state.hasElevenLabsKey) {
                    "Speaking with: ${state.selectedVoiceName}"
                } else {
                    "Using the device's built-in voice."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hands-free wake", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Hold an active media session so a glasses gesture wakes the " +
                            "assistant. Only works while nothing else is playing audio.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.wakeEnabled,
                    onCheckedChange = { viewModel.setWakeEnabled(it) },
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("Wake gesture", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WakeTrigger.entries.forEach { option ->
                    FilterChip(
                        selected = state.wakeTrigger == option,
                        onClick = { viewModel.setWakeTrigger(option) },
                        label = { Text(option.label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Tip: a Quick Settings tile and the on-screen mic button work " +
                    "anytime, even when another app owns audio.",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { BatteryOptimization.requestExemption(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (BatteryOptimization.isIgnoring(context)) {
                        "Battery optimization: ignored ✓ (review)"
                    } else {
                        "Disable battery optimization"
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
