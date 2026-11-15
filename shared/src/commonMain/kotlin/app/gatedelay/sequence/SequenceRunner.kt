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
    /** Set while a gate is being retried because there is no connection (retry number, 1-based). */
    val retry: Int? = null,
) {
    val finishedOk get() = !running && kind != null && error == null && steps.all { it.status == StepStatus.DONE }

    /** One-line summary for widget / notification. */
    fun summary(): String = when {
        kind == null -> "Ready"
        running && secondsLeft != null -> "${kind.title}: opening ${steps.getOrNull(1)?.role?.title} in ${secondsLeft}s"
        running && retry != null -> "${kind.title}: no connection, retrying ${steps.firstOrNull { it.status == StepStatus.OPENING }?.role?.title ?: ""} ($retry)…"
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
 *
 * Connection errors (e.g. the phone is re-attaching to the cellular network between gates) are retried
 * every [retryDelayMs] for up to [retryWindowMs] per gate. PalGate API errors are not retried.
 */
class SequenceRunner(
    private val repo: GateRepository,
    private val scope: CoroutineScope,
    private val retryDelayMs: Long = 2_000,
    private val retryWindowMs: Long = 60_000,
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
        val error = openWithRetry(ref) { n -> _state.update { it.copy(retry = n) } }
        _state.update { it.copy(retry = null) }
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

    /**
     * @return null on success, else an error message.
     * Connection errors are retried every [retryDelayMs] until [retryWindowMs] has passed; API errors are not retried.
     */
    private suspend fun openWithRetry(ref: GateRef, onRetry: (Int) -> Unit = {}): String? {
        var lastError: Exception? = null
        val outcome = withTimeoutOrNull(retryWindowMs) { retryLoop(ref, onRetry) { lastError = it } }
        return when (outcome) {
            OPENED -> null
            null -> "No connection (kept trying for ${retryWindowMs / 1000}s): ${lastError?.let { it.message ?: it::class.simpleName } ?: "timeout"}"
            else -> outcome
        }
    }

    /** Loops until the gate opens ([OPENED]) or PalGate returns an error (its message). */
    private suspend fun retryLoop(ref: GateRef, onRetry: (Int) -> Unit, onNetworkError: (Exception) -> Unit): String {
        var retries = 0
        while (true) {
            try {
                repo.openGate(ref)
                return OPENED
            } catch (e: CancellationException) {
                throw e
            } catch (e: PalgateException) {
                return e.message ?: "PalGate error"
            } catch (e: Exception) {
                onNetworkError(e)
                delay(retryDelayMs)
                onRetry(++retries)
            }
        }
    }

    private companion object {
        const val OPENED = " opened"
    }

    private fun setStep(index: Int, status: StepStatus) = _state.update { s ->
        s.copy(steps = s.steps.mapIndexed { i, st -> if (i == index) st.copy(status = status) else st })
    }
}
