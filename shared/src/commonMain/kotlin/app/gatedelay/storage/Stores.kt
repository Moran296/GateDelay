package app.gatedelay.storage

/** Plain key/value persistence for non-secret config (Android: SharedPreferences, iOS: NSUserDefaults). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** Encrypted persistence for session tokens (Android: Keystore, iOS: Keychain). */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class InMemoryStore : KeyValueStore, SecretStore {
    private val map = mutableMapOf<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) = putString(key, value)
}
