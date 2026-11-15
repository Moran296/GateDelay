package app.gatedelay.model

import kotlinx.serialization.Serializable

/** A linked PalGate account. The session token itself is kept in [app.gatedelay.storage.SecretStore]. */
@Serializable
data class Account(
    val phone: String,
    val tokenType: Int,
    val displayName: String = "+$phone",
)

/** One controllable gate = one output of one PalGate device, opened through one account. */
@Serializable
data class GateRef(
    val accountPhone: String,
    val deviceId: String,
    val outputNum: Int = 1,
    val label: String,
    val address: String? = null,
)

@Serializable
enum class GateRole(val title: String) { MAIN("Main"), ENTRY("Entry"), EXIT("Exit") }

@Serializable
enum class SequenceKind(val title: String) { ENTER("ENTER"), EXIT("EXIT") }

@Serializable
data class SequenceConfig(val first: GateRole, val delaySec: Int, val second: GateRole)

@Serializable
data class AppConfig(
    val accounts: List<Account> = emptyList(),
    val gates: Map<GateRole, GateRef> = emptyMap(),
    val enter: SequenceConfig = SequenceConfig(GateRole.MAIN, 10, GateRole.ENTRY),
    val exit: SequenceConfig = SequenceConfig(GateRole.EXIT, 27, GateRole.MAIN),
) {
    fun sequence(kind: SequenceKind) = when (kind) {
        SequenceKind.ENTER -> enter
        SequenceKind.EXIT -> exit
    }

    fun isReady(kind: SequenceKind): Boolean {
        val s = sequence(kind)
        return gates[s.first] != null && gates[s.second] != null
    }
}

/** Credentials obtained from device linking or manual import. */
data class LinkedCredentials(val phone: String, val tokenHex: String, val tokenType: Int)
