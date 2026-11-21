package com.homeflix.tv.data.remote.api

import android.content.Context
import android.content.SharedPreferences
import com.homeflix.tv.data.remote.dto.*
import com.homeflix.tv.comingsoon.*
import com.homeflix.tv.livetv.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * HomeFlixApiService implementation backed by a standard CMS V10 (TVBox type:1)
 * source. The rest of the app (Compose UI, ViewModels, player) is unchanged.
 */
class VodApiAdapter(private val context: Context) : HomeFlixApiService {

    companion object {
        // Slow but complete (list carries poster/description) — used for the home feed.
        private const val API = "https://taopianapi.com/cjapi/mc/vod/json.html"
        // Fast (~0.5s) — used for search. Detail lookup also fast.
        private const val FAST_API = "http://ffzy5.tv/api.php/provide/vod"
        private const val PREFS = "vod_adapter_state"
        private const val KEY_LIST = "mylist"
        private const val KEY_PROGRESS = "progress"
        private val GSON = Gson()
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cache = mutableMapOf<Int, MediaDto>()
    // id -> base url it came from, so playback/detail hits the right source
    private val sourceOf = mutableMapOf<Int, String>()
    private var allCache: List<MediaDto>? = null

    // ---------- low level HTTP ----------
    private fun fetch(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) { null }

    private fun json(url: String): JsonObject? =
        fetch(url)?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }

    private fun vodList(url: String): List<JsonObject> {
        val root = json(url) ?: return emptyList()
        val arr = root.getAsJsonArray("list") ?: return emptyList()
        return arr.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }
    }

    private fun parsePlayUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        // format: 第1集$url#第2集$url  -> take first playable url
        return raw.split("#").firstOrNull()?.let { seg ->
            val idx = seg.lastIndexOf('$')
            if (idx >= 0 && idx < seg.length - 1) seg.substring(idx + 1) else seg
        }?.trim() ?: ""
    }

    // ---------- Vod -> MediaDto ----------
    private fun toDto(v: JsonObject, source: String = API): MediaDto? {
        val id = runCatching { v.get("vod_id").asInt }.getOrNull() ?: return null
        val name = v.optString("vod_name").ifBlank { return null }
        val typeName = v.optString("type_name")
        val pic = v.optString("vod_pic")
        val content = v.optString("vod_content")
        val year = runCatching { v.get("vod_year").asInt }.getOrNull()
        val remarks = v.optString("vod_remarks")
        val actor = v.optString("vod_actor").split(",").map { it.trim() }.filter { it.isNotBlank() }
        val director = v.optString("vod_director").split(",").map { it.trim() }.filter { it.isNotBlank() }
        val playFrom = v.optString("vod_play_from")
        val playUrl = v.optString("vod_play_url")
        val isSeries = playUrl.count { it == '#' } > 0 || typeName.contains("剧")
        val dto = MediaDto(
            id = id,
            uuid = "vod$id",
            title = name,
            originalTitle = v.optString("vod_sub").ifBlank { null },
            type = if (isSeries) "series" else "movie",
            filePath = parsePlayUrl(playUrl),
            fileSize = 0L,
            duration = 0,
            tagline = remarks.ifBlank { null },
            shortDesc = remarks.ifBlank { null },
            description = content.ifBlank { null },
            year = year,
            rating = 0.0,
            country = v.optString("vod_area").ifBlank { null },
            language = v.optString("vod_lang").ifBlank { null },
            stars = actor,
            director = director,
            genreNames = listOf(typeName).filter { it.isNotBlank() },
            posterPath = pic.ifBlank { null },
            bannerPath = pic.ifBlank { null },
            thumbnailPath = pic.ifBlank { null },
            tmdbPosterUrl = pic.ifBlank { null },
            tmdbBackdropUrl = pic.ifBlank { null },
            viewCount = id,
            createdAt = v.optString("vod_time").ifBlank { null }
        )
        // Avoid id collisions between the two sources: shift fast-source ids
        // into a high range so a search result never overwrites a feed item.
        val key = if (source == FAST_API) id + 5_000_000 else id
        val finalDto = if (key == id) dto else dto.copy(id = key, uuid = "fast$id")
        cache[key] = finalDto
        sourceOf[key] = source
        return finalDto
    }

    private fun JsonObject.optString(key: String): String =
        runCatching { get(key)?.takeUnless { it.isJsonNull }?.asString ?: "" }.getOrDefault("")

    @Synchronized
    private fun loadAll(force: Boolean = false): List<MediaDto> {
        allCache?.let { if (!force && it.isNotEmpty()) return it }
        // The list endpoint is slow (~10s) and returns only 8 fields (no poster,
        // no play url). Fetch page 1, then one batched detail call to get full
        // records with posters and play urls — so playback is instant (cache hit).
        val list = vodList("$API?ac=list")
        val out = mutableListOf<MediaDto>()
        if (list.isNotEmpty()) {
            val ids = list.mapNotNull { runCatching { it.get("vod_id").asString }.getOrNull() }
            val details = if (ids.isNotEmpty()) vodList("$API?ac=detail&ids=" + ids.joinToString(",")) else emptyList()
            val detailMap = details.associateBy { runCatching { it.get("vod_id").asString }.getOrNull() }
            for (v in list) {
                val vid = runCatching { v.get("vod_id").asString }.getOrNull()
                toDto(detailMap[vid] ?: v)?.let { out.add(it) }
            }
        }
        allCache = out
        return out
    }

    private fun findById(id: String): MediaDto? {
        val iid = id.toIntOrNull() ?: return null
        cache[iid]?.let { return it }
        val base = sourceOf[iid] ?: API
        val realId = if (base == FAST_API && iid >= 5_000_000) iid - 5_000_000 else iid
        val root = json("$base?ac=detail&ids=$realId") ?: return null
        val arr = root.getAsJsonArray("list") ?: return null
        val v = arr.firstOrNull()?.asJsonObject ?: return null
        return toDto(v, base)
    }

    // ---------- persisted lightweight state ----------
    private fun getMyListIds(): MutableSet<Int> =
        (prefs.getStringSet(KEY_LIST, emptySet()) ?: emptySet()).mapNotNull { it.toIntOrNull() }.toMutableSet()
    private fun saveMyListIds(ids: Set<Int>) =
        prefs.edit().putStringSet(KEY_LIST, ids.map { it.toString() }.toSet()).apply()


    override suspend fun getAllMedia(limit: Int, offset: Int, genre: String?, type: String?): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        val list = loadAll()
        val filtered = list.filter { d ->
            (type.isNullOrBlank() || d.type == type) &&
            (genre.isNullOrBlank() || d.genreNames?.any { it.contains(genre, true) } == true)
        }
        Response.success(filtered.drop(offset).take(limit))
    }

    override suspend fun getMovies(limit: Int, offset: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(loadAll().filter { it.type == "movie" }.drop(offset).take(limit))
    }

    override suspend fun getTVShows(limit: Int, offset: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(loadAll().filter { it.type == "series" }.drop(offset).take(limit))
    }

    override suspend fun getMediaById(id: String): Response<MediaDto> = withContext(Dispatchers.IO) {
        val d = findById(id)
        if (d != null) Response.success(d) else Response.success(null)
    }

    override suspend fun getMediaByGenre(genre: String, limit: Int, offset: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(loadAll().filter { d ->
            d.genreNames?.any { it.contains(genre, true) } == true || d.title.contains(genre, true)
        }.drop(offset).take(limit))
    }

    override suspend fun searchMedia(query: String, limit: Int, offset: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext Response.success(emptyList<MediaDto>())
        // Search via the fast source (~0.5s). Its search rows lack poster/play
        // url, so fetch detail for a bounded number of results (also fast).
        val rows = vodList("$FAST_API?ac=vod&wd=" + URLEncoder.encode(q, "UTF-8"))
        val out = mutableListOf<MediaDto>()
        for (v in rows.take(12)) {
            val realId = v.optString("vod_id")
            val detail = json("$FAST_API?ac=detail&ids=" + URLEncoder.encode(realId, "UTF-8"))
            val item = (detail?.getAsJsonArray("list")?.firstOrNull()?.asJsonObject ?: v)
            toDto(item, FAST_API)?.let { out.add(it) }
            if (out.size >= limit) break
        }
        Response.success(out)
    }

    override suspend fun getStreamUrl(id: String): Response<StreamInfoDto> = withContext(Dispatchers.IO) {
        val d = findById(id)
        Response.success(StreamInfoDto(streamUrl = d?.filePath ?: "", mediaType = "video/mp4", duration = 0L))
    }

    override suspend fun getStreamDecision(id: Int, profile: String, sid: String, startSeconds: Long, videoCodecs: String?, audioCodecs: String?): Response<StreamDecisionDto> = withContext(Dispatchers.IO) {
        val d = findById(id.toString())
        val url = d?.filePath ?: ""
        Response.success(StreamDecisionDto(
            strategy = if (url.contains(".m3u8", true)) "hls" else "direct",
            url = url,
            reason = "vod-adapter"))
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
        Response.success(loadAll().take(10))
    }

    override suspend fun getContinueWatching(): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(loadAll().take(10))
    }

    override suspend fun getRecentlyWatchedWithProgress(userId: String): Response<List<RecentlyWatchedItemDto>> = withContext(Dispatchers.IO) {
        val ids = prefs.getStringSet(KEY_PROGRESS, emptySet()) ?: emptySet()
        val out = ids.mapNotNull { raw ->
            val parts = raw.split(":"); val d = findById(parts[0]) ?: return@mapNotNull null
            RecentlyWatchedItemDto(
                id = d.id, mediaId = d.id, userId = "1",
                lastWatchedAt = "2026-01-01T00:00:00Z",
                progressSeconds = parts.getOrNull(1)?.toLongOrNull() ?: 0,
                durationSeconds = 0, media = d)
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
        val ids = getMyListIds()
        Response.success(ids.mapNotNull { id ->
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
        val chans = (1..12).map { i ->
            LiveChannel(id = "ch$i", name = "频道 $i", number = i, status = "online", categories = listOf("General"), country = "CN")
        }
        Response.success(LiveLineup(enabled = true, channels = chans))
    }

    override suspend fun livePlay(id: String): Response<com.homeflix.tv.livetv.LivePlay> = withContext(Dispatchers.IO) {
        val n = id.removePrefix("ch").toIntOrNull() ?: 1
        Response.success(LivePlay(urls = listOf(
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")))
    }

    override suspend fun getTop10(kind: String, limit: Int): Response<ChartDto> = withContext(Dispatchers.IO) {
        val items = loadAll().take(10).mapIndexed { i, d ->
            ChartItemDto(id = d.id, type = d.type, title = d.title, rank = i + 1, plays = 1000 - i)
        }
        Response.success(ChartDto(kind = kind, windowDays = 0, source = "catalog", items = items))
    }

    override suspend fun getRecommendations(category: String?, limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(loadAll().shuffled().take(limit))
    }

    override suspend fun getMixedRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(loadAll().shuffled().take(limit))
    }

    override suspend fun getTrendingRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(loadAll().shuffled().take(limit))
    }

    override suspend fun getPopularRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(loadAll().sortedByDescending { it.viewCount }.take(limit))
    }

    override suspend fun getRecentRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(loadAll().sortedByDescending { it.title.hashCode() }.take(limit))
    }

    override suspend fun getPersonalizedRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(loadAll().shuffled().take(limit))
    }

    override suspend fun getUniqueRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(loadAll().distinctBy { it.title }.take(limit))
    }

    override suspend fun getTopRatedRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(loadAll().sortedByDescending { it.rating }.take(limit))
    }

    override suspend fun getGenreRecommendations(limit: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(loadAll().shuffled().take(limit))
    }

    override suspend fun getSimilarMedia(id: String): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        val d = findById(id)
        val g = d?.genreNames?.firstOrNull()
        Response.success(loadAll().filter { it.id != d?.id && (g == null || it.genreNames?.contains(g) == true) }.take(24))
    }

    override suspend fun trackRecommendationClick(id: String): Response<Unit> = Response.success(Unit)

    override suspend fun getNotifications(limit: Int): Response<NotificationsResponseDto> = Response.success(NotificationsResponseDto(notifications = emptyList(), count = 0))

    override suspend fun markNotificationRead(id: String): Response<Unit> = Response.success(Unit)

    override suspend fun getAllGenres(): Response<List<GenreDto>> = withContext(Dispatchers.IO) {
        val names = loadAll().mapNotNull { it.genreNames?.firstOrNull() }.distinct().take(20)
        Response.success(names.mapIndexed { i, n -> GenreDto(id = i + 1, name = n) })
    }

    override suspend fun getGenreById(id: Int): Response<GenreDto> = withContext(Dispatchers.IO) {
        val names = loadAll().mapNotNull { it.genreNames?.firstOrNull() }.distinct()
        val n = names.getOrNull(id - 1) ?: "Movie"
        Response.success(GenreDto(id = id, name = n))
    }

    override suspend fun getTvSeries(sort: String?, order: String?, limit: Int?, offset: Int?): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        Response.success(loadAll().filter { it.type == "series" }.take(limit ?: 100))
    }

    override suspend fun getTvSeriesById(id: Int): Response<MediaDto> = withContext(Dispatchers.IO) {
        val d = findById(id.toString()) ?: MediaDto(id=id, uuid="s$id", title="Unknown Series", type="series", filePath="", fileSize=0, duration=0)
        Response.success(d)
    }

    override suspend fun getTvSeriesSeasons(id: Int): Response<List<SeasonDto>> = withContext(Dispatchers.IO) {
        val d = findById(id.toString())
        val season = SeasonDto(id = id, season_number = 1, name = "Season 1", overview = null, air_date = null, episode_count = 1, poster_path = d?.posterPath, episodes = null)
        Response.success(listOf(season))
    }

    override suspend fun getTvSeriesSeason(id: Int, seasonNumber: Int): Response<SeasonDto> = withContext(Dispatchers.IO) {
        val d = findById(id.toString())
        Response.success(SeasonDto(id = id, season_number = seasonNumber, name = "Season $seasonNumber", overview = null, air_date = null, episode_count = 1, poster_path = d?.posterPath, episodes = null))
    }

    override suspend fun getTvSeriesEpisodes(id: Int, seasonNumber: Int): Response<List<MediaDto>> = withContext(Dispatchers.IO) {
        val d = findById(id.toString())
        val ep = d?.copy(id = id, type = "episode", seriesId = id, seasonNumber = seasonNumber, episodeNumber = 1, title = d.title)
        Response.success(listOfNotNull(ep))
    }

}
