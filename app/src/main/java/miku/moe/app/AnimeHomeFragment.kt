package miku.moe.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import miku.moe.app.api.AnimeRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AnimeHomeFragment : Fragment() {
    private var sourceRecyclerView: RecyclerView? = null
    private var progressBar: ProgressBar? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var adapter: AnimeHomeSectionAdapter? = null
    private val sections = ArrayList<SourceSection>()
    private var generation = 0
    private val repository = AnimeRepository()

    class SourceSection(val sourceId: String) {
        val sourceLabel: String = AnimeSettingsManager.labelForSourceId(sourceId)
        val items = ArrayList<AnimePost>()
        var loading = true
        var finished = false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_anime_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        sourceRecyclerView = view.findViewById(R.id.sourceRecyclerView)
        progressBar = view.findViewById(R.id.progressBar)
        sourceRecyclerView?.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        adapter = AnimeHomeSectionAdapter(requireContext(), sections, object : AnimeHomeSectionAdapter.ActionListener {
            override fun onViewAll(section: SourceSection) { openViewAll(section) }
            override fun onAnimeClick(section: SourceSection, post: AnimePost) { openAnime(section, post) }
        })
        sourceRecyclerView?.adapter = adapter
        swipeRefreshLayout?.setOnRefreshListener { refreshHome(true) }
        refreshHome(true)
    }

    override fun onDestroyView() {
        generation++
        sourceRecyclerView?.adapter = null
        sourceRecyclerView = null
        progressBar = null
        swipeRefreshLayout = null
        adapter = null
        super.onDestroyView()
    }

    fun refreshHome() { refreshHome(false) }

    fun refreshHome(forceNetwork: Boolean) {
        if (!isAdded) return
        val run = ++generation
        sections.clear()
        AnimeSettingsManager.getEnabledAnimeSources(requireContext()).forEach { sections.add(SourceSection(it)) }
        adapter?.notifyDataSetChanged()
        progressBar?.visibility = if (sections.isEmpty()) View.GONE else View.VISIBLE
        if (sections.isEmpty()) {
            swipeRefreshLayout?.isRefreshing = false
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val tasks = sections.mapIndexed { index, section ->
                async {
                    val data = try {
                        loadSection(section.sourceId)
                    } catch (e: Exception) {
                        Log.e("AnimeHome", "Load source error ${section.sourceId}", e)
                        emptyList()
                    }
                    index to data
                }
            }
            tasks.forEach { task ->
                val result = task.await()
                if (!isAdded || run != generation) return@forEach
                val section = sections.getOrNull(result.first) ?: return@forEach
                section.items.clear()
                result.second.forEach { post ->
                    post.sourceId = section.sourceId
                    section.items.add(post)
                }
                section.loading = false
                section.finished = true
                adapter?.notifyItemChanged(result.first)
            }
            if (isAdded && run == generation) {
                progressBar?.visibility = View.GONE
                swipeRefreshLayout?.isRefreshing = false
            }
        }
    }

    private suspend fun loadSection(sourceId: String): List<AnimePost> {
        return when (sourceId) {
            AnimeSettingsManager.SOURCE_ANIMEKU -> loadAnimekuLatest()
            AnimeSettingsManager.SOURCE_ANIMELOVERZ -> loadAnimeLoverzLatest()
            else -> loadDefaultLatest()
        }
    }

    private suspend fun loadDefaultLatest(): List<AnimePost> {
        val response = repository.getHomePosts(1, HOME_LIMIT, deviceId())
        if (!response.status.equals("ok", true)) return emptyList()
        return response.posts.orEmpty().mapNotNull { item ->
            val categoryId = item.categoryId ?: item.cid ?: -1
            val channelId = item.channelId ?: -1
            val title = item.categoryName.orEmpty()
            if (categoryId <= 0 || title.isBlank() || channelId == BLOCKED_CHANNEL_ID) return@mapNotNull null
            AnimePost(item.imgUrl.orEmpty(), title, categoryId, channelId).apply {
                sourceId = AnimeSettingsManager.SOURCE_DEFAULT
                channelName = item.channelName.orEmpty()
                episodeCount = item.channelName.orEmpty()
                created = item.created.orEmpty()
                countView = item.countView ?: item.totalViews.orEmpty()
                ongoing = item.ongoing == 1
                hdAvailable = item.isHdAvailable == true
                fhdAvailable = item.isFhdAvailable == true
                rating = item.rating.orEmpty()
                scheduleDay = item.days ?: -1
                year = item.years?.toIntOrNull() ?: 0
            }
        }.take(HOME_LIMIT)
    }

    private suspend fun loadAnimekuLatest(): List<AnimePost> = withContext(Dispatchers.IO) {
        val url = "$ANIMEKU_API_BASE/get_videos?page=1&count=$HOME_LIMIT&api_key=$ANIMEKU_API_KEY"
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "max-age=0")
            .header("Data-Agent", "Your Videos Channel")
            .header("User-Agent", "Dalvik/7.1.12.1.0 (com.newanimeku.animechanneldonghuasubindosubenglish U; Android ; 20175 Build/NMF260)")
            .header("Accept", "application/vnd.yourapi.v1.full+json")
            .build()
        val body = httpClient.newCall(request).execute().use { response -> response.body?.string().orEmpty() }
        val json = JSONObject(body)
        if (!json.optString("status").equals("ok", true)) return@withContext emptyList()
        parseAnimekuLatest(json.optJSONArray("latest_anime")).take(HOME_LIMIT)
    }

    private fun parseAnimekuLatest(array: JSONArray?): ArrayList<AnimePost> {
        val result = ArrayList<AnimePost>()
        if (array == null) return result
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val categoryId = item.optInt("cat_id", -1)
            val videoId = item.optInt("vid", -1)
            if (categoryId <= 0 || videoId <= 0) continue
            val title = cleanAnimeTitle(item.optString("category_name", ""))
            if (title.isEmpty()) continue
            val videoTitle = item.optString("video_title", "")
            val post = AnimePost(imageUrl(firstUseful(item.optString("category_image", ""), item.optString("video_thumbnail", ""))), title, categoryId, videoId)
            post.sourceId = AnimeSettingsManager.SOURCE_ANIMEKU
            post.channelName = episodeLabel(videoTitle)
            post.episodeCount = episodeLabel(videoTitle)
            post.statusVideo = normalizeStatus(item.optString("status_video", ""))
            post.ongoing = isOngoing(post.statusVideo)
            result.add(post)
        }
        return result
    }


    private suspend fun loadAnimeLoverzLatest(): List<AnimePost> = withContext(Dispatchers.IO) {
        val url = "$ANIMELOVERZ_API_BASE/home/ongoing.php?page=1&type=all"
        val request = Request.Builder()
            .url(url)
            .header("user-agent", "Dart/3.9 (dart:io)")
            .header("accept", "application/json")
            .build()
        val body = httpClient.newCall(request).execute().use { response -> response.body?.string().orEmpty() }
        parseAnimeLoverzList(JSONArray(body)).take(HOME_LIMIT)
    }

    private fun parseAnimeLoverzList(array: JSONArray?): ArrayList<AnimePost> {
        val result = ArrayList<AnimePost>()
        if (array == null) return result
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val title = item.optString("judul", "").trim()
            val slug = item.optString("url", "").trim().trim('/')
            if (title.isEmpty() || slug.isEmpty()) continue
            val id = item.optString("id", "").toIntOrNull() ?: positiveId(slug)
            result.add(AnimePost(item.optString("cover", ""), title, id, -1).apply {
                sourceId = AnimeSettingsManager.SOURCE_ANIMELOVERZ
                this.slug = slug
                channelName = item.optString("lastch", "")
                episodeCount = item.optString("lastch", "")
                statusVideo = item.optString("lastup", "")
            })
        }
        return result
    }

    private fun openViewAll(section: SourceSection) {
        if (!isAdded) return
        val intent = Intent(requireContext(), AnimexAll::class.java)
        intent.putExtra(AnimexAll.EXTRA_SOURCE_ID, section.sourceId)
        intent.putExtra(AnimexAll.EXTRA_SOURCE_LABEL, section.sourceLabel)
        startActivity(intent)
    }

    private fun openAnime(section: SourceSection, post: AnimePost) {
        if (!isAdded) return
        when (section.sourceId) {
            AnimeSettingsManager.SOURCE_ANIMEKU -> (requireActivity() as MainActivity).openAnimekuDetail(post.categoryId, post.channelId, post.categoryName, post.imgUrl, post.genre, post.rating, post.year, post.countView, post.episodeCount, post.description)
            AnimeSettingsManager.SOURCE_ANIMELOVERZ -> (requireActivity() as MainActivity).openAnimeLoverzDetail(post.slug, post.categoryName, post.imgUrl, post.genre, post.rating, post.statusVideo, post.description)
            else -> (requireActivity() as MainActivity).openDetail(post.categoryId, post.channelId)
        }
    }

    private fun deviceId(): String {
        val safeContext = context ?: return ""
        return Settings.Secure.getString(safeContext.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    }

    private fun cleanAnimeTitle(value: String?): String {
        if (value == null) return ""
        var result = value.trim().replace(Regex("\\s+"), " ")
        result = result.replace(Regex("(?i)\\bsub\\s*indo\\b"), "")
        result = result.replace(Regex("(?i)\\bsubtitle\\s*indonesia\\b"), "")
        result = result.replace(Regex("\\s+"), " ").trim()
        return result
    }

    private fun episodeLabel(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val matcher = Regex("(?i)(episode|eps|ep)\\s*([0-9]+(?:\\.[0-9]+)?)").find(raw)
        if (matcher != null) return "Episode ${matcher.groupValues[2]}"
        return raw
    }

    private fun normalizeStatus(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("null", true)) return ""
        val lower = raw.lowercase()
        if (lower.contains("complete") || lower.contains("finished")) return "Completed"
        if (lower.contains("ongoing") || lower.contains("on going") || lower.contains("currently")) return "Ongoing"
        return raw
    }

    private fun isOngoing(value: String?): Boolean {
        val raw = value?.trim()?.lowercase().orEmpty()
        return raw.isNotEmpty() && !raw.contains("complete") && !raw.contains("finished") && raw != "selesai"
    }

    private fun imageUrl(image: String?): String {
        val value = image?.trim().orEmpty()
        if (value.isEmpty() || value.equals("null", true)) return ""
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return ANIMEKU_IMAGE_BASE + value
    }


    private fun positiveId(value: String?): Int {
        val hash = value?.hashCode() ?: 1
        return if (hash == Int.MIN_VALUE) 1 else kotlin.math.abs(hash)
    }

    private fun firstUseful(primary: String?, fallback: String?): String {
        val p = primary?.trim().orEmpty()
        if (p.isNotEmpty() && !p.equals("null", true)) return p
        return fallback?.trim().orEmpty()
    }

    companion object {
        private const val HOME_LIMIT = 10
        private const val BLOCKED_CHANNEL_ID = 45784
        private const val ANIMEKU_API_BASE = "https://pencarinafkah.xyz/vA6//api/"
        private const val ANIMEKU_API_KEY = "cda11y63tfI7rwln8BLeiKTvjsD5g2Mox01RzkhQCEXSGWbqYO"
        private const val ANIMEKU_IMAGE_BASE = "http://elara.whatbox.ca:29318/Duljanah/"
        private const val ANIMELOVERZ_API_BASE = "https://apps.animekita.org/api/v1.2.5"
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        }
    }
}
