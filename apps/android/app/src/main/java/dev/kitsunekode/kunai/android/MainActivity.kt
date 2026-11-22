package dev.kitsunekode.kunai.android

import android.app.Activity
import android.content.Intent
import android.content.ContentValues
import android.content.ContentUris
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import android.widget.Toast
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
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
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ColorFilter
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import dev.kitsunekode.kunai.android.data.CalendarWeek
import dev.kitsunekode.kunai.android.data.Catalog
import dev.kitsunekode.kunai.android.data.Episode
import dev.kitsunekode.kunai.android.data.Kaa
import dev.kitsunekode.kunai.android.data.Kind
import dev.kitsunekode.kunai.android.data.Library
import dev.kitsunekode.kunai.android.data.MalEntry
import dev.kitsunekode.kunai.android.data.MalProfile
import dev.kitsunekode.kunai.android.data.MalSnapshot
import dev.kitsunekode.kunai.android.data.Movy
import dev.kitsunekode.kunai.android.data.Title
import dev.kitsunekode.kunai.android.data.Upcoming
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
private val sections = listOf("Home", "Discover", "Calendar", "Continue", "My List", "Settings")
// Exact icon shapes and navigation order from the shared Kunai TV remote UI.
private fun navIcon(section: String) = when (section) {
    "Home" -> R.drawable.ic_kunai_home
    "Discover" -> R.drawable.ic_kunai_discover
    "Calendar" -> R.drawable.ic_kunai_calendar
    "Continue" -> R.drawable.ic_kunai_continue
    "My List" -> R.drawable.ic_kunai_list
    else -> R.drawable.ic_kunai_settings
}

private fun safeMessage(e: Throwable) = when {
    e.message.orEmpty().contains("429") -> "Catalog is rate-limited. Try again shortly."
    e.message.orEmpty().contains("HTTP") -> "Catalog temporarily unavailable."
    else -> "Could not load catalog. Check the internet connection."
}

class MainActivity : ComponentActivity() {
    private var libraryRefresh by mutableIntStateOf(0)
    override fun onResume() {
        super.onResume()
        libraryRefresh++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val export = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) runCatching {
                val bytes = Library(this).exportJson().toByteArray(Charsets.UTF_8)
                contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    ?: error("Cannot open the selected file")
            }.onSuccess { notifyUser("Library exported") }
                .onFailure { notifyUser("Export failed. Choose another location.") }
        }
        val import = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFromUri(uri)
        }
        setContent {
            KunaiApp(this, tv, libraryRefresh,
                onExport = {
                    if (tv && Build.VERSION.SDK_INT >= 29) backupToDownloads()
                    else export.launch("kunai-library.json")
                },
                onImport = {
                    if (tv && Build.VERSION.SDK_INT >= 29) restoreLatestBackup()
                    else import.launch(arrayOf("application/json", "text/plain"))
                })
        }
    }

    private fun importFromUri(uri: Uri) {
        runCatching {
            val input = contentResolver.openInputStream(uri)
                ?: error("Cannot open the selected file")
            val out = ByteArrayOutputStream()
            input.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= Library.MAX_BACKUP_BYTES) {
                        "Library file is too large"
                    }
                    out.write(buffer, 0, count)
                }
            }
            Library(this).mergeJson(out.toString(Charsets.UTF_8.name()))
        }.onSuccess { result ->
            libraryRefresh++
            notifyUser("Merged: ${result.savedAdded} titles, ${result.historyAdded} episodes")
        }.onFailure { notifyUser(it.message ?: "Import failed") }
    }

    private fun backupToDownloads() {
        if (Build.VERSION.SDK_INT < 29) return
        runCatching {
            val name = "kunai-library-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Kunai")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val location = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(location, values)
                ?: error("Could not create a backup")
            try {
                contentResolver.openOutputStream(uri, "w")?.use { output ->
                    output.write(Library(this).exportJson().toByteArray(Charsets.UTF_8))
                } ?: error("Could not write the backup")
                check(contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null) > 0) { "Backup could not be finalized" }
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        }.onSuccess { notifyUser("Saved to Downloads/Kunai") }
            .onFailure { notifyUser(it.message ?: "Could not export library") }
    }

    private fun restoreLatestBackup() {
        if (Build.VERSION.SDK_INT < 29) return
        runCatching {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
            val cursor = contentResolver.query(collection, columns, null, null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
                ?: error("Downloads are unavailable")
            val latest = cursor.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                var found: Uri? = null
                while (it.moveToNext()) {
                    val name = it.getString(nameCol).orEmpty()
                    if (name.matches(Regex("kunai-library-[0-9]{8}-[0-9]{6}\\.json"))) {
                        found = ContentUris.withAppendedId(collection, it.getLong(idCol))
                        break
                    }
                }
                found
            }
            latest ?: error("No Kunai backup found in Downloads/Kunai")
        }.onSuccess(::importFromUri)
            .onFailure { notifyUser(it.message ?: "Could not find a backup") }
    }

    private fun notifyUser(message: String) =
        Toast.makeText(this, message.take(150), Toast.LENGTH_LONG).show()
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
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    androidx.compose.foundation.Image(
                        painterResource(navIcon(item)), contentDescription = null,
                        colorFilter = ColorFilter.tint(
                            if (current == item) Ember.pink else Ember.muted),
                        modifier = Modifier.size(21.dp))
                    KText(item, size = 15.sp, weight = FontWeight.SemiBold,
                        color = if (current == item) Ember.pink else Ember.text)
                }
            }
        }
    }
}

@Composable private fun BottomNavigation(current: String, navigate: (String) -> Unit) {
    // Same six native vector icons and order as the TV remote's Kunai sidebar.
    BoxWithConstraints(Modifier.fillMaxWidth().background(Ember.sidebar)) {
        val compact = maxWidth < 370.dp
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            sections.forEach { name ->
                val active = current == name
                Column(Modifier.weight(1f).height(58.dp)
                    .clip(round)
                    .background(if (active) Color(0xFF2A2030) else Color.Transparent)
                    .clickable { navigate(name) }
                    .padding(horizontal = 1.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    androidx.compose.foundation.Image(
                        painterResource(navIcon(name)), contentDescription = null,
                        colorFilter = ColorFilter.tint(if (active) Ember.pink else Ember.muted),
                        modifier = Modifier.size(20.dp))
                    KText(name, size = if (compact) 9.sp else 10.sp,
                        weight = FontWeight.SemiBold,
                        color = if (active) Ember.pink else Ember.muted,
                        maxLines = 1)
                }
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

@Composable private fun MalGridPage(
    profile: MalSnapshot,
    tv: Boolean,
    filter: String,
    onFilter: (String) -> Unit,
    onClick: (Title) -> Unit,
    showFilters: Boolean = true,
    recentIds: Set<String> = emptySet(),
) {
    val labels = listOf("Watching", "Plan to Watch", "Completed", "All", "Subscribed", "Watchlist")
    val entries = when (filter) {
        "Watching" -> profile.watching.sortedWith(
            compareByDescending<MalEntry> { it.title.id in recentIds }
                .thenByDescending { it.updatedAt })
        "Plan to Watch" -> profile.planned
        "Completed" -> profile.completed
        "Subscribed" -> profile.subscribed
        "Watchlist" -> profile.watchlist
        else -> profile.entries.sortedBy { it.title.name }
    }
    Column(Modifier.fillMaxSize()) {
        if (showFilters) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                labels.forEach { label ->
                    FocusButton(label, active = filter == label, onClick = { onFilter(label) })
                }
            }
        }
        KText(if (profile.account.isNotBlank())
            entries.size.toString() + " anime · MyAnimeList @" + profile.account
            else "Connect MyAnimeList to see your anime",
            size = 12.sp, color = Ember.muted,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 13.dp))
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                KText("No anime in this list yet.", color = Ember.muted)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (tv) 145.dp else 125.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp),
            ) {
                items(entries, key = { it.malId }) { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Poster(entry.title, tv, Modifier.fillMaxWidth()) { onClick(entry.title) }
                        val progress = if (entry.status == "Plan to Watch" &&
                            entry.watched == 0) "Plan to Watch" else
                            (if (entry.status == "Plan to Watch") "Plan · " else "") +
                                entry.watched.toString() + " / " +
                                (entry.episodes.takeIf { it > 0 }?.toString() ?: "?") + " watched"
                        KText(progress + if (entry.score > 0) " · " +
                            entry.score.toString() + "/10" else "",
                            size = 11.sp, color = Ember.muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable private fun CalendarPage(
    week: LocalDate,
    selectedDay: LocalDate,
    upcoming: List<Upcoming>,
    busy: Boolean,
    problem: String,
    tv: Boolean,
    onWeek: (LocalDate) -> Unit,
    onDay: (LocalDate) -> Unit,
    onOpen: (Title) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    val days = CalendarWeek.days(week)
    val todayWeek = CalendarWeek.monday(today)
    val dayEvents = remember(upcoming, selectedDay) {
        CalendarWeek.onDay(upcoming, selectedDay, zone)
    }
    val rangeLabel = week.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) +
        " – " + days.last().format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            KText("Schedule", size = 21.sp, weight = FontWeight.Bold)
            FocusButton("This week", active = week == todayWeek,
                onClick = { onWeek(todayWeek) })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            FocusButton("‹", modifier = Modifier.width(42.dp),
                onClick = { onWeek(week.minusWeeks(1)) })
            KText(rangeLabel, modifier = Modifier.weight(1f), size = 15.sp,
                weight = FontWeight.SemiBold, align = TextAlign.Center, maxLines = 1)
            FocusButton("›", modifier = Modifier.width(42.dp),
                onClick = { onWeek(week.plusWeeks(1)) })
        }
        // The full Monday–Sunday row always fits on screen. No horizontal
        // scrolling, even in the phone's smallest supported text layout.
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            days.forEach { date ->
                val active = date == selectedDay
                val isToday = date == today
                val weekday = date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
                    .trimEnd('.')
                Column(Modifier.weight(1f).padding(horizontal = 2.dp)
                    .height(58.dp).clip(round)
                    .background(if (active) Ember.panelHover else Ember.panel)
                    .border(if (isToday) 1.5.dp else 1.dp,
                        if (active) Ember.pink else if (isToday)
                            Ember.border.copy(alpha = .92f) else Ember.border.copy(alpha = .42f),
                        round)
                    .clickable { onDay(date) }
                    .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    KText(weekday, size = if (tv) 11.sp else 10.sp,
                        color = if (active) Ember.pink else Ember.muted,
                        weight = FontWeight.SemiBold, maxLines = 1)
                    KText(date.dayOfMonth.toString(), size = 14.sp,
                        weight = FontWeight.Bold,
                        color = if (active) Ember.pink else Ember.text)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 7.dp,
            bottom = 13.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            KText(selectedDay.format(DateTimeFormatter.ofPattern(
                "EEEE, d MMMM", Locale.getDefault())), size = 16.sp,
                weight = FontWeight.SemiBold)
            if (dayEvents.isNotEmpty()) KText(
                dayEvents.size.toString() + if (dayEvents.size == 1) " episode" else " episodes",
                size = 12.sp, color = Ember.muted)
        }
        if (busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingState()
            }
        } else if (dayEvents.isEmpty() || problem.isNotEmpty()) {
            Box(Modifier.fillMaxSize().padding(horizontal = 18.dp),
                contentAlignment = Alignment.TopCenter) {
                KText(problem.ifEmpty { "Nothing scheduled for this day." },
                    color = Ember.muted, modifier = Modifier.padding(top = 20.dp))
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(if (tv) 145.dp else 125.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(17.dp)
            ) {
                items(dayEvents, key = { it.title.id + ":" + it.episode }) { event ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(event.title) },
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Artwork(event.title.poster, Modifier.fillMaxWidth().aspectRatio(.73f)
                            .clip(round).background(Ember.panel))
                        KText(event.title.name, weight = FontWeight.SemiBold, size = 12.sp,
                            maxLines = 2)
                        val time = Instant.ofEpochSecond(event.airingAt).atZone(zone)
                            .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
                        KText("Episode " + event.episode + " · " + time,
                            color = Ember.pink, size = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable private fun EpisodeCard(
    episode: Episode,
    poster: String,
    watchedOnAniWorld: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember(episode.number) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 89.dp)
        .onFocusChanged { focused = it.isFocused }
        .clip(round).background(Ember.panel)
        .border(if (focused) 3.dp else 1.dp,
            if (focused) Ember.pink else if (watchedOnAniWorld)
                Ember.pink.copy(alpha = .65f) else Ember.border, round)
        .clickable(onClick = onClick).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Artwork(episode.still.ifBlank { poster },
            Modifier.width(130.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            KText("Episode ${episode.number}", color = Ember.pink, weight = FontWeight.Bold)
            KText(episode.name, size = 12.sp, maxLines = 2)
            if (watchedOnAniWorld)
                KText("✓ Watched on AniWorld", size = 11.sp, color = Ember.muted)
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
    onTrailer: (String) -> Unit,
    malEntry: MalEntry?,
    malSuggestion: Boolean,
    onOpenMal: (Int) -> Unit,
) {
    var full by remember(title.id) { mutableStateOf(title) }
    var episodes by remember(title.id) { mutableStateOf<List<Episode>>(emptyList()) }
    var busy by remember(title.id) { mutableStateOf(true) }
    var trailerId by remember(title.id) { mutableStateOf<String?>(null) }
    var msg by remember(title.id) { mutableStateOf("") }
    var season by remember(title.id) { mutableIntStateOf(1) }
    var saved by remember(title.id) { mutableStateOf(library.has(title)) }
    LaunchedEffect(title.id, season) {
        busy = true
        val metadata = async { runCatching { catalog.details(title) }.getOrDefault(title) }
        val episodeList = async {
            runCatching {
                if (title.kind == Kind.ANIME) kaa.episodes(title)
                else catalog.episodes(title, season)
            }.getOrDefault(emptyList())
        }
        val filmTrailer = if (title.kind != Kind.ANIME) async {
            runCatching { catalog.trailer(title) }.getOrNull()
        } else null
        full = metadata.await()
        episodes = episodeList.await()
        trailerId = if (title.kind == Kind.ANIME) full.trailerId?.takeIf {
            it.matches(Regex("[A-Za-z0-9_-]{11}"))
        } else filmTrailer?.await()
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
                if (malEntry != null) {
                    KText(malEntry.status + " · " + malEntry.watched + " / " +
                        (malEntry.episodes.takeIf { it > 0 }?.toString() ?: "?") +
                        " watched" + if (malEntry.score > 0) " · Rated " +
                        malEntry.score + "/10" else "",
                        size = 12.sp, color = Ember.pink)
                    if (malEntry.aniworld.isNotEmpty())
                        KText("AniWorld watched · " +
                            malEntry.aniworld.joinToString(" · ") { it.label() },
                            size = 12.sp, color = Ember.muted)
                    FocusButton("Rate or edit on MAL", onClick = {
                        onOpenMal(malEntry.malId)
                    })
                } else if (malSuggestion) {
                    KText("Recommended for you", size = 12.sp, color = Ember.pink)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    FocusButton(if (saved) "✓ Saved locally" else "+ Save locally",
                        active = saved, onClick = {
                            library.toggle(title)
                            saved = library.has(title)
                            onSaved()
                        })
                    if (title.kind == Kind.MOVIE) {
                        FocusButton("▶ Play movie", primary = true,
                            onClick = { onPlay(full, Episode(1, full.name)) })
                    }
                    trailerId?.let { trailer ->
                        FocusButton("▶ Trailer", onClick = { onTrailer(trailer) })
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
            // A MAL season can correspond to only one AniWorld season here.
            // Never mark overlapping episode numbers across ambiguous seasons.
            val watchedNumbers = if (title.kind == Kind.ANIME &&
                malEntry?.aniworld?.size == 1)
                malEntry.aniworld.single().episodes.toSet() else emptySet()
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
                                    EpisodeCard(episode, full.poster,
                                        watchedOnAniWorld = episode.number in watchedNumbers) {
                                        onPlay(full, episode)
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    episodes.forEach { episode ->
                        EpisodeCard(episode, full.poster,
                            watchedOnAniWorld = episode.number in watchedNumbers) {
                            onPlay(full, episode)
                        }
                    }
                }
            }
        }
        }
        if (msg.isNotEmpty()) KText(msg, color = Ember.muted)
        Spacer(Modifier.height(22.dp))
    }
}

@Composable private fun LoadingState() {
    val transition = rememberInfiniteTransition(label = "catalog-loading")
    val rotation by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = LinearEasing)
        ),
        label = "catalog-ring",
    )
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(24.dp).graphicsLayer(rotationZ = rotation)) {
            drawArc(
                color = Ember.pink, startAngle = -90f, sweepAngle = 255f,
                useCenter = false,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        KText("Loading…", color = Ember.muted)
    }
}

@Composable private fun LibrarySettings(
    library: Library,
    profile: MalSnapshot,
    tv: Boolean,
    syncing: Boolean,
    syncError: String,
    onSync: () -> Unit,
    onOpenMal: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 25.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        KText("MyAnimeList", size = 24.sp, weight = FontWeight.Bold)
        if (profile.entries.isNotEmpty()) {
            KText("@" + profile.account + " · " + profile.entries.size +
                " anime", color = Ember.text, size = 15.sp)
            KText(profile.watching.size.toString() + " Watching · " +
                profile.planned.size + " Plan to Watch · " +
                profile.completed.size + " Completed",
                color = Ember.muted, size = 12.sp)
            KText("Saved offline · last checked " + profile.syncedAt.take(16)
                .replace("T", " "), color = Ember.muted, size = 12.sp)
        } else {
            KText("No MAL account cached. Import or connect a library to get started.",
                color = Ember.muted, size = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusButton(if (syncing) "Syncing…" else "Sync now", primary = true,
                onClick = onSync)
            FocusButton("Open MyAnimeList", onClick = onOpenMal)
        }
        if (syncError.isNotBlank()) KText(syncError, color = Ember.muted, size = 12.sp)
        Spacer(Modifier.height(9.dp))
        KText("Local playback", size = 18.sp, weight = FontWeight.SemiBold)
        KText(library.saved().size.toString() + " locally saved · " +
            library.recent().size + " playback positions",
            color = Ember.muted, size = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusButton(if (tv) "Save backup" else "Export",
                onClick = onExport)
            FocusButton(if (tv) "Restore latest" else "Import & merge",
                onClick = onImport)
        }
        KText("Local backups preserve offline favorites and playback positions. " +
            "Your MAL library is cached separately and syncs directly when online. " +
            "Watching does not depend on a desktop or home server.",
            color = Ember.muted, size = 12.sp)
        Spacer(Modifier.height(8.dp))
        KText("Kunai · Native Android TV & phone", color = Ember.muted, size = 12.sp)
    }
}

@Composable private fun KunaiApp(
    host: Activity,
    tv: Boolean,
    externalLibraryRevision: Int,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val catalog = remember { Catalog(host.applicationContext) }
    val kaa = remember { Kaa() }
    val movy = remember { Movy() }
    val library = remember { Library(host) }
    val malData = remember { MalProfile(host) }
    var mal by remember { mutableStateOf(malData.cached()) }
    var malSyncing by remember { mutableStateOf(false) }
    var malError by remember { mutableStateOf("") }
    var malFilter by remember { mutableStateOf("Watching") }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("Home") }
    var search by remember { mutableStateOf("") }
    var kinds by remember { mutableStateOf(Kind.entries.toSet()) }
    var selected by remember { mutableStateOf<Title?>(null) }
    var items by remember { mutableStateOf<List<Title>>(emptyList()) }
    var shelves by remember { mutableStateOf<Map<Kind, List<Title>>>(emptyMap()) }
    var featured by remember { mutableStateOf<Title?>(null) }
    val localZone = remember { ZoneId.systemDefault() }
    var calendarWeek by remember { mutableStateOf(CalendarWeek.monday(LocalDate.now(localZone))) }
    var calendarDay by remember { mutableStateOf(LocalDate.now(localZone)) }
    var calendarLoading by remember { mutableStateOf(false) }
    var upcoming by remember { mutableStateOf<List<Upcoming>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var libraryRevision by remember { mutableIntStateOf(0) }
    var resolving by remember { mutableStateOf(false) }

    // Direct-to-MAL refresh. All navigation and offline playback use the cached
    // snapshot if MyAnimeList is unreachable. No other device is required.
    LaunchedEffect(externalLibraryRevision) {
        if (mal.entries.isEmpty()) return@LaunchedEffect
        while (true) {
            malSyncing = true
            try {
                mal = malData.refresh(mal)
                malError = ""
            } catch (e: Exception) {
                malError = "Offline library shown · MAL refresh unavailable"
            } finally {
                malSyncing = false
            }
            delay(600_000L)
        }
    }

    BackHandler(enabled = selected != null || page != "Home" || search.isNotEmpty()) {
        when {
            selected != null -> selected = null
            search.isNotEmpty() -> search = ""
            else -> page = "Home"
        }
    }

    LaunchedEffect(page, search, kinds, calendarWeek) {
        if (selected != null) return@LaunchedEffect
        if (search.length == 1) {
            items = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = page in setOf("Home", "Discover")
        error = ""
        try {
            if (search.isNotBlank()) {
                val term = search
                delay(240)
                items = mal.entries.asSequence()
                    .filter { it.title.name.contains(term, ignoreCase = true) }
                    .map { it.title }.take(50).toList()
                coroutineScope {
                    Kind.entries.filter { it in kinds }.map { kind ->
                        launch {
                            val found = withTimeoutOrNull(9_000) {
                                runCatching { catalog.search(term, setOf(kind)) }
                                    .getOrDefault(emptyList())
                            }.orEmpty()
                            if (search == term && found.isNotEmpty()) {
                                items = (items + found).distinctBy { it.kind to it.id }
                            }
                        }
                    }.joinAll()
                }
            } else when (page) {
                "Home" -> {
                    shelves = shelves.filterKeys { it in kinds }
                    if (featured?.kind !in kinds) featured = null
                    coroutineScope {
                        Kind.entries.filter { it in kinds }.map { kind ->
                            launch {
                                val found = withTimeoutOrNull(9_000) {
                                    runCatching { catalog.browse(kind) }
                                        .getOrDefault(emptyList())
                                }.orEmpty()
                                if (found.isNotEmpty()) {
                                    shelves = shelves + (kind to found)
                                    if (featured == null) featured = found.first()
                                }
                            }
                        }.joinAll()
                    }
                    if (shelves.values.all { it.isEmpty() }) {
                        error = "Catalog unavailable. Check your connection and retry."
                    }
                }
                "Discover" -> {
                    items = emptyList()
                    coroutineScope {
                        Kind.entries.filter { it in kinds }.map { kind ->
                            launch {
                                val found = withTimeoutOrNull(9_000) {
                                    runCatching { catalog.browse(kind) }
                                        .getOrDefault(emptyList())
                                }.orEmpty()
                                if (found.isNotEmpty()) items = (items + found)
                                    .distinctBy { it.kind to it.id }
                            }
                        }.joinAll()
                    }
                }
                "Calendar" -> {
                    calendarLoading = true
                    try {
                        val range = CalendarWeek.range(calendarWeek, localZone)
                        upcoming = if (Kind.ANIME in kinds) {
                            withTimeoutOrNull(12_000) {
                                catalog.calendar(range.first, range.last + 1)
                            } ?: throw IllegalStateException("Calendar request timed out")
                        } else emptyList()
                    } finally {
                        calendarLoading = false
                    }
                }
            }
        } catch (e: Exception) {
            error = safeMessage(e)
        } finally {
            loading = false
        }
    }

    fun changeCalendarWeek(next: LocalDate) {
        val nextMonday = CalendarWeek.monday(next)
        val today = LocalDate.now(localZone)
        if (!CalendarWeek.weekInRange(nextMonday, today)) return
        calendarWeek = nextMonday
        calendarDay = if (nextMonday == CalendarWeek.monday(today)) today else nextMonday
    }

    fun syncMalNow() {
        if (malSyncing || mal.entries.isEmpty()) return
        scope.launch {
            malSyncing = true
            try {
                mal = malData.refresh(mal)
                malError = ""
            } catch (e: Exception) {
                malError = "MAL is unavailable. Your offline library is unchanged."
            } finally {
                malSyncing = false
            }
        }
    }
    fun openMal(id: Int? = null) {
        val account = mal.account.takeIf { it.matches(Regex("[A-Za-z0-9_-]{2,40}")) }
            ?: return
        val url = if (id != null && id > 0)
            "https://myanimelist.net/ownlist/anime/" + id + "/edit"
        else "https://myanimelist.net/animelist/" + account
        runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { malError = "No app could open MyAnimeList." }
    }

    fun nav(next: String) {
        selected = null
        page = next
        if (next != "Discover") search = ""
    }
    fun trailer(id: String) {
        if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return
        runCatching {
            host.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/watch?v=$id")))
        }.onFailure { error = "No app is available to open this trailer." }
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

    Column(Modifier.fillMaxSize().background(Ember.bg)
        .statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.weight(1f)) {
            if (tv) SideNavigation(page) { nav(it) }
            Column(Modifier.weight(1f)) {
                if (page != "Settings") Header(search, {
                    search = it
                    if (it.isNotBlank()) page = "Discover"
                }, kinds, { kinds = it }, tv)
                if (selected != null) {
                    Details(selected!!, tv, library, catalog, kaa,
                        onClose = { selected = null },
                        onSaved = { libraryRevision++ },
                        onPlay = ::play,
                        onTrailer = ::trailer,
                        malEntry = mal.entries.firstOrNull {
                            it.title.id == selected!!.id && it.title.kind == selected!!.kind
                        },
                        malSuggestion = mal.suggestions.any { it.title.id == selected!!.id },
                        onOpenMal = { openMal(it) })
                } else {
                    when {
                        page == "Settings" -> LibrarySettings(library, mal, tv,
                            malSyncing, malError, ::syncMalNow,
                            { openMal() }, onExport, onImport)
                        loading && items.isEmpty() && shelves.isEmpty() ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                LoadingState()
                            }
                        search.isNotBlank() || page == "Discover" ->
                            TitleGrid(items.filter { it.kind in kinds }, tv) { selected = it }
                        page == "Home" ->
                            LazyColumn(Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    horizontal = if (tv) 28.dp else 15.dp, vertical = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(29.dp)
                            ) {
                                featured?.let { featured ->
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
                                if (Kind.ANIME in kinds && mal.watching.isNotEmpty()) item {
                                    Shelf("Continue watching", mal.watching.take(20).map { it.title },
                                        tv, { selected = it }, { page = "Continue" })
                                }
                                if (Kind.ANIME in kinds && mal.suggestions.isNotEmpty()) item {
                                    Shelf("For you", mal.suggestions.map { it.title },
                                        tv, { selected = it }, {
                                            page = "Discover"
                                            items = mal.suggestions.map { it.title }
                                        })
                                }
                                if (Kind.ANIME in kinds && mal.planned.isNotEmpty()) item {
                                    Shelf("Plan to Watch", mal.planned.take(20).map { it.title },
                                        tv, { selected = it }, {
                                            page = "My List"; malFilter = "Plan to Watch"
                                        })
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
                            CalendarPage(
                                calendarWeek, calendarDay, upcoming,
                                calendarLoading,
                                error.ifEmpty {
                                    if (Kind.ANIME !in kinds) "Select Anime to see release dates."
                                    else ""
                                },
                                tv, ::changeCalendarWeek,
                                { calendarDay = it }, { selected = it })
                        page == "My List" ->
                            if (mal.entries.isNotEmpty() && Kind.ANIME in kinds) {
                                MalGridPage(mal, tv, malFilter, { malFilter = it },
                                    { selected = it })
                            } else {
                                TitleGrid(remember(libraryRevision, externalLibraryRevision) {
                                    library.saved()
                                }.filter { it.kind in kinds }, tv) { selected = it }
                            }
                        page == "Continue" ->
                            if (mal.watching.isNotEmpty() && Kind.ANIME in kinds) {
                                MalGridPage(mal, tv, "Watching", {}, { selected = it },
                                    showFilters = false,
                                    recentIds = library.recent().map { it.title.id }.toSet())
                            } else {
                                TitleGrid(remember(libraryRevision, externalLibraryRevision) {
                                    library.recent().map { it.title }
                                        .distinctBy { entry -> entry.kind to entry.id }
                                }.filter { it.kind in kinds }, tv) { selected = it }
                            }
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
