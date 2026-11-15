package app.gatedelay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gatedelay.data.LinkState

@Composable
fun LinkScreen(deps: AppDeps, onDone: () -> Unit) {
    val state by deps.linker.state.collectAsState()

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Link a PalGate account", style = MaterialTheme.typography.headlineSmall)

        when (val s = state) {
            is LinkState.WaitingForScan -> {
                Text(
                    "1. Tap \"Show on another screen\" and open the QR on a laptop, tablet or another phone.\n" +
                        "2. On THIS phone open the PalGate app → menu → Device Linking (Linked Devices) → Link a device.\n" +
                        "3. Scan the QR, then come back here. Linking completes automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val bitmap = remember(s.qrPayload) { deps.platform.qrImage(s.qrPayload, 720) }
                Image(bitmap, contentDescription = "Linking QR code", modifier = Modifier.size(260.dp))
                Button(onClick = { deps.platform.shareQr(s.qrPayload) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Show on another screen (share QR)")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("Waiting for the scan…")
                }
                Text(
                    "Keep this QR private: whoever scans it with their PalGate app links their account to this phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { deps.linker.cancel(); onDone() }) { Text("Cancel") }
            }
            is LinkState.Linked -> {
                Text("✓ Linked +${s.phone}", style = MaterialTheme.typography.titleLarge)
                Text("Now choose your Main, Entry and Exit gates in Settings.")
                Button(onClick = { deps.linker.cancel(); onDone() }) { Text("Continue") }
            }
            is LinkState.Failed -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { deps.linker.start() }) { Text("Try again") }
                    OutlinedButton(onClick = { deps.linker.cancel(); onDone() }) { Text("Back") }
                }
            }
            LinkState.Idle -> {
                Button(onClick = { deps.linker.start() }) { Text("Start linking") }
                OutlinedButton(onClick = onDone) { Text("Back") }
            }
        }
    }
}
