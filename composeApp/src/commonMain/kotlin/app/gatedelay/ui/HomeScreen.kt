package app.gatedelay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gatedelay.model.GateRole
import app.gatedelay.model.SequenceKind
import app.gatedelay.sequence.RunState
import app.gatedelay.sequence.SingleStatus
import app.gatedelay.sequence.StepStatus

@Composable
fun HomeScreen(deps: AppDeps, onSettings: () -> Unit) {
    val config by deps.repo.config.collectAsState()
    val run by deps.controller.state.collectAsState()
    val singles by deps.controller.singles.collectAsState()
    val haptic = LocalHapticFeedback.current

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GateDelay", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text("⚙ Settings") }
        }

        StatusCard(run, onSkip = deps.controller::skipWait, onCancel = deps.controller::cancel)

        Spacer(Modifier.weight(1f))

        for (kind in SequenceKind.entries) {
            val ready = config.isReady(kind)
            val seq = config.sequence(kind)
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    deps.controller.start(kind)
                },
                enabled = ready && !run.running,
                modifier = Modifier.fillMaxWidth().height(140.dp),
                shape = RoundedCornerShape(24.dp),
                colors = if (kind == SequenceKind.EXIT) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(kind.title, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (ready) "${seq.first.title} → ${seq.delaySec}s → ${seq.second.title}" else "Configure in Settings",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Text("Open a single gate", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (role in GateRole.entries) {
                val single = singles[role]
                val suffix = when (single?.status) {
                    SingleStatus.OPENING -> " …"
                    SingleStatus.DONE -> " ✓"
                    SingleStatus.FAILED -> " ✗"
                    null -> ""
                }
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        deps.controller.openSingle(role)
                    },
                    enabled = config.gates[role] != null && single?.status != SingleStatus.OPENING,
                    modifier = Modifier.weight(1f),
                ) { Text(role.title + suffix, maxLines = 1) }
            }
        }
        singles.values.firstNotNullOfOrNull { it.error }?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusCard(run: RunState, onSkip: () -> Unit, onCancel: () -> Unit) {
    if (run.kind == null) return
    val colors = when {
        run.error != null -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        run.finishedOk -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else -> CardDefaults.cardColors()
    }
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(run.summary(), style = MaterialTheme.typography.titleMedium)
            run.steps.forEachIndexed { i, step ->
                val icon = when (step.status) {
                    StepStatus.PENDING -> "○"
                    StepStatus.OPENING -> "…"
                    StepStatus.DONE -> "✓"
                    StepStatus.FAILED -> "✗"
                    StepStatus.SKIPPED -> "–"
                }
                Text("$icon  ${i + 1}. ${step.role.title}  (${step.label})")
            }
            run.secondsLeft?.let {
                Text(
                    "$it",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (run.running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (run.secondsLeft != null) Button(onClick = onSkip) { Text("Open now") }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}
