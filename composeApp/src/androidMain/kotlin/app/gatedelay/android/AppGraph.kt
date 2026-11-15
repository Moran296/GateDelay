package app.gatedelay.android

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.gatedelay.data.DeviceLinker
import app.gatedelay.data.GateRepository
import app.gatedelay.data.LinkState
import app.gatedelay.model.GateRole
import app.gatedelay.model.SequenceKind
import app.gatedelay.palgate.PalgateApi
import app.gatedelay.sequence.GateController
import app.gatedelay.sequence.SequenceRunner
import app.gatedelay.sequence.SingleStatus
import app.gatedelay.ui.AppDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class GateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}

/** Process-wide singletons (manual DI). */
object AppGraph {
    lateinit var runner: SequenceRunner
        private set
    lateinit var deps: AppDeps
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(app: Context) {
        val kv = PrefsKeyValueStore(app)
        val repo = GateRepository(kv, KeystoreSecretStore(app), PalgateApi())
        runner = SequenceRunner(repo, scope)
        val linker = DeviceLinker(repo, scope, kv)
        deps = AppDeps(
            repo = repo,
            controller = ServiceGateController(app, runner),
            linker = linker,
            platform = AndroidPlatformUi(app),
        )
        // While waiting for the PalGate scan, hold a foreground service: otherwise Android blocks
        // our network as soon as the user switches to the PalGate app, and the token is lost.
        scope.launch {
            linker.state.collect { if (it is LinkState.WaitingForScan) LinkService.start(app) }
        }
        linker.resumePending()
        // Mirror progress to the home-screen widget.
        scope.launch {
            combine(runner.state, runner.singles) { run, singles ->
                val single = singles.entries.lastOrNull()?.let { (role, s) ->
                    when (s.status) {
                        SingleStatus.OPENING -> "Opening ${role.title}…"
                        SingleStatus.DONE -> "${role.title} opened ✓"
                        SingleStatus.FAILED -> "${role.title} failed: ${s.error}"
                    }
                }
                if (run.running || single == null) run.summary() else single
            }.distinctUntilChanged().collect { GateWidget.pushStatus(app, it) }
        }
    }
}

/** Routes gate actions through [SequenceService] so they keep running when the app is in the background. */
class ServiceGateController(private val context: Context, private val runner: SequenceRunner) : GateController {
    override val state = runner.state
    override val singles = runner.singles
    override fun start(kind: SequenceKind) = send(SequenceService.startIntent(context, kind))
    override fun openSingle(role: GateRole) = send(SequenceService.singleIntent(context, role))
    override fun skipWait() = runner.skipWait()
    override fun cancel() = runner.cancel()

    private fun send(intent: Intent) = ContextCompat.startForegroundService(context, intent)
}
