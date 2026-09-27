package com.sankaku.nativ

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class Tag(
    @SerialName("name_en") val nameEn: String = "",
    val type: Int = 0,
    val count: Int = 0,
    @SerialName("tagName") val tagName: String = "",
)

@Serializable
data class Post(
    val id: String,
    val rating: String = "",
    @SerialName("preview_url") val previewUrl: String = "",
    @SerialName("sample_url") val sampleUrl: String = "",
    @SerialName("file_url") val fileUrl: String = "",
    @SerialName("file_type") val fileType: String = "",
    @SerialName("file_ext") val fileExt: String = "",
    @SerialName("file_size") val fileSize: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("fav_count") val favCount: Int = 0,
    @SerialName("redirect_to_signup") val redirectToSignup: Boolean = false,
    val tags: List<Tag> = emptyList(),
) {
    val isVideo get() = fileType.startsWith("video")
    val bestUrl get() = sampleUrl.ifEmpty { fileUrl }.ifEmpty { previewUrl }
    val tagString get() = tags.take(12).joinToString(" ") { it.tagName.ifEmpty { it.nameEn } }
    val tagNames: Set<String> get() = tags.map { (it.tagName.ifEmpty { it.nameEn }).lowercase() }.toSet()
}

interface SankakuApi {
    @GET("posts")
    suspend fun posts(
        @Query("page") page: Int,
        @Query("limit") limit: Int = 40,
        @Query("tags") tags: String = "",
        @Query("lang") lang: String = "en",
    ): List<Post>

    /**
     * Favourites are not an endpoint — the site lists them as a tag search
     * (`fav:<username>`) on /posts. Writes are posts/{id}/favorite, per the
     * site's own api.js: `post(`posts/${e}/favorite`)`.
     */
    @POST("posts/{id}/favorite")
    suspend fun favorite(@Path("id") id: String): ResponseBody

    @DELETE("posts/{id}/favorite")
    suspend fun unfavorite(@Path("id") id: String): ResponseBody
}

@Serializable
data class LoginBody(val login: String, val password: String)

@Serializable
data class RefreshBody(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class MeUser(val name: String = "", val email: String? = null)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    // the site reads this off the same response; we were dropping it silently
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("current_user") val currentUser: MeUser,
)

@Serializable
data class MeResponse(val user: MeUser)

interface AuthApi {
    @POST("auth/token")
    suspend fun token(@Body body: LoginBody): LoginResponse

    /** Same endpoint, different body — this is how the site renews a session. */
    @POST("auth/token")
    suspend fun refresh(@Body body: RefreshBody): LoginResponse

    // ponytail: was capi-v2.sankakucomplex.com, which nginx 403s every path
    // (verified 2026-09-27, incl. /users/me). login host serves the same payload.
    @GET("users/me")
    suspend fun me(@Header("Authorization") auth: String): MeResponse
}

/** Sign-in for sankakuapi.com. Must not use the token-injecting client. */
interface ApiAuthApi {
    @POST("auth/token")
    suspend fun apiToken(@Body body: LoginBody): LoginResponse

    @POST("auth/token")
    suspend fun refresh(@Body body: RefreshBody): LoginResponse
}

/** In-memory auth header; persisted in DataStore, loaded at startup. */
object AuthState {
    @Volatile var header: String? = null

    /** Separate token for sankakuapi.com — the one above is rejected there. */
    @Volatile var apiHeader: String? = null

    /** Lets a dead 7-day access token be replaced without asking for a password. */
    @Volatile var refreshToken: String? = null
    @Volatile var apiRefreshToken: String? = null

    /** Set when the server rejects our token; tokens are only good ~7 days and
     *  without this the app silently drops to anonymous (page 1 only, no favourites). */
    val expired = MutableStateFlow(false)
}

object Api {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "SankakuNative/0.1")
                .header("Accept", "application/json")
            // the two hosts want different tokens; and never clobber one the call set itself
            val token =
                if (chain.request().url.host.endsWith("sankakuapi.com")) AuthState.apiHeader
                else AuthState.header
            if (token != null && chain.request().header("Authorization") == null)
                req.header("Authorization", token)
            var res = chain.proceed(req.build())
            // /auth/token doubles as the refresh endpoint on both hosts. Retry once
            // on 401 so a 7-day-old access token heals itself instead of logging
            // the user out. Skipped for auth/* so a dead refresh token can't recurse.
            if (res.code == 401 && !chain.request().url.encodedPath.contains("auth/")) {
                val fresh = runCatching { refresh(chain.request().url.host.endsWith("sankakuapi.com")) }
                    .getOrNull()
                if (fresh != null) {
                    res = chain.proceed(
                        chain.request().newBuilder().header("Authorization", fresh).build()
                    )
                }
            }
            if (token != null && res.code == 401) AuthState.expired.value = true
            res
        }
        .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
        .build()

    /** No token injection at all — signing in must not present an existing token. */    private val plainClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "SankakuNative/0.1")
                .header("Accept", "application/json")
                .build())
        }
        .build()

    val apiAuth: ApiAuthApi = Retrofit.Builder()
        .baseUrl("https://sankakuapi.com/")
        .client(plainClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ApiAuthApi::class.java)

    val service: SankakuApi = Retrofit.Builder()
        .baseUrl("https://sankakuapi.com/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(SankakuApi::class.java)
    val authService: AuthApi = Retrofit.Builder()
        .baseUrl("https://login.sankakucomplex.com/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(AuthApi::class.java)

    /**
     * Trades a refresh token for a fresh access token on whichever host asked.
     * Returns the new Authorization header, or null if it can't be refreshed.
     *
     * ponytail: runBlocking inside an interceptor, which OkHttp generally advises
     * against. It is the only way to refresh before the response is consumed, and
     * one blocked dispatcher thread out of many is tolerable here.
     */
    private fun refresh(apiHost: Boolean): String? = runBlocking {
        val rt = if (apiHost) AuthState.apiRefreshToken else AuthState.refreshToken
        if (rt.isNullOrBlank()) return@runBlocking null
        val body = RefreshBody(rt)
        val res = if (apiHost) apiAuth.refresh(body) else authService.refresh(body)
        val token = res.accessToken
        if (token.isBlank()) {
            android.util.Log.w("SankakuAuth", "refresh returned no token for apiHost=$apiHost")
            return@runBlocking null
        }
        val header = "${res.tokenType.ifBlank { "Bearer" }} $token"
        if (apiHost) {
            AuthState.apiHeader = header
            AuthState.apiRefreshToken = res.refreshToken.ifBlank { rt }
            onApiToken?.invoke(res.refreshToken.ifBlank { rt })
        } else {
            AuthState.header = header
            AuthState.refreshToken = res.refreshToken.ifBlank { rt }
            onToken?.invoke(res.refreshToken.ifBlank { rt })
        }
        header
    }

    /** Wired up by the ViewModel so a rotated token is persisted. */
    var onToken: ((String) -> Unit)? = null
    var onApiToken: ((String) -> Unit)? = null
}

/** Rating filter is a tag in this API (tags=rating:safe), not a query param —
 *  verified 2026-09-27, a `rating=s` param is silently ignored. */
private val RATING_TAG = mapOf("s" to "safe", "q" to "questionable", "e" to "explicit")

class PostsPagingSource(
    private val api: SankakuApi = Api.service,
    private val tags: String,
    private val ratings: Set<String> = setOf("s", "q", "e"),
    private val blacklist: Set<String> = emptySet(),
) : PagingSource<Int, Post>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Post> {
        val page = params.key ?: 1
        return try {
            // Only one rating:tag is honoured; space-separated tags are AND and
            // comma/pipe aren't OR, so push it down only for a single selection.
            // Blacklist stays local: -tag is rejected for non-premium accounts
            // (snackbar__account_regular_excluded-tags-limit).
            val serverTags = buildString {
                if (tags.isNotBlank()) append(tags.trim())
                if (ratings.size == 1) {
                    if (isNotEmpty()) append(' ')
                    append("rating:").append(RATING_TAG[ratings.first()] ?: ratings.first())
                }
            }
            // Anon API redacts gated posts (no URLs) — drop them, full access needs login.
            val posts = api.posts(page = page, limit = 40, tags = serverTags)
                .filter { !it.redirectToSignup && (it.previewUrl.isNotBlank() || it.bestUrl.isNotBlank()) }
                .filter { it.rating.firstOrNull()?.lowercase() in ratings }
                .filter { it.tagNames.intersect(blacklist).isEmpty() }
            LoadResult.Page(
                data = posts,
                prevKey = if (page == 1) null else page - 1,
                // ponytail: fixed limit pages, keyed cursor if API adds next-page token
                nextKey = if (posts.isEmpty()) null else page + 1,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
    override fun getRefreshKey(state: PagingState<Int, Post>): Int? =
        state.anchorPosition?.let { state.closestPageToPosition(it)?.prevKey?.plus(1) }
}

fun postsPager(tags: String, ratings: Set<String> = setOf("s", "q", "e"), blacklist: Set<String> = emptySet()) =
    Pager(PagingConfig(pageSize = 40, prefetchDistance = 10)) {
        PostsPagingSource(tags = tags, ratings = ratings, blacklist = blacklist)
    }.flow
