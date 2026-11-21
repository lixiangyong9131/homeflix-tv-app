# -*- coding: utf-8 -*-
"""Generate VodApiAdapter.kt: Douban posters/metadata + CMS V10 source for playback."""
import re

SRC='app/src/main/java/com/homeflix/tv/data/remote/api/HomeFlixApiService.kt'
s=open(SRC,encoding='utf-8').read()
methods=re.findall(r'suspend fun (\w+)\s*\((.*?)\)\s*:\s*(Response<[^\n]+>)',s,re.S)

def clean_params(params):
    p=re.sub(r'@\w+\([^)]*?\)\s*','',params,flags=re.S)
    p=re.sub(r'@\w+\s+','',p)
    p=re.sub(r'\s*=\s*[^,)]+','',p)
    p=p.split('//')[0]
    p=' '.join(p.split())
    return p

def default_for(ret):
    if 'Response<Unit>' in ret: return 'Response.success(Unit)'
    if 'Response<ByteArray>' in ret: return 'Response.success(ByteArray(0))'
    if 'Response<String>' in ret: return 'Response.success("")'
    if 'Response<StreamDecisionDto>' in ret: return 'Response.success(StreamDecisionDto())'
    if 'Response<LiveLineup>' in ret: return 'Response.success(LiveLineup(enabled = false, channels = emptyList()))'
    if 'Response<LivePlay>' in ret: return 'Response.success(LivePlay(urls = emptyList()))'
    if 'Response<PlaybackProgressDto>' in ret: return 'Response.success(null)'
    if 'Response<MyListCheckDto>' in ret: return 'Response.success(MyListCheckDto(false))'
    if 'Response<RatingDto>' in ret: return 'Response.success(RatingDto())'
    if 'Response<TasteResultDto>' in ret: return 'Response.success(TasteResultDto())'
    if 'Response<ChartDto>' in ret: return 'Response.success(ChartDto(items = emptyList()))'
    if 'Response<NotificationsResponseDto>' in ret: return 'Response.success(NotificationsResponseDto(notifications = emptyList(), count = 0))'
    if 'Response<ComingSoonList>' in ret: return 'Response.success(ComingSoonList())'
    if 'Response<ComingSoonItem>' in ret: return 'Response.success(null)'
    if 'Response<NotifyResult>' in ret: return 'Response.success(NotifyResult())'
    if 'Response<List<' in ret: return 'Response.success(emptyList())'
    return 'Response.success(null)'

core={
 'getAllMedia','getMovies','getTVShows','getMediaById','getMediaByGenre','searchMedia',
 'getStreamDecision','getStreamUrl','getTrendingRecommendations','getMixedRecommendations',
 'getPopularRecommendations','getRecentRecommendations','getPersonalizedRecommendations',
 'getUniqueRecommendations','getTopRatedRecommendations','getGenreRecommendations',
 'getRecommendations','getSimilarMedia','getTop10','getAllGenres','getGenreById',
 'getTvSeries','getTvSeriesById','getTvSeriesSeasons','getTvSeriesSeason','getTvSeriesEpisodes',
 'getMyList','getMyListEntries','checkMyList','addToMyList','removeFromMyList',
 'getRecentlyWatchedWithProgress','getRecentlyWatched','getContinueWatching',
 'liveChannels','livePlay',
}

def core_impl(name):
    impls={
     'getAllMedia':'        Response.success(doubanAll().drop(offset).take(limit))',
     'getMovies':'        Response.success(doubanMovies().drop(offset).take(limit))',
     'getTVShows':'        Response.success(doubanSeries().drop(offset).take(limit))',
     'getMediaById':'        val d = findById(id)\n        Response.success(d)',
     'getMediaByGenre':'        val all = doubanAll()\n        Response.success(all.filter { d -> d.genreNames?.any { it.contains(genre, true) } == true }.drop(offset).take(limit))',
     'searchMedia':'        Response.success(searchByTitle(query).take(limit))',
     'getStreamDecision':'        val url = resolvePlayUrl(id)\n        Response.success(StreamDecisionDto(strategy = if (url.contains(".m3u8", true)) "hls" else "direct", url = url, reason = "douban-vod"))',
     'getStreamUrl':'        val d = findById(id)\n        Response.success(StreamInfoDto(streamUrl = resolvePlayUrl(id), mediaType = "video/mp4", duration = 0L))',
     'getTrendingRecommendations':'        Response.success(doubanAll().shuffled().take(limit))',
     'getMixedRecommendations':'        Response.success(doubanAll().shuffled().take(limit))',
     'getPopularRecommendations':'        Response.success(doubanAll().sortedByDescending { it.rating }.take(limit))',
     'getRecentRecommendations':'        Response.success(doubanAll().shuffled().take(limit))',
     'getPersonalizedRecommendations':'        Response.success(doubanAll().shuffled().take(limit))',
     'getUniqueRecommendations':'        Response.success(doubanAll().distinctBy { it.title }.take(limit))',
     'getTopRatedRecommendations':'        Response.success(doubanAll().sortedByDescending { it.rating }.take(limit))',
     'getGenreRecommendations':'        Response.success(doubanAll().shuffled().take(limit))',
     'getRecommendations':'        Response.success(doubanAll().shuffled().take(limit))',
     'getSimilarMedia':'        Response.success(doubanAll().shuffled().take(24))',
     'getTop10':'        val items = doubanAll().sortedByDescending { it.rating }.take(10).mapIndexed { i, d ->\n            ChartItemDto(id = d.id, type = d.type, title = d.title, rank = i + 1, plays = 1000 - i)\n        }\n        Response.success(ChartDto(kind = kind, windowDays = 0, source = "catalog", items = items))',
     'getAllGenres':'        val names = doubanAll().mapNotNull { it.genreNames?.firstOrNull() }.distinct().take(20)\n        Response.success(names.mapIndexed { i, n -> GenreDto(id = i + 1, name = n) })',
     'getGenreById':'        val names = doubanAll().mapNotNull { it.genreNames?.firstOrNull() }.distinct()\n        Response.success(GenreDto(id = id, name = names.getOrNull(id - 1) ?: "Movie"))',
     'getTvSeries':'        Response.success(doubanSeries().take(limit ?: 100))',
     'getTvSeriesById':'        val d = findById(id.toString()) ?: MediaDto(id=id, uuid="s$id", title="Unknown Series", type="series", filePath="", fileSize=0, duration=0)\n        Response.success(d)',
     'getTvSeriesSeasons':'        val d = findById(id.toString())\n        Response.success(listOf(SeasonDto(id = id, season_number = 1, name = "Season 1", overview = null, air_date = null, episode_count = 1, poster_path = d?.posterPath, episodes = null)))',
     'getTvSeriesSeason':'        val d = findById(id.toString())\n        Response.success(SeasonDto(id = id, season_number = seasonNumber, name = "Season $seasonNumber", overview = null, air_date = null, episode_count = 1, poster_path = d?.posterPath, episodes = null))',
     'getTvSeriesEpisodes':'        val d = findById(id.toString())\n        Response.success(listOfNotNull(d?.copy(type = "episode", seasonNumber = seasonNumber, episodeNumber = 1)))',
     'getMyList':'        Response.success(getMyListIds().mapNotNull { id ->\n            val d = findById(id.toString()) ?: return@mapNotNull null\n            WatchlistItemDto(id = id, mediaId = id, userId = 1, media = d, addedAt = "")\n        })',
     'getMyListEntries':'        Response.success(getMyListIds().map { MyListEntryDto(mediaId = it, addedAt = "") })',
     'checkMyList':'        Response.success(MyListCheckDto(inList = getMyListIds().contains(id.toIntOrNull() ?: -1)))',
     'addToMyList':'        val ids = getMyListIds(); id.toIntOrNull()?.let { ids.add(it) }; saveMyListIds(ids)\n        Response.success(Unit)',
     'removeFromMyList':'        val ids = getMyListIds(); ids.remove(id.toIntOrNull() ?: -1); saveMyListIds(ids)\n        Response.success(Unit)',
     'getRecentlyWatchedWithProgress':'        val ids = prefs.getStringSet(KEY_PROGRESS, emptySet()) ?: emptySet()\n        val out = ids.mapNotNull { raw ->\n            val parts = raw.split("|"); val d = findById(parts[0]) ?: return@mapNotNull null\n            RecentlyWatchedItemDto(id = d.id, mediaId = d.id, userId = "1", lastWatchedAt = "2026-01-01T00:00:00Z", progressSeconds = parts.getOrNull(1)?.toLongOrNull() ?: 0, durationSeconds = 0, media = d)\n        }\n        Response.success(out)',
     'getRecentlyWatched':'        Response.success(doubanAll().take(10))',
     'getContinueWatching':'        Response.success(doubanAll().take(10))',
     'liveChannels':'        val chans = (1..12).map { i -> LiveChannel(id = "ch$i", name = "频道 $i", number = i, status = "online", categories = listOf("General"), country = "CN") }\n        Response.success(LiveLineup(enabled = true, channels = chans))',
     'livePlay':'        Response.success(LivePlay(urls = listOf("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")))',
    }
    return impls.get(name, default_for(ret))

out=[]
out.append('''package com.homeflix.tv.data.remote.api

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
        val cover = opt(s, "cover")
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
        .replace(Regex("\\\\s*[（(].*[)）]"), "")
        .replace(Regex("\\\\s*[:：](#.*)?"), "")
        .trim()

    private fun vodJson(url: String): JsonObject? =
        json(url, mapOf("User-Agent" to "Mozilla/5.0"))

    @Synchronized
    private fun resolvePlayUrl(id: Int): String {
        playCache[id]?.let { return it }
        val title = titleOf[id] ?: findById(id.toString())?.title ?: return ""
        val q = normalizeTitle(title)
        val search = vodJson("$VOD_API?ac=vod&wd=" + URLEncoder.encode(q, "UTF-8"))
        val rows = search?.getAsJsonArray("list") ?: run {
            // fallback: drop trailing numbered part
            val short = q.replace(Regex("\\\\d+$"), "").trim()
            vodJson("$VOD_API?ac=vod&wd=" + URLEncoder.encode(short, "UTF-8"))?.getAsJsonArray("list")
        } ?: return ""
        val cand = rows.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }.firstOrNull { v ->
            val n = normalizeTitle(opt(v, "vod_name"))
            n.contains(q.take(6), true) || q.contains(n.take(6), true)
        } ?: rows.firstOrNull()?.asJsonObject ?: return ""
        val vid = opt(cand, "vod_id")
        val detail = vodJson("$VOD_API?ac=detail&ids=" + URLEncoder.encode(vid, "UTF-8"))
        val v = (detail?.getAsJsonArray("list")?.firstOrNull()?.asJsonObject ?: cand)
        val raw = opt(v, "vod_play_url")
        val url = raw.split("#").firstOrNull()?.let { seg ->
            val idx = seg.lastIndexOf('$')
            if (idx >= 0 && idx < seg.length - 1) seg.substring(idx + 1) else seg
        }?.trim() ?: ""
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
''')

for name,params,ret in methods:
    p=clean_params(params); ret=ret.strip()
    if name in core:
        out.append(f'\n    override suspend fun {name}({p}): {ret} = withContext(Dispatchers.IO) {{\n{core_impl(name)}\n    }}')
    else:
        out.append(f'\n    override suspend fun {name}({p}): {ret} = {default_for(ret)}')
out.append('\n}\n')
open('app/src/main/java/com/homeflix/tv/data/remote/api/VodApiAdapter.kt','w',encoding='utf-8').write('\n'.join(out))
print('generated',len(methods),'methods')
