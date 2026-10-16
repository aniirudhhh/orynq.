package com.theveloper.pixelplay.data.youtube

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.theveloper.pixelplay.BuildConfig
import timber.log.Timber
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.*
import com.metrolist.innertubex.models.YouTubeClient
import com.theveloper.pixelplay.data.model.Song
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class OnlineTrack(val videoId: String, val title: String, val artist: String, val artwork: String?, val durationMs: Long = 0, val album: String? = null) {
    fun asSong() = Song(
        id = "youtube:$videoId", title = title, artist = artist, artistId = -1,
        album = album ?: "YouTube Music", albumId = -1, path = "", contentUriString = "youtube://$videoId",
        albumArtUriString = artwork?.let(::highResolutionYouTubeArtwork), duration = durationMs, mimeType = null, bitrate = null, sampleRate = null,
    )
}

/** Resize only known YouTube CDN transforms; preserve unrelated URLs and video thumbnails. */
internal fun highResolutionYouTubeArtwork(url: String): String {
    val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return url
    if (host == "i.ytimg.com") {
        val uri = java.net.URI(url)
        if (!uri.rawQuery.isNullOrEmpty()) return url // Preserve signed Music crop/resize parameters.
        if (Regex("/vi/[A-Za-z0-9_-]{11}/(?:default|mqdefault|hqdefault|sddefault|hq720|maxresdefault)\\.jpg").matches(uri.path)) {
            return "https://i.ytimg.com${uri.path.substringBeforeLast('/')}/maxresdefault.jpg"
        }
        return url
    }
    if (host !in setOf("lh3.googleusercontent.com", "yt3.googleusercontent.com", "yt3.ggpht.com")) return url
    return url.replace(Regex("=w\\d+-h\\d+"), "=w1200-h1200")
        .replace(Regex("=s\\d+"), "=s1200")
}

/** All private YouTube protocols stay behind this boundary; the UI never retains signed URLs. */
@Singleton
class YouTubeRepository @Inject constructor(@ApplicationContext context: Context) {
    private val http = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 25_000; connectTimeoutMillis = 10_000 }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        followRedirects = false
    }
    private val logger = InnerTubeLogger { event ->
        if (BuildConfig.DEBUG) {
            val safeDetails = event.details.filter { (key, value) ->
                key in setOf("clientName", "profileId", "outcome", "exceptionType", "status", "reasonCode") &&
                    Regex("[A-Za-z0-9_.:-]{1,80}").matches(value)
            }
            Timber.tag("YouTubeProvider").d("%s %s %s", event.tag, event.level.name, safeDetails)
        }
    }
    private val client = InnerTube(httpClient = http, logger = logger)
    private val config = RemotePlayerConfigStore(http, PlayerConfigRepository.disabled(), logger = logger)
    private val cipher = YouTubeCipherService(http, config, logger = logger)
    private val extractor = InnerTubeExtractor(
        configParser = YtConfigParserImpl(http, client, config),
        cipherService = cipher, innerTube = client, logger = logger,
        fallbackStrategy = MusicFirstClientStrategy(),
        tokenProvider = YouTubeWebTokenProvider(context, http),
    )
    private val sessionStore = YouTubeSessionStore(context)
    private val accountLock = Any()
    private var accountGeneration = 0L
    private var accountClient: InnerTube? = null
    private val accountPreferences = context.getSharedPreferences("youtube_account_home", Context.MODE_PRIVATE)
    private val mutableAccount = MutableStateFlow<MusicAccount?>(null)
    val account = mutableAccount.asStateFlow()
    private val mutableHomeCacheNamespace = MutableStateFlow("anonymous")
    val homeCacheNamespace = mutableHomeCacheNamespace.asStateFlow()
    init {
        sessionStore.read()?.let { stored ->
            accountClient = newAccountClient(stored.cookie, stored.authUser)
            mutableAccount.value = MusicAccount(stored.name, stored.avatar)
            val namespace = accountPreferences.getString("home_cache_namespace", null) ?: java.util.UUID.randomUUID().toString().also {
                accountPreferences.edit().putString("home_cache_namespace", it).apply()
            }
            mutableHomeCacheNamespace.value = namespace
        }
    }
    private fun newAccountClient(cookie: String, authUser: String) = InnerTube(httpClient = http, logger = logger).apply {
        this.cookie = cookie
        this.authUser = authUser
        useLoginForBrowse = true
    }
    suspend fun connectAccount(input: String, authUser: String = "0") = withContext(Dispatchers.IO) {
        require(Regex("[0-9]").matches(authUser)) { "Account index must be 0�9." }
        val cookie = parseMusicSessionCookie(input)
        val generation = synchronized(accountLock) { ++accountGeneration }
        val candidate = newAccountClient(cookie, authUser)
        val response = candidate.accountMenu(YouTubeClient.WEB_REMIX)
        require(response.status.value == 200) { "YouTube Music rejected this session. Please sign in again." }
        val verified = parseMusicAccount(json.parseToJsonElement(response.bodyAsText()))
            ?: throw IllegalArgumentException("No signed-in account found. Check the session and account index.")
        synchronized(accountLock) {
            check(generation == accountGeneration) { "Connection cancelled." }
            sessionStore.write(StoredMusicSession(cookie, authUser, verified.name, verified.avatar))
            accountPreferences.edit().clear().commit()
            accountClient = candidate
            val namespace = java.util.UUID.randomUUID().toString()
            accountPreferences.edit().putString("home_cache_namespace", namespace).commit()
            mutableHomeCacheNamespace.value = namespace
            mutableAccount.value = verified
        }
    }
    fun disconnectAccount() = synchronized(accountLock) {
        ++accountGeneration
        accountClient?.cookie = null
        accountClient = null
        sessionStore.clear()
        accountPreferences.edit().clear().commit()
        mutableHomeCacheNamespace.value = "anonymous"
        mutableAccount.value = null
    }
    private fun homePreferences() = if (accountClient != null) accountPreferences else preferences
    private val json = Json { ignoreUnknownKeys = true }
    private val preferences = context.getSharedPreferences("youtube_library", Context.MODE_PRIVATE)
    private fun readTracks(key: String): List<OnlineTrack> = runCatching {
        json.decodeFromString<List<OnlineTrack>>(preferences.getString(key, "[]").orEmpty())
    }.getOrDefault(emptyList())
    @Synchronized
    fun rememberOnlineSong(song: Song) {
        val id = song.id.removePrefix("youtube:")
        if (!song.id.startsWith("youtube:") || !Regex("[A-Za-z0-9_-]{11}").matches(id)) return
        val track = OnlineTrack(id, song.title, song.artist, song.albumArtUriString, song.duration, song.album.takeUnless { it.isBlank() || it == "YouTube Music" })
        val previous = readTracks("listened_metadata")
        if (previous.firstOrNull { it.videoId == id } == track) return
        preferences.edit().putString("listened_metadata", json.encodeToString(
            (listOf(track) + previous.filterNot { it.videoId == id }).take(500)
        )).apply()
    }

    fun knownOnlineTracks(): List<OnlineTrack> =
        (readTracks("listened_metadata") + playlistTracks.value + savedTracks.value + cachedHome()).distinctBy { it.videoId }

    private val mutablePlaylistTracks = MutableStateFlow(readTracks("playlist_tracks"))
    val playlistTracks = mutablePlaylistTracks.asStateFlow()
    @Synchronized
    fun retainPlaylistSong(song: Song) {
        val id = onlineVideoId(song) ?: return
        val track = OnlineTrack(id, song.title, song.artist, song.albumArtUriString, song.duration, song.album.takeUnless { it.isBlank() || it == "YouTube Music" })
        val next = listOf(track) + mutablePlaylistTracks.value.filterNot { it.videoId == id }
        preferences.edit().putString("playlist_tracks", json.encodeToString(next)).apply()
        mutablePlaylistTracks.value = next
    }

    private val mutableSaved = MutableStateFlow(readTracks("saved"))
    val savedTracks = mutableSaved.asStateFlow()
    private val mutableRecentSearches = MutableStateFlow(runCatching {
        json.decodeFromString<List<String>>(preferences.getString("recent_searches", "[]").orEmpty())
    }.getOrDefault(emptyList()))
    val recentSearches = mutableRecentSearches.asStateFlow()
    fun rememberSearch(query: String) {
        val term = query.trim().take(120)
        if (term.isEmpty()) return
        val next = (listOf(term) + mutableRecentSearches.value.filterNot { it.equals(term, ignoreCase = true) }).take(8)
        mutableRecentSearches.value = next
        preferences.edit().putString("recent_searches", json.encodeToString(next)).apply()
    }
    fun clearRecentSearches() {
        mutableRecentSearches.value = emptyList()
        preferences.edit().remove("recent_searches").apply()
    }
    fun cachedHome(): List<OnlineTrack> = synchronized(accountLock) {
        runCatching { json.decodeFromString<List<OnlineTrack>>(homePreferences().getString("home", "[]").orEmpty()) }.getOrDefault(emptyList())
    }
    fun cachedHomeFeed(): OnlineHomeFeed = synchronized(accountLock) {
        runCatching { json.decodeFromString<OnlineHomeFeed>(homePreferences().getString("home_feed", "{}").orEmpty()) }
            .getOrDefault(OnlineHomeFeed()).let { if (it.shelves.isEmpty() && it.tracks.isEmpty()) it.copy(tracks = cachedHome()) else it }
    }
    internal fun legacyHomeSnapshot(): HomeFeedSnapshot? = synchronized(accountLock) {
        cachedHomeFeed().takeIf { it.isUsable() }?.let {
            HomeFeedSnapshot(listOf(it), homePreferences().getLong("home_refreshed_at", 0L))
        }
    }
    fun homeNeedsRefresh(): Boolean {
        val age = System.currentTimeMillis() - homePreferences().getLong("home_refreshed_at", 0L)
        return homePreferences().getInt("home_feed_version", 0) != 2 || cachedHomeFeed().shelves.isEmpty() || age < 0L || age >= 30 * 60_000L
    }
    fun toggleSaved(track: OnlineTrack) {
        val tracks = mutableSaved.value
        val next = if (tracks.any { it.videoId == track.videoId }) tracks.filterNot { it.videoId == track.videoId }
            else listOf(track) + tracks
        mutableSaved.value = next
        preferences.edit().putString("saved", json.encodeToString(next)).apply()
    }
    private val mutex = Mutex()
    private val streams = linkedMapOf<String, ExtractedStream>()
    private val rejectedClients = linkedMapOf<String, Pair<Long, Set<String>>>()

    suspend fun rejectStream(videoId: String, stream: ExtractedStream) = mutex.withLock {
        streams.remove(videoId)
        val previous = rejectedClients[videoId]?.takeIf { it.first > System.currentTimeMillis() }?.second.orEmpty()
        if (rejectedClients.size >= 32 && videoId !in rejectedClients) rejectedClients.remove(rejectedClients.keys.first())
        rejectedClients[videoId] = (System.currentTimeMillis() + 5 * 60_000) to
            (previous + listOfNotNull(stream.clientName, stream.profileId))
    }

    suspend fun home(): List<OnlineTrack> = homeFeed().tracks

    suspend fun homeFeed(): OnlineHomeFeed = withContext(Dispatchers.IO) {
        val (generation, signedInClient) = synchronized(accountLock) { accountGeneration to accountClient }
        if (signedInClient != null) {
            val response = signedInClient.accountMenu(YouTubeClient.WEB_REMIX)
            if (response.status.value in setOf(401, 403) ||
                (response.status.value == 200 && parseMusicAccount(json.parseToJsonElement(response.bodyAsText())) == null)) {
                synchronized(accountLock) {
                    if (generation == accountGeneration) mutableAccount.value = mutableAccount.value?.copy(needsReconnect = true)
                }
                throw IllegalStateException("YouTube Music connection expired. Reconnect your account.")
            }
            check(response.status.value == 200) { "YouTube Music is temporarily unavailable." }
        }
        val homeResponse = (signedInClient ?: client).browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_home", setLogin = signedInClient != null)
        check(homeResponse.status.value == 200) { "YouTube Music is temporarily unavailable." }
        val parsed = OnlineHomeFeedParser.parse(json.parseToJsonElement(homeResponse.bodyAsText()), homePage = true)
        val feed = parsed
        synchronized(accountLock) {
            check(generation == accountGeneration) { "Account changed during feed refresh." }
            if (feed.shelves.isNotEmpty() || feed.tracks.isNotEmpty()) homePreferences().edit()
                .putString("home_feed", json.encodeToString(feed)).putString("home", json.encodeToString(feed.tracks))
                .putInt("home_feed_version", 2)
                .putLong("home_refreshed_at", System.currentTimeMillis()).apply()
        }
        feed
    }

    internal suspend fun enrichHomeFeed(parsed: OnlineHomeFeed): OnlineHomeFeed {
        val tracks = parsed.tracks.withMusicArtwork()
        val covers = tracks.associateBy { it.videoId }
        return parsed.copy(tracks = tracks, shelves = parsed.shelves.map { shelf ->
            shelf.copy(items = shelf.items.map { item ->
                item.track?.let { covers[it.videoId] }?.let { item.copy(track = it, artwork = it.artwork) } ?: item
            })
        })
    }

    internal suspend fun homePage(continuation: String): OnlineHomeFeed = withContext(Dispatchers.IO) {
        val (generation, signedInClient) = synchronized(accountLock) { accountGeneration to accountClient }
        val response = (signedInClient ?: client).browse(YouTubeClient.WEB_REMIX,
            continuation = continuation, setLogin = signedInClient != null)
        check(response.status.value == 200) { "Couldn't load more recommendations." }
        val page = OnlineHomeFeedParser.parse(json.parseToJsonElement(response.bodyAsText()), homePage = true)
        synchronized(accountLock) { check(generation == accountGeneration) { "Account changed during feed refresh." } }
        page
    }

    suspend fun moreHome(continuation: String): OnlineHomeFeed = withContext(Dispatchers.IO) {
        val (generation, signedInClient) = synchronized(accountLock) { accountGeneration to accountClient }
        val response = (signedInClient ?: client).browse(YouTubeClient.WEB_REMIX,
            continuation = continuation, setLogin = signedInClient != null)
        check(response.status.value == 200) { "Couldn't load more recommendations." }
        val page = enrichHomeFeed(OnlineHomeFeedParser.parse(json.parseToJsonElement(response.bodyAsText()), homePage = true))
        synchronized(accountLock) {
            check(generation == accountGeneration) { "Account changed during feed refresh." }
            val merged = appendCollectionPage(cachedHomeFeed(), page)
            homePreferences().edit().putString("home_feed", json.encodeToString(merged))
                .putString("home", json.encodeToString(merged.tracks)).apply()
            merged
        }
    }

    suspend fun browseCollection(item: HomeFeedItem, continuation: String? = null): OnlineHomeFeed = withContext(Dispatchers.IO) {
        val id = requireNotNull(item.browseId ?: item.playlistId)
        require(id.length in 1..512 && id.none(Char::isISOControl))
        val (generation, signedInClient) = synchronized(accountLock) { accountGeneration to accountClient }
        val provider = signedInClient ?: client
        val response = if (item.playlistId != null && continuation == null) {
            provider.next(YouTubeClient.WEB_REMIX, playlistId = item.playlistId)
        } else provider.browse(YouTubeClient.WEB_REMIX, browseId = if (continuation == null) id else null,
            params = if (continuation == null) item.params else null, continuation = continuation, setLogin = signedInClient != null)
        check(response.status.value == 200) { "Couldn't open this collection." }
        val root = json.parseToJsonElement(response.bodyAsText())
        val feed = if (item.browseId?.let { it.startsWith("UC") || it.startsWith("MPLA") } == true) OnlineHomeFeedParser.parse(root)
            else OnlineHomeFeedParser.parseCollection(root)
        synchronized(accountLock) { check(generation == accountGeneration) { "Account changed during browse." } }
        feed
    }

    internal suspend fun reportListen(event: PendingListen, beforeSend: suspend () -> Boolean): ListenReport = withContext(Dispatchers.IO) {
        val (generation, provider) = synchronized(accountLock) { accountGeneration to accountClient }
        fun current() = synchronized(accountLock) {
            generation == accountGeneration && accountClient === provider &&
                mutableHomeCacheNamespace.value == event.ticket.namespace
        }
        fun reconnect(): ListenReport {
            synchronized(accountLock) {
                if (generation == accountGeneration) mutableAccount.value = mutableAccount.value?.copy(needsReconnect = true)
            }
            return ListenReport.RECONNECT
        }
        if (provider == null || !current()) return@withContext ListenReport.UNAVAILABLE
        if (mutableAccount.value?.needsReconnect == true) return@withContext ListenReport.RECONNECT
        val session = provider.sessionSnapshot()
        if (!provider.hasSapCookieAuth(session)) return@withContext reconnect()
        var unauthorized = false
        val root = try {
            withTimeoutOrNull(20_000) {
                val response = provider.playerWithSessionBound(YouTubeClient.WEB_REMIX,
                    videoId = event.ticket.videoId, playlistId = null, signatureTimestamp = null,
                    poToken = null, requestVisitorData = null, requestSession = session)
                if (response.status.value in setOf(401, 403)) {
                    unauthorized = true
                    return@withTimeoutOrNull null
                }
                if (response.status.value == 429 || response.status.value >= 500) error("retry")
                check(response.status.value == 200)
                json.parseToJsonElement(response.bodyAsText())
            } ?: return@withContext if (unauthorized && current()) reconnect()
                else if (current()) ListenReport.RETRY else ListenReport.UNAVAILABLE
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withContext ListenReport.RETRY }
        val status = (root as? JsonObject)?.get("playabilityStatus") as? JsonObject
        if (status?.get("status")?.jsonPrimitive?.contentOrNull == "LOGIN_REQUIRED") return@withContext reconnect()
        val loggedOut = ((root as? JsonObject)?.get("responseContext") as? JsonObject)
            ?.get("mainAppWebResponseContext")?.jsonObject?.get("loggedOut")?.jsonPrimitive?.booleanOrNull
        if (loggedOut == true) return@withContext reconnect()
        val url = playbackHistoryUrl(root, event.ticket.videoId) ?: return@withContext ListenReport.UNAVAILABLE
        if (!current() || !beforeSend() || !current()) return@withContext ListenReport.UNAVAILABLE
        try {
            val response = withTimeoutOrNull(15_000) {
                provider.registerPlaybackWithSession(YouTubeClient.WEB_REMIX, url = url,
                    cpn = event.ticket.id.replace("-", "").take(16), playlistId = null, requestSession = session)
            } ?: return@withContext ListenReport.UNCONFIRMED
            when (response.status.value) {
                204 -> ListenReport.ACCEPTED
                401, 403 -> reconnect()
                else -> ListenReport.UNCONFIRMED
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ListenReport.UNCONFIRMED }
    }

    /** Fetch the lyrics tab belonging to this exact video, using the current account. */
    suspend fun songLyrics(videoId: String): String? = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId))
        val (generation, signedInClient) = synchronized(accountLock) { accountGeneration to accountClient }
        val provider = signedInClient ?: client
        val next = provider.next(YouTubeClient.WEB_REMIX, videoId = videoId)
        check(next.status.value == 200) { "Lyrics lookup failed." }
        val id = YouTubeLyricsParser.browseId(json.parseToJsonElement(next.bodyAsText()))
            ?: return@withContext null
        synchronized(accountLock) { check(generation == accountGeneration) { "Account changed." } }
        val response = provider.browse(YouTubeClient.WEB_REMIX, browseId = id, setLogin = signedInClient != null)
        check(response.status.value == 200) { "Lyrics lookup failed." }
        val lyrics = YouTubeLyricsParser.lyrics(json.parseToJsonElement(response.bodyAsText()))
        synchronized(accountLock) { check(generation == accountGeneration) { "Account changed." } }
        lyrics
    }

    suspend fun songRadio(videoId: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId))
        val (generation, signedInClient) = synchronized(accountLock) { accountGeneration to accountClient }
        val root = json.parseToJsonElement((signedInClient ?: client).next(
            YouTubeClient.WEB_REMIX, videoId = videoId, playlistId = "RDAMVM$videoId"
        ).bodyAsText())
        synchronized(accountLock) { check(generation == accountGeneration) { "Account changed during radio request." } }
        val tracks = parseSongRadio(root, videoId).withMusicArtwork()
        synchronized(accountLock) { check(generation == accountGeneration) { "Account changed during radio request." } }
        tracks
    }

    private val musicArtworkCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val artworkRequests = Semaphore(4)

    private suspend fun List<OnlineTrack>.withMusicArtwork(): List<OnlineTrack> {
        // Bound optional artwork work so missing album matches never block the feed indefinitely.
        val resolved = java.util.concurrent.ConcurrentHashMap<String, String>()
        forEach { track -> musicArtworkCache[track.videoId]?.let { resolved[track.videoId] = it } }
        withTimeoutOrNull(8_000L) {
            coroutineScope {
                this@withMusicArtwork.filter {
                    !resolved.containsKey(it.videoId) && it.artwork?.let(::isMusicAlbumArtwork) != true
                }.forEach { track ->
                    launch {
                        artworkRequests.withPermit {
                            try {
                                val cover = withTimeoutOrNull(3_000L) {
                                    matchingMusicArtwork(track, search("${track.title.substringBefore('|').trim()} ${track.artist}"))
                                }
                                if (cover != null) {
                                    if (musicArtworkCache.size >= 256) musicArtworkCache.clear()
                                    musicArtworkCache[track.videoId] = cover
                                    resolved[track.videoId] = cover
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* Keep the original thumbnail on lookup failure. */ }
                        }
                    }
                }
            }
        }
        return map { track -> resolved[track.videoId]?.let { track.copy(artwork = it) } ?: track }
    }

    suspend fun search(query: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        require(query.isNotBlank())
        OnlineCatalogParser.parse(json.parseToJsonElement(
            client.search(YouTubeClient.WEB_REMIX, query = query.trim(),
                params = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D", setLogin = false).bodyAsText()
        ), songsOnly = true).let { refineMusicSearchResults(query, it) }
    }

    suspend fun searchCatalog(query: String, filter: com.theveloper.pixelplay.data.model.SearchFilterType): OnlineSearchResults = withContext(Dispatchers.IO) {
        require(query.isNotBlank())
        val response = client.search(YouTubeClient.WEB_REMIX, query = query.trim(),
            params = musicSearchParams(filter), setLogin = false)
        check(response.status.value == 200) { "Search failed." }
        val results = parseMusicSearch(json.parseToJsonElement(response.bodyAsText()), filter)
        results.copy(tracks = refineMusicSearchResults(query, results.tracks))
    }

    private val infoMutex = Mutex()
    private val songInfoCache = linkedMapOf<String, OnlineSongMetadata>()

    suspend fun songInfo(videoId: String): OnlineSongMetadata = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId))
        infoMutex.withLock {
            val (generation, signedInClient) = synchronized(accountLock) { accountGeneration to accountClient }
            val key = "$generation:$videoId"
            val cachedInfo = songInfoCache[key]
            if (cachedInfo?.durationMs != null && cachedInfo.album != null) return@withLock cachedInfo
            val provider = signedInClient ?: client
            suspend fun fetch(playerDetails: Boolean): OnlineSongMetadata {
                return try {
                    withTimeoutOrNull(6_000) {
                        val response = if (playerDetails) provider.player(YouTubeClient.WEB_REMIX, videoId = videoId)
                            else provider.next(YouTubeClient.WEB_REMIX, videoId = videoId)
                        if (response.status.value == 200) parseOnlineSongMetadata(json.parseToJsonElement(response.bodyAsText()), videoId)
                        else OnlineSongMetadata()
                    } ?: OnlineSongMetadata()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { OnlineSongMetadata() }
            }
            var info = (cachedInfo ?: OnlineSongMetadata()).merge(fetch(false))
            if (info.durationMs == null) info = info.merge(fetch(true))
            synchronized(accountLock) { check(generation == accountGeneration) { "Account changed." } }
            if (info != OnlineSongMetadata()) {
                if (songInfoCache.size >= 64) songInfoCache.remove(songInfoCache.keys.first())
                songInfoCache[key] = info
            }
            info
        }
    }

    suspend fun stream(videoId: String, refresh: Boolean = false): ExtractedStream = mutex.withLock {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId)) { "Invalid YouTube track" }
        val cached = streams[videoId]
        val excluded = rejectedClients[videoId]?.takeIf { it.first > System.currentTimeMillis() }?.second.orEmpty()
        if (!refresh && cached != null && cached.expiresAt?.toEpochMilliseconds()?.let {
                it > System.currentTimeMillis() + 60_000
            } == true) return@withLock cached
        // The host currently handles progressive audio. Never pass SABR protocol URLs to ExoPlayer.
        val resolved = withTimeout(45_000) {
            extractor.extract(videoId, hints = ContentHints(wantVideo = false).withStreamCapabilities(
                allowHls = false, allowSabr = false, allowBoundedRange = true,
            ), audioQuality = AudioQuality.HIGH, excludedClients = excluded)
        } ?: error("No supported audio stream is available for this track")
        check(resolved.audioUrl.startsWith("https://")) { "Unsupported audio transport" }
        if (BuildConfig.DEBUG) Timber.tag("YouTubeAudio").d("client=%s codec=%s bitrate=%s sampleRate=%s itag=%s",
            resolved.clientName, resolved.codecs, resolved.bitrate, resolved.sampleRate, resolved.itag)
        if (streams.size >= 32) streams.remove(streams.keys.first())
        streams[videoId] = resolved
        resolved
    }
}

/** Keep server ranking; narrow clear title/artist matches without breaking typos or transliteration. */
internal fun refineMusicSearchResults(query: String, tracks: List<OnlineTrack>): List<OnlineTrack> {
    fun words(value: String) = java.text.Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.isNotBlank() }
    val terms = words(query)
    if (terms.isEmpty()) return tracks
    val matches = tracks.filter { track ->
        val metadata = words("${track.title} ${track.artist}")
        terms.all { term -> metadata.any { word -> word.startsWith(term) } }
    }
    return matches.ifEmpty { tracks }
}

/** Accept playable cards only; album/playlist browse IDs are not playable video IDs. */
internal fun parseSongRadio(root: JsonElement, seedId: String): List<OnlineTrack> {
    fun panel(node: JsonElement): JsonObject? = when (node) {
        is JsonObject -> (node["playlistPanelRenderer"] as? JsonObject)
            ?: node.values.firstNotNullOfOrNull(::panel)
        is JsonArray -> node.firstNotNullOfOrNull(::panel)
        else -> null
    }
    val contents = panel(root)?.get("contents") as? JsonArray ?: return emptyList()
    val songs = JsonArray(contents.filter { item ->
        val card = (item as? JsonObject)?.get("playlistPanelVideoRenderer") as? JsonObject ?: return@filter false
        val type = card["musicVideoType"]?.jsonPrimitive?.contentOrNull
        type == null || type in setOf("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV")
    })
    return OnlineCatalogParser.parse(songs).filterNot { it.videoId == seedId }.take(40)
}

internal object OnlineCatalogParser {
    private val idPattern = Regex("[A-Za-z0-9_-]{11}")
    fun parse(root: JsonElement, songsOnly: Boolean = false): List<OnlineTrack> {
        val tracks = linkedMapOf<String, OnlineTrack>()
        fun visit(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    for (key in listOf("musicResponsiveListItemRenderer", "musicTwoRowItemRenderer", "videoRenderer", "playlistPanelVideoRenderer")) {
                        val card = node[key] as? JsonObject ?: continue
                        if (songsOnly && key != "musicResponsiveListItemRenderer") continue
                        val musicType = firstValue(card, "musicVideoType")
                        if (songsOnly && musicType != null && musicType != "MUSIC_VIDEO_TYPE_ATV") continue
                        val identity = card["playlistItemData"] ?: card["navigationEndpoint"] ?: card["overlay"]
                        val id = ((card["videoId"] as? JsonPrimitive)?.contentOrNull ?: identity?.let { firstValue(it, "videoId") }
                            ?: if (!songsOnly) firstValue(card, "videoId") else null)
                            ?.takeIf(idPattern::matches) ?: continue
                        val columns = card["flexColumns"] as? JsonArray
                        val titleNode = card["title"] ?: columns?.firstOrNull()
                        val title = text(titleNode).ifBlank { text(card["headline"]) }
                        if (title.isBlank()) continue
                        val artist = artistNames(card).joinToString(", ").ifBlank {
                            text(card["subtitle"] ?: card["longBylineText"] ?: columns?.getOrNull(1)).ifBlank { "YouTube Music" }
                        }
                        val duration = listOf(card["lengthText"], card["fixedColumns"], card["subtitle"], columns?.lastOrNull())
                            .firstNotNullOfOrNull { musicDurationMs(text(it)) } ?: 0
                        tracks.putIfAbsent(id, OnlineTrack(id, title, artist, artwork(card["thumbnail"] ?: card), duration, musicAlbum(card)))
                    }
                    node.values.forEach(::visit)
                }
                is JsonArray -> node.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return tracks.values.take(80)
    }
    private fun artistNames(root: JsonElement): List<String> {
        val names = linkedSetOf<String>()
        fun visit(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    val endpoint = node["navigationEndpoint"] as? JsonObject
                    val browse = endpoint?.get("browseEndpoint") as? JsonObject
                    val id = (browse?.get("browseId") as? JsonPrimitive)?.contentOrNull
                    if (id?.startsWith("UC") == true) {
                        (node["text"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let(names::add)
                    }
                    node.values.forEach(::visit)
                }
                is JsonArray -> node.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return names.toList()
    }
    internal fun artwork(node: JsonElement): String? = when (node) {
        is JsonObject -> (node["thumbnails"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.maxWithOrNull(compareBy<JsonObject>(
                { (it["url"] as? JsonPrimitive)?.contentOrNull?.let(::isMusicAlbumArtwork) == true },
                { val width = (it["width"] as? JsonPrimitive)?.intOrNull ?: 0
                  val height = (it["height"] as? JsonPrimitive)?.intOrNull ?: 0
                  width > 0 && height > 0 && kotlin.math.abs(width - height) <= width / 10 },
                { (it["width"] as? JsonPrimitive)?.intOrNull ?: 0 }
            ))
            ?.let { (it["url"] as? JsonPrimitive)?.contentOrNull }
            ?: node.values.firstNotNullOfOrNull(::artwork)
        is JsonArray -> node.firstNotNullOfOrNull(::artwork)
        else -> null
    }?.takeIf { it.startsWith("https://") }
    internal fun firstValue(node: JsonElement, key: String): String? = when (node) {
        is JsonObject -> (node[key] as? JsonPrimitive)?.contentOrNull
            ?: node.values.firstNotNullOfOrNull { firstValue(it, key) }
        is JsonArray -> node.firstNotNullOfOrNull { firstValue(it, key) }
        else -> null
    }
    internal fun text(node: JsonElement?): String = when (node) {
        is JsonObject -> (node["simpleText"] as? JsonPrimitive)?.contentOrNull
            ?: (node["runs"] as? JsonArray)?.joinToString("") { (it.jsonObject["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
            ?: node.values.firstNotNullOfOrNull { text(it).takeIf(String::isNotBlank) }.orEmpty()
        is JsonArray -> node.firstNotNullOfOrNull { text(it).takeIf(String::isNotBlank) }.orEmpty()
        else -> ""
    }
}
