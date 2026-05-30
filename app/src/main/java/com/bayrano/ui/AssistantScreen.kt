package com.bayrano.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bayrano.assistant.AssistantUiState
import com.bayrano.assistant.TranscriptEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    viewModel: AssistantViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(state.transcript.size) {
        if (state.transcript.isNotEmpty()) {
            listState.animateScrollToItem(state.transcript.lastIndex)
        }
    }

    val voicePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) viewModel.startVoiceQuery()
    }
    fun launchVoice() {
        val micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            viewModel.startVoiceQuery()
        } else {
            val perms = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            voicePermissionLauncher.launch(perms.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BayRanO") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            GlassesControls(
                state = state,
                onToggleMock = viewModel::setUseMockDevice,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect,
                onCapture = viewModel::captureAndDescribe,
            )

            if (state.transcript.isEmpty()) {
                Text(
                    text = "Tap the mic to speak, or type a question.\n" +
                        "Connect the glasses to ground answers in what you see.\n" +
                        "Say \"elaborate\" after an answer for more detail.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                )
            }

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) {
                items(state.transcript) { entry -> TranscriptBubble(entry) }
            }

            if (state.isListening) {
                Text(
                    text = "Listening…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                if (state.isListening) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(8.dp))
                } else {
                    IconButton(
                        onClick = { launchVoice() },
                        enabled = !state.isBusy,
                    ) { Icon(Icons.Filled.Mic, contentDescription = "Speak") }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Question") },
                    singleLine = true,
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f),
                )
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(start = 16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    TextButton(
                        onClick = {
                            viewModel.ask(draft)
                            draft = ""
                        },
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Ask") }
                }
            }
        }
    }
}

@Composable
private fun GlassesControls(
    state: AssistantUiState,
    onToggleMock: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCapture: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(state.glassesStatus, style = MaterialTheme.typography.titleSmall)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Text("Mock device", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.useMockDevice,
                    onCheckedChange = onToggleMock,
                    enabled = !state.isConnected && !state.isConnecting,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                if (state.isConnecting) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(4.dp))
                } else if (state.isConnected) {
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                    Button(
                        onClick = onCapture,
                        enabled = !state.isBusy,
                    ) { Text("Capture & describe") }
                } else {
                    Button(onClick = onConnect) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun TranscriptBubble(entry: TranscriptEntry) {
    val label = when (entry.speaker) {
        TranscriptEntry.Speaker.USER -> "You"
        TranscriptEntry.Speaker.ASSISTANT -> "BayRanO"
        TranscriptEntry.Speaker.SYSTEM -> "System"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(entry.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
