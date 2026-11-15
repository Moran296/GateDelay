package app.gatedelay.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as FixedColor
import app.gatedelay.model.SequenceKind

/**
 * One big home-screen button per sequence: [EnterWidget] (blue) and [ExitWidget] (orange).
 * Tapping anywhere starts the sequence in [SequenceService]; a small status line shows progress.
 */
abstract class SequenceWidget(private val kind: SequenceKind) : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            WidgetButton(kind, currentState<Preferences>()[STATUS] ?: "")
        }
    }

    companion object {
        private val STATUS = stringPreferencesKey("status")

        /** Updates the status line of all placed widgets of [kind]. */
        suspend fun pushStatus(context: Context, kind: SequenceKind, text: String) {
            val widget = widgetFor(kind)
            val ids = runCatching { GlanceAppWidgetManager(context).getGlanceIds(widget::class.java) }.getOrNull() ?: return
            for (id in ids) {
                updateAppWidgetState(context, id) { it[STATUS] = text }
                widget.update(context, id)
            }
        }

        private fun widgetFor(kind: SequenceKind): SequenceWidget = when (kind) {
            SequenceKind.ENTER -> EnterWidget()
            SequenceKind.EXIT -> ExitWidget()
        }
    }
}

class EnterWidget : SequenceWidget(SequenceKind.ENTER)
class ExitWidget : SequenceWidget(SequenceKind.EXIT)

class EnterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EnterWidget()
}

class ExitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExitWidget()
}

private val enterColor = ColorProvider(day = Color(0xFF1F5FBF), night = Color(0xFF2F6FD0))
private val exitColor = ColorProvider(day = Color(0xFFD9730D), night = Color(0xFFC86A10))
private val white = FixedColor(Color.White)

@Composable
private fun WidgetButton(kind: SequenceKind, status: String) {
    val context = LocalContext.current
    Column(
        GlanceModifier
            .fillMaxSize()
            .cornerRadius(24.dp)
            .background(if (kind == SequenceKind.ENTER) enterColor else exitColor)
            .clickable(actionStartService(SequenceService.startIntent(context, kind), isForegroundService = true))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            kind.title,
            style = TextStyle(color = white, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        )
        if (status.isNotEmpty()) {
            Text(status, maxLines = 1, style = TextStyle(color = white, fontSize = 12.sp, textAlign = TextAlign.Center))
        }
    }
}
