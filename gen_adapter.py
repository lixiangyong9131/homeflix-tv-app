# -*- coding: utf-8 -*-
"""Generate VodApiAdapter.kt implementing HomeFlixApiService backed by Taopian (CMS V10) API."""
import re

SRC='app/src/main/java/com/homeflix/tv/data/remote/api/HomeFlixApiService.kt'
s=open(SRC,encoding='utf-8').read()
# extract methods: name, params(raw with defaults+annotations), return type
methods=re.findall(r'suspend fun (\w+)\s*\((.*?)\)\s*:\s*([^\n{]+?)\s*\{?\s*$',s,re.S|re.M)
if not methods:
    methods=re.findall(r'suspend fun (\w+)\s*\((.*?)\)\s*:\s*(Response<[^\n]+>)',s,re.S)

def clean_params(params):
    # remove annotations with args
    p=re.sub(r'@\w+\([^)]*?\)\s*','',params,flags=re.S)
    p=re.sub(r'@\w+\s+','',p)
    # override 方法不允许默认值，去掉 = xxx
    p=re.sub(r'\s*=\s*[^,)]+','',p)
    p=p.split('//')[0]
    p=' '.join(p.split())
    return p

def default_for(ret):
    if 'Response<Unit>' in ret: return 'Response.success(Unit)'
    if 'Response<ByteArray>' in ret: return 'Response.success(ByteArray(0))'
    if 'Response<String>' in ret: return 'Response.success("")'
    if 'Response<Int>' in ret: return 'Response.success(0)'
    if 'Response<Boolean>' in ret: return 'Response.success(false)'
    if 'Response<StreamDecisionDto>' in ret: return 'Response.success(StreamDecisionDto(url = null))'
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

# core methods implemented manually
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
     'getAllMedia': '''        val list = loadAll()
        val filtered = list.filter { d ->
            (type.isNullOrBlank() || d.type == type) &&
            (genre.isNullOrBlank() || d.genreNames?.any { it.contains(genre, true) } == true)
        }
        Response.success(filtered.drop(offset).take(limit))''',
     'getMovies': '''        Response.success(loadAll().filter { it.type == "movie" }.drop(offset).take(limit))''',
     'getTVShows': '''        Response.success(loadAll().filter { it.type == "series" }.drop(offset).take(limit))''',
     'getMediaById': '''        val d = findById(id)
        if (d != null) Response.success(d) else Response.success(null)''',
     'getMediaByGenre': '''        Response.success(loadAll().filter { d ->
            d.genreNames?.any { it.contains(genre, true) } == true || d.title.contains(genre, true)
        }.drop(offset).take(limit))''',
     'searchMedia': '''        val q = query.trim()
        if (q.isBlank()) return@withContext Response.success(emptyList<MediaDto>())
        val v = vodList("$API?ac=vod&wd=" + URLEncoder.encode(q, "UTF-8"))
        Response.success(v.mapNotNull { toDto(it) }.take(limit))''',
     'getStreamDecision': '''        val d = findById(id.toString())
        val url = d?.filePath ?: ""
        Response.success(StreamDecisionDto(
            strategy = if (url.contains(".m3u8", true)) "hls" else "direct",
            url = url,
            reason = "vod-adapter"))''',
     'getStreamUrl': '''        val d = findById(id)
        Response.success(StreamInfoDto(streamUrl = d?.filePath ?: "", mediaType = "video/mp4", duration = 0L))''',
     'getTrendingRecommendations': '''        Response.success(loadAll().shuffled().take(limit))''',
     'getMixedRecommendations': '''        Response.success(loadAll().shuffled().take(limit))''',
     'getPopularRecommendations': '''        Response.success(loadAll().sortedByDescending { it.viewCount }.take(limit))''',
     'getRecentRecommendations': '''        Response.success(loadAll().sortedByDescending { it.title.hashCode() }.take(limit))''',
     'getPersonalizedRecommendations': '''        Response.success(loadAll().shuffled().take(limit))''',
     'getUniqueRecommendations': '''        Response.success(loadAll().distinctBy { it.title }.take(limit))''',
     'getTopRatedRecommendations': '''        Response.success(loadAll().sortedByDescending { it.rating }.take(limit))''',
     'getGenreRecommendations': '''        Response.success(loadAll().shuffled().take(limit))''',
     'getRecommendations': '''        Response.success(loadAll().shuffled().take(limit))''',
     'getSimilarMedia': '''        val d = findById(id)
        val g = d?.genreNames?.firstOrNull()
        Response.success(loadAll().filter { it.id != d?.id && (g == null || it.genreNames?.contains(g) == true) }.take(24))''',
     'getTop10': '''        val items = loadAll().take(10).mapIndexed { i, d ->
            ChartItemDto(id = d.id, type = d.type, title = d.title, rank = i + 1, plays = 1000 - i)
        }
        Response.success(ChartDto(kind = kind, windowDays = 0, source = "catalog", items = items))''',
     'getAllGenres': '''        val names = loadAll().mapNotNull { it.genreNames?.firstOrNull() }.distinct().take(20)
        Response.success(names.mapIndexed { i, n -> GenreDto(id = i + 1, name = n) })''',
     'getGenreById': '''        val names = loadAll().mapNotNull { it.genreNames?.firstOrNull() }.distinct()
        val n = names.getOrNull(id - 1) ?: "Movie"
        Response.success(GenreDto(id = id, name = n))''',
     'getTvSeries': '''        Response.success(loadAll().filter { it.type == "series" }.take(limit ?: 100))''',
     'getTvSeriesById': '''        val d = findById(id.toString()) ?: MediaDto(id=id, uuid="s$id", title="Unknown Series", type="series", filePath="", fileSize=0, duration=0)
        Response.success(d)''',
     'getTvSeriesSeasons': '''        val d = findById(id.toString())
        val season = SeasonDto(id = id, season_number = 1, name = "Season 1", overview = null, air_date = null, episode_count = 1, poster_path = d?.posterPath, episodes = null)
        Response.success(listOf(season))''',
     'getTvSeriesSeason': '''        val d = findById(id.toString())
        Response.success(SeasonDto(id = id, season_number = seasonNumber, name = "Season $seasonNumber", overview = null, air_date = null, episode_count = 1, poster_path = d?.posterPath, episodes = null))''',
     'getTvSeriesEpisodes': '''        val d = findById(id.toString())
        val ep = d?.copy(id = id, type = "episode", seriesId = id, seasonNumber = seasonNumber, episodeNumber = 1, title = d.title)
        Response.success(listOfNotNull(ep))''',
     'getMyList': '''        val ids = getMyListIds()
        Response.success(ids.mapNotNull { id ->
            val d = findById(id.toString()) ?: return@mapNotNull null
            WatchlistItemDto(id = id, mediaId = id, userId = 1, media = d, addedAt = "")
        })''',
     'getMyListEntries': '''        Response.success(getMyListIds().map { MyListEntryDto(mediaId = it, addedAt = "") })''',
     'checkMyList': '''        Response.success(MyListCheckDto(inList = getMyListIds().contains(id.toIntOrNull() ?: -1)))''',
     'addToMyList': '''        val ids = getMyListIds(); id.toIntOrNull()?.let { ids.add(it) }; saveMyListIds(ids)
        Response.success(Unit)''',
     'removeFromMyList': '''        val ids = getMyListIds(); ids.remove(id.toIntOrNull() ?: -1); saveMyListIds(ids)
        Response.success(Unit)''',
     'getRecentlyWatchedWithProgress': '''        val ids = prefs.getStringSet(KEY_PROGRESS, emptySet()) ?: emptySet()
        val out = ids.mapNotNull { raw ->
            val parts = raw.split(":"); val d = findById(parts[0]) ?: return@mapNotNull null
            RecentlyWatchedItemDto(
                id = d.id, mediaId = d.id, userId = "1",
                lastWatchedAt = "2026-01-01T00:00:00Z",
                progressSeconds = parts.getOrNull(1)?.toLongOrNull() ?: 0,
                durationSeconds = 0, media = d)
        }
        Response.success(out)''',
     'getRecentlyWatched': '''        Response.success(loadAll().take(10))''',
     'getContinueWatching': '''        Response.success(loadAll().take(10))''',
     'liveChannels': '''        val chans = (1..12).map { i ->
            LiveChannel(id = "ch$i", name = "频道 $i", number = i, status = "online", categories = listOf("General"), country = "CN")
        }
        Response.success(LiveLineup(enabled = true, channels = chans))''',
     'livePlay': '''        val n = id.removePrefix("ch").toIntOrNull() ?: 1
        Response.success(LivePlay(urls = listOf(
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")))''',
    }
    return impls.get(name, 'Response.success(null)')

out=[]
out.append('''package com.homeflix.tv.data.remote.api

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
        private const val API = "https://taopianapi.com/cjapi/mc/vod/json.html"
        private const val PREFS = "vod_adapter_state"
        private const val KEY_LIST = "mylist"
        private const val KEY_PROGRESS = "progress"
        private val GSON = Gson()
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cache = mutableMapOf<Int, MediaDto>()
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
    private fun toDto(v: JsonObject): MediaDto? {
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
        cache[id] = dto
        return dto
    }

    private fun JsonObject.optString(key: String): String =
        runCatching { get(key)?.takeUnless { it.isJsonNull }?.asString ?: "" }.getOrDefault("")

    private fun loadAll(force: Boolean = false): List<MediaDto> {
        allCache?.let { if (!force && it.isNotEmpty()) return it }
        val out = mutableListOf<MediaDto>()
        for (pg in 1..6) {
            val list = vodList("$API?ac=list&pg=$pg")
            if (list.isEmpty()) break
            list.forEach { toDto(it)?.let { d -> out.add(d) } }
            if (out.size >= 240) break
        }
        allCache = out
        return out
    }

    private fun findById(id: String): MediaDto? {
        val iid = id.toIntOrNull() ?: return null
        cache[iid]?.let { return it }
        val root = json("$API?ac=detail&ids=$iid") ?: return null
        val arr = root.getAsJsonArray("list") ?: return null
        val v = arr.firstOrNull()?.asJsonObject ?: return null
        return toDto(v)
    }

    // ---------- persisted lightweight state ----------
    private fun getMyListIds(): MutableSet<Int> =
        (prefs.getStringSet(KEY_LIST, emptySet()) ?: emptySet()).mapNotNull { it.toIntOrNull() }.toMutableSet()
    private fun saveMyListIds(ids: Set<Int>) =
        prefs.edit().putStringSet(KEY_LIST, ids.map { it.toString() }.toSet()).apply()
''')

for name,params,ret in methods:
    p=clean_params(params)
    ret=ret.strip()
    if name in core:
        impl=core_impl(name)
        out.append(f'\n    override suspend fun {name}({p}): {ret} = withContext(Dispatchers.IO) {{\n{impl}\n    }}')
    else:
        out.append(f'\n    override suspend fun {name}({p}): {ret} = {default_for(ret)}')

out.append('\n}\n')
open('app/src/main/java/com/homeflix/tv/data/remote/api/VodApiAdapter.kt','w',encoding='utf-8').write('\n'.join(out))
print('generated',len(methods),'methods')
