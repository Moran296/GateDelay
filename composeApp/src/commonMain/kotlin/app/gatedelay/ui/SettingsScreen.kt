package app.gatedelay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gatedelay.data.GateListing
import app.gatedelay.model.GateRole
import app.gatedelay.model.LinkedCredentials
import app.gatedelay.model.SequenceConfig
import app.gatedelay.model.SequenceKind
import app.gatedelay.sequence.SingleStatus
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(deps: AppDeps, onBack: () -> Unit, onLink: () -> Unit) {
    val config by deps.repo.config.collectAsState()
    val singles by deps.controller.singles.collectAsState()
    val scope = rememberCoroutineScope()

    var listing by remember { mutableStateOf<GateListing?>(null) }
    var loading by remember { mutableStateOf(false) }
    var pickerFor by remember { mutableStateOf<GateRole?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        scope.launch {
            listing = runCatching { deps.repo.listAllGates() }.getOrElse { GateListing(emptyList(), mapOf("all" to (it.message ?: "error"))) }
            loading = false
        }
    }
    LaunchedEffect(config.accounts) { if (config.accounts.isNotEmpty()) refresh() }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        // --- Accounts ---------------------------------------------------------------------
        Section("PalGate accounts") {
            if (config.accounts.isEmpty()) {
                Text("No account linked yet. Link your PalGate account to get started.")
            }
            for (acc in config.accounts) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(acc.displayName, style = MaterialTheme.typography.titleSmall)
                        Text("Linked device #${acc.tokenType}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { confirmRemove = acc.phone }) { Text("Remove") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLink) { Text("Link account") }
                OutlinedButton(onClick = { showImport = true }) { Text("Import manually") }
            }
        }

        // --- Gates ------------------------------------------------------------------------
        Section("Gates") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Assign the building's gates. Use Test to make sure the right gate moves.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (loading) CircularProgressIndicator(Modifier.width(24.dp))
                else TextButton(onClick = ::refresh, enabled = config.accounts.isNotEmpty()) { Text("Refresh") }
            }
            listing?.errors?.forEach { (acc, err) ->
                Text("$acc: $err", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            for (role in GateRole.entries) {
                val ref = config.gates[role]
                val single = singles[role]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { pickerFor = role }) {
                        Text(role.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            ref?.let { "${it.label}${if (it.outputNum > 1) " (output ${it.outputNum})" else ""}" } ?: "Not set — tap to choose",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ref == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        single?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(onClick = { pickerFor = role }) { Text("Choose") }
                    OutlinedButton(
                        onClick = { deps.controller.openSingle(role) },
                        enabled = ref != null && single?.status != SingleStatus.OPENING,
                    ) {
                        Text(
                            when (single?.status) {
                                SingleStatus.OPENING -> "…"
                                SingleStatus.DONE -> "✓"
                                SingleStatus.FAILED -> "✗"
                                null -> "Test"
                            },
                        )
                    }
                }
            }
        }

        // --- Sequences --------------------------------------------------------------------
        Section("Sequences") {
            for (kind in SequenceKind.entries) {
                SequenceEditor(kind, config.sequence(kind)) { deps.repo.setSequence(kind, it) }
                if (kind != SequenceKind.entries.last()) HorizontalDivider()
            }
        }

        Text(
            "Unofficial app using PalGate's private API. Your account must be allowed to use Linked Devices " +
                "(gate admin: Gate Settings → Manager Options → Users → Linked Device).",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    pickerFor?.let { role ->
        GatePickerDialog(
            role = role,
            listing = listing,
            loading = loading,
            onPick = { deps.repo.assignGate(role, it); pickerFor = null },
            onClear = { deps.repo.assignGate(role, null); pickerFor = null },
            onDismiss = { pickerFor = null },
        )
    }

    if (showImport) {
        ManualImportDialog(
            onImport = { creds ->
                runCatching { deps.repo.addAccount(creds) }.exceptionOrNull()?.message
                    .also { if (it == null) showImport = false }
            },
            onDismiss = { showImport = false },
        )
    }

    confirmRemove?.let { phone ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove +$phone?") },
            text = { Text("Gates using this account will be unassigned. To free the linked-device slot, also remove it in the PalGate app (Device Linking).") },
            confirmButton = { TextButton(onClick = { deps.repo.removeAccount(phone); confirmRemove = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SequenceEditor(kind: SequenceKind, seq: SequenceConfig, onChange: (SequenceConfig) -> Unit) {
    var delayText by remember(seq.delaySec) { mutableStateOf(seq.delaySec.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(kind.title, style = MaterialTheme.typography.titleSmall)
        Text("First open", style = MaterialTheme.typography.labelMedium)
        RoleChips(seq.first) { onChange(seq.copy(first = it)) }
        OutlinedTextField(
            value = delayText,
            onValueChange = { txt ->
                delayText = txt.filter { it.isDigit() }.take(3)
                delayText.toIntOrNull()?.let { onChange(seq.copy(delaySec = it)) }
            },
            label = { Text("Then wait (seconds)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        Text("Then open", style = MaterialTheme.typography.labelMedium)
        RoleChips(seq.second) { onChange(seq.copy(second = it)) }
    }
}

@Composable
private fun RoleChips(selected: GateRole, onSelect: (GateRole) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (role in GateRole.entries) {
            FilterChip(selected = role == selected, onClick = { onSelect(role) }, label = { Text(role.title) })
        }
    }
}

@Composable
private fun GatePickerDialog(
    role: GateRole,
    listing: GateListing?,
    loading: Boolean,
    onPick: (app.gatedelay.model.GateRef) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose the ${role.title} gate") },
        text = {
            when {
                loading -> CircularProgressIndicator()
                listing == null || listing.options.isEmpty() ->
                    Text("No gates found. Link an account first, then tap Refresh.")
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(listing.options) { opt ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(opt.ref) }.padding(vertical = 10.dp),
                        ) {
                            Text(
                                opt.ref.label + if (opt.ref.outputNum > 1) " (output ${opt.ref.outputNum})" else "",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                listOfNotNull(opt.ref.address, "via ${opt.accountName}", opt.ref.deviceId).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onClear) { Text("Clear") } },
    )
}

@Composable
private fun ManualImportDialog(onImport: (LinkedCredentials) -> String?, onDismiss: () -> Unit) {
    var phone by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("1") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import account manually") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Use credentials from tools/link_probe.py, pylgate or Home Assistant.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone (e.g. 972501234567)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(token, { token = it }, label = { Text("Session token (32 hex chars)") }, singleLine = true)
                OutlinedTextField(type, { type = it.filter(Char::isDigit).take(1) }, label = { Text("Token type (0 SMS, 1 primary, 2 secondary)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onImport(LinkedCredentials(phone, token, type.toIntOrNull() ?: -1)) }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
