package dev.kitsunekode.kunai.android

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.*
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.kitsunekode.kunai.android.data.Catalog
import dev.kitsunekode.kunai.android.data.Episode
import dev.kitsunekode.kunai.android.data.Kaa
import dev.kitsunekode.kunai.android.data.Kind
import dev.kitsunekode.kunai.android.data.Library
import dev.kitsunekode.kunai.android.data.Movy
import dev.kitsunekode.kunai.android.data.Title
import dev.kitsunekode.kunai.android.data.Upcoming
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kunai's established Ember Dusk tokens; no default TV/phone Material palette. */
private object Ember {
    val bg = Color(0xFF100B0F)
    val sidebar = Color(0xFF0B080D)
    val panel = Color(0xFF211924)
    val panelHover = Color(0xFF302337)
    val border = Color(0xFF49394B)
    val pink = Color(0xFFFF8FB0)
    val text = Color(0xFFF6EFF4)
    val muted = Color(0xFFAB9DAC)
}
private val round = RoundedCornerShape(12.dp)
private val sections = listOf("Home", "Discover", "Calendar", "Continue", "My List")
private fun safeMessage(e: Throwable) = when {
    e.message.orEmpty().contains("429") -> "Catalog is rate-limited. Try again shortly."
    e.message.orEmpty().contains("HTTP") -> "Catalog temporarily unavailable."
    else -> "Could not load catalog. Check the internet connection."
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        setContent { KunaiApp(this, tv) }
    }
}

@Composable private fun KText(
    value: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    color: Color = Ember.text,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    BasicText(value, modifier = modifier,
        style = TextStyle(color = color, fontSize = size,
            fontWeight = weight, fontFamily = FontFamily.SansSerif,
            lineHeight = size * 1.35f, textAlign = align ?: TextAlign.Unspecified),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable private fun FocusButton(
    text: String,
    active: Boolean = false,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fill = when {
        primary -> Ember.pink
        active -> Color(0xFF3D2A3C)
        focused -> Ember.panelHover
        else -> Ember.panel
    }
    Box(modifier
        .onFocusChanged { focused = it.isFocused }
        .clip(round)
        .background(fill)
        .border(if (focused) 3.dp else 1.dp,
            if (focused) Ember.pink else if (active) Color(0xFFAC7691) else Ember.border,
            round)
        .clickable(onClick = onClick)
        .padding(horizontal = 15.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        KText(text, size = 13.sp, weight = FontWeight.SemiBold,
            color = if (primary) Ember.bg else if (active) Ember.pink else Ember.text,
            maxLines = 1)
    }
}

@Composable private fun Poster(
    item: Title,
    tv: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember(item.id) { mutableStateOf(false) }
    Column(modifier
        .onFocusChanged { focused = it.isFocused }
        .clickable { onClick() }
        .padding(1.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(.70f)
            .clip(round)
            .background(Ember.panel)
            .border(if (focused) 3.dp else 1.dp,
                if (focused) Ember.pink else Ember.border, round),
            contentAlignment = Alignment.Center
        ) {
            Artwork(item.poster, Modifier.fillMaxSize().padding(if (focused) 3.dp else 1.dp))
        }
        KText(item.name, size = if (tv) 13.sp else 12.sp,
            weight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.fillMaxWidth())
        KText(item.year?.toString().orEmpty(), size = 11.sp, color = Ember.muted)
    }
}

@Composable private fun Artwork(url: String, modifier: Modifier = Modifier) {
    if (url.isBlank()) {
        Box(modifier.background(Ember.panel), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(
                painterResource(R.drawable.kunai_mark),
                contentDescription = null,
                modifier = Modifier.size(30.dp))
        }
    } else {
        SubcomposeAsyncImage(model = url, contentDescription = null,
            modifier = modifier, contentScale = ContentScale.Crop) {
            when (painter.state) {
                is coil.compose.AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                else -> Box(Modifier.fillMaxSize().background(Ember.panel),
                    contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(painterResource(R.drawable.kunai_mark),
                        contentDescription = null, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

@Composable private fun Header(
    search: String,
    onSearch: (String) -> Unit,
    kinds: Set<Kind>,
    onKinds: (Set<Kind>) -> Unit,
    tv: Boolean,
    modifier: Modifier = Modifier,
) {
    @Composable fun SearchInput(modifier: Modifier) {
        Row(modifier.height(45.dp)
            .clip(round).background(Ember.panel)
            .border(1.dp, Ember.border, round)
            .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            KText("⌕", size = 22.sp, color = Ember.muted)
            BasicTextField(
                value = search, onValueChange = { onSearch(it.take(110)) },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(fontSize = 14.sp, color = Ember.text),
                singleLine = true,
                cursorBrush = SolidColor(Ember.pink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    Box {
                        if (search.isEmpty()) KText("Search titles", color = Ember.muted)
                        inner()
                    }
                }
            )
            if (search.isNotBlank()) KText("×", modifier = Modifier
                .clickable { onSearch("") }.padding(3.dp),
                size = 20.sp, color = Ember.muted)
        }
    }

    if (tv) {
        Row(modifier.fillMaxWidth().background(Ember.bg).padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchInput(Modifier.weight(1f))
            Kind.entries.forEach { kind ->
                FocusButton(kind.label, active = kind in kinds, onClick = {
                    val next = if (kind in kinds) kinds - kind else kinds + kind
                    if (next.isNotEmpty()) onKinds(next)
                })
            }
        }
    } else {
        Column(modifier.fillMaxWidth().background(Ember.bg).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            SearchInput(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Kind.entries.forEach { kind ->
                    FocusButton(kind.label, active = kind in kinds, onClick = {
                        val next = if (kind in kinds) kinds - kind else kinds + kind
                        if (next.isNotEmpty()) onKinds(next)
                    })
                }
            }
        }
    }
}

@Composable private fun SideNavigation(
    current: String,
    onNavigate: (String) -> Unit,
) {
    Column(Modifier.width(174.dp).fillMaxHeight().background(Ember.sidebar)
        .padding(start = 12.dp, end = 12.dp, top = 25.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.padding(start = 9.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(painterResource(R.drawable.kunai_mark),
                contentDescription = null, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(5.dp))
            KText("kunai", size = 25.sp, weight = FontWeight.Bold)
        }
        sections.forEach { item ->
            var focused by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .clip(round)
                .background(if (current == item) Color(0xFF2A2030)
                    else if (focused) Ember.panel else Color.Transparent)
                .border(if (focused) 3.dp else 0.dp,
                    if (focused) Ember.pink else Color.Transparent, round)
                .clickable { onNavigate(item) }
                .padding(vertical = 16.dp, horizontal = 13.dp)
            ) {
                KText(item, size = 15.sp, weight = FontWeight.SemiBold,
                    color = if (current == item) Ember.pink else Ember.text)
            }
        }
    }
}

@Composable private fun BottomNavigation(current: String, navigate: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Ember.sidebar).padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        sections.forEach { name ->
            Box(Modifier.weight(1f).clip(round)
                .background(if (current == name) Color(0xFF2A2030) else Color.Transparent)
                .clickable { navigate(name) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                KText(name, size = 11.sp, weight = FontWeight.SemiBold,
                    color = if (current == name) Ember.pink else Ember.muted,
                    maxLines = 1)
            }
        }
    }
}

@Composable private fun Shelf(
    name: String, titles: List<Title>, tv: Boolean,
    onClick: (Title) -> Unit, onMore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            KText(name, size = 21.sp, weight = FontWeight.Bold)
            FocusButton("See all", onClick = onMore)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            items(titles.take(20), key = { it.kind.key + it.id }) { item ->
                Poster(item, tv, modifier = Modifier.width(if (tv) 147.dp else 128.dp)) {
                    onClick(item)
                }
            }
        }
    }
}

@Composable private fun TitleGrid(
    titles: List<Title>, tv: Boolean, onClick: (Title) -> Unit,
) {
    val size = if (tv) 145.dp else 125.dp
    LazyVerticalGrid(columns = GridCells.Adaptive(size),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        items(titles, key = { it.kind.key + it.id }) {
            Poster(it, tv, Modifier.fillMaxWidth()) { onClick(it) }
        }
    }
}

@Composable private fun EpisodeCard(
    episode: Episode,
    poster: String,
    onClick: () -> Unit,
) {
    var focused by remember(episode.number) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 89.dp)
        .onFocusChanged { focused = it.isFocused }
        .clip(round).background(Ember.panel)
        .border(if (focused) 3.dp else 1.dp,
            if (focused) Ember.pink else Ember.border, round)
        .clickable(onClick = onClick).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Artwork(episode.still.ifBlank { poster },
            Modifier.width(130.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            KText("Episode ${episode.number}", color = Ember.pink, weight = FontWeight.Bold)
            KText(episode.name, size = 12.sp, maxLines = 2)
        }
    }
}

@Composable private fun Details(
    title: Title,
    tv: Boolean,
    library: Library,
    catalog: Catalog,
    kaa: Kaa,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    onPlay: (Title, Episode) -> Unit,
) {
    var full by remember(title.id) { mutableStateOf(title) }
    var episodes by remember(title.id) { mutableStateOf<List<Episode>>(emptyList()) }
    var busy by remember(title.id) { mutableStateOf(true) }
    var msg by remember(title.id) { mutableStateOf("") }
    var season by remember(title.id) { mutableIntStateOf(1) }
    var saved by remember(title.id) { mutableStateOf(library.has(title)) }
    LaunchedEffect(title.id, season) {
        busy = true
        full = runCatching { catalog.details(title) }.getOrDefault(title)
        episodes = runCatching {
            if (title.kind == Kind.ANIME) kaa.episodes(title)
            else catalog.episodes(title, season)
        }.getOrDefault(emptyList())
        busy = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = if (tv) 26.dp else 15.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FocusButton("‹ Back", modifier = Modifier.align(Alignment.Start), onClick = onClose)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(21.dp)) {
            Artwork(full.poster,
                Modifier.width(if (tv) 170.dp else 116.dp)
                    .aspectRatio(.70f).clip(round))
            Column(Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KText(full.name, size = if (tv) 29.sp else 22.sp, weight = FontWeight.Bold)
                KText(listOfNotNull(full.year?.toString(),
                    if (full.kind == Kind.ANIME) "Anime" else full.kind.label)
                    .joinToString("  ·  "), color = Ember.muted, size = 12.sp)
                if (full.description.isNotBlank()) {
                    KText(full.description, size = 13.sp, color = Color(0xFFE1D4DD),
                        maxLines = if (tv) 5 else 4)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    FocusButton(if (saved) "✓ My List" else "+ My List",
                        active = saved, onClick = {
                            library.toggle(title)
                            saved = library.has(title)
                            onSaved()
                        })
                    if (title.kind == Kind.MOVIE) {
                        FocusButton("▶ Play movie", primary = true,
                            onClick = { onPlay(full, Episode(1, full.name)) })
                    }
                }
            }
        }
        if (title.kind == Kind.SERIES && (full.seasonCount ?: 1) > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (1..(full.seasonCount ?: 1).coerceAtMost(20)).forEach { n ->
                    FocusButton("Season $n", active = season == n, onClick = { season = n })
                }
            }
        }
        if (title.kind != Kind.MOVIE) {
            KText("Episodes", size = 20.sp, weight = FontWeight.Bold)
            when {
            busy -> KText("Loading episodes…", color = Ember.muted)
            episodes.isEmpty() -> KText(
                if (title.kind == Kind.ANIME)
                    "No verified episodes available for this title."
                else "Episodes are not available yet.",
                color = Ember.muted)
            else -> {
                if (tv) {
                    episodes.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { episode ->
                                Box(Modifier.weight(1f)) {
                                    EpisodeCard(episode, full.poster) { onPlay(full, episode) }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    episodes.forEach { episode ->
                        EpisodeCard(episode, full.poster) { onPlay(full, episode) }
                    }
                }
            }
        }
        }
        if (msg.isNotEmpty()) KText(msg, color = Ember.muted)
        Spacer(Modifier.height(22.dp))
    }
}

@Composable private fun KunaiApp(host: Activity, tv: Boolean) {
    val catalog = remember { Catalog() }
    val kaa = remember { Kaa() }
    val movy = remember { Movy() }
    val library = remember { Library(host) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("Home") }
    var search by remember { mutableStateOf("") }
    var kinds by remember { mutableStateOf(Kind.entries.toSet()) }
    var selected by remember { mutableStateOf<Title?>(null) }
    var items by remember { mutableStateOf<List<Title>>(emptyList()) }
    var shelves by remember { mutableStateOf<Map<Kind, List<Title>>>(emptyMap()) }
    var upcoming by remember { mutableStateOf<List<Upcoming>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var libraryRevision by remember { mutableIntStateOf(0) }
    var resolving by remember { mutableStateOf(false) }

    BackHandler(enabled = selected != null || page != "Home" || search.isNotEmpty()) {
        when {
            selected != null -> selected = null
            search.isNotEmpty() -> search = ""
            else -> page = "Home"
        }
    }

    LaunchedEffect(page, search, kinds) {
        if (selected != null) return@LaunchedEffect
        if (search.length in 1..1) return@LaunchedEffect
        loading = true
        error = ""
        try {
            if (search.isNotBlank()) {
                delay(240)
                items = catalog.search(search, kinds)
                page = "Discover"
            } else when (page) {
                "Home" -> {
                    val pages = kinds.map { k -> scope.async { k to catalog.browse(k) } }.awaitAll()
                    shelves = pages.toMap()
                }
                "Discover" -> {
                    items = catalog.recommendations(kinds)
                }
                "Calendar" -> {
                    val today = System.currentTimeMillis() / 1000L
                    upcoming = if (Kind.ANIME in kinds) catalog.calendar(today - 3600, today + 7 * 86400L)
                        else emptyList()
                }
            }
        } catch (e: Exception) {
            error = safeMessage(e)
        } finally {
            loading = false
        }
    }

    fun nav(next: String) {
        selected = null
        page = next
        if (next != "Discover") search = ""
    }
    fun play(title: Title, episode: Episode) {
        if (resolving) return
        resolving = true
        error = ""
        scope.launch {
            try {
                val stream = if (title.kind == Kind.ANIME) kaa.resolve(title, episode.number)
                    else movy.resolve(title, episode.season, episode.number)
                if (stream != null) PlayerActivity.launch(host, title, episode.number, episode.season, stream)
                else error = "No verified playable stream for this title. Nothing else was opened."
            } catch (e: Exception) {
                error = "This episode could not be played. ${safeMessage(e)}"
            } finally {
                resolving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Ember.bg)) {
        Row(Modifier.weight(1f)) {
            if (tv) SideNavigation(page) { nav(it) }
            Column(Modifier.weight(1f)) {
                Header(search, {
                    search = it
                    if (it.isNotBlank()) page = "Discover"
                }, kinds, { kinds = it }, tv)
                if (selected != null) {
                    Details(selected!!, tv, library, catalog, kaa,
                        onClose = { selected = null },
                        onSaved = { libraryRevision++ },
                        onPlay = ::play)
                } else {
                    when {
                        loading && items.isEmpty() && shelves.isEmpty() ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                KText("Loading Kunai…", color = Ember.muted)
                            }
                        search.isNotBlank() || page == "Discover" ->
                            TitleGrid(items.filter { it.kind in kinds }, tv) { selected = it }
                        page == "Home" ->
                            LazyColumn(Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    horizontal = if (tv) 28.dp else 15.dp, vertical = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(29.dp)
                            ) {
                                shelves.values.flatten().firstOrNull()?.let { featured ->
                                    item {
                                        Box(Modifier.fillMaxWidth().height(if (tv) 230.dp else 195.dp)
                                            .clip(RoundedCornerShape(16.dp)).background(Ember.panel)
                                            .clickable { selected = featured }
                                        ) {
                                            Artwork(featured.backdrop.ifBlank { featured.poster },
                                                Modifier.fillMaxSize())
                                            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                                                listOf(Ember.bg, Ember.bg.copy(alpha = .90f),
                                                    Ember.bg.copy(alpha = .4f)))))
                                            Column(Modifier.align(Alignment.CenterStart)
                                                .padding(22.dp).fillMaxWidth(.75f),
                                                verticalArrangement = Arrangement.spacedBy(9.dp)
                                            ) {
                                                KText(featured.name, size = if (tv) 29.sp else 22.sp,
                                                    weight = FontWeight.Bold, maxLines = 2)
                                                KText(featured.description, size = 12.sp,
                                                    maxLines = 2, color = Color(0xFFDFD0DB))
                                                FocusButton("Details", primary = true,
                                                    modifier = Modifier.align(Alignment.Start)) {
                                                    selected = featured
                                                }
                                            }
                                        }
                                    }
                                }
                                Kind.entries.filter { it in kinds }.forEach { kind ->
                                    val rows = shelves[kind].orEmpty()
                                    if (rows.isNotEmpty()) item {
                                        Shelf(kind.label, rows, tv, { selected = it }) {
                                            page = "Discover"; items = rows
                                        }
                                    }
                                }
                                if (error.isNotBlank()) item { KText(error, color = Ember.muted) }
                            }
                        page == "Calendar" ->
                            LazyColumn(Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(18.dp),
                                verticalArrangement = Arrangement.spacedBy(11.dp)
                            ) {
                                item { KText("This week", size = 21.sp, weight = FontWeight.Bold) }
                                if (upcoming.isEmpty()) item {
                                    KText(if (loading) "Loading calendar…" else
                                        "No verified upcoming anime episodes this week.",
                                        color = Ember.muted)
                                }
                                items(upcoming, key = { it.title.id + ":" + it.episode }) { event ->
                                    Row(Modifier.fillMaxWidth().clip(round).background(Ember.panel)
                                        .clickable { selected = event.title }.padding(10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Artwork(event.title.poster, Modifier.width(76.dp).height(101.dp)
                                            .clip(round))
                                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                            KText(event.title.name, weight = FontWeight.Bold)
                                            KText("Episode ${event.episode}", color = Ember.pink)
                                            KText(SimpleDateFormat("EEE, MMM d · HH:mm",
                                                Locale.getDefault()).format(Date(event.airingAt * 1000)),
                                                size = 12.sp, color = Ember.muted)
                                        }
                                    }
                                }
                            }
                        page == "My List" ->
                            TitleGrid(remember(libraryRevision) { library.saved() }
                                .filter { it.kind in kinds }, tv) { selected = it }
                        page == "Continue" ->
                            TitleGrid(remember(libraryRevision) { library.recent().map { it.title }
                                .distinctBy { entry -> entry.kind to entry.id } }
                                .filter { it.kind in kinds }, tv) { selected = it }
                    }
                }
            }
        }
        if (!tv) BottomNavigation(page) { nav(it) }
        if (error.isNotBlank() || resolving) {
            Row(Modifier.fillMaxWidth().background(Ember.panel).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                KText(if (resolving) "Resolving an episode on this device…" else error,
                    modifier = Modifier.weight(1f), color = Ember.text, maxLines = 2)
                if (!resolving) FocusButton("Close", onClick = { error = "" })
            }
        }
    }
}
