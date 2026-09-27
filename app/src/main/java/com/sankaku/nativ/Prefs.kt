package com.sankaku.nativ

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val Context.dataStore by preferencesDataStore("settings")

object PrefKeys {
    val theme = stringPreferencesKey("theme") // system | light | dark
    val ratings = stringSetPreferencesKey("ratings") // s, q, e
    val columns = intPreferencesKey("columns")
    val blacklist = stringSetPreferencesKey("blacklist")
    // ponytail: whole Post JSON cached, so the signed image URLs (?e=&expires=)
    // go stale and signed-out hearts eventually show broken images. Superseded by
    // account favourites when signed in; rehydrate by id if that ever matters.
    val favs = stringSetPreferencesKey("favs") // Post JSON, keyed by id
    val history = stringSetPreferencesKey("history") // DlEntry JSON, keyed by post id
    val authToken = stringPreferencesKey("auth_token")
    val tokenType = stringPreferencesKey("token_type")
    val accountName = stringPreferencesKey("account_name")
    val accountEmail = stringPreferencesKey("account_email")
    // sankakuapi.com rejects the login-host token with common_unauthorized, so it
    // needs its own; without it the app can only ever load page 1.
    val apiToken = stringPreferencesKey("api_token")
}

@Serializable
data class DlEntry(
    val id: String,
    val filename: String,
    val mime: String,
    val uri: String,
    val bytes: Long,
    val at: Long,
)

@Serializable
data class Account(val name: String, val email: String = "")

class PrefsStore(private val ctx: Context) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    val theme: Flow<String> = ctx.dataStore.data.map { it[PrefKeys.theme] ?: "system" }
    val ratings: Flow<Set<String>> = ctx.dataStore.data.map { it[PrefKeys.ratings] ?: setOf("s", "q", "e") }
    val columns: Flow<Int> = ctx.dataStore.data.map { it[PrefKeys.columns] ?: 2 }
    val blacklist: Flow<Set<String>> = ctx.dataStore.data.map { it[PrefKeys.blacklist] ?: emptySet() }
    val favs: Flow<List<Post>> = ctx.dataStore.data.map { prefs ->
        (prefs[PrefKeys.favs] ?: emptySet()).mapNotNull {
            runCatching { json.decodeFromString<Post>(it) }.getOrNull()
        }.sortedBy { it.id }
    }
    val history: Flow<List<DlEntry>> = ctx.dataStore.data.map { prefs ->
        (prefs[PrefKeys.history] ?: emptySet()).mapNotNull {
            runCatching { json.decodeFromString<DlEntry>(it) }.getOrNull()
        }.sortedByDescending { it.at }
    }
    // ponytail: token in plain DataStore; EncryptedFile if this ever holds more than a booru login
    val authHeader: Flow<String?> = ctx.dataStore.data.map { prefs ->
        val t = prefs[PrefKeys.authToken]
        if (t.isNullOrBlank()) null else "${prefs[PrefKeys.tokenType] ?: "Bearer"} $t"
    }
    val account: Flow<Account?> = ctx.dataStore.data.map { prefs ->
        val n = prefs[PrefKeys.accountName]
        if (n.isNullOrBlank()) null else Account(n, prefs[PrefKeys.accountEmail] ?: "")
    }
    val apiTokenFlow: Flow<String?> = ctx.dataStore.data.map {
        it[PrefKeys.apiToken]?.takeIf { t -> t.isNotBlank() }
    }

    suspend fun setTheme(v: String) { ctx.dataStore.edit { it[PrefKeys.theme] = v } }
    suspend fun setRatings(v: Set<String>) { ctx.dataStore.edit { it[PrefKeys.ratings] = v } }
    suspend fun setColumns(v: Int) { ctx.dataStore.edit { it[PrefKeys.columns] = v } }
    suspend fun setBlacklist(v: Set<String>) { ctx.dataStore.edit { it[PrefKeys.blacklist] = v } }

    suspend fun toggleFav(post: Post) {
        ctx.dataStore.edit { prefs ->
            val cur = (prefs[PrefKeys.favs] ?: emptySet()).toMutableSet()
            val existing = cur.firstOrNull { runCatching { json.decodeFromString<Post>(it).id == post.id }.getOrDefault(false) }
            if (existing != null) cur.remove(existing)
            else cur.add(json.encodeToString(Post.serializer(), post))
            prefs[PrefKeys.favs] = cur
        }
    }

    suspend fun addHistory(e: DlEntry) {
        ctx.dataStore.edit { prefs ->
            val cur = (prefs[PrefKeys.history] ?: emptySet())
                .filterNot { runCatching { json.decodeFromString<DlEntry>(it).id == e.id }.getOrDefault(false) }
                .toMutableSet()
            cur.add(json.encodeToString(DlEntry.serializer(), e))
            prefs[PrefKeys.history] = cur
        }
    }

    suspend fun removeHistory(id: String) {
        ctx.dataStore.edit { prefs ->
            prefs[PrefKeys.history] = (prefs[PrefKeys.history] ?: emptySet())
                .filterNot { runCatching { json.decodeFromString<DlEntry>(it).id == id }.getOrDefault(false) }
                .toSet()
        }
    }

    suspend fun clearHistory() {
        ctx.dataStore.edit { it.remove(PrefKeys.history) }
    }

    suspend fun saveAccount(token: String, type: String, name: String, email: String) {
        ctx.dataStore.edit {
            it[PrefKeys.authToken] = token
            it[PrefKeys.tokenType] = type
            it[PrefKeys.accountName] = name
            it[PrefKeys.accountEmail] = email
        }
    }

    /** Mints the sankakuapi.com token from the same credentials. */
    suspend fun saveApiToken(token: String) {
        ctx.dataStore.edit { it[PrefKeys.apiToken] = token }
    }

    suspend fun clearAccount() {
        ctx.dataStore.edit {
            it.remove(PrefKeys.authToken)
            it.remove(PrefKeys.tokenType)
            it.remove(PrefKeys.accountName)
            it.remove(PrefKeys.accountEmail)
            it.remove(PrefKeys.apiToken)
        }
    }
}
