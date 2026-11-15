package app.gatedelay.data

import app.gatedelay.model.LinkedCredentials
import app.gatedelay.palgate.PalgateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

sealed interface LinkState {
    data object Idle : LinkState
    /** Show [qrPayload] as a QR code to be scanned by the PalGate app (Device Linking -> Link a device). */
    data class WaitingForScan(val qrPayload: String) : LinkState
    data class Linked(val phone: String) : LinkState
    data class Failed(val message: String) : LinkState
}

/**
 * PalGate device linking, as in ha-palgate's config flow: random UUID -> QR `{"id": "<uuid>"}` ->
 * long-poll `un/secondary/init/<uuid>` until the PalGate app scans it.
 * Runs in an app-wide scope so it survives screen changes and the app going to the background
 * (the user has to switch to the PalGate app to scan).
 */
class DeviceLinker(private val repo: GateRepository, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        job?.cancel()
        val linkId = randomUuid()
        _state.value = LinkState.WaitingForScan("""{"id": "$linkId"}""")
        job = scope.launch {
            var networkFailures = 0
            // Up to ~60 polls (~15+ min): each poll ends on scan, server timeout or error.
            repeat(MAX_POLLS) {
                try {
                    val result = repo.api.waitForLink(linkId)
                    if (result != null) {
                        repo.addAccount(LinkedCredentials(result.phone, result.tokenHex, result.tokenType))
                        _state.value = LinkState.Linked(result.phone)
                        return@launch
                    }
                    networkFailures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PalgateException) {
                    _state.value = LinkState.Failed(e.message ?: "Linking failed")
                    return@launch
                } catch (e: Exception) {
                    // Timeouts / dropped connections (e.g. app in background): keep polling for a while.
                    if (++networkFailures >= 20) {
                        _state.value = LinkState.Failed("Network problem while waiting for the scan: ${e.message}")
                        return@launch
                    }
                    delay(1_000)
                }
            }
            _state.value = LinkState.Failed("Timed out waiting for the QR scan. Try again.")
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = LinkState.Idle
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun randomUuid(): String = Uuid.random().toString() // cryptographically secure: the id acts as a secret

    private companion object {
        const val MAX_POLLS = 60
    }
}
