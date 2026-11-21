package com.homeflix.tv.data.remote.api

import android.content.Context
import android.content.SharedPreferences
import com.homeflix.tv.data.remote.dto.*
import com.homeflix.tv.comingsoon.*
import com.homeflix.tv.livetv.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * HomeFlixApiService implementation: posters and metadata from Douban movie,
 * playback resolved by title against a CMS V10 (TVBox type:1) source.
 * All Compose UI / ViewModels / player are unchanged.
 */
class VodApiAdapter(private val context: Context) : HomeFlixApiService {

    companion object {
        private const val DOUBAN = "https://movie.douban.com/j/search_subjects"
        private const val DOUBAN_ABSTRACT = "https://movie.douban.com/j/subject_abstract?subject_id="
        private const val VOD_API = "http://ffzy5.tv/api.php/provide/vod"
        private const val PREFS = "vod_adapter_state"
        private const val KEY_LIST = "mylist"
        private const val KEY_PROGRESS = "progress"
        private val DOUBAN_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Referer" to "https://movie.douban.com/"
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cache = mutableMapOf<Int, MediaDto>()
    private val titleOf = mutableMapOf<Int, String>()
    private val playCache = mutableMapOf<Int, String>()
    private var allCache: List<MediaDto>? = null

    // ---------- HTTP ----------
    private fun fetch(url: String, headers: Map<String, String> = DOUBAN_HEADERS): String? = try {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 20000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) { null }

    private fun json(url: String, headers: Map<String, String> = DOUBAN_HEADERS): JsonObject? =
        fetch(url, headers)?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }

    private fun opt(o: JsonObject, k: String): String =
        runCatching { o.get(k)?.takeUnless { it.isJsonNull }?.asString ?: "" }.getOrDefault("")

    // ---------- Douban -> MediaDto ----------
    private fun doubanDto(s: JsonObject): MediaDto? {
        val dbId = opt(s, "id").ifBlank { return null }
        val title = opt(s, "title").ifBlank { return null }
        // Douban returns the small s_ratio_poster thumbnail by default;
        // l_ratio_poster is ~12x larger and much sharper on a TV screen.
        val cover = opt(s, "cover").replace("s_ratio_poster", "l_ratio_poster")
        val rate = runCatching { opt(s, "rate").toDouble() }.getOrDefault(0.0)
        val key = dbId.hashCode()
        val dto = MediaDto(
            id = key, uuid = "douban:$dbId", title = title,
            type = "movie", filePath = "", fileSize = 0L, duration = 0,
            description = null, year = null, rating = rate,
            posterPath = cover.ifBlank { null },
            bannerPath = cover.ifBlank { null },
            thumbnailPath = cover.ifBlank { null },
            tmdbPosterUrl = cover.ifBlank { null },
            tmdbBackdropUrl = cover.ifBlank { null },
            createdAt = "2026-01-01T00:00:00Z"
        )
        cache[key] = dto
        titleOf[key] = title
        return dto
    }

    @Synchronized
    private fun doubanFetch(tag: String, type: String, start: Int = 0): List<MediaDto> {
        val u = "$DOUBAN?type=$type&tag=" + URLEncoder.encode(tag, "UTF-8") +
            "&sort=recommend&page_limit=20&page_start=$start"
        val root = json(u) ?: return emptyList()
        val arr = root.getAsJsonArray("subjects") ?: return emptyList()
        return arr.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }.mapNotNull { doubanDto(it) }
    }

    @Synchronized
    private fun doubanAll(): List<MediaDto> {
        allCache?.let { if (it.isNotEmpty()) return it }
        val out = mutableListOf<MediaDto>()
        doubanFetch("热门", "movie").let { out.addAll(it) }
        doubanFetch("豆瓣高分", "movie").let { out.addAll(it) }
        doubanFetch("热门", "tv").let { out.addAll(it) }
        doubanFetch("最新", "movie").let { out.addAll(it) }
        val distinct = out.distinctBy { it.uuid }
        allCache = distinct
        return distinct
    }

    private fun doubanMovies(): List<MediaDto> = doubanAll().filter { it.type == "movie" }
    private fun doubanSeries(): List<MediaDto> = doubanAll().filter { it.type == "series" }

    private fun findById(id: String): MediaDto? {
        val key = id.toIntOrNull() ?: return null
        cache[key]?.let { return it }
        return doubanAll().firstOrNull { it.id == key }
    }

    // ---------- playback: title -> CMS V10 source ----------
    private fun normalizeTitle(t: String): String = t
        .replace(Regex("\\s*[（(].*[)）]"), "")
        .replace(Regex("\\s*[:：](#.*)?"), "")
        .trim()

    private fun vodJson(url: String): JsonObject? =
        json(url, mapOf("User-Agent" to "Mozilla/5.0"))

    /** Pick a direct playable URL from CMS V10 multi-source fields. */
    private fun pickPlayUrl(v: JsonObject): String {
        val groups = opt(v, "vod_play_url").split("$$$")
        val names = opt(v, "vod_play_from").split("$$$")
        val candidates = mutableListOf<String>()
        for ((i, group) in groups.withIndex()) {
            val label = names.getOrNull(i) ?: ""
            // skip non-direct groups (share pages / iframe players)
            if (label.contains("share", true) || label.equals("feifan", true)) continue
            // group format: 第1集$url#第2集$url  (or url directly)
            for (ep in group.split("#")) {
                val idx = ep.lastIndexOf('$')
                val u = (if (idx >= 0 && idx < ep.length - 1) ep.substring(idx + 1) else ep).trim()
                if (u.startsWith("http")) {
                    // prefer a real m3u8/mp4 link over a redirect/share page
                    if (u.contains(".m3u8", true) || u.contains(".mp4", true)) return u
                    candidates.add(u)
                }
            }
        }
        return candidates.firstOrNull().orEmpty()
    }

    @Synchronized
    private fun resolvePlayUrl(id: Int): String {
        playCache[id]?.let { return it }
        val title = titleOf[id] ?: findById(id.toString())?.title ?: return ""
        val q = normalizeTitle(title)
        val search = vodJson("$VOD_API?ac=vod&wd=" + URLEncoder.encode(q, "UTF-8"))
        val rows = search?.getAsJsonArray("list") ?: run {
            val short = q.replace(Regex("\\d+$"), "").trim()
            vodJson("$VOD_API?ac=vod&wd=" + URLEncoder.encode(short, "UTF-8"))?.getAsJsonArray("list")
        } ?: return ""
        val cand = rows.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }.firstOrNull { v ->
            val n = normalizeTitle(opt(v, "vod_name"))
            n.contains(q.take(6), true) || q.contains(n.take(6), true)
        } ?: rows.firstOrNull()?.asJsonObject ?: return ""
        val vid = opt(cand, "vod_id")
        val detail = vodJson("$VOD_API?ac=detail&ids=" + URLEncoder.encode(vid, "UTF-8"))
        val v = (detail?.getAsJsonArray("list")?.firstOrNull()?.asJsonObject ?: cand)
        val url = pickPlayUrl(v)
        playCache[id] = url
        return url
    }

    @Synchronized
    private fun searchByTitle(query: String): List<MediaDto> {
        val local = doubanAll().filter { it.title.contains(query, true) }
        if (local.isNotEmpty()) return local
        val rowsJson = vodJson("$VOD_API?ac=vod&wd=" + URLEncoder.encode(query, "UTF-8"))
        val rows = rowsJson?.getAsJsonArray("list") ?: return emptyList()
        return rows.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }.mapNotNull { v ->
            val vid = runCatching { opt(v, "vod_id").toInt() }.getOrNull() ?: return@mapNotNull null
            val detail = vodJson("$VOD_API?ac=detail&ids=$vid")
            val full = detail?.getAsJsonArray("list")?.firstOrNull()?.asJsonObject ?: v
            doubanDto(full) ?: MediaDto(
                id = vid + 9_000_000, uuid = "vod$vid", title = opt(full, "vod_name"),
                type = "movie", filePath = "", fileSize = 0L, duration = 0,
                posterPath = opt(full, "vod_pic").ifBlank { null },
                tmdbPosterUrl = opt(full, "vod_pic").ifBlank { null }
            ).also { cache[it.id] = it; titleOf[it.id] = it.title }
        }
    }

    // ---------- persisted lightweight state ----------
    private fun getMyListIds(): MutableSet<Int> =
        (prefs.getStringSet(KEY_LIST, emptySet()) ?: emptySet()).mapNotNull { it.toIntOrNull() }.toMutableSet()
    private fun saveMyListIds(ids: Set<Int>) =
        prefs.edit().putStringSet(KEY_LIST, ids.map { it.toString() }.toSet()).apply()


    override suspend fun getAllMedia(limit: Int, offset: Int, genre: String?, type: String?): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanAll().drop(offset).take(limit))
    }

    override suspend fun getMovies(limit: Int, offset: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanMovies().drop(offset).take(limit))
    }

    override suspend fun getTVShows(limit: Int, offset: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanSeries().drop(offset).take(limit))
    }

    override suspend fun getMediaById(id: String): Response<MediaDto> = withContext(Dispatchers.IO) {
        val d = findById(id)
        Response.success(d)
    }

    override suspend fun getMediaByGenre(genre: String, limit: Int, offset: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        val all = doubanAll()
        Response.success(all.filter { d -> d.genreNames?.any { it.contains(genre, true) } == true }.drop(offset).take(limit))
    }

    override suspend fun searchMedia(query: String, limit: Int, offset: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(searchByTitle(query).take(limit))
    }

    override suspend fun getStreamUrl(id: String): Response<StreamInfoDto> = withContext(Dispatchers.IO) {
        val d = findById(id)
        Response.success(StreamInfoDto(streamUrl = resolvePlayUrl(id.toIntOrNull() ?: -1), mediaType = "video/mp4", duration = 0L))
    }

    override suspend fun getStreamDecision(id: Int, profile: String, sid: String, startSeconds: Long, videoCodecs: String?, audioCodecs: String?): Response<StreamDecisionDto> = withContext(Dispatchers.IO) {
        val url = resolvePlayUrl(id)
        Response.success(StreamDecisionDto(strategy = if (url.contains(".m3u8", true)) "hls" else "direct", url = url, reason = "douban-vod"))
    }

    override suspend fun releaseHlsSession(id: Int, sid: String): Response<Unit> = Response.success(Unit)

    override suspend fun getPreviewClip(id: String): Response<StreamInfoDto> = Response.success(null)

    override suspend fun getThumbnail(id: String): Response<ByteArray> = Response.success(ByteArray(0))

    override suspend fun getPoster(id: String): Response<ByteArray> = Response.success(ByteArray(0))

    override suspend fun getPreview(id: String): Response<ByteArray> = Response.success(ByteArray(0))

    override suspend fun getSubtitleTracks(id: String): Response<List<SubtitleTrackDto>> = Response.success(emptyList())

    override suspend fun getAudioTracks(id: String): Response<List<AudioTrackDto>> = Response.success(emptyList())

    override suspend fun getSubtitleFile(mediaId: String, trackId: String): Response<String> = Response.success("")

    override suspend fun trackView(id: String): Response<Unit> = Response.success(Unit)

    override suspend fun updatePlaybackProgress(request: PlaybackProgressRequest): Response<Unit> = Response.success(Unit)

    override suspend fun updatePlaybackProgressAlt(userId: String, request: PlaybackProgressAltRequest): Response<Unit> = Response.success(Unit)

    override suspend fun getPlaybackProgress(id: String): Response<PlaybackProgressDto> = Response.success(null)

    override suspend fun initializePlaybackProgress(id: String): Response<PlaybackProgressDto> = Response.success(null)

    override suspend fun getRecentlyWatched(): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanAll().take(10))
    }

    override suspend fun getContinueWatching(): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanAll().take(10))
    }

    override suspend fun getRecentlyWatchedWithProgress(userId: String): Response<List<RecentlyWatchedItemDto>> = withContext(Dispatchers.IO) {
        val ids = prefs.getStringSet(KEY_PROGRESS, emptySet()) ?: emptySet()
        val out = ids.mapNotNull { raw ->
            val parts = raw.split("|"); val d = findById(parts[0]) ?: return@mapNotNull null
            RecentlyWatchedItemDto(id = d.id, mediaId = d.id, userId = "1", lastWatchedAt = "2026-01-01T00:00:00Z", progressSeconds = parts.getOrNull(1)?.toLongOrNull() ?: 0, durationSeconds = 0, media = d)
        }
        Response.success(out)
    }

    override suspend fun getWatchHistory(): Response<List<ViewHistoryDto>> = Response.success(emptyList())

    override suspend fun addToMyList(id: String, type: String?): Response<Unit> = withContext(Dispatchers.IO) {
        val ids = getMyListIds(); id.toIntOrNull()?.let { ids.add(it) }; saveMyListIds(ids)
        Response.success(Unit)
    }

    override suspend fun removeFromMyList(id: String, type: String?): Response<Unit> = withContext(Dispatchers.IO) {
        val ids = getMyListIds(); ids.remove(id.toIntOrNull() ?: -1); saveMyListIds(ids)
        Response.success(Unit)
    }

    override suspend fun getMyList(): Response<List<WatchlistItemDto>> = withContext(Dispatchers.IO) {
        Response.success(getMyListIds().mapNotNull { id ->
            val d = findById(id.toString()) ?: return@mapNotNull null
            WatchlistItemDto(id = id, mediaId = id, userId = 1, media = d, addedAt = "")
        })
    }

    override suspend fun getMyListEntries(): Response<List<MyListEntryDto>> = withContext(Dispatchers.IO) {
        Response.success(getMyListIds().map { MyListEntryDto(mediaId = it, addedAt = "") })
    }

    override suspend fun checkMyList(id: String, type: String?): Response<MyListCheckDto> = withContext(Dispatchers.IO) {
        Response.success(MyListCheckDto(inList = getMyListIds().contains(id.toIntOrNull() ?: -1)))
    }

    override suspend fun getRating(kind: String, id: Int): Response<RatingDto> = Response.success(RatingDto())

    override suspend fun putRating(kind: String, id: Int, body: RatingBody): Response<RatingDto> = Response.success(RatingDto())

    override suspend fun getForYou(limit: Int, kind: String?, unique: Int?): Response<TasteResultDto> = Response.success(TasteResultDto())

    override suspend fun getComingSoon(type: String?): Response<com.homeflix.tv.comingsoon.ComingSoonList> = Response.success(null)

    override suspend fun getComingSoonTitle(type: String, id: Int): Response<com.homeflix.tv.comingsoon.ComingSoonItem> = Response.success(null)

    override suspend fun comingSoonNotify(body: com.homeflix.tv.comingsoon.NotifyBody): Response<com.homeflix.tv.comingsoon.NotifyResult> = Response.success(null)

    override suspend fun comingSoonUnnotify(type: String, id: Int): Response<com.homeflix.tv.comingsoon.NotifyResult> = Response.success(null)

    override suspend fun liveChannels(country: String?, category: String?, q: String?): Response<com.homeflix.tv.livetv.LiveLineup> = withContext(Dispatchers.IO) {
        val chans = (1..12).map { i -> LiveChannel(id = "ch$i", name = "频道 $i", number = i, status = "online", categories = listOf("General"), country = "CN") }
        Response.success(LiveLineup(enabled = true, channels = chans))
    }

    override suspend fun livePlay(id: String): Response<com.homeflix.tv.livetv.LivePlay> = withContext(Dispatchers.IO) {
        Response.success(LivePlay(urls = listOf("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")))
    }

    override suspend fun getTop10(kind: String, limit: Int): Response<ChartDto> = withContext(Dispatchers.IO) {
        val items = doubanAll().sortedByDescending { it.rating }.take(10).mapIndexed { i, d ->
            ChartItemDto(id = d.id, type = d.type, title = d.title, rank = i + 1, plays = 1000 - i)
        }
        Response.success(ChartDto(kind = kind, windowDays = 0, source = "catalog", items = items))
    }

    override suspend fun getRecommendations(category: String?, limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanAll().shuffled().take(limit))
    }

    override suspend fun getMixedRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanAll().shuffled().take(limit))
    }

    override suspend fun getTrendingRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanAll().shuffled().take(limit))
    }

    override suspend fun getPopularRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanAll().sortedByDescending { it.rating }.take(limit))
    }

    override suspend fun getRecentRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanAll().shuffled().take(limit))
    }

    override suspend fun getPersonalizedRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanAll().shuffled().take(limit))
    }

    override suspend fun getUniqueRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanAll().distinctBy { it.title }.take(limit))
    }

    override suspend fun getTopRatedRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanAll().sortedByDescending { it.rating }.take(limit))
    }

    override suspend fun getGenreRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanAll().shuffled().take(limit))
    }

    override suspend fun getSimilarMedia(id: String): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanAll().shuffled().take(24))
    }

    override suspend fun trackRecommendationClick(id: String): Response<Unit> = Response.success(Unit)

    override suspend fun getNotifications(limit: Int): Response<NotificationsResponseDto> = Response.success(NotificationsResponseDto(notifications = emptyList(), count = 0))

    override suspend fun markNotificationRead(id: String): Response<Unit> = Response.success(Unit)

    override suspend fun getAllGenres(): Response<List<GenreDto>> = withContext(Dispatchers.IO) {
        val names = doubanAll().mapNotNull { it.genreNames?.firstOrNull() }.distinct().take(20)
        Response.success(names.mapIndexed { i, n -> GenreDto(id = i + 1, name = n) })
    }

    override suspend fun getGenreById(id: Int): Response<GenreDto> = withContext(Dispatchers.IO) {
        val names = doubanAll().mapNotNull { it.genreNames?.firstOrNull() }.distinct()
        Response.success(GenreDto(id = id, name = names.getOrNull(id - 1) ?: "Movie"))
    }

    override suspend fun getTvSeries(sort: String?, order: String?, limit: Int?, offset: Int?): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(doubanSeries().take(limit ?: 100))
    }

    override suspend fun getTvSeriesById(id: Int): Response<MediaDto> = withContext(Dispatchers.IO) {
        val d = findById(id.toString()) ?: MediaDto(id=id, uuid="s$id", title="Unknown Series", type="series", filePath="", fileSize=0, duration=0)
        Response.success(d)
    }

    override suspend fun getTvSeriesSeasons(id: Int): Response<List<SeasonDto>> = withContext(Dispatchers.IO) {
        val d = findById(id.toString())
        Response.success(listOf(SeasonDto(id = id, season_number = 1, name = "Season 1", overview = null, air_date = null, episode_count = 1, poster_path = d?.posterPath, episodes = null)))
    }

    override suspend fun getTvSeriesSeason(id: Int, seasonNumber: Int): Response<SeasonDto> = withContext(Dispatchers.IO) {
        val d = findById(id.toString())
        Response.success(SeasonDto(id = id, season_number = seasonNumber, name = "Season $seasonNumber", overview = null, air_date = null, episode_count = 1, poster_path = d?.posterPath, episodes = null))
    }

    override suspend fun getTvSeriesEpisodes(id: Int, seasonNumber: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        val d = findById(id.toString())
        Response.success(listOfNotNull(d?.copy(type = "episode", seasonNumber = seasonNumber, episodeNumber = 1)))
    }

}
