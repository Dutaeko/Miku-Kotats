@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package miku.moe.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.os.bundleOf
import com.google.android.material.color.MaterialColors
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern
import kotlinx.coroutines.delay

sealed interface LibraryDisplayMode {
    data object CompactGrid : LibraryDisplayMode
    data object ComfortableGrid : LibraryDisplayMode
    data object List : LibraryDisplayMode
    data object CoverOnlyGrid : LibraryDisplayMode

    companion object {
        val default: LibraryDisplayMode = CompactGrid
        fun deserialize(serialized: String?): LibraryDisplayMode {
            return when (serialized) {
                "COMFORTABLE_GRID" -> ComfortableGrid
                "COMPACT_GRID" -> CompactGrid
                "COVER_ONLY_GRID" -> CoverOnlyGrid
                "LIST" -> List
                else -> default
            }
        }
    }

    fun serialize(): String {
        return when (this) {
            ComfortableGrid -> "COMFORTABLE_GRID"
            CompactGrid -> "COMPACT_GRID"
            CoverOnlyGrid -> "COVER_ONLY_GRID"
            List -> "LIST"
        }
    }
}

private fun String?.asBrowseSourceId(): String {
    val value = this?.trim().orEmpty()
    return if (MangaSettingsManager.isValidSource(value)) value else MangaSettingsManager.MANGA_SOURCE_KOMIKCAST
}

private fun String?.asBrowseSourceLabel(sourceId: String): String {
    return this?.trim().orEmpty().ifBlank { MangaSourceFactory.labelForSourceId(sourceId) }
}

class BrowseSourceScreen : androidx.fragment.app.Fragment() {
    companion object {
        private const val ARG_SOURCE_ID = "source_id"
        private const val ARG_SOURCE_LABEL = "source_label"
        private const val ARG_QUERY = "query"
        private const val ARG_GENRE_TITLE = "genre_title"
        private const val ARG_GENRE_VALUE = "genre_value"
        private const val ARG_GENRE_ROUTE = "genre_route"

        @JvmStatic
        fun newSource(sourceId: String?, sourceLabel: String?, query: String?): BrowseSourceScreen {
            return BrowseSourceScreen().apply {
                arguments = bundleOf(
                    ARG_SOURCE_ID to sourceId.asBrowseSourceId(),
                    ARG_SOURCE_LABEL to sourceLabel.asBrowseSourceLabel(sourceId.asBrowseSourceId()),
                    ARG_QUERY to query.orEmpty(),
                    ARG_GENRE_TITLE to "",
                    ARG_GENRE_VALUE to "",
                    ARG_GENRE_ROUTE to false,
                )
            }
        }

        @JvmStatic
        fun newGenre(sourceId: String?, sourceLabel: String?, genreTitle: String?, genreValue: String?): BrowseSourceScreen {
            val safeSourceId = sourceId.asBrowseSourceId()
            return BrowseSourceScreen().apply {
                arguments = bundleOf(
                    ARG_SOURCE_ID to safeSourceId,
                    ARG_SOURCE_LABEL to sourceLabel.asBrowseSourceLabel(safeSourceId),
                    ARG_QUERY to "",
                    ARG_GENRE_TITLE to genreTitle.orEmpty().ifBlank { "Genre" },
                    ARG_GENRE_VALUE to genreValue.orEmpty().ifBlank { genreTitle.orEmpty() },
                    ARG_GENRE_ROUTE to true,
                )
            }
        }

    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val sourceId = arguments?.getString(ARG_SOURCE_ID).asBrowseSourceId()
        val sourceLabel = arguments?.getString(ARG_SOURCE_LABEL).asBrowseSourceLabel(sourceId)
        val query = arguments?.getString(ARG_QUERY).orEmpty()
        val genreTitle = arguments?.getString(ARG_GENRE_TITLE).orEmpty()
        val genreValue = arguments?.getString(ARG_GENRE_VALUE).orEmpty()
        val genreRoute = arguments?.getBoolean(ARG_GENRE_ROUTE) == true
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MikuSourceTheme {
                    val controller = remember(sourceId, query, genreValue, genreRoute) {
                        BrowseSourceController(requireContext().applicationContext, sourceId, sourceLabel, query, genreTitle, genreValue, genreRoute)
                    }
                    BrowseSourceScreenContent(
                        controller = controller,
                        navigateUp = { closeScreen() },
                        onMangaClick = { openMangaDetail(it) },
                        onChapterClick = { openLatestChapter(it) },
                    )
                }
            }
        }
    }

    private fun closeScreen() {
        if (!isAdded) return
        if (parentFragmentManager.backStackEntryCount > 0) parentFragmentManager.popBackStack() else requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    private fun openMangaDetail(post: MangaPost) {
        if (!isAdded) return
        when (val activity = requireActivity()) {
            is MainActivity -> activity.openMangaDetail(post)
            is MikuAll -> activity.openMangaDetail(post)
        }
    }

    private fun openLatestChapter(post: MangaPost) {
        if (!isAdded || post.slug.orEmpty().trim().isEmpty()) return
        MangaSourceFactory.createBySourceId(post.getSourceId()).chapters(post.slug, object : KomikcastClient.Result<ArrayList<MangaChapter>> {
            override fun onSuccess(chapters: ArrayList<MangaChapter>?, hasNext: Boolean) {
                if (!isAdded) return
                val list = chapters ?: arrayListOf()
                if (list.isEmpty()) {
                    Toast.makeText(requireContext(), "Chapter belum tersedia", Toast.LENGTH_SHORT).show()
                    return
                }
                val pos = findChapterPosition(list, post.latestChapter)
                when (val activity = requireActivity()) {
                    is MainActivity -> activity.openMangaReader(post, ArrayList(list), pos)
                    is MikuAll -> activity.openMangaReader(post, ArrayList(list), pos)
                }
            }

            override fun onError(message: String?) {
                if (!isAdded) return
                Toast.makeText(requireContext(), message?.trim().orEmpty().ifBlank { "Gagal membuka chapter" }, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun findChapterPosition(chapters: ArrayList<MangaChapter>, latestChapter: String?): Int {
        val target = parseChapterIndex(latestChapter)
        if (target >= 0f) {
            chapters.forEachIndexed { index, chapter ->
                if (kotlin.math.abs(chapter.index - target) < 0.001f) return index
            }
        }
        var newest = 0
        for (i in 1 until chapters.size) {
            if (chapters[i].index > chapters[newest].index) newest = i
        }
        return newest
    }

    private fun parseChapterIndex(text: String?): Float {
        if (text == null) return -1f
        val matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(text.replace(',', '.'))
        if (!matcher.find()) return -1f
        return try { matcher.group(1)?.toFloat() ?: -1f } catch (_: Exception) { -1f }
    }

}

private data class BrowsePageCacheEntry(val posts: ArrayList<MangaPost>, val hasNext: Boolean)

private data class PreparedBrowsePage(val posts: ArrayList<MangaPost>)

private data class MangaUiFlags(val showTypeLabel: Boolean, val showLatestChapterLabel: Boolean, val boldTitle: Boolean)

private class BrowseSourceController(
    private val context: Context,
    val sourceId: String,
    val sourceLabel: String,
    initialQuery: String,
    private val genreTitle: String,
    initialGenre: String,
    val genreRoute: Boolean,
) {
    val posts = mutableStateListOf<MangaPost>()
    val genreItems = mutableStateListOf<KomikcastClient.GenreItem>()
    var query by mutableStateOf(initialQuery)
    var toolbarQuery by mutableStateOf<String?>(if (initialQuery.isBlank()) null else initialQuery)
    var sort by mutableStateOf("latest")
    var selectedGenre by mutableStateOf(initialGenre)
    var selectedTypeLabel by mutableStateOf("")
    var displayMode by mutableStateOf(readDisplayMode(context))
    var loading by mutableStateOf(false)
    var loadingGenres by mutableStateOf(false)
    var hasMore by mutableStateOf(true)
    var errorMessage by mutableStateOf("")
    var page by mutableIntStateOf(1)
    @Volatile private var generation = 0
    @Volatile private var destroyed = false
    private var filteredAutoLoadCount = 0
    private val loadedKeys = LinkedHashSet<String>()
    private val latestEnrichKeys = LinkedHashSet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pageExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "MikuBrowsePage").apply { isDaemon = true } }
    private val enrichExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "MikuBrowseEnrich").apply { isDaemon = true } }
    private val pageCache = object : LinkedHashMap<String, BrowsePageCacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BrowsePageCacheEntry>?): Boolean {
            return size > 32
        }
    }

    init {
        reload()
    }

    fun destroy() {
        destroyed = true
        generation++
        mainHandler.removeCallbacksAndMessages(null)
        pageExecutor.shutdownNow()
        enrichExecutor.shutdownNow()
    }

    fun title(): String {
        if (genreRoute) return genreTitle.ifBlank { "Genre" }
        return sourceLabel
    }

    fun getColumnsPreference(configuration: Configuration): GridCells {
        val columns = MangaSettingsManager.getMangaGridColumns(context)
        return GridCells.Fixed(columns)
    }

    fun setListing(value: String) {
        sort = value
        selectedTypeLabel = defaultTypeForSort(value)
        reload()
    }

    fun setType(value: String) {
        selectedTypeLabel = value
        reload()
    }

    fun setGenre(value: String) {
        selectedGenre = value
        reload()
    }

    fun search(value: String) {
        query = value.trim()
        toolbarQuery = query.ifBlank { null }
        if (query.isNotBlank()) selectedGenre = ""
        reload()
    }

    fun updateDisplayMode(mode: LibraryDisplayMode) {
        displayMode = mode
        context.getSharedPreferences("miku_manga_settings", Context.MODE_PRIVATE).edit().putString("pref_display_mode_catalogue", mode.serialize()).apply()
    }

    fun reload() {
        if (destroyed) return
        generation++
        page = 1
        hasMore = true
        loading = false
        errorMessage = ""
        loadedKeys.clear()
        latestEnrichKeys.clear()
        filteredAutoLoadCount = 0
        posts.clear()
        loadNextPage()
    }

    fun loadNextPage() {
        if (destroyed || loading || !hasMore) return
        val run = generation
        val targetPage = page.coerceAtLeast(1)
        val sortSnapshot = sort
        val querySnapshot = query
        val typeSnapshot = selectedTypeLabel.trim()
        val loadTypeSnapshot = MangaSettingsManager.shouldLoadTypeLabel(context) || typeSnapshot.isNotEmpty()
        val requestFilter = requestFilterForSource(querySnapshot, typeSnapshot)
        val key = pageCacheKey(targetPage, requestFilter, sortSnapshot, querySnapshot, typeSnapshot)
        val cached = synchronized(pageCache) { pageCache[key] }
        if (cached != null) {
            loading = true
            handlePageResultAsync(ArrayList(cached.posts), cached.hasNext, targetPage, run, typeSnapshot, loadTypeSnapshot)
            return
        }
        loading = true
        pageExecutor.execute {
            if (destroyed || run != generation) return@execute
            MangaSourceFactory.createBySourceId(sourceId).list(targetPage, sortSnapshot, querySnapshot, requestFilter, object : KomikcastClient.Result<ArrayList<MangaPost>> {
                override fun onSuccess(data: ArrayList<MangaPost>?, next: Boolean) {
                    if (destroyed || run != generation) return
                    val incoming = ArrayList<MangaPost>()
                    if (data != null) incoming.addAll(data)
                    synchronized(pageCache) { pageCache[key] = BrowsePageCacheEntry(ArrayList(incoming), next) }
                    handlePageResultAsync(incoming, next, targetPage, run, typeSnapshot, loadTypeSnapshot)
                }

                override fun onError(message: String?) {
                    if (destroyed || run != generation) return
                    mainHandler.post {
                        if (destroyed || run != generation) return@post
                        loading = false
                        hasMore = false
                        errorMessage = message?.trim().orEmpty().ifBlank { "Gagal memuat manga" }
                    }
                }
            })
        }
    }

    private fun handlePageResultAsync(data: ArrayList<MangaPost>, next: Boolean, targetPage: Int, run: Int, typeFilter: String, loadTypeLabel: Boolean) {
        pageExecutor.execute {
            if (destroyed || run != generation) return@execute
            val prepared = preparePageResult(data, typeFilter, loadTypeLabel)
            mainHandler.post {
                if (destroyed || run != generation) return@post
                applyPageResult(prepared, next, targetPage, run, typeFilter)
            }
        }
    }

    private fun preparePageResult(data: ArrayList<MangaPost>, typeFilter: String, loadTypeLabel: Boolean): PreparedBrowsePage {
        val label = MangaSourceFactory.labelForSourceId(sourceId)
        val ready = arrayListOf<MangaPost>()
        data.forEach { post ->
            post.withSource(sourceId, label)
            applySelectedTypeFallback(post, typeFilter, loadTypeLabel)
            if (matchesTypeFilter(post, typeFilter)) ready.add(post)
        }
        return PreparedBrowsePage(ready)
    }

    private fun applyPageResult(prepared: PreparedBrowsePage, next: Boolean, targetPage: Int, run: Int, typeFilter: String) {
        if (destroyed || run != generation) return
        loading = false
        hasMore = next
        page = targetPage + 1
        errorMessage = ""
        val ready = arrayListOf<MangaPost>()
        val appended = arrayListOf<MangaPost>()
        prepared.posts.forEach { post ->
            val key = sourceItemKey(post)
            if (key.isNotBlank() && loadedKeys.add(key)) {
                ready.add(post)
                appended.add(post)
            }
        }
        if (ready.isNotEmpty()) posts.addAll(ready)
        scheduleEnrichment(appended, run)
        if (hasTypeFilter(typeFilter) && hasMore && posts.size < 12 && filteredAutoLoadCount < 3) {
            filteredAutoLoadCount++
            loadNextPage()
        }
    }

    private fun pageCacheKey(targetPage: Int, requestFilter: String, sortValue: String, queryValue: String, typeValue: String): String {
        return listOf(sourceId, targetPage.toString(), sortValue, queryValue.trim(), requestFilter, typeValue.trim()).joinToString("|")
    }

    fun loadGenres() {
        if (loadingGenres || genreItems.isNotEmpty()) return
        loadingGenres = true
        MangaSourceFactory.createBySourceId(sourceId).genres(object : KomikcastClient.Result<ArrayList<KomikcastClient.GenreItem>> {
            override fun onSuccess(data: ArrayList<KomikcastClient.GenreItem>?, hasNext: Boolean) {
                loadingGenres = false
                genreItems.clear()
                val clean = data.orEmpty().filter { it.title.trim().isNotEmpty() && it.value.trim().isNotEmpty() && !it.value.startsWith("type:") && !it.value.startsWith("status:") }
                if (clean.isNotEmpty()) genreItems.addAll(clean) else genreItems.addAll(fallbackGenres(sourceId))
            }

            override fun onError(message: String?) {
                loadingGenres = false
                genreItems.clear()
                genreItems.addAll(fallbackGenres(sourceId))
            }
        })
    }

    fun sortOptions(): List<Pair<String, String>> {
        val list = arrayListOf("latest" to if (sourceId == MangaSettingsManager.MANGA_SOURCE_AINZSCANSS) "Latest" else if (sourceId == MangaSettingsManager.MANGA_SOURCE_NATSU || sourceId == MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL) "Update" else "Terbaru")
        list.add(if (sourceId == MangaSettingsManager.MANGA_SOURCE_AINZSCANSS) "views" to "Top Views" else "popularity" to "Populer")
        when (sourceId) {
            MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG -> list.addAll(listOf("title_latest" to "Judul Terbaru", "random" to "Random"))
            MangaSettingsManager.MANGA_SOURCE_KOMIKU -> list.addAll(listOf("ongoing" to "Ongoing", "completed" to "Completed"))
            MangaSettingsManager.MANGA_SOURCE_MANGASUSU -> list.add("added" to "Baru ditambahkan")
            MangaSettingsManager.MANGA_SOURCE_COSMICSCANS -> list.addAll(listOf("project" to "Project", "ongoing" to "Ongoing"))
            MangaSettingsManager.MANGA_SOURCE_KIRYUU -> Unit
            MangaSettingsManager.MANGA_SOURCE_NATSU, MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL -> list.add("project" to "Project")
            MangaSettingsManager.MANGA_SOURCE_AINZSCANSS -> list.addAll(listOf("bookmark" to "Top Favorite", "rate" to "Top Rate"))
            MangaSettingsManager.MANGA_SOURCE_APKOMIK -> list.add("project" to "Project")
            MangaSettingsManager.MANGA_SOURCE_DOUJINDESU -> Unit
        }
        return list
    }

    fun typeFilters(): List<Pair<String, String>> {
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_DOUJINDESU) {
            if (sort == "manga") return listOf("" to "All", "MANGA" to "Manga")
            if (sort == "manhwa") return listOf("" to "All", "MANHWA" to "Manhwa")
            return listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "DOUJINSHI" to "Doujinshi")
        }
        return when (sourceId) {
            MangaSettingsManager.MANGA_SOURCE_KOMIKCAST,
            MangaSettingsManager.MANGA_SOURCE_KOMIKU,
            MangaSettingsManager.MANGA_SOURCE_MANGASUSU,
            MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG,
            MangaSettingsManager.MANGA_SOURCE_COSMICSCANS,
            MangaSettingsManager.MANGA_SOURCE_KIRYUU,
            MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL,
            MangaSettingsManager.MANGA_SOURCE_NATSU,
            MangaSettingsManager.MANGA_SOURCE_AINZSCANSS,
            MangaSettingsManager.MANGA_SOURCE_APKOMIK,
            MangaSettingsManager.MANGA_SOURCE_BACAKOMIK,
            MangaSettingsManager.MANGA_SOURCE_KOMIKINDO,
            MangaSettingsManager.MANGA_SOURCE_SHINIGAMI,
            MangaSettingsManager.MANGA_SOURCE_WESTMANGA,
            MangaSettingsManager.MANGA_SOURCE_IKIRU -> listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua")
            else -> listOf("" to "Semua", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua", "WEBTOON" to "Webtoon")
        }
    }

    private fun requestFilterForSource(queryValue: String = query, typeValue: String = selectedTypeLabel): String {
        val genre = if (queryValue.trim().isEmpty()) selectedGenre.trim() else ""
        val rawType = typeValue.trim()
        if (rawType.isEmpty()) return genre
        val supportsTypeKey = sourceId == MangaSettingsManager.MANGA_SOURCE_IKIRU || sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKU || sourceId == MangaSettingsManager.MANGA_SOURCE_MANGASUSU || sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG || sourceId == MangaSettingsManager.MANGA_SOURCE_COSMICSCANS || sourceId == MangaSettingsManager.MANGA_SOURCE_KIRYUU || sourceId == MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL || sourceId == MangaSettingsManager.MANGA_SOURCE_NATSU || sourceId == MangaSettingsManager.MANGA_SOURCE_AINZSCANSS || sourceId == MangaSettingsManager.MANGA_SOURCE_APKOMIK || sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKINDO || sourceId == MangaSettingsManager.MANGA_SOURCE_DOUJINDESU
        if (!supportsTypeKey) return genre
        val normalizedType = normalizeTypeFilterValue(rawType)
        val typeFilter = "type:$normalizedType"
        return if (genre.isEmpty()) typeFilter else "$genre|$typeFilter"
    }

    private fun defaultTypeForSort(value: String): String {
        if (sourceId != MangaSettingsManager.MANGA_SOURCE_DOUJINDESU && sourceId != MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG && sourceId != MangaSettingsManager.MANGA_SOURCE_COSMICSCANS && sourceId != MangaSettingsManager.MANGA_SOURCE_KIRYUU) return ""
        return when (value) {
            "manga" -> "MANGA"
            "manhwa" -> "MANHWA"
            "manhua" -> "MANHUA"
            else -> ""
        }
    }

    private fun normalizeTypeFilterValue(rawType: String): String {
        return when (rawType.uppercase(Locale.ROOT)) {
            "MANHWA" -> "manhwa"
            "MANHUA" -> "manhua"
            "DOUJINSHI" -> "doujinshi"
            "MANGA" -> "manga"
            else -> rawType.trim()
        }
    }

    fun selectedGenreLabel(): String {
        val value = selectedGenre.trim()
        if (value.isEmpty()) return "Filter Genre"
        return genreItems.firstOrNull { it.value == value }?.title?.trim().orEmpty().ifBlank { genreTitle.ifBlank { value } }
    }

    private fun hasTypeFilter(value: String = selectedTypeLabel): Boolean {
        return value.trim().isNotEmpty()
    }

    private fun applySelectedTypeFallback(post: MangaPost, typeFilter: String = selectedTypeLabel, loadTypeLabel: Boolean = MangaSettingsManager.shouldLoadTypeLabel(context)) {
        if (!loadTypeLabel && !hasTypeFilter(typeFilter)) return
        val type = typeFilter.trim().uppercase(Locale.ROOT)
        if (type.isEmpty()) return
        if (type != "MANGA" && type != "MANHWA" && type != "MANHUA" && type != "DOUJINSHI" && type != "WEBTOON") return
        if (sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKCAST || sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKINDO) return
        val raw = post.typeLabel?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("MANGA", true)) post.typeLabel = type
    }

    private fun matchesTypeFilter(post: MangaPost, typeFilter: String = selectedTypeLabel): Boolean {
        val type = typeFilter.trim()
        if (type.isEmpty()) return true
        val postType = post.getTypeLabel()
        return postType.equals(type, true)
    }

    private fun shouldEnrichPost(post: MangaPost): Boolean {
        val loadChapter = MangaSettingsManager.shouldLoadLatestChapterLabel(context)
        val loadType = MangaSettingsManager.shouldLoadTypeLabel(context) || hasTypeFilter()
        if (!loadChapter && !loadType) return false
        val missingChapter = loadChapter && post.latestChapter.orEmpty().trim().isEmpty()
        val rawType = post.typeLabel?.trim().orEmpty()
        val missingType = loadType && rawType.isEmpty()
        val komikindoDefaultType = loadType && sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKINDO && rawType.equals("MANGA", true)
        val enrichTypeSource = loadType && (sourceId == MangaSettingsManager.MANGA_SOURCE_MANGASUSU || sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG || sourceId == MangaSettingsManager.MANGA_SOURCE_COSMICSCANS || sourceId == MangaSettingsManager.MANGA_SOURCE_KIRYUU || sourceId == MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL || sourceId == MangaSettingsManager.MANGA_SOURCE_NATSU || sourceId == MangaSettingsManager.MANGA_SOURCE_AINZSCANSS || sourceId == MangaSettingsManager.MANGA_SOURCE_APKOMIK || sourceId == MangaSettingsManager.MANGA_SOURCE_KOMIKINDO)
        return missingChapter || (enrichTypeSource && (missingType || komikindoDefaultType))
    }

    private fun scheduleEnrichment(items: ArrayList<MangaPost>, run: Int) {
        if (items.isEmpty()) return
        if (!MangaSettingsManager.shouldLoadLatestChapterLabel(context) && !MangaSettingsManager.shouldLoadTypeLabel(context) && !hasTypeFilter()) return
        val targets = arrayListOf<MangaPost>()
        items.forEach { post ->
            val key = sourceItemKey(post)
            if (post.slug.orEmpty().trim().isNotEmpty() && shouldEnrichPost(post) && key.isNotEmpty() && latestEnrichKeys.add(key)) targets.add(post)
        }
        if (targets.isEmpty()) return
        mainHandler.postDelayed({ if (!destroyed) enrichExecutor.execute { enrichLatestBatch(targets, 0, run) } }, 320L)
    }

    private fun enrichLatestBatch(targets: ArrayList<MangaPost>, start: Int, run: Int) {
        if (run != generation || start >= targets.size) return
        val end = minOf(targets.size, start + 2)
        val batch = ArrayList(targets.subList(start, end))
        MangaSourceFactory.createBySourceId(sourceId).enrichLatest(batch) {
            if (run != generation) return@enrichLatest
            mainHandler.post {
                if (destroyed || run != generation) return@post
                refreshPostsAfterEnrich(batch)
                mainHandler.postDelayed({ if (!destroyed) enrichExecutor.execute { enrichLatestBatch(targets, end, run) } }, 220L)
            }
        }
    }

    private fun refreshPostsAfterEnrich(items: ArrayList<MangaPost>) {
        var changed = false
        items.forEach { item ->
            val key = sourceItemKey(item)
            val index = posts.indexOfFirst { sourceItemKey(it) == key }
            if (index >= 0) {
                posts[index] = item
                changed = true
            }
        }
        if (!changed) return
    }

    private fun sourceItemKey(post: MangaPost): String {
        val base = post.slug.orEmpty().trim().ifBlank { post.title.orEmpty().trim() }
        if (base.isEmpty()) return ""
        return post.getSourceId() + ":" + base
    }

    private fun readDisplayMode(context: Context): LibraryDisplayMode {
        val saved = context.getSharedPreferences("miku_manga_settings", Context.MODE_PRIVATE).getString("pref_display_mode_catalogue", null)
        return LibraryDisplayMode.deserialize(saved)
    }
}

@Composable
private fun MikuSourceTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val night = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val surface = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface))
    val surfaceVariant = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceVariant))
        val onSurface = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface))
    val onSurfaceVariant = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant))
    val primary = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary))
    val onPrimary = Color(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimary))
    val scheme = if (night) darkColorScheme(primary = primary, onPrimary = onPrimary, surface = surface, surfaceVariant = surfaceVariant, background = surface, onSurface = onSurface, onSurfaceVariant = onSurfaceVariant) else lightColorScheme(primary = primary, onPrimary = onPrimary, surface = surface, surfaceVariant = surfaceVariant, background = surface, onSurface = onSurface, onSurfaceVariant = onSurfaceVariant)
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun BrowseSourceScreenContent(controller: BrowseSourceController, navigateUp: () -> Unit, onMangaClick: (MangaPost) -> Unit, onChapterClick: (MangaPost) -> Unit) {
    DisposableEffect(controller) {
        onDispose { controller.destroy() }
    }
    var showFilterSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uiFlags = remember(controller.sourceId, controller.displayMode) {
        MangaUiFlags(
            showTypeLabel = !MangaSettingsManager.shouldHideTypeLabel(context),
            showLatestChapterLabel = !MangaSettingsManager.shouldHideLatestChapterLabel(context),
            boldTitle = MangaSettingsManager.isBoldMangaTitleEnabled(context),
        )
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            BrowseSourceToolbar(
                title = controller.title(),
                searchQuery = controller.toolbarQuery,
                onSearchQueryChange = { controller.toolbarQuery = it },
                displayMode = controller.displayMode,
                onDisplayModeChange = controller::updateDisplayMode,
                navigateUp = navigateUp,
                onSearchClick = { controller.toolbarQuery = controller.query },
                onCloseSearch = { controller.toolbarQuery = null },
                onSearch = controller::search,
            )
            if (!controller.genreRoute) {
                BrowseSourceFilterRow(
                    controller = controller,
                    onFilterClick = {
                        controller.loadGenres()
                        showFilterSheet = true
                    },
                )
            }
            Divider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            BrowseSourceContent(
                posts = controller.posts,
                columns = controller.getColumnsPreference(LocalConfiguration.current),
                displayMode = controller.displayMode,
                loading = controller.loading,
                hasMore = controller.hasMore,
                errorMessage = controller.errorMessage,
                onLoadMore = controller::loadNextPage,
                onMangaClick = onMangaClick,
                onChapterClick = onChapterClick,
                uiFlags = uiFlags,
            )
        }
    }
    if (showFilterSheet) {
        GenreFilterSheet(
            controller = controller,
            onDismiss = { showFilterSheet = false },
            onSelect = {
                controller.setGenre(it)
                showFilterSheet = false
            },
        )
    }
}

@Composable
private fun BrowseSourceToolbar(
    title: String,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onSearchClick: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearch: (String) -> Unit,
) {
    var selectingDisplayMode by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            if (searchQuery != null) {
                TextField(
                    value = searchQuery,
                    onValueChange = { onSearchQueryChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                    placeholder = { Text("Cari manga") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(searchQuery) }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            } else {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            IconButton(onClick = navigateUp) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Kembali")
            }
        },
        actions = {
            if (searchQuery != null) {
                IconButton(onClick = onCloseSearch) {
                    Icon(Icons.Filled.Close, contentDescription = "Tutup pencarian")
                }
            } else {
                IconButton(onClick = onSearchClick) {
                    Icon(Icons.Filled.Search, contentDescription = "Cari")
                }
            }
            Box {
                IconButton(onClick = { selectingDisplayMode = true }) {
                    Icon(if (displayMode == LibraryDisplayMode.List) Icons.Filled.ViewList else Icons.Filled.ViewModule, contentDescription = "Ubah tata letak")
                }
                DropdownMenu(expanded = selectingDisplayMode, onDismissRequest = { selectingDisplayMode = false }) {
                    RadioDisplayModeMenuItem("Grid nyaman", displayMode == LibraryDisplayMode.ComfortableGrid) {
                        selectingDisplayMode = false
                        onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                    }
                    RadioDisplayModeMenuItem("Grid kompak", displayMode == LibraryDisplayMode.CompactGrid) {
                        selectingDisplayMode = false
                        onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                    }
                    RadioDisplayModeMenuItem("Daftar", displayMode == LibraryDisplayMode.List) {
                        selectingDisplayMode = false
                        onDisplayModeChange(LibraryDisplayMode.List)
                    }
                }
            }
        },
    )
}

@Composable
private fun RadioDisplayModeMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { RadioButton(selected = checked, onClick = onClick) },
        onClick = onClick,
    )
}

@Composable
private fun BrowseSourceFilterRow(controller: BrowseSourceController, onFilterClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            controller.sortOptions().forEach { item ->
                val leadingIcon: (@Composable () -> Unit)? = when {
                    item.first == "popularity" || item.first == "views" -> ({ Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp)) })
                    item.first == "latest" -> ({ Icon(Icons.Outlined.NewReleases, contentDescription = null, modifier = Modifier.size(18.dp)) })
                    else -> null
                }
                FilterChip(
                    selected = controller.sort == item.first,
                    onClick = { controller.setListing(item.first) },
                    leadingIcon = leadingIcon,
                    label = { Text(item.second, maxLines = 1) },
                )
            }
            FilterChip(
                selected = controller.selectedGenre.isNotBlank(),
                onClick = onFilterClick,
                leadingIcon = { Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text(if (controller.selectedGenre.isBlank()) "Filter Genre" else "Genre: ${controller.selectedGenreLabel()}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        val typeFilters = controller.typeFilters()
        if (typeFilters.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                typeFilters.forEach { item ->
                    FilterChip(
                        selected = controller.selectedTypeLabel == item.first,
                        onClick = { controller.setType(item.first) },
                        label = { Text(item.second, maxLines = 1) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GenreFilterSheet(controller: BrowseSourceController, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Filter Genre", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (controller.loadingGenres && controller.genreItems.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    item {
                        GenreSheetRow("Semua", controller.selectedGenre.isBlank()) { onSelect("") }
                    }
                    items(controller.genreItems, key = { it.value }) { item ->
                        GenreSheetRow(item.title, controller.selectedGenre == item.value) { onSelect(item.value) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreSheetRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun BrowseSourceContent(
    posts: List<MangaPost>,
    columns: GridCells,
    displayMode: LibraryDisplayMode,
    loading: Boolean,
    hasMore: Boolean,
    errorMessage: String,
    onLoadMore: () -> Unit,
    onMangaClick: (MangaPost) -> Unit,
    onChapterClick: (MangaPost) -> Unit,
    uiFlags: MangaUiFlags,
) {
    when {
        posts.isEmpty() && loading -> LoadingScreen()
        posts.isEmpty() -> EmptyScreen(errorMessage.ifBlank { "Tidak ada hasil" })
        else -> BrowseSourceStableMikuContent(
            posts = posts,
            displayMode = displayMode,
            loading = loading,
            hasMore = hasMore,
            onLoadMore = onLoadMore,
            onMangaClick = onMangaClick,
            onChapterClick = onChapterClick,
            uiFlags = uiFlags,
        )
    }
}

private class BrowseSourceAndroidHolder(
    var mode: Int,
    val listView: android.widget.AbsListView,
    val adapter: BrowseSourceMihonAdapter,
)

@Composable
private fun BrowseSourceStableMikuContent(
    posts: List<MangaPost>,
    displayMode: LibraryDisplayMode,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onMangaClick: (MangaPost) -> Unit,
    onChapterClick: (MangaPost) -> Unit,
    uiFlags: MangaUiFlags,
) {
    val mode = when (displayMode) {
        LibraryDisplayMode.ComfortableGrid -> BrowseSourceMihonAdapter.MODE_COMFORTABLE_GRID
        LibraryDisplayMode.List -> BrowseSourceMihonAdapter.MODE_LIST
        else -> BrowseSourceMihonAdapter.MODE_COMPACT_GRID
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            android.widget.FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { container ->
            var holder = container.getTag(R.id.browseSourceAndroidHolder) as? BrowseSourceAndroidHolder
            if (holder == null || holder.mode != mode) {
                container.removeAllViews()
                val adapter = BrowseSourceMihonAdapter(container.context, posts, { onMangaClick(it) }, { onChapterClick(it) })
                adapter.setMode(mode)
                adapter.bindFlags(uiFlags.showTypeLabel, uiFlags.showLatestChapterLabel, uiFlags.boldTitle)
                val listView: android.widget.AbsListView = if (mode == BrowseSourceMihonAdapter.MODE_LIST) {
                    android.widget.ListView(container.context).apply {
                        divider = null
                        dividerHeight = 0
                        setPadding(0, dpValue(context, 8), 0, dpValue(context, 88))
                        clipToPadding = false
                    }
                } else {
                    android.widget.GridView(container.context).apply {
                        numColumns = MangaSettingsManager.getMangaGridColumns(container.context)
                        stretchMode = android.widget.GridView.STRETCH_COLUMN_WIDTH
                        horizontalSpacing = dpValue(context, 4)
                        verticalSpacing = dpValue(context, 4)
                        setPadding(dpValue(context, 8), dpValue(context, 8), dpValue(context, 8), dpValue(context, 88))
                        clipToPadding = false
                    }
                }
                listView.adapter = adapter
                listView.cacheColorHint = android.graphics.Color.TRANSPARENT
                listView.isFastScrollEnabled = false
                listView.isVerticalScrollBarEnabled = false
                container.addView(listView, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                holder = BrowseSourceAndroidHolder(mode, listView, adapter)
                container.setTag(R.id.browseSourceAndroidHolder, holder)
            }
            holder.adapter.setMode(mode)
            holder.adapter.bindFlags(uiFlags.showTypeLabel, uiFlags.showLatestChapterLabel, uiFlags.boldTitle)
            val activeListView = holder.listView
            if (activeListView is android.widget.GridView && mode != BrowseSourceMihonAdapter.MODE_LIST) {
                activeListView.numColumns = MangaSettingsManager.getMangaGridColumns(container.context)
            }
            holder.listView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) = Unit
                override fun onScroll(view: android.widget.AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                    if (hasMore && !loading && totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 4) onLoadMore()
                }
            })
            holder.adapter.notifyDataSetChanged()
        },
    )
}

private fun dpValue(context: Context, value: Int): Int {
    return kotlin.math.round(value * context.resources.displayMetrics.density).toInt()
}

@Composable
private fun BrowseSourceComfortableGrid(posts: List<MangaPost>, columns: GridCells, loading: Boolean, hasMore: Boolean, onLoadMore: () -> Unit, onMangaClick: (MangaPost) -> Unit, onChapterClick: (MangaPost) -> Unit, uiFlags: MangaUiFlags) {
    val state = rememberLazyGridState()
    LazyVerticalGrid(
        state = state,
        columns = columns,
        contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 88.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        items(posts.size, key = { posts[it].slug.ifBlank { posts[it].title } }) { index ->
            MangaComfortableGridItem(post = posts[index], onClick = { onMangaClick(posts[index]) }, onChapterClick = { onChapterClick(posts[index]) }, uiFlags = uiFlags)
        }
        if (hasMore || loading) item(span = { GridItemSpan(maxLineSpan) }) { BrowseSourceLoadMoreItem(loading, hasMore, onLoadMore) }
    }
}

@Composable
private fun BrowseSourceCompactGrid(posts: List<MangaPost>, columns: GridCells, loading: Boolean, hasMore: Boolean, onLoadMore: () -> Unit, onMangaClick: (MangaPost) -> Unit, onChapterClick: (MangaPost) -> Unit, uiFlags: MangaUiFlags) {
    val state = rememberLazyGridState()
    LazyVerticalGrid(
        state = state,
        columns = columns,
        contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 88.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        items(posts.size, key = { posts[it].slug.ifBlank { posts[it].title } }) { index ->
            MangaCompactGridItem(post = posts[index], onClick = { onMangaClick(posts[index]) }, onChapterClick = { onChapterClick(posts[index]) }, uiFlags = uiFlags)
        }
        if (hasMore || loading) item(span = { GridItemSpan(maxLineSpan) }) { BrowseSourceLoadMoreItem(loading, hasMore, onLoadMore) }
    }
}

@Composable
private fun BrowseSourceList(posts: List<MangaPost>, loading: Boolean, hasMore: Boolean, onLoadMore: () -> Unit, onMangaClick: (MangaPost) -> Unit, onChapterClick: (MangaPost) -> Unit, uiFlags: MangaUiFlags) {
    val state = rememberLazyListState()
    LazyColumn(state = state, contentPadding = PaddingValues(0.dp, 8.dp, 0.dp, 88.dp)) {
        items(posts, key = { it.slug.ifBlank { it.title } }) { post ->
            MangaListItem(post = post, onClick = { onMangaClick(post) }, onChapterClick = { onChapterClick(post) }, uiFlags = uiFlags)
        }
        if (hasMore || loading) item { BrowseSourceLoadMoreItem(loading, hasMore, onLoadMore) }
    }
}

private object CommonMangaItemDefaults {
    val GridHorizontalSpacer = 4.dp
    val GridVerticalSpacer = 4.dp
}

@Composable
private fun MangaCompactGridItem(post: MangaPost, onClick: () -> Unit, onChapterClick: () -> Unit, uiFlags: MangaUiFlags) {
    Box(
        modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(4.dp),
    ) {
        MangaGridCover(post = post) {
            MangaTypeFlag(post = post, modifier = Modifier.align(Alignment.TopStart).padding(6.dp), uiFlags = uiFlags)
            MangaChapterBadge(post = post, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp), onClick = onChapterClick, uiFlags = uiFlags)
            CoverTextOverlay(title = post.title, uiFlags = uiFlags)
        }
    }
}

@Composable
private fun MangaComfortableGridItem(post: MangaPost, onClick: () -> Unit, onChapterClick: () -> Unit, uiFlags: MangaUiFlags) {
    Column(modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(4.dp)) {
        MangaGridCover(post = post) {
            MangaTypeFlag(post = post, modifier = Modifier.align(Alignment.TopStart).padding(6.dp), uiFlags = uiFlags)
            MangaChapterBadge(post = post, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp), onClick = onChapterClick, uiFlags = uiFlags)
        }
        GridItemTitle(
            modifier = Modifier.padding(4.dp),
            title = post.title,
            style = MaterialTheme.typography.titleSmall,
            minLines = 2,
            maxLines = 2,
            uiFlags = uiFlags,
        )
    }
}

@Composable
private fun MangaGridCover(post: MangaPost, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        MangaCoverImage(post = post, modifier = Modifier.fillMaxSize())
        content()
    }
}

@Composable
private fun MangaCoverImage(post: MangaPost, modifier: Modifier = Modifier) {
    val coverUrl = post.coverImage.orEmpty().trim()
    val sourceId = post.getSourceId().orEmpty()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = false
            }
        },
        update = { imageView ->
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            if (coverUrl.isBlank()) MangaImageLoader.clear(imageView) else MangaImageLoader.loadForSource(imageView, coverUrl, sourceId, false, null)
        },
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CoverTextOverlay(title: String, uiFlags: MangaUiFlags) {
    Box(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.33f).background(
            Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xAA000000)),
        ),
    )
    GridItemTitle(
        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        title = title,
        style = MaterialTheme.typography.titleSmall.copy(color = Color.White),
        minLines = 1,
        maxLines = 2,
        uiFlags = uiFlags,
    )
}

@Composable
private fun MangaTypeFlag(post: MangaPost, modifier: Modifier = Modifier, uiFlags: MangaUiFlags) {
    if (!uiFlags.showTypeLabel) return
    Image(
        painter = painterResource(typeFlagRes(post)),
        contentDescription = post.getTypeLabel(),
        modifier = modifier.size(width = 28.dp, height = 20.dp),
    )
}

@Composable
private fun MangaChapterBadge(post: MangaPost, modifier: Modifier = Modifier, onClick: () -> Unit, uiFlags: MangaUiFlags) {
    if (!uiFlags.showLatestChapterLabel) return
    val text = latestChapterText(post)
    if (text.isBlank()) return
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xAA000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        color = Color.White,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun latestChapterText(post: MangaPost): String {
    return post.latestChapter.orEmpty().trim()
}

private fun typeFlagRes(post: MangaPost): Int {
    val type = post.getTypeLabel()
    return when {
        type.equals("MANHUA", true) -> R.drawable.ic_flag_china
        type.equals("MANHWA", true) -> R.drawable.ic_flag_korea
        else -> R.drawable.ic_flag_japan
    }
}

@Composable
private fun GridItemTitle(title: String, style: TextStyle, minLines: Int, modifier: Modifier = Modifier, maxLines: Int = 2, uiFlags: MangaUiFlags) {
    Text(
        modifier = modifier,
        text = title,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        minLines = minLines,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        fontWeight = if (uiFlags.boldTitle) FontWeight.SemiBold else FontWeight.Normal,
        style = style,
    )
}

@Composable
private fun MangaListItem(post: MangaPost, onClick: () -> Unit, onChapterClick: () -> Unit, uiFlags: MangaUiFlags) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            MangaCoverImage(post = post, modifier = Modifier.fillMaxSize())
            MangaTypeFlag(post = post, modifier = Modifier.align(Alignment.TopStart).padding(2.dp).size(width = 18.dp, height = 13.dp), uiFlags = uiFlags)
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp).weight(1f)) {
            Text(
                text = post.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (uiFlags.boldTitle) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
            )
            val chapter = latestChapterText(post)
            if (uiFlags.showLatestChapterLabel && chapter.isNotBlank()) {
                Text(
                    text = chapter,
                    modifier = Modifier.clickable(onClick = onChapterClick),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun BrowseSourceLoadMoreItem(loading: Boolean, hasMore: Boolean, onLoadMore: () -> Unit) {
    LaunchedEffect(hasMore, loading) {
        if (hasMore && !loading) {
            delay(180L)
            onLoadMore()
        }
    }
    if (loading) BrowseSourceLoadingItem()
}

@Composable
private fun BrowseSourceLoadingItem() {
    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyScreen(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fallbackGenres(sourceId: String): List<KomikcastClient.GenreItem> {
    val values = when (sourceId) {
        MangaSettingsManager.MANGA_SOURCE_DOUJINDESU -> listOf("Action" to "action", "Adventure" to "adventure", "Comedy" to "comedy", "Drama" to "drama", "Fantasy" to "fantasy", "Romance" to "romance", "School Life" to "school-life", "Shounen" to "shounen", "Slice of Life" to "slice-of-life", "Doujinshi" to "doujinshi")
        MangaSettingsManager.MANGA_SOURCE_WESTMANGA -> listOf("Action" to "action", "Adventure" to "adventure", "Comedy" to "comedy", "Drama" to "drama", "Fantasy" to "fantasy", "Historical" to "historical", "Horror" to "horror", "Isekai" to "isekai", "Romance" to "romance", "School Life" to "school-life", "Seinen" to "seinen", "Shounen" to "shounen", "Supernatural" to "supernatural")
        MangaSettingsManager.MANGA_SOURCE_COSMICSCANS -> listOf("Action" to "14", "Adventure" to "33", "Comedy" to "22", "Drama" to "23", "Fantasy" to "27", "Horror" to "34", "Romance" to "37", "School Life" to "39", "Shounen" to "16", "Slice of Life" to "21", "Supernatural" to "41")
        MangaSettingsManager.MANGA_SOURCE_APKOMIK -> listOf("Action" to "14", "Adventure" to "15", "Comedy" to "2", "Drama" to "11", "Fantasy" to "16", "Harem" to "29", "Isekai" to "17", "Magic" to "41", "Martial Arts" to "18", "Romance" to "13", "School Life" to "6", "Shounen" to "5", "Supernatural" to "7")
        else -> listOf("Action" to "Action", "Adventure" to "Adventure", "Comedy" to "Comedy", "Drama" to "Drama", "Fantasy" to "Fantasy", "Harem" to "Harem", "Historical" to "Historical", "Horror" to "Horror", "Isekai" to "Isekai", "Martial Arts" to "Martial Arts", "Romance" to "Romance", "School Life" to "School Life", "Seinen" to "Seinen", "Shounen" to "Shounen", "Slice of Life" to "Slice of Life", "Supernatural" to "Supernatural")
    }
    return values.map { KomikcastClient.GenreItem(it.first, it.second) }
}
