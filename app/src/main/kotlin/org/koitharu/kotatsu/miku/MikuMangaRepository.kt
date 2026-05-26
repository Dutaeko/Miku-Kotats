package org.koitharu.kotatsu.miku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.miku.app.parser.KomikcastClient
import moe.miku.app.parser.MangaChapter as MikuChapter
import moe.miku.app.parser.MangaPost
import moe.miku.app.parser.MangaSourceFactory
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MikuMangaRepository(
	override val source: MikuMangaSource,
	cache: MemoryContentCache,
) : CachingMangaRepository(cache) {

	private val client: KomikcastClient = MangaSourceFactory.createBySourceId(source.sourceId)

	override val sortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.RELEVANCE,
	)

	override var defaultSortOrder: SortOrder = SortOrder.NEWEST

	override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isMultipleTagsSupported = false,
	)

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
		val safeFilter = filter ?: MangaListFilter.EMPTY
		val page = (offset / PAGE_SIZE) + 1
		val sort = order.toMikuSort(safeFilter.query)
		val query = safeFilter.query.orEmpty().ifBlank { safeFilter.author.orEmpty() }
		val genre = safeFilter.tags.firstOrNull()?.key.orEmpty()
		return withContext(Dispatchers.IO) {
			client.awaitList(page, sort, query, genre).map { it.toManga(source) }
		}
	}

	override suspend fun getDetailsImpl(manga: Manga): Manga {
		val post = withContext(Dispatchers.IO) { client.awaitDetail(manga.url) }
		val chapters = withContext(Dispatchers.IO) { client.awaitChapters(manga.url) }.map { it.toChapter(source, manga.url) }
		return post.toManga(source).copy(
			id = manga.id,
			url = manga.url,
			chapters = chapters,
		)
	}

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> {
		val slug = chapter.url.substringBefore(CHAPTER_SEPARATOR)
		val index = chapter.url.substringAfter(CHAPTER_SEPARATOR, "0").toFloatOrNull() ?: chapter.number
		return withContext(Dispatchers.IO) {
			client.awaitPages(slug, index).mapIndexed { pageIndex, url ->
				MangaPage(
					id = stableId(source.name, chapter.url, pageIndex.toString()),
					url = url,
					preview = null,
					source = source,
				)
			}
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String = page.url

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val tags = withContext(Dispatchers.IO) {
			client.awaitGenres().mapTo(LinkedHashSet()) {
				MangaTag(
					title = it.title,
					key = it.value.ifBlank { it.title },
					source = source,
				)
			}
		}
		return MangaListFilterOptions(availableTags = tags)
	}

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> {
		val query = seed.tags.firstOrNull()?.title ?: seed.title.split(' ').firstOrNull().orEmpty()
		return withContext(Dispatchers.IO) {
			client.awaitList(1, "latest", query, "").map { it.toManga(source) }.filterNot { it.id == seed.id }
		}
	}

	private fun SortOrder?.toMikuSort(query: String?): String = when {
		!query.isNullOrBlank() -> "relevance"
		this == SortOrder.POPULARITY -> "popular"
		this == SortOrder.ALPHABETICAL -> "title"
		else -> "latest"
	}

	private suspend fun KomikcastClient.awaitList(page: Int, sort: String, query: String, genre: String): List<MangaPost> = suspendCancellableCoroutine { cont ->
		list(page, sort, query, genre, object : KomikcastClient.Result<ArrayList<MangaPost>> {
			override fun onSuccess(data: ArrayList<MangaPost>, hasNext: Boolean) {
				cont.resume(data)
			}

			override fun onError(message: String) {
				cont.resumeWithException(IllegalStateException(message))
			}
		})
	}

	private suspend fun KomikcastClient.awaitDetail(slug: String): MangaPost = suspendCancellableCoroutine { cont ->
		detail(slug, object : KomikcastClient.Result<MangaPost> {
			override fun onSuccess(data: MangaPost, hasNext: Boolean) {
				cont.resume(data)
			}

			override fun onError(message: String) {
				cont.resumeWithException(IllegalStateException(message))
			}
		})
	}

	private suspend fun KomikcastClient.awaitChapters(slug: String): List<MikuChapter> = suspendCancellableCoroutine { cont ->
		chapters(slug, object : KomikcastClient.Result<ArrayList<MikuChapter>> {
			override fun onSuccess(data: ArrayList<MikuChapter>, hasNext: Boolean) {
				cont.resume(data.sortedByDescending { it.index })
			}

			override fun onError(message: String) {
				cont.resumeWithException(IllegalStateException(message))
			}
		})
	}

	private suspend fun KomikcastClient.awaitPages(slug: String, index: Float): List<String> = suspendCancellableCoroutine { cont ->
		pages(slug, index, object : KomikcastClient.Result<ArrayList<String>> {
			override fun onSuccess(data: ArrayList<String>, hasNext: Boolean) {
				cont.resume(data)
			}

			override fun onError(message: String) {
				cont.resumeWithException(IllegalStateException(message))
			}
		})
	}

	private suspend fun KomikcastClient.awaitGenres(): List<KomikcastClient.GenreItem> = suspendCancellableCoroutine { cont ->
		genres(object : KomikcastClient.Result<ArrayList<KomikcastClient.GenreItem>> {
			override fun onSuccess(data: ArrayList<KomikcastClient.GenreItem>, hasNext: Boolean) {
				cont.resume(data)
			}

			override fun onError(message: String) {
				cont.resume(emptyList())
			}
		})
	}

	private fun MangaPost.toManga(source: MikuMangaSource): Manga {
		return Manga(
			id = stableId(source.name, slug),
			title = title.ifBlank { slug },
			altTitles = emptySet(),
			url = slug,
			publicUrl = source.domain.trimEnd('/') + "/" + slug,
			rating = 0f,
			contentRating = null,
			coverUrl = coverImage.ifBlank { null },
			largeCoverUrl = coverImage.ifBlank { null },
			description = synopsis.ifBlank { null },
			tags = genre.split(',').mapNotNullTo(LinkedHashSet()) { value ->
				value.trim().takeIf { it.isNotEmpty() }?.let { MangaTag(title = it, key = it, source = source) }
			},
			state = status.toMangaState(),
			authors = setOfNotNull(author.takeIf { it.isNotBlank() }),
			chapters = null,
			source = source,
		)
	}

	private fun MikuChapter.toChapter(source: MikuMangaSource, slug: String): MangaChapter {
		val key = slug + CHAPTER_SEPARATOR + MikuChapter.formatIndex(index)
		return MangaChapter(
			id = stableId(source.name, key),
			title = title,
			number = index,
			volume = 0,
			url = key,
			scanlator = source.title,
			uploadDate = date.toEpochMillis(),
			branch = null,
			source = source,
		)
	}

	private fun String?.toMangaState(): MangaState? {
		val text = orEmpty().lowercase(Locale.ROOT)
		return when {
			text.contains("ongoing") || text.contains("berjalan") -> MangaState.ONGOING
			text.contains("complete") || text.contains("finished") || text.contains("tamat") -> MangaState.FINISHED
			text.contains("hiatus") || text.contains("pause") -> MangaState.PAUSED
			else -> null
		}
	}

	private fun String?.toEpochMillis(): Long {
		val value = orEmpty().trim()
		if (value.isEmpty()) return 0L
		val formats = arrayOf("dd MMM yyyy", "yyyy-MM-dd", "dd/MM/yyyy")
		for (pattern in formats) {
			try {
				return SimpleDateFormat(pattern, Locale("id", "ID")).parse(value)?.time ?: 0L
			} catch (_: Exception) {
			}
		}
		return 0L
	}

	private companion object {
		const val PAGE_SIZE = 12
		const val CHAPTER_SEPARATOR = "#chapter#"
	}
}
