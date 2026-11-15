package app.gatedelay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import app.gatedelay.data.DeviceLinker
import app.gatedelay.data.GateRepository
import app.gatedelay.sequence.GateController

/** Platform-specific helpers needed by the shared UI. */
interface PlatformUi {
    /** Renders [payload] as a QR code bitmap. */
    fun qrImage(payload: String, sizePx: Int): ImageBitmap
    /** Shares the QR as an image so it can be shown on another screen. */
    fun shareQr(payload: String)
}

/** Everything the UI needs. Built once per process by the platform (see Android `AppGraph`). */
class AppDeps(
    val repo: GateRepository,
    val controller: GateController,
    val linker: DeviceLinker,
    val platform: PlatformUi,
)

private enum class Screen { HOME, SETTINGS, LINK }

@Composable
fun App(deps: AppDeps) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) {
        darkColorScheme(primary = Color(0xFF7FB6FF), secondary = Color(0xFFFFB86B))
    } else {
        lightColorScheme(primary = Color(0xFF1F5FBF), secondary = Color(0xFFB85C00))
    }
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            var screen by remember { mutableStateOf(Screen.HOME) }
            Box(Modifier.fillMaxSize()) {
                when (screen) {
                    Screen.HOME -> HomeScreen(deps, onSettings = { screen = Screen.SETTINGS })
                    Screen.SETTINGS -> SettingsScreen(
                        deps,
                        onBack = { screen = Screen.HOME },
                        onLink = { deps.linker.start(); screen = Screen.LINK },
                    )
                    Screen.LINK -> LinkScreen(deps, onDone = { screen = Screen.SETTINGS })
                }
            }
            BackHandlerCompat(enabled = screen != Screen.HOME) {
                screen = if (screen == Screen.LINK) { deps.linker.cancel(); Screen.SETTINGS } else Screen.HOME
            }
        }
    }
}

/** System back button handling (Android). No-op where not applicable. */
@Composable
expect fun BackHandlerCompat(enabled: Boolean, onBack: () -> Unit)
