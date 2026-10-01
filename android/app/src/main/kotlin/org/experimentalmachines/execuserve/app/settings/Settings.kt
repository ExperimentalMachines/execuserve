package org.experimentalmachines.execuserve.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.experimentalmachines.execuserve.host.HostSettings
import org.experimentalmachines.execuserve.host.HostStore
import org.experimentalmachines.execuserve.server.ApiKey
import java.security.SecureRandom

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Serializable
private data class StoredKey(val id: String, val name: String, val secret: String)

/**
 * [HostSettings] and the API keys, in DataStore. A setting is stored only while it differs
 * from its default, so a default changed in the engine reaches everyone who never touched it.
 */
class SettingsStore(private val context: Context) : HostStore {

    override val settings: Flow<HostSettings> = context.store.data.map(::fromPrefs)

    suspend fun current(): HostSettings = settings.first()

    suspend fun update(change: (HostSettings) -> HostSettings) {
        context.store.edit { p ->
            // Read from the transaction's own snapshot: reading the store's flow inside an
            // edit waits for the edit to finish, which is a deadlock.
            val next = change(fromPrefs(p))
            FIELDS.forEach { it.write(p, next) }
        }
    }

    private fun fromPrefs(p: Preferences): HostSettings = FIELDS.fold(DEFAULTS) { settings, field -> field.read(p, settings) }

    // Keys --------------------------------------------------------------------------------

    override val keys: Flow<List<ApiKey>> = context.store.data.map { p -> decodeKeys(p[KEYS]) }

    override suspend fun ensureKeys(): List<ApiKey> {
        var result: List<ApiKey> = emptyList()
        context.store.edit { p ->
            val existing = decodeKeys(p[KEYS])
            result = existing.ifEmpty { listOf(newKey("Default")) }
            if (existing.isEmpty()) p[KEYS] = encodeKeys(result)
        }
        return result
    }

    suspend fun addKey(name: String, secret: String? = null): ApiKey {
        val key = newKey(name, secret)
        context.store.edit { p -> p[KEYS] = encodeKeys(decodeKeys(p[KEYS]) + key) }
        return key
    }

    /** Replaces the key called [name] (the adb CLI's), or adds it. */
    suspend fun putKey(name: String, secret: String) {
        context.store.edit { p ->
            val rest = decodeKeys(p[KEYS]).filterNot { it.name == name }
            p[KEYS] = encodeKeys(rest + newKey(name, secret))
        }
    }

    /** The key called [name], made on first use: the console's own test requests use one. */
    suspend fun keyFor(name: String): ApiKey {
        keys.first().firstOrNull { it.name == name }?.let { return it }
        return addKey(name)
    }

    suspend fun revokeKey(id: String) {
        context.store.edit { p -> p[KEYS] = encodeKeys(decodeKeys(p[KEYS]).filterNot { it.id == id }) }
    }

    /**
     * The last time the server came back without being asked: Android restarted the service
     * after the process died. Cleared by a start someone asked for.
     */
    val recovery: Flow<Recovery?> = context.store.data.map { p ->
        p[RECOVERED_AT]?.let { Recovery(it, p[RECOVERED_WEDGED] ?: false) }
    }

    suspend fun setRecovery(recovery: Recovery?) {
        context.store.edit { p ->
            if (recovery == null) {
                p.remove(RECOVERED_AT)
                p.remove(RECOVERED_WEDGED)
            } else {
                p[RECOVERED_AT] = recovery.atMs
                p[RECOVERED_WEDGED] = recovery.afterWedge
            }
        }
    }

    /** Set just before the process ends itself over a wedged runtime, so the restart can say why. */
    suspend fun setWedged(wedged: Boolean) {
        context.store.edit { it[WEDGED] = wedged }
    }

    suspend fun wedged(): Boolean = context.store.data.first()[WEDGED] ?: false

    /** Whether the server was running when the process last died, for a sticky restart. */
    suspend fun setWasServing(serving: Boolean) {
        context.store.edit { it[WAS_SERVING] = serving }
    }

    suspend fun wasServing(): Boolean = context.store.data.first()[WAS_SERVING] ?: false

    private fun newKey(name: String, secret: String? = null) = ApiKey(
        id = randomToken(ID_CHARS),
        name = name.trim().ifEmpty { "Key" },
        secret = secret ?: ("es-" + randomToken(SECRET_CHARS)),
    )

    private fun decodeKeys(raw: String?): List<ApiKey> = raw?.let {
        runCatching { JSON.decodeFromString(ListSerializer(StoredKey.serializer()), it) }.getOrNull()
    }.orEmpty().map { ApiKey(it.id, it.name, it.secret) }

    private fun encodeKeys(keys: List<ApiKey>): String = JSON.encodeToString(
        ListSerializer(StoredKey.serializer()),
        keys.map { StoredKey(it.id, it.name, it.secret) },
    )

    internal companion object {
        val DEFAULTS = HostSettings()
        val KEYS = stringPreferencesKey("keys")
        val WAS_SERVING = booleanPreferencesKey("was_serving")
        val WEDGED = booleanPreferencesKey("wedged")
        val RECOVERED_AT = longPreferencesKey("recovered_at_ms")
        val RECOVERED_WEDGED = booleanPreferencesKey("recovered_after_wedge")

        /** Every stored setting, once: its key (unchanged since the first release), and how it maps. */
        val FIELDS: List<Field<*>> = listOf(
            Field(intPreferencesKey("port"), { it.port }) { s, v -> s.copy(port = v) },
            choice("bind", { it.bind }) { s, v -> s.copy(bind = v) },
            Field(booleanPreferencesKey("open_loopback"), { it.openLoopback }) { s, v -> s.copy(openLoopback = v) },
            Field(stringPreferencesKey("default_model"), { it.defaultModel }) { s, v -> s.copy(defaultModel = v) },
            Field(stringSetPreferencesKey("preload_models"), { it.preloadModels }) { s, v -> s.copy(preloadModels = v) },
            Field(intPreferencesKey("max_resident_models"), { it.maxResidentModels }) { s, v -> s.copy(maxResidentModels = v.coerceIn(1, 3)) },
            Field(booleanPreferencesKey("start_at_boot"), { it.startAtBoot }) { s, v -> s.copy(startAtBoot = v) },
            choice("wake", { it.wake }) { s, v -> s.copy(wake = v) },
            Field(intPreferencesKey("max_queued"), { it.maxQueued }) { s, v -> s.copy(maxQueued = v) },
            Field(intPreferencesKey("max_per_client"), { it.maxPerClient }) { s, v -> s.copy(maxPerClient = v) },
            choice("thinking", { it.thinking }) { s, v -> s.copy(thinking = v) },
            Field(intPreferencesKey("min_battery"), { it.minBatteryPercent }) { s, v -> s.copy(minBatteryPercent = v) },
            Field(intPreferencesKey("idle_unload_minutes"), { it.idleUnloadMinutes }) { s, v -> s.copy(idleUnloadMinutes = v) },
            Field(stringPreferencesKey("cors"), { it.corsOrigins }) { s, v -> s.copy(corsOrigins = v) },
            Field(stringPreferencesKey("extra_hosts"), { it.extraHosts }) { s, v -> s.copy(extraHosts = v) },
            Field(booleanPreferencesKey("external_start"), { it.allowExternalStart }) { s, v -> s.copy(allowExternalStart = v) },
            choice("theme", { it.theme }) { s, v -> s.copy(theme = v) },
            Field(floatPreferencesKey("temperature"), { it.temperature }) { s, v -> s.copy(temperature = v) },
            Field(booleanPreferencesKey("keep_reasoning"), { it.keepReasoningInHistory }) { s, v -> s.copy(keepReasoningInHistory = v) },
            Field(intPreferencesKey("queue_timeout_s"), { it.queueTimeoutSeconds }) { s, v -> s.copy(queueTimeoutSeconds = v) },
            Field(intPreferencesKey("request_timeout_s"), { it.requestTimeoutSeconds }) { s, v -> s.copy(requestTimeoutSeconds = v) },
            Field(intPreferencesKey("threads"), { it.threads }) { s, v -> s.copy(threads = v) },
        )

        /** An enum, stored by name; a name this build does not know reads as the default. */
        inline fun <reified E : Enum<E>> choice(name: String, noinline get: (HostSettings) -> E, noinline set: (HostSettings, E) -> HostSettings) =
            Field(stringPreferencesKey(name), { get(it).name }) { s, v -> enumValues<E>().firstOrNull { it.name == v }?.let { set(s, it) } ?: s }

        const val ID_CHARS = 8
        const val SECRET_CHARS = 32
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val JSON = Json { ignoreUnknownKeys = true }
        val random = SecureRandom()

        fun randomToken(length: Int) = buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
    }
}

/** One setting in DataStore. */
internal class Field<T : Any>(val key: Preferences.Key<T>, val get: (HostSettings) -> T?, val set: (HostSettings, T) -> HostSettings) {
    fun read(p: Preferences, into: HostSettings): HostSettings = p[key]?.let { set(into, it) } ?: into

    fun write(p: MutablePreferences, from: HostSettings) {
        val value = get(from)
        if (value == null || value == get(HostSettings())) p.remove(key) else p[key] = value
    }
}

/** The server came back at [atMs] after its process died; [afterWedge] when it ended itself over a stuck runtime. */
data class Recovery(val atMs: Long, val afterWedge: Boolean)
