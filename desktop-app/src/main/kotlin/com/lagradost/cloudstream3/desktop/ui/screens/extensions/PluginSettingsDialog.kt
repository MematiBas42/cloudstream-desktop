package com.lagradost.cloudstream3.desktop.ui.screens.extensions

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.PluginSettingSchema
import com.lagradost.common.storage.PluginSettingsSchemaRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginSettingsDialog(
    pluginName: String,
    prefName: String,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val schemaUpdates by PluginSettingsSchemaRegistry.schemaUpdates.collectAsState()
    var showAddToggle by remember { mutableStateOf(false) }

    val settings = remember(schemaUpdates) {
        PluginSettingsSchemaRegistry.getSettingsForPlugin(prefName).sortedBy { it.key }
    }

    if (showAddToggle) {
        AddToggleDialog(
            prefName = prefName,
            onDismiss = { showAddToggle = false },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$pluginName Settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (settings.isEmpty()) {
                    Text("No configurable settings detected for this plugin. You can add custom boolean toggles below.")
                } else {
                    settings.forEach { schema ->
                        SettingRow(schema = schema, prefName = prefName)
                    }
                }

                OutlinedButton(
                    onClick = { showAddToggle = true },
                    modifier = Modifier.fillMaxWidth().focusable(),
                ) {
                    Text("+ Add Custom Toggle")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onReload,
                modifier = Modifier.focusable(),
            ) {
                Text("Apply & Reload")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusable(),
            ) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun SettingRow(schema: PluginSettingSchema, prefName: String) {
    val fullKey = if (schema.isGlobal) schema.key else schema.pluginPrefName + schema.key
    var currentValue by remember(schema, prefName) {
        mutableStateOf(
            DesktopDataStore.getKey<Any>(fullKey) ?: schema.defaultValue
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        when (schema.type) {
            "Boolean" -> {
                Text(
                    text = schema.key
                        .replace("_", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = (currentValue as? Boolean) == true,
                    modifier = Modifier.focusable(),
                    onCheckedChange = { newValue ->
                        currentValue = newValue
                        if (newValue == null) {
                            DesktopDataStore.removeKey(fullKey)
                        } else {
                            DesktopDataStore.setKey(fullKey, newValue)
                        }
                    },
                )
            }
            "Int", "Long", "Float" -> {
                OutlinedTextField(
                    value = currentValue?.toString() ?: "",
                    onValueChange = { newValue ->
                        val parsed = when (schema.type) {
                            "Int" -> newValue.toIntOrNull()
                            "Long" -> newValue.toLongOrNull()
                            "Float" -> newValue.toFloatOrNull()
                            else -> newValue
                        }
                        if (parsed != null || newValue.isEmpty()) {
                            currentValue = parsed
                            if (parsed == null) {
                                DesktopDataStore.removeKey(fullKey)
                            } else {
                                DesktopDataStore.setKey(fullKey, parsed)
                            }
                        }
                    },
                    label = { Text(schema.key) },
                    modifier = Modifier.fillMaxWidth().focusable(),
                )
            }
            else -> {
                OutlinedTextField(
                    value = currentValue?.toString() ?: "",
                    onValueChange = { newValue ->
                        currentValue = newValue
                        DesktopDataStore.setKey(fullKey, newValue)
                    },
                    label = { Text(schema.key) },
                    modifier = Modifier.fillMaxWidth().focusable(),
                )
            }
        }
    }
}

@Composable
private fun AddToggleDialog(
    prefName: String,
    onDismiss: () -> Unit,
) {
    var toggleName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Toggle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter a name for the toggle. This creates a switch that stores a boolean value.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = toggleName,
                    onValueChange = {
                        toggleName = it
                        error = null
                    },
                    label = { Text("Toggle Name") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().focusable(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = toggleName.trim()
                    if (trimmed.isBlank()) {
                        error = "Name cannot be empty"
                        return@Button
                    }
                    val sanitizedKey = trimmed.replace(" ", "_")
                    PluginSettingsSchemaRegistry.register(
                        pluginPrefName = prefName,
                        key = sanitizedKey,
                        type = "Boolean",
                        defaultValue = false,
                        isGlobal = false,
                    )
                    DesktopDataStore.setKey(prefName + sanitizedKey, true)
                    onDismiss()
                },
                modifier = Modifier.focusable(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusable(),
            ) {
                Text("Cancel")
            }
        },
    )
}
