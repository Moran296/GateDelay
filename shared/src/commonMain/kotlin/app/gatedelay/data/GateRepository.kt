package app.gatedelay.data

import app.gatedelay.model.Account
import app.gatedelay.model.AppConfig
import app.gatedelay.model.GateRef
import app.gatedelay.model.GateRole
import app.gatedelay.model.LinkedCredentials
import app.gatedelay.model.SequenceConfig
import app.gatedelay.model.SequenceKind
import app.gatedelay.palgate.PalgateApi
import app.gatedelay.palgate.PalgateAuth
import app.gatedelay.palgate.PalgateException
import app.gatedelay.palgate.TokenType
import app.gatedelay.palgate.hexToBytes
import app.gatedelay.storage.KeyValueStore
import app.gatedelay.storage.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/** A gate offered in the picker, together with the account it comes from. */
data class GateOption(val ref: GateRef, val accountName: String)

/** Result of loading gates for all accounts (errors per account are reported, not thrown). */
data class GateListing(val options: List<GateOption>, val errors: Map<String, String>)

/** Single source of truth for configuration, accounts and gate operations. */
class GateRepository(
    private val kv: KeyValueStore,
    private val secrets: SecretStore,
    val api: PalgateApi,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private fun load(): AppConfig =
        kv.getString(CONFIG_KEY)?.let { runCatching { json.decodeFromString<AppConfig>(it) }.getOrNull() } ?: AppConfig()

    private fun save(transform: (AppConfig) -> AppConfig) {
        _config.update(transform)
        kv.putString(CONFIG_KEY, json.encodeToString(AppConfig.serializer(), _config.value))
    }

    // --- Accounts -----------------------------------------------------------------------------

    fun addAccount(creds: LinkedCredentials) {
        val phone = creds.phone.trim().removePrefix("+")
        require(phone.isNotEmpty() && phone.all { it.isDigit() }) { "Phone must be digits in international format, e.g. 972501234567" }
        val token = creds.tokenHex.trim()
        require(runCatching { token.hexToBytes() }.getOrNull()?.size == 16) { "Token must be 32 hex characters" }
        require(creds.tokenType in listOf(TokenType.SMS, TokenType.PRIMARY, TokenType.SECONDARY)) { "Token type must be 0, 1 or 2" }

        secrets.put(tokenKey(phone), token)
        save { cfg -> cfg.copy(accounts = cfg.accounts.filterNot { it.phone == phone } + Account(phone, creds.tokenType)) }
    }

    fun removeAccount(phone: String) {
        secrets.put(tokenKey(phone), null)
        save { cfg ->
            cfg.copy(
                accounts = cfg.accounts.filterNot { it.phone == phone },
                gates = cfg.gates.filterValues { it.accountPhone != phone },
            )
        }
    }

    // --- Gates & sequences --------------------------------------------------------------------

    fun assignGate(role: GateRole, ref: GateRef?) = save { cfg ->
        cfg.copy(gates = if (ref == null) cfg.gates - role else cfg.gates + (role to ref))
    }

    fun setSequence(kind: SequenceKind, seq: SequenceConfig) = save { cfg ->
        val clean = seq.copy(delaySec = seq.delaySec.coerceIn(0, 300))
        when (kind) {
            SequenceKind.ENTER -> cfg.copy(enter = clean)
            SequenceKind.EXIT -> cfg.copy(exit = clean)
        }
    }

    suspend fun listAllGates(): GateListing {
        val options = mutableListOf<GateOption>()
        val errors = mutableMapOf<String, String>()
        for (account in config.value.accounts) {
            try {
                api.listGates(auth(account.phone)).forEach { g ->
                    options += GateOption(
                        GateRef(account.phone, g.deviceId, g.outputNum, g.name, g.address),
                        account.displayName,
                    )
                }
            } catch (e: Exception) {
                errors[account.displayName] = e.message ?: e.toString()
            }
        }
        return GateListing(options, errors)
    }

    suspend fun openGate(ref: GateRef) = api.openGate(auth(ref.accountPhone), ref.deviceId, ref.outputNum)

    private fun auth(phone: String): PalgateAuth {
        val account = config.value.accounts.firstOrNull { it.phone == phone }
            ?: throw PalgateException("Account +$phone is not linked anymore")
        val token = secrets.get(tokenKey(phone)) ?: throw PalgateException("Missing token for +$phone - re-link the account")
        return PalgateAuth(phone.toLong(), token.hexToBytes(), account.tokenType)
    }

    private fun tokenKey(phone: String) = "token_$phone"

    companion object {
        private const val CONFIG_KEY = "app_config_v1"
    }
}
