package app.gatedelay.sequence

import app.gatedelay.data.GateRepository
import app.gatedelay.model.GateRef
import app.gatedelay.model.GateRole
import app.gatedelay.model.SequenceKind
import app.gatedelay.palgate.PalgateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class StepStatus { PENDING, OPENING, DONE, FAILED, SKIPPED }

data class StepState(val role: GateRole, val label: String, val status: StepStatus = StepStatus.PENDING)

/** State of the current (or last) ENTER/EXIT run. */
data class RunState(
    val kind: SequenceKind? = null,
    val steps: List<StepState> = emptyList(),
    val secondsLeft: Int? = null,
    val running: Boolean = false,
    val error: String? = null,
) {
    val finishedOk get() = !running && kind != null && error == null && steps.all { it.status == StepStatus.DONE }

    /** One-line summary for widget / notification. */
    fun summary(): String = when {
        kind == null -> "Ready"
        running && secondsLeft != null -> "${kind.title}: opening ${steps.getOrNull(1)?.role?.title} in ${secondsLeft}s"
        running -> "${kind.title}: opening ${steps.firstOrNull { it.status == StepStatus.OPENING }?.role?.title ?: "…"}"
        error != null -> "${kind.title} failed: $error"
        steps.any { it.status == StepStatus.SKIPPED } -> "${kind.title} cancelled"
        else -> "${kind.title} done ✓"
    }
}

enum class SingleStatus { OPENING, DONE, FAILED }

data class SingleState(val status: SingleStatus, val error: String? = null)

/** Controls the gates. Implemented directly by [SequenceRunner], or by a platform wrapper (e.g. Android service). */
interface GateController {
    val state: StateFlow<RunState>
    val singles: StateFlow<Map<GateRole, SingleState>>
    fun start(kind: SequenceKind)
    fun openSingle(role: GateRole)
    fun skipWait()
    fun cancel()
}

/**
 * Runs ENTER/EXIT sequences: open first gate -> countdown -> open second gate.
 * If the first gate fails, the second one is NOT opened.
 */
class SequenceRunner(
    private val repo: GateRepository,
    private val scope: CoroutineScope,
    private val retryDelayMs: Long = 1_000,
) {
    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val _singles = MutableStateFlow<Map<GateRole, SingleState>>(emptyMap())
    val singles: StateFlow<Map<GateRole, SingleState>> = _singles.asStateFlow()

    private var job: Job? = null
    private val skip = Channel<Unit>(Channel.CONFLATED)

    val isRunning get() = job?.isActive == true

    /** Starts a sequence. Returns null (and does nothing) when one is already running or config is incomplete. */
    fun start(kind: SequenceKind): Job? {
        if (isRunning) return null
        val cfg = repo.config.value
        val seq = cfg.sequence(kind)
        val first = cfg.gates[seq.first]
        val second = cfg.gates[seq.second]
        if (first == null || second == null) {
            _state.value = RunState(kind, error = "Assign the ${seq.first.title} and ${seq.second.title} gates in Settings")
            return null
        }
        skip.tryReceive() // drop stale skip requests
        _state.value = RunState(
            kind = kind,
            steps = listOf(StepState(seq.first, first.label), StepState(seq.second, second.label)),
            running = true,
        )
        return scope.launch {
            try {
                if (!openStep(0, first)) return@launch

                for (left in seq.delaySec downTo 1) {
                    _state.update { it.copy(secondsLeft = left) }
                    val skipped = withTimeoutOrNull(1_000) { skip.receive() } != null
                    if (skipped) break
                }
                _state.update { it.copy(secondsLeft = null) }

                openStep(1, second)
            } catch (e: CancellationException) {
                _state.update { s ->
                    s.copy(steps = s.steps.map { if (it.status == StepStatus.PENDING || it.status == StepStatus.OPENING) it.copy(status = StepStatus.SKIPPED) else it })
                }
                throw e
            } finally {
                _state.update { it.copy(running = false, secondsLeft = null) }
            }
        }.also { job = it }
    }

    fun skipWait() { skip.trySend(Unit) }

    fun cancel() { job?.cancel() }

    fun openSingle(role: GateRole): Job? {
        val ref = repo.config.value.gates[role] ?: run {
            _singles.update { it + (role to SingleState(SingleStatus.FAILED, "Not configured")) }
            return null
        }
        if (_singles.value[role]?.status == SingleStatus.OPENING) return null
        _singles.update { it + (role to SingleState(SingleStatus.OPENING)) }
        return scope.launch {
            val error = openWithRetry(ref)
            _singles.update { it + (role to SingleState(if (error == null) SingleStatus.DONE else SingleStatus.FAILED, error)) }
            delay(4_000)
            _singles.update { cur -> if (cur[role]?.status != SingleStatus.OPENING) cur - role else cur }
        }
    }

    private suspend fun openStep(index: Int, ref: GateRef): Boolean {
        setStep(index, StepStatus.OPENING)
        val error = openWithRetry(ref)
        return if (error == null) {
            setStep(index, StepStatus.DONE)
            true
        } else {
            setStep(index, StepStatus.FAILED)
            _state.update { s ->
                s.copy(
                    error = "${s.steps[index].role.title}: $error",
                    steps = s.steps.map { if (it.status == StepStatus.PENDING) it.copy(status = StepStatus.SKIPPED) else it },
                )
            }
            false
        }
    }

    /** @return null on success, else an error message. Network errors are retried once; API errors are not. */
    private suspend fun openWithRetry(ref: GateRef): String? {
        repeat(2) { attempt ->
            try {
                repo.openGate(ref)
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: PalgateException) {
                return e.message ?: "PalGate error"
            } catch (e: Exception) {
                if (attempt == 1) return "Network error: ${e.message ?: e::class.simpleName}"
                delay(retryDelayMs)
            }
        }
        return "Unknown error"
    }

    private fun setStep(index: Int, status: StepStatus) = _state.update { s ->
        s.copy(steps = s.steps.mapIndexed { i, st -> if (i == index) st.copy(status = status) else st })
    }
}
