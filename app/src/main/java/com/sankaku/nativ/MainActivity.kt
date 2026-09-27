package com.sankaku.nativ

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = PrefsStore(app)

    val theme = prefs.theme.stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    val ratings = prefs.ratings.stateIn(viewModelScope, SharingStarted.Eagerly, setOf("s", "q", "e"))
    val columns = prefs.columns.stateIn(viewModelScope, SharingStarted.Eagerly, 2)
    val blacklist = prefs.blacklist.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val favList = prefs.favs.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val history = prefs.history.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val account = prefs.account.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val loginError = MutableStateFlow<String?>(null)

    private val query = MutableStateFlow("")
    private val authBump = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            AuthState.header = prefs.authHeader.first()
            AuthState.apiHeader = prefs.apiTokenFlow.first()?.let { "Bearer $it" }
            authBump.value += 1
        }
    }

    /**
     * sankakuapi.com rejects the login-host token, so the account needs signing in
     * there too. Best-effort: the app still browses anonymously without it.
     */
    fun grantApiToken(login: String, password: String) = viewModelScope.launch {
        if (login.isBlank() || password.isBlank()) return@launch
        apiTokenError = null
        runCatching { Api.apiAuth.apiToken(LoginBody(login.trim(), password)).accessToken }
            .onSuccess {
                prefs.saveApiToken(it)
                AuthState.apiHeader = "Bearer $it"
                authBump.value += 1
            }
            .onFailure { apiTokenError = it.message ?: "sign-in failed" }
    }

    var apiTokenError by mutableStateOf<String?>(null)
        private set

    /** Signed in, but still missing the token sankakuapi.com needs. */
    val needsApiToken: Flow<Boolean> =
        combine(prefs.account, prefs.apiTokenFlow) { a, t -> a != null && t == null }

    @OptIn(ExperimentalCoroutinesApi::class)
    val posts: Flow<PagingData<Post>> =
        combine(query, ratings, blacklist, authBump) { q, r, b, _ -> Triple(q, r, b) }
            .flatMapLatest { (q, r, b) -> postsPager(q, r, b) }
            .cachedIn(viewModelScope)

    /**
     * The account's favourites, as the `fav:<name>` tag search the website uses.
     * Empty page when signed out, so the tab falls back to local hearts.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val accountFavs: Flow<PagingData<Post>> =
        combine(account, authBump) { a, _ -> a?.name }
            .flatMapLatest { name ->
                if (name == null) flowOf(PagingData.empty()) else postsPager("fav:$name")
            }
            .cachedIn(viewModelScope)

    fun login(login: String, password: String, token: String) {
        loginError.value = null
        viewModelScope.launch {
            try {
                if (token.isNotBlank()) {
                    val me = Api.authService.me("Bearer ${token.trim()}")
                    prefs.saveAccount(token.trim(), "Bearer", me.user.name, me.user.email ?: "")
                } else {
                    val res = Api.authService.token(LoginBody(login.trim(), password))
                    prefs.saveAccount(res.accessToken, res.tokenType, res.currentUser.name, res.currentUser.email ?: "")
                }
                AuthState.header = prefs.authHeader.first()
                authBump.value += 1
                AuthState.expired.value = false
                // same credentials, second host — only possible with a password
                if (token.isBlank()) grantApiToken(login, password)
            } catch (e: HttpException) {
                val raw = runCatching { e.response()?.errorBody()?.string() ?: "" }.getOrDefault("")
                val code = runCatching {
                    val o = org.json.JSONObject(raw)
                    o.optString("code", "").ifBlank { o.optString("error", "") }
                }.getOrDefault("")
                loginError.value = "Login failed (HTTP ${e.code()}${if (code.isNotBlank()) ": $code" else ""})"
            } catch (e: Exception) {
                loginError.value = "Login failed: ${e.message}"
            }
        }
    }

    fun logout() = viewModelScope.launch {
        prefs.clearAccount()
        AuthState.header = null
        AuthState.apiHeader = null
        AuthState.expired.value = false
        authBump.value += 1
    }

    fun search(tags: String) { query.value = tags.trim() }
    fun setTheme(v: String) = viewModelScope.launch { prefs.setTheme(v) }
    fun toggleRating(r: String) = viewModelScope.launch {
        val cur = ratings.value.toMutableSet()
        if (r in cur) { if (cur.size > 1) cur.remove(r) } else cur.add(r)
        prefs.setRatings(cur)
    }
    fun setColumns(v: Int) = viewModelScope.launch { prefs.setColumns(v.coerceIn(2, 5)) }
    fun setBlacklistCsv(csv: String) = viewModelScope.launch {
        prefs.setBlacklist(csv.split(",", " ", "\n").map { it.trim().lowercase().replace(" ", "_") }.filter { it.isNotBlank() }.toSet())
    }
    /**
     * Hearting keeps the local cache in step so signed-out use still works, and
     * best-effort mirrors to the account when signed in — the favourites tab reads
     * the account, so without the write the heart would look like it did nothing.
     */
    fun toggleFav(post: Post) = viewModelScope.launch {
        val wasFav = favList.value.any { it.id == post.id }
        prefs.toggleFav(post)
        if (account.value != null) {
            runCatching {
                if (wasFav) Api.service.unfavorite(post.id) else Api.service.favorite(post.id)
            }
            authBump.value += 1
        }
    }
    fun removeDl(e: DlEntry) = viewModelScope.launch {
        runCatching { getApplication<Application>().contentResolver.delete(Uri.parse(e.uri), null, null) }
        prefs.removeHistory(e.id)
    }
    fun clearDl(es: List<DlEntry>) = viewModelScope.launch {
        val cr = getApplication<Application>().contentResolver
        es.forEach { runCatching { cr.delete(Uri.parse(it.uri), null, null) } }
        prefs.clearHistory()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel()
            val theme by vm.theme.collectAsState()
            val dark = when (theme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            val ensureNotif: () -> Unit = {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                MainScreen(vm, ensureNotif)
            }
        }
    }
}

@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
fun MainScreen(vm: MainViewModel, ensureNotif: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Post?>(null) }
    val openPost: (Post) -> Unit = { selected = it }
    val searchTag: (String) -> Unit = { vm.search(it); tab = 0 }

    Scaffold(
        topBar = {
            if (AuthState.expired.collectAsState().value) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        "Session expired — sign in again to restore favourites and full results",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar {
                listOf(
                    "Browse" to R.drawable.anim_nav_browse,
                    "Favorites" to R.drawable.anim_nav_favorites,
                    "Downloads" to R.drawable.anim_nav_downloads,
                    "Settings" to R.drawable.anim_nav_settings,
                ).forEachIndexed { i, (label, res) ->
                    val selected = tab == i
                    val anim = AnimatedImageVector.animatedVectorResource(res)
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = i },
                        icon = {
                            Icon(
                                painter = rememberAnimatedVectorPainter(anim, selected),
                                contentDescription = label,
                            )
                        },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> BrowseScreen(vm, openPost)
                1 -> FavoritesScreen(vm, openPost)
                2 -> DownloadsScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
    selected?.let { ViewerDialog(it, vm, ensureNotif, onSearchTag = { searchTag(it); selected = null }) { selected = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
/** Blank grid with no explanation is the worst failure mode — say what went wrong. */
@Composable
private fun PagingError(items: LazyPagingItems<Post>, modifier: Modifier = Modifier) {
    val state = items.loadState.refresh
    if (state !is LoadState.Error) return
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Couldn't load posts\n${state.error.messageOrDetail()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { items.retry() }) { Text("Retry") }
        }
    }
}

private fun Throwable.messageOrDetail(): String = when (this) {
    is HttpException -> "HTTP ${code()}"
    is IOException -> message ?: "no connection"
    else -> message ?: javaClass.simpleName
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(vm: MainViewModel, onOpen: (Post) -> Unit) {
    var text by remember { mutableStateOf("") }
    val ratings by vm.ratings.collectAsState()
    val columns by vm.columns.collectAsState()
    val items = vm.posts.collectAsLazyPagingItems()

    Column(Modifier.fillMaxSize()) {
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = text,
                    onQueryChange = { text = it },
                    onSearch = { vm.search(text) },
                    expanded = false,
                    onExpandedChange = {},
                    placeholder = { Text("tags, e.g. touhou rating:safe") },
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
            },
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier.fillMaxWidth(),
        ) {}
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("s" to "Safe", "q" to "Questionable", "e" to "Explicit").forEach { (r, label) ->
                FilterChip(selected = r in ratings, onClick = { vm.toggleRating(r) }, label = { Text(label) })
            }
        }
        Box(Modifier.fillMaxSize()) {
            LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(columns), modifier = Modifier.fillMaxSize()) {
                items(items.itemCount) { i ->
                    items[i]?.let { post ->
                        Card(Modifier.padding(4.dp).clickable { onOpen(post) }) {
                            Box {
                                AsyncImage(
                                    model = post.previewUrl.ifEmpty { post.bestUrl },
                                    contentDescription = post.id,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(0.75f),
                                )
                                Surface(
                                    color = Color.Black.copy(alpha = 0.6f),
                                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                                ) {
                                    Text(
                                        (if (post.isVideo) "▶" else "") + post.rating.uppercase(),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            PagingError(items)
        }
    }
}

/**
 * Signed-out favourites are the only cached thumbnails on screen, and their signed
 * URLs (?e=&expires=) do expire. They can't be refreshed: /posts/{id} rejects the
 * numeric ids that anonymous requests hand out (verified — it only accepts the
 * obfuscated form), so an expired one gets an honest tile instead of a blank box.
 */
@Composable
private fun CachedThumb(post: Post, onOpen: () -> Unit) {
    var expired by remember(post.id) { mutableStateOf(false) }
    Card(Modifier.padding(4.dp).clickable(onClick = onOpen)) {
        Box {
            AsyncImage(
                model = post.previewUrl.ifEmpty { post.bestUrl },
                contentDescription = post.id,
                contentScale = ContentScale.Crop,
                onState = { if (it is AsyncImagePainter.State.Error) expired = true },
                modifier = Modifier.fillMaxWidth().aspectRatio(0.75f),
            )
            if (expired) {
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    modifier = Modifier.matchParentSize(),
                ) {
                    Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Saved link\nexpired",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FavoritesScreen(vm: MainViewModel, onOpen: (Post) -> Unit) {
    val account by vm.account.collectAsState()
    val columns by vm.columns.collectAsState()
    val items = vm.accountFavs.collectAsLazyPagingItems()

    if (account != null) {
        Box(Modifier.fillMaxSize()) {
            if (items.itemCount == 0 && items.loadState.refresh !is LoadState.Error) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No favourites on ${account!!.name} yet")
                }
            } else {
                LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(columns), modifier = Modifier.fillMaxSize()) {
                    items(items.itemCount) { i ->
                        items[i]?.let { post ->
                            Card(Modifier.padding(4.dp).clickable { onOpen(post) }) {
                                AsyncImage(
                                    model = post.previewUrl.ifEmpty { post.bestUrl },
                                    contentDescription = post.id,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(0.75f),
                                )
                            }
                        }
                    }
                }
            }
            PagingError(items)
        }
        return
    }

    val favs by vm.favList.collectAsState()
    if (favs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No favorites yet — ♥ a post to save it here") }
    } else {
        LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(columns), modifier = Modifier.fillMaxSize()) {
            items(favs.size) { i ->
                val post = favs[i]
                CachedThumb(post) { onOpen(post) }
            }
        }
    }
}

@Composable
fun DownloadsScreen(vm: MainViewModel) {
    val history by vm.history.collectAsState()
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No downloads yet") }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${history.size} files", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { vm.clearDl(history) }) { Text("Clear all") }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(history, key = { it.id }) { e ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(e.uri), e.mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    }) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(e.filename, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "%.1f MB • %s".format(
                                        e.bytes / 1048576.0,
                                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(e.at)),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { vm.removeDl(e) }) { Text("✕") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val theme by vm.theme.collectAsState()
    val ratings by vm.ratings.collectAsState()
    val columns by vm.columns.collectAsState()
    val blacklist by vm.blacklist.collectAsState()
    val account by vm.account.collectAsState()
    val loginError by vm.loginError.collectAsState()
    var blText by remember(blacklist) { mutableStateOf(blacklist.joinToString(", ")) }
    var loginText by remember { mutableStateOf("") }
    var passText by remember { mutableStateOf("") }
    var tokenText by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Account", style = MaterialTheme.typography.titleMedium)
        if (account == null) {
            OutlinedTextField(value = loginText, onValueChange = { loginText = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Email or username") }, singleLine = true)
            OutlinedTextField(value = passText, onValueChange = { passText = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(value = tokenText, onValueChange = { tokenText = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Access token (optional, instead of password)") }, singleLine = true)
            loginError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { vm.login(loginText, passText, tokenText) }) { Text("Log in") }
        } else {
            Text("Logged in as ${account!!.name}" + if (account!!.email.isNotBlank()) " (${account!!.email})" else "")
            if (vm.needsApiToken.collectAsState(initial = false).value) {
                Text(
                    "Sankaku's post server needs its own sign-in, or only the first page of results will load.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(value = loginText, onValueChange = { loginText = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Email") }, singleLine = true)
                OutlinedTextField(value = passText, onValueChange = { passText = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                vm.apiTokenError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { vm.grantApiToken(loginText, passText) }) { Text("Enable full browsing") }
            }
            TextButton(onClick = { vm.logout() }) { Text("Log out") }
        }
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (v, label) ->
                FilterChip(selected = theme == v, onClick = { vm.setTheme(v) }, label = { Text(label) })
            }
        }
        Text("Ratings shown", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("s" to "Safe", "q" to "Questionable", "e" to "Explicit").forEach { (r, label) ->
                FilterChip(selected = r in ratings, onClick = { vm.toggleRating(r) }, label = { Text(label) })
            }
        }
        Text("Grid columns: $columns", style = MaterialTheme.typography.titleMedium)
        Slider(value = columns.toFloat(), onValueChange = { vm.setColumns(it.toInt()) }, valueRange = 2f..5f, steps = 2)
        Text("Blacklisted tags (comma separated)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = blText, onValueChange = { blText = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("e.g. ai_generated, comic") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.setBlacklistCsv(blText) }) { Text("Save blacklist") }
            TextButton(onClick = { ctx.openUrl("https://sankakuapi.com/posts?lang=en&limit=1") }) { Text("API status") }
        }
        Text(
            if (account == null) "Login-gated explicit posts are hidden without an account."
            else "Logged in — full feed unlocked.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun ViewerDialog(post: Post, vm: MainViewModel, ensureNotif: () -> Unit, onSearchTag: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val favs by vm.favList.collectAsState()
    val isFav = favs.any { it.id == post.id }
    val mediaUrl = post.fileUrl.ifEmpty { post.bestUrl }
    val scope = rememberCoroutineScope()
    var done by remember(post.id) { mutableLongStateOf(-1L) }
    var total by remember(post.id) { mutableLongStateOf(-1L) }
    var msg by remember(post.id) { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.toggleFav(post) }) { Text(if (isFav) "♥" else "♡", color = Color.White) }
                TextButton(onClick = {
                    ensureNotif()
                    msg = null; done = 0; total = -1
                    scope.launch {
                        try {
                            val bytes = ctx.downloadPost(post, mediaUrl) { d, t -> done = d; total = t }
                            msg = "Saved %.1f MB".format(bytes / 1048576.0)
                        } catch (e: Exception) {
                            msg = "Failed: ${e.message}"
                        }
                        done = -1
                    }
                }) { Text("Download", color = Color.White) }
                TextButton(onClick = { ctx.openUrl(mediaUrl) }) { Text("Open", color = Color.White) }
                TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
            }
            if (done >= 0 || msg != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (done >= 0) {
                        if (total > 0) LinearProgressIndicator(
                            progress = { done.toFloat() / total },
                            modifier = Modifier.weight(1f),
                        )
                        else LinearProgressIndicator(modifier = Modifier.weight(1f))
                        Text(
                            if (total > 0) "${(100 * done / total).toInt()}%" else "${done / 1024} KB",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    } else msg?.let {
                        Text(it, color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (post.isVideo) VideoPlayer(mediaUrl)
                else ZoomableImage(post.bestUrl.ifEmpty { post.previewUrl })
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(8.dp),
            ) {
                // ponytail: FlowRow over LazyRow — horizontal scrollers measure 0 in Dialogs here
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    post.tags.map { it.tagName.ifEmpty { it.nameEn } }.filter { it.isNotBlank() }.forEach { name ->
                        AssistChip(onClick = { onSearchTag(name) }, label = { Text(name) })
                    }
                }
            }
        }
    }
}

@Composable
fun ZoomableImage(url: String) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            },
    )
}

@Composable
fun VideoPlayer(url: String) {
    val ctx = LocalContext.current
    val exo = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(exo) { onDispose { exo.release() } }
    AndroidView(factory = { PlayerView(it).apply { player = exo } }, modifier = Modifier.fillMaxSize())
}

private fun Context.openUrl(url: String) {
    if (url.isBlank()) return
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
