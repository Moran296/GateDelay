package app.gatedelay.android

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.gatedelay.model.GateRole
import app.gatedelay.model.SequenceKind
import app.gatedelay.ui.App

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Optional: lets the progress notification show during a sequence. Sequences work without it.
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { App(AppGraph.deps) }
    }
}

/**
 * Invisible trampoline for launcher shortcuts (long-press the app icon -> ENTER / EXIT / single gate).
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = when (intent?.action) {
            "app.gatedelay.shortcut.ENTER" -> SequenceService.startIntent(this, SequenceKind.ENTER)
            "app.gatedelay.shortcut.EXIT" -> SequenceService.startIntent(this, SequenceKind.EXIT)
            "app.gatedelay.shortcut.MAIN" -> SequenceService.singleIntent(this, GateRole.MAIN)
            else -> null
        }
        if (intent != null) ContextCompat.startForegroundService(this, intent)
        finish()
    }
}
