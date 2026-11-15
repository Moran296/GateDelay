package app.gatedelay.data

import app.gatedelay.model.LinkedCredentials
import app.gatedelay.palgate.PalgateException
import app.gatedelay.storage.KeyValueStore
import io.ktor.util.date.getTimeMillis
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
 *
 * The user must switch to the PalGate app to scan, so this app goes to the background meanwhile:
 * - it runs in an app-wide scope (the Android side also holds a foreground service so the network stays allowed);
 * - the pending link id is persisted, so if the process is killed, polling resumes on next start.
 */
class DeviceLinker(
    private val repo: GateRepository,
    private val scope: CoroutineScope,
    private val kv: KeyValueStore? = null,
    private val now: () -> Long = { getTimeMillis() },
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()
    private var job: Job? = null

    /** Resumes a link that was still waiting when the app was last killed. Call once at startup. */
    fun resumePending() {
        val saved = kv?.getString(PENDING_KEY) ?: return
        val id = saved.substringBefore('|')
        val startedAt = saved.substringAfter('|').toLongOrNull() ?: 0
        if (now() - startedAt < PENDING_MAX_AGE_MS) poll(id) else kv.putString(PENDING_KEY, null)
    }

    fun start() {
        val linkId = randomUuid()
        kv?.putString(PENDING_KEY, "$linkId|${now()}")
        poll(linkId)
    }

    fun cancel() = finish(LinkState.Idle)

    fun fail(message: String) = finish(LinkState.Failed(message))

    private fun finish(state: LinkState) {
        job?.cancel()
        kv?.putString(PENDING_KEY, null)
        _state.value = state
    }

    private fun poll(linkId: String) {
        job?.cancel()
        _state.value = LinkState.WaitingForScan("""{"id": "$linkId"}""")
        job = scope.launch {
            var networkFailures = 0
            repeat(MAX_POLLS) {
                try {
                    val result = repo.api.waitForLink(linkId)
                    if (result != null) {
                        repo.addAccount(LinkedCredentials(result.phone, result.tokenHex, result.tokenType))
                        kv?.putString(PENDING_KEY, null)
                        _state.value = LinkState.Linked(result.phone)
                        return@launch
                    }
                    networkFailures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PalgateException) {
                    kv?.putString(PENDING_KEY, null)
                    _state.value = LinkState.Failed(e.message ?: "Linking failed")
                    return@launch
                } catch (e: Exception) {
                    // Timeouts / dropped connections: keep polling for a while.
                    if (++networkFailures >= 30) {
                        _state.value = LinkState.Failed("Network problem while waiting for the scan: ${e.message}")
                        return@launch
                    }
                    delay(2_000)
                }
            }
            kv?.putString(PENDING_KEY, null)
            _state.value = LinkState.Failed("Timed out waiting for the QR scan. Try again.")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun randomUuid(): String = Uuid.random().toString() // cryptographically secure: the id acts as a secret

    private companion object {
        const val MAX_POLLS = 60
        const val PENDING_KEY = "pending_link"
        const val PENDING_MAX_AGE_MS = 15 * 60_000L
    }
}
