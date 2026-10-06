package com.frameender.pocketstash.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.normalizeServerUrl
import com.frameender.pocketstash.ui.common.friendly
import com.frameender.pocketstash.ui.theme.Ink
import com.frameender.pocketstash.ui.theme.Mono
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val container = LocalContext.current.container
    val current = container.settings.value
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(current?.serverUrl ?: "") }
    var key by remember { mutableStateOf(current?.apiKey ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = Ink.Line, focusedBorderColor = Ink.Amber,
        unfocusedContainerColor = Ink.Surface, focusedContainerColor = Ink.Surface,
    )

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(buildAnnotatedString {
            append("Pocket")
            withStyle(SpanStyle(color = Ink.Amber)) { append("Stash") }
        }, style = MaterialTheme.typography.displaySmall)
        Text(
            "Connect to your Stash server.",
            style = MaterialTheme.typography.bodyLarge, color = Ink.Muted,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null; success = null },
            label = { Text("Server URL") },
            placeholder = { Text("http://192.168.1.10:9999") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; error = null; success = null },
            label = { Text("API key (blank if auth is off)") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Toggle key visibility")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Find the key in Stash → Settings → Security → API Key.",
            style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
        )

        error?.let { Text(it, color = Ink.Red, style = MaterialTheme.typography.bodyMedium) }
        success?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = Ink.Green, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(it, color = Ink.Green, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Button(
            onClick = {
                val base = normalizeServerUrl(url)
                if (base == null) {
                    error = "That doesn't look like a URL."
                    return@Button
                }
                busy = true
                error = null
                scope.launch {
                    try {
                        val info = container.repository.testConnection(base, key.trim())
                        success = "Connected — Stash ${info.version ?: "(unknown version)"}"
                        container.settingsStore.saveServer(base.toString(), key)
                        // Wait for the live connection to pick up the new server
                        // before screens start querying it.
                        container.settings.first { it != null && it.baseUrl == base && it.apiKey == key.trim() }
                        onDone()
                    } catch (e: Exception) {
                        error = e.friendly()
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && url.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Amber, contentColor = Ink.Bg),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) CircularProgressIndicator(color = Ink.Bg, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            else Text("Connect", style = MaterialTheme.typography.titleMedium)
        }
    }
}
