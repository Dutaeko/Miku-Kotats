package miku.moe.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.HashSet
import kotlin.coroutines.resume
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine

class MangaDetailV1Fragment : Fragment() {
    private var initialManga: MangaPost? = null

    companion object {
        @JvmStatic
        fun newInstance(manga: MangaPost): MangaDetailV1Fragment {
            val fragment = MangaDetailV1Fragment()
            val args = Bundle()
            args.putSerializable("manga", manga)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialManga = arguments?.getSerializable("manga") as? MangaPost
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                MikuMangaDetailTheme {
                    MangaDetailV1Screen(
                        initial = initialManga,
                        onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
                        onChapterClick = { manga, chapter, chapters -> openChapter(manga, chapter, chapters) },
                        onMangaClick = { openMangaDetail(it) },
                        onGenreClick = { sourceId, sourceLabel, title, value -> openGenre(sourceId, sourceLabel, title, value) }
                    )
                }
            }
        }
    }

    private fun openChapter(manga: MangaPost, chapter: MangaChapter, chapters: List<MangaChapter>) {
        val list = ArrayList(chapters)
        val position = list.indexOfFirst { kotlin.math.abs(it.index - chapter.index) < 0.001f }.coerceAtLeast(0)
        val activity = requireActivity()
        when (activity) {
            is MainActivity -> activity.openMangaReader(manga, list, position)
            is MikuAll -> activity.openMangaReader(manga, list, position)
        }
    }

    private fun openMangaDetail(manga: MangaPost) {
        val activity = requireActivity()
        when (activity) {
            is MainActivity -> activity.openMangaDetail(manga)
            is MikuAll -> activity.openMangaDetail(manga)
        }
    }

    private fun openGenre(sourceId: String, sourceLabel: String, title: String, value: String) {
        when (val activity = activity) {
            is MainActivity -> activity.openMangaGenreResult(sourceId, sourceLabel, title, value)
            is MikuAll -> activity.openMangaGenreResult(sourceId, sourceLabel, title, value)
        }
    }
}

@Composable
private fun MikuMangaDetailTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFFFF78C8),
        onPrimary = Color(0xFF31111F),
        primaryContainer = Color(0xFF5B2F4B),
        onPrimaryContainer = Color(0xFFFFD7EC),
        secondary = Color(0xFFB7C7FF),
        secondaryContainer = Color(0xFF3D4563),
        onSecondaryContainer = Color(0xFFE0E6FF),
        tertiaryContainer = Color(0xFF604064),
        onTertiaryContainer = Color(0xFFFFD6FA),
        background = Color(0xFF1C1B20),
        surface = Color(0xFF242229),
        surfaceVariant = Color(0xFF302830),
        onSurface = Color(0xFFF2EEF5),
        onSurfaceVariant = Color(0xFFD0C7D2)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MangaDetailV1Screen(
    initial: MangaPost?,
    onBack: () -> Unit,
    onChapterClick: (MangaPost, MangaChapter, List<MangaChapter>) -> Unit,
    onMangaClick: (MangaPost) -> Unit,
    onGenreClick: (String, String, String, String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("miku_detail_chapter_prefs", 0) }
    val lifecycleOwner = context as? LifecycleOwner
    var manga by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(initial) }
    var chapters by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf<List<MangaChapter>>(emptyList()) }
    var related by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf<List<MangaPost>>(emptyList()) }
    var genres by remember(initial?.getSourceId()) { mutableStateOf<List<KomikcastClient.GenreItem>>(emptyList()) }
    var loading by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(true) }
    var isFavorite by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(initial?.let { MangaFavoriteManager.isFavorite(context, it) } ?: false) }
    var chapterDescending by remember { mutableStateOf(prefs.getBoolean("global_chapter_order_newest_first", false)) }
    var chapterGrid by remember { mutableStateOf(MangaSettingsManager.isChapterGrid2(context)) }
    var historyVersion by remember(initial?.slug, initial?.getSourceId()) { mutableIntStateOf(0) }

    DisposableEffect(initial?.slug, initial?.getSourceId(), lifecycleOwner) {
        val historyPrefs = context.getSharedPreferences("miku_manga_history", 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "items" || key == "chapter_progress") historyVersion++
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) historyVersion++
        }
        historyPrefs.registerOnSharedPreferenceChangeListener(listener)
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            historyPrefs.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }

    LaunchedEffect(initial?.slug, initial?.getSourceId()) {
        val base = initial
        if (base == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val source = MangaSourceFactory.createFor(base, context)
        val loaded = coroutineScope {
            val detailJob = async { loadDetail(source, base) }
            val chapterJob = async { loadChapters(source, base.slug) }
            val genreJob = async { loadGenres(source) }
            Triple(detailJob.await(), chapterJob.await(), genreJob.await())
        }
        val detail = loaded.first ?: base
        detail.totalChapters = loaded.second.size
        manga = detail
        chapters = loaded.second
        genres = loaded.third
        isFavorite = MangaFavoriteManager.isFavorite(context, detail)
        loading = false
        related = loadRelated(context, source, detail, loaded.third)
    }

    Scaffold(
        topBar = {
            CompactTopBar(manga?.title.orEmpty(), onBack) {
                val current = manga
                if (current != null) {
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, current.title)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    }) { Icon(Icons.Default.Share, contentDescription = null) }
                    IconButton(onClick = {
                        val favoritePost = favoriteSnapshotForV1(current, chapters)
                        MangaFavoriteManager.toggle(context, favoritePost)
                        isFavorite = MangaFavoriteManager.isFavorite(context, favoritePost)
                    }) { Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = null) }
                }
            }
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val current = manga
            if (current == null) {
                EmptyStateV1("Detail manga gagal dimuat", Modifier.padding(padding))
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    item { DetailHeroV1(current) }
                    item {
                        SectionHeaderV1("Deskripsi")
                        ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(22.dp)) {
                            SelectionContainer {
                                Text(
                                    current.synopsis.ifBlank { "Deskripsi belum tersedia" },
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    item {
                        SectionHeaderV1("Informasi")
                        ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(22.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                InfoRowV1("Source", current.getSourceLabel())
                                InfoRowV1("Judul", current.title)
                                detailRows(current, chapters.size).forEach { InfoRowV1(it.first, it.second) }
                            }
                        }
                    }
                    item {
                        val genreItems = current.genre.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        SectionHeaderV1("Daftar Genre")
                        if (genreItems.isEmpty()) EmptyStateV1("Genre belum tersedia") else FlowRow(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = 3
                        ) {
                            genreItems.forEach { label ->
                                val value = genres.firstOrNull { it.title.equals(label, true) }?.value ?: label
                                AssistChip(onClick = { onGenreClick(current.getSourceId(), current.getSourceLabel(), label, value) }, label = { Text(label, maxLines = 1) })
                            }
                        }
                    }
                    if (related.isNotEmpty()) {
                        item {
                            SectionHeaderV1("Related Manga")
                            MangaMiniGridV1(related, onMangaClick)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${chapters.size} Chapter", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            if (chapters.isNotEmpty()) {
                                Button(
                                    onClick = { onChapterClick(current, startChapter(context, current, chapters), chapters) },
                                    shape = RoundedCornerShape(99.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Mulai Baca")
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                }
                                FilledTonalIconButton(onClick = {
                                    chapterDescending = !chapterDescending
                                    prefs.edit().putBoolean("global_chapter_order_newest_first", chapterDescending).apply()
                                }) { Icon(Icons.Default.Sort, contentDescription = null) }
                                FilledTonalIconButton(onClick = {
                                    val nextGrid = !chapterGrid
                                    chapterGrid = nextGrid
                                    MangaSettingsManager.setChapterLayout(context, if (nextGrid) MangaSettingsManager.CHAPTER_LAYOUT_GRID_2 else MangaSettingsManager.CHAPTER_LAYOUT_DEFAULT)
                                }) { Icon(if (chapterGrid) Icons.Default.ViewList else Icons.Default.GridView, contentDescription = null) }
                            }
                        }
                    }
                    val shownChapters = if (chapterDescending) chapters.sortedByDescending { it.index } else chapters.sortedBy { it.index }
                    if (chapterGrid) {
                        items(shownChapters.chunked(2)) { rowItems ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowItems.forEach { chapter -> ChapterGridItemV1(current, chapter, historyVersion, onChapterClick, chapters, Modifier.weight(1f)) }
                                repeat(2 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    } else {
                        items(shownChapters) { chapter -> ChapterListItemV1(current, chapter, historyVersion, onChapterClick, chapters, Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) }
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CompactTopBar(title: String, onBack: () -> Unit, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(58.dp).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
            Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            actions()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHeroV1(current: MangaPost) {
    Box(Modifier.fillMaxWidth().height(330.dp)) {
        AsyncImage(
            model = mangaImageRequest(current.coverImage, current.getSourceId()),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.25f), MaterialTheme.colorScheme.background),
                    startY = 0f,
                    endY = 900f
                )
            )
        )
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            AsyncImage(
                model = mangaImageRequest(current.coverImage, current.getSourceId()),
                contentDescription = null,
                modifier = Modifier.size(width = 150.dp, height = 220.dp).clip(RoundedCornerShape(24.dp)),
                contentScale = ContentScale.Crop
            )
            Column(Modifier.weight(1f)) {
                SelectionContainer {
                    Text(current.title.ifBlank { current.slug.substringAfterLast('/') }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoPillV1(current.getSourceLabel())
                    InfoPillV1(current.getTypeLabel())
                    InfoPillV1(current.status)
                }
            }
        }
    }
}

@Composable
private fun SectionHeaderV1(title: String) {
    Text(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun InfoPillV1(text: String) {
    if (text.isBlank()) return
    Surface(shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun InfoRowV1(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value.ifBlank { "-" }, modifier = Modifier.weight(0.6f), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MangaMiniGridV1(items: List<MangaPost>, onMangaClick: (MangaPost) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.take(6).chunked(3).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { MangaCardV1(it, Modifier.weight(1f), onMangaClick) }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MangaCardV1(post: MangaPost, modifier: Modifier, onClick: (MangaPost) -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).clickable { onClick(post) }) {
        Box {
            AsyncImage(
                model = mangaImageRequest(post.coverImage, post.getSourceId()),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(18.dp)),
                contentScale = ContentScale.Crop
            )
            if (post.latestChapter.isNotBlank()) {
                Surface(modifier = Modifier.align(Alignment.BottomStart).padding(6.dp), shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)) {
                    Text(post.latestChapter, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Text(post.title.ifBlank { post.slug.substringAfterLast('/') }, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChapterListItemV1(manga: MangaPost, chapter: MangaChapter, historyVersion: Int, onChapterClick: (MangaPost, MangaChapter, List<MangaChapter>) -> Unit, chapters: List<MangaChapter>, modifier: Modifier) {
    val context = LocalContext.current
    val progress = remember(historyVersion, manga.slug, manga.getSourceId(), chapter.index) { MangaHistoryManager.getProgress(context, manga, chapter.index) }
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth().clickable { onChapterClick(manga, chapter, chapters) }) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(chapter.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text(progress?.let { "Hal. ${it.page + 1}/${it.totalPages}" } ?: chapter.date, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (progress != null && progress.totalPages > 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = ((progress.page + 1).toFloat() / progress.totalPages.toFloat()).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                }
            }
            Icon(Icons.Default.PlayArrow, contentDescription = null)
        }
    }
}

@Composable
private fun ChapterGridItemV1(manga: MangaPost, chapter: MangaChapter, historyVersion: Int, onChapterClick: (MangaPost, MangaChapter, List<MangaChapter>) -> Unit, chapters: List<MangaChapter>, modifier: Modifier) {
    val context = LocalContext.current
    val progress = remember(historyVersion, manga.slug, manga.getSourceId(), chapter.index) { MangaHistoryManager.getProgress(context, manga, chapter.index) }
    Surface(modifier = modifier.padding(5.dp).clickable { onChapterClick(manga, chapter, chapters) }, shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().height(74.dp).padding(8.dp), verticalArrangement = Arrangement.Center) {
            Text(chapter.title, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (progress != null) {
                Text("Hal. ${progress.page + 1}/${progress.totalPages}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (progress.totalPages > 0) LinearProgressIndicator(progress = ((progress.page + 1).toFloat() / progress.totalPages.toFloat()).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
            } else if (chapter.date.isNotBlank()) {
                Text(chapter.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyStateV1(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun mangaImageRequest(url: String, sourceId: String): Any {
    val context = LocalContext.current
    return remember(url, sourceId) {
        val builder = ImageRequest.Builder(context).data(url)
        val headers = MangaImageLoader.headersFor(url, sourceId)
        headers.names().forEach { name ->
            val value = headers[name]
            if (value != null) builder.setHeader(name, value)
        }
        builder.build()
    }
}

private suspend fun loadDetail(source: KomikcastClient, base: MangaPost): MangaPost? = suspendCancellableCoroutine { cont ->
    source.detail(base.slug, object : KomikcastClient.Result<MangaPost> {
        override fun onSuccess(data: MangaPost, hasNext: Boolean) {
            if (cont.isActive) cont.resume(data.withSource(base.getSourceId(), base.getSourceLabel()))
        }

        override fun onError(message: String) {
            if (cont.isActive) cont.resume(base)
        }
    })
}

private suspend fun loadChapters(source: KomikcastClient, slug: String): List<MangaChapter> = suspendCancellableCoroutine { cont ->
    source.chapters(slug, object : KomikcastClient.Result<ArrayList<MangaChapter>> {
        override fun onSuccess(data: ArrayList<MangaChapter>, hasNext: Boolean) {
            if (cont.isActive) cont.resume(data)
        }

        override fun onError(message: String) {
            if (cont.isActive) cont.resume(emptyList())
        }
    })
}

private suspend fun loadGenres(source: KomikcastClient): List<KomikcastClient.GenreItem> = suspendCancellableCoroutine { cont ->
    source.genres(object : KomikcastClient.Result<ArrayList<KomikcastClient.GenreItem>> {
        override fun onSuccess(data: ArrayList<KomikcastClient.GenreItem>, hasNext: Boolean) {
            if (cont.isActive) cont.resume(data)
        }

        override fun onError(message: String) {
            if (cont.isActive) cont.resume(emptyList())
        }
    })
}

private suspend fun loadRelated(context: android.content.Context, source: KomikcastClient, manga: MangaPost, genres: List<KomikcastClient.GenreItem>): List<MangaPost> {
    val genreLabels = manga.genre.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(2)
    val genreValues = genreLabels.map { label -> genres.firstOrNull { it.title.equals(label, true) }?.value ?: label }
    val out = LinkedHashMap<String, MangaPost>()
    coroutineScope {
        val jobs = ArrayList<kotlinx.coroutines.Deferred<List<MangaPost>>>()
        genreValues.forEach { value -> jobs.add(async { loadList(source, 1, "latest", "", value) }) }
        jobs.add(async { loadList(source, 1, "popular", "", "") })
        jobs.forEach { job ->
            job.await().forEach { post ->
                val p = post.withSource(manga.getSourceId(), manga.getSourceLabel())
                val key = p.getSourceId() + "|" + p.slug
                if (p.slug != manga.slug && !out.containsKey(key)) out[key] = p
            }
        }
    }
    return out.values.take(6)
}

private suspend fun loadList(source: KomikcastClient, page: Int, sort: String, query: String, genre: String): List<MangaPost> = suspendCancellableCoroutine { cont ->
    source.list(page, sort, query, genre, object : KomikcastClient.Result<ArrayList<MangaPost>> {
        override fun onSuccess(data: ArrayList<MangaPost>, hasNext: Boolean) {
            if (cont.isActive) cont.resume(data)
        }

        override fun onError(message: String) {
            if (cont.isActive) cont.resume(emptyList())
        }
    })
}

private fun detailRows(manga: MangaPost, totalChapters: Int): List<Pair<String, String>> {
    val rows = ArrayList<Pair<String, String>>()
    val used = HashSet<String>()
    fun add(label: String, value: String) {
        val clean = value.trim()
        val key = label.lowercase()
        if (clean.isEmpty() || used.contains(key)) return
        used.add(key)
        rows.add(label to clean)
    }
    manga.info.split("||").forEach { raw ->
        val idx = raw.indexOf(':')
        if (idx > 0) add(raw.substring(0, idx), raw.substring(idx + 1))
    }
    add("Author", manga.author.ifBlank { "-" })
    add("Status", manga.status.ifBlank { "-" })
    add("Tipe", manga.getTypeLabel())
    add("Total Chapter", totalChapters.toString())
    return rows
}


private fun favoriteSnapshotForV1(manga: MangaPost, chapters: List<MangaChapter>): MangaPost {
    val copy = MangaPost(
        manga.slug,
        manga.title,
        manga.coverImage,
        manga.author,
        manga.status,
        manga.synopsis,
        manga.genre,
        manga.getTypeLabel(),
        compactFavoriteChapter(manga.latestChapter),
        manga.latestChapterDate
    ).withSource(manga.getSourceId(), manga.getSourceLabel())
    copy.info = manga.info
    copy.totalChapters = kotlin.math.max(manga.totalChapters, chapters.size)
    val newest = chapters.maxByOrNull { it.index }
    if (newest != null) {
        copy.latestChapter = MangaChapter.formatIndex(newest.index)
        copy.latestChapterDate = newest.date.orEmpty()
    }
    return copy
}

private fun compactFavoriteChapter(value: String?): String {
    return value.orEmpty()
        .trim()
        .replace(Regex("(?i)^chapter\\s+"), "")
        .replace(Regex("(?i)^ch\\.?\\s*"), "")
        .trim()
}

private fun startChapter(context: android.content.Context, manga: MangaPost, chapters: List<MangaChapter>): MangaChapter {
    val resume = MangaHistoryManager.getLastReadChapterIndex(context, manga)
    if (resume >= 0f) chapters.firstOrNull { kotlin.math.abs(it.index - resume) < 0.001f }?.let { return it }
    return chapters.minByOrNull { it.index } ?: chapters.first()
}
