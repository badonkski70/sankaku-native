package com.sankaku.nativ

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
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
data class MeUser(val name: String = "", val email: String? = null)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("current_user") val currentUser: MeUser,
)

@Serializable
data class MeResponse(val user: MeUser)

interface AuthApi {
    @POST("auth/token")
    suspend fun token(@Body body: LoginBody): LoginResponse

    // ponytail: was capi-v2.sankakucomplex.com, which nginx 403s every path
    // (verified 2026-09-27, incl. /users/me). login host serves the same payload.
    @GET("users/me")
    suspend fun me(@Header("Authorization") auth: String): MeResponse
}

/** Sign-in for sankakuapi.com. Must not use the token-injecting client. */
interface ApiAuthApi {
    @POST("auth/token")
    suspend fun apiToken(@Body body: LoginBody): LoginResponse
}

/** In-memory auth header; persisted in DataStore, loaded at startup. */
object AuthState {
    @Volatile var header: String? = null

    /** Separate token for sankakuapi.com — the one above is rejected there. */
    @Volatile var apiHeader: String? = null
}

object Api {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "SankakuNative/0.1")
                .header("Accept", "application/json")
            // the two hosts want different tokens; and never clobber one the call set itself
            if (chain.request().header("Authorization") == null) {
                val token =
                    if (chain.request().url.host.endsWith("sankakuapi.com")) AuthState.apiHeader
                    else AuthState.header
                token?.let { req.header("Authorization", it) }
            }
            chain.proceed(req.build())
        }
        .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
        .build()

    /** No token injection at all — signing in must not present an existing token. */
    private val plainClient = OkHttpClient.Builder()
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
}

class PostsPagingSource(
    private val api: SankakuApi = Api.service,
    private val tags: String,
    private val ratings: Set<String> = setOf("s", "q", "e"),
    private val blacklist: Set<String> = emptySet(),
) : PagingSource<Int, Post>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Post> {
        val page = params.key ?: 1
        return try {
            // Anon API redacts gated posts (no URLs) — drop them, full access needs login.
            val posts = api.posts(page = page, limit = 40, tags = tags)
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
