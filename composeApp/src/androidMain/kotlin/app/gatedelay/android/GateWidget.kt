package app.gatedelay.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as FixedColor
import app.gatedelay.model.GateRole
import app.gatedelay.model.SequenceKind
import androidx.glance.LocalContext

/** Home-screen widget: big ENTER / EXIT buttons, small single-gate buttons and a status line. */
class GateWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val status = currentState<Preferences>()[STATUS] ?: "Ready"
            WidgetContent(status)
        }
    }

    companion object {
        private val STATUS = stringPreferencesKey("status")

        suspend fun pushStatus(context: Context, text: String) {
            val ids = runCatching { GlanceAppWidgetManager(context).getGlanceIds(GateWidget::class.java) }.getOrNull() ?: return
            for (id in ids) {
                updateAppWidgetState(context, id) { it[STATUS] = text }
                GateWidget().update(context, id)
            }
        }
    }
}

private val enterColor = ColorProvider(day = Color(0xFF1F5FBF), night = Color(0xFF7FB6FF))
private val exitColor = ColorProvider(day = Color(0xFFB85C00), night = Color(0xFFFFB86B))
private val smallColor = ColorProvider(day = Color(0xFFE3E8F0), night = Color(0xFF2E3440))
private val bg = ColorProvider(day = Color(0xF2FFFFFF), night = Color(0xF21B1F24))
private val fg = ColorProvider(day = Color(0xFF111111), night = Color(0xFFEEEEEE))
private val onBig = FixedColor(Color.White)

@Composable
private fun WidgetContent(status: String) {
    val context = LocalContext.current
    Column(
        GlanceModifier.fillMaxSize().background(bg).cornerRadius(20.dp).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(status, maxLines = 1, style = TextStyle(color = fg, fontSize = 13.sp))
        Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
            Button(
                text = SequenceKind.ENTER.title,
                onClick = actionStartService(SequenceService.startIntent(context, SequenceKind.ENTER), isForegroundService = true),
                modifier = GlanceModifier.defaultWeight().fillMaxSize(),
                colors = ButtonDefaults.buttonColors(backgroundColor = enterColor, contentColor = onBig),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.width(8.dp))
            Button(
                text = SequenceKind.EXIT.title,
                onClick = actionStartService(SequenceService.startIntent(context, SequenceKind.EXIT), isForegroundService = true),
                modifier = GlanceModifier.defaultWeight().fillMaxSize(),
                colors = ButtonDefaults.buttonColors(backgroundColor = exitColor, contentColor = onBig),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            GateRole.entries.forEachIndexed { i, role ->
                if (i > 0) Spacer(GlanceModifier.width(6.dp))
                Button(
                    text = role.title,
                    onClick = actionStartService(SequenceService.singleIntent(context, role), isForegroundService = true),
                    modifier = GlanceModifier.defaultWeight().height(36.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = smallColor, contentColor = fg),
                    style = TextStyle(fontSize = 12.sp),
                )
            }
        }
    }
}

class GateWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GateWidget()
}
