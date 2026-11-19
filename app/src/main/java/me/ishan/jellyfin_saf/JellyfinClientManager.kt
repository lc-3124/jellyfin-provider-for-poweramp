package me.ishan.jellyfin_saf

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.os.Build
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ishan.jellyfin_saf.db.AppDatabase
import me.ishan.jellyfin_saf.db.TrackDao
import me.ishan.jellyfin_saf.db.TrackEntity
import me.ishan.jellyfin_saf.db.TrackLyricsEntity
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.lyricsApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.io.File
import java.nio.channels.FileChannel
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.Path
import kotlin.io.path.extension

class JellyfinClientManager(private val context: Context? = null) {
	private val cryptoManager = CryptoManager()
	
	private val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
		return@addInterceptor chain.proceed(
			chain.request().newBuilder().header(
				"User-Agent",
				"${context?.getString(R.string.app_name)}/${context?.getString(R.string.app_version)} via okhttp/${okhttp3.OkHttp.VERSION}"
			).build()
		)
	}.connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
		.writeTimeout(15, TimeUnit.SECONDS).connectionPool(
			ConnectionPool(
				maxIdleConnections = 5, keepAliveDuration = 5, TimeUnit.MINUTES
			)
		).retryOnConnectionFailure(true).build()
	
	private val prefs: SharedPreferences? =
		context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	
	private val db = context?.let { AppDatabase.getDatabase(it) }
	private var jellyfin: Jellyfin? = null
	private var api: ApiClient? = null
	private var currentUserId: UUID? = null
	
	/** 根目录会被播放器反复查询，短缓存避免每次都打服务器。 */
	@Volatile
	private var albumsCache: Pair<Long, List<BaseItemDto>>? = null
	
	// Credentials
	private var serverUrl: String = ""
	private var username: String = ""
	private var password: String = ""
	private var accessToken: String? = null
	
	init {
		loadCredentials()
		initializeJellyfin()
	}
	
	private fun initializeJellyfin() {
		context.let { ctx ->
			jellyfin = createJellyfin {
				context = ctx
				clientInfo = ClientInfo(
					name = context?.getString(R.string.app_name) ?: "Android Jellyfin Provider",
					version = context?.getString(R.string.app_version) ?: "0.0.1"
				)
				deviceInfo = DeviceInfo(
					id = getDeviceId(), name = Build.MODEL
				)
			}
		}
	}
	
	private fun loadCredentials() {
		prefs?.let { p ->
			serverUrl = p.getString(KEY_SERVER_URL, "") ?: ""
			username = p.getString(KEY_USERNAME, "") ?: ""
			
			// Decrypt sensitive data
			val encryptedPassword = p.getString(KEY_PASSWORD, null)
			password = if (!encryptedPassword.isNullOrEmpty()) {
				try {
					cryptoManager.decrypt(encryptedPassword)
				} catch (e: Exception) {
					""
				}
			} else ""
			
			val encryptedToken = p.getString(KEY_ACCESS_TOKEN, null)
			accessToken = if (!encryptedToken.isNullOrEmpty()) {
				try {
					cryptoManager.decrypt(encryptedToken)
				} catch (e: Exception) {
					null
				}
			} else null
			
			currentUserId = p.getString(KEY_USER_ID, null)?.let { UUID.fromString(it) }
		}
	}
	
	fun getUrl(): String = serverUrl
	fun getUsername(): String = username
	fun getPassword(): String = password
	fun getUserId(): UUID? = currentUserId
	fun getAccessToken(): String? = accessToken
	fun isAuthenticated(): Boolean {
		if (accessToken == null || currentUserId == null) {
			loadCredentials()
		}
		return accessToken != null && currentUserId != null
	}
	
	fun getQuality(): AudioQuality =
		prefs?.getString(KEY_QUALITY, null)?.let { stored ->
			runCatching { AudioQuality.valueOf(stored) }.getOrNull()
		} ?: AudioQuality.LOSSLESS
	
	fun setQuality(quality: AudioQuality) {
		prefs?.edit { putString(KEY_QUALITY, quality.name) }
	}
	
	fun getMaxCacheSize(): Long = prefs?.getLong(KEY_MAX_CACHE_SIZE, 2048) ?: 2048
	
	fun setMaxCacheSize(sizeMB: Long) {
		prefs?.edit { putLong(KEY_MAX_CACHE_SIZE, sizeMB) }
	}
	
	fun updateCredentials(url: String, user: String, pass: String) {
		val url = if (!url.startsWith("http://") && !url.startsWith("https://")) {
			"http://$url"
		} else {
			url
		}
		
		serverUrl = url.trimEnd('/')
		username = user
		password = pass
	}
	
	private fun persistCredentials() {
		prefs?.edit {
			putString(KEY_SERVER_URL, serverUrl)
			putString(KEY_USERNAME, username)
			
			// Encrypt password
			val encryptedPassword = try {
				cryptoManager.encrypt(password)
			} catch (e: Exception) {
				""
			}
			putString(KEY_PASSWORD, encryptedPassword)
			
			// Encrypt token
			val encryptedToken = try {
				accessToken?.let { cryptoManager.encrypt(it) }
			} catch (e: Exception) {
				null
			}
			putString(KEY_ACCESS_TOKEN, encryptedToken)
			
			putString(KEY_USER_ID, currentUserId.toString())
		}
	}
	
	/**
	 * Authenticate with Jellyfin server
	 */
	suspend fun login(): Boolean = withContext(Dispatchers.IO) {
		if (serverUrl.isEmpty() || username.isEmpty()) {
			Log.e(TAG, "Server URL or username is empty")
			return@withContext false
		}
		
		try {
			val jellyfinInstance = jellyfin ?: run {
				Log.e(TAG, "Jellyfin not initialized")
				return@withContext false
			}
			
			api = jellyfinInstance.createApi(baseUrl = serverUrl)
			
			// Authenticate using SDK
			val authResult = api!!.userApi.authenticateUserByName(
				username = username, password = password
			)
			
			val token = authResult.content.accessToken
			val userId = authResult.content.user?.id
			
			if (token == null || userId == null) {
				Log.e(TAG, "Authentication failed: no token or user ID")
				return@withContext false
			}
			
			// Update API with token
			api!!.update(accessToken = token)
			
			// Save credentials
			accessToken = token
			currentUserId = userId
			albumsCache = null
			
			persistCredentials()
			
			Log.d(TAG, "Login successful: userId=$currentUserId")
			
			// TODO: notify documents provider
			true
			
		} catch (e: Exception) {
			Log.e(TAG, "Login failed", e)
			false
		}
	}
	
	/**
	 * 把收藏状态写到 Jellyfin 并更新本地库。
	 */
	suspend fun setFavourite(trackId: UUID, favourite: Boolean): Boolean =
		withContext(Dispatchers.IO) {
			val apiClient = getApiClient() ?: return@withContext false
			val userId = currentUserId ?: return@withContext false
			try {
				if (favourite) {
					apiClient.userLibraryApi.markFavoriteItem(itemId = trackId, userId = userId)
				} else {
					apiClient.userLibraryApi.unmarkFavoriteItem(itemId = trackId, userId = userId)
				}
				db?.trackDao()?.getTrackById(trackId)?.let { track ->
					db.trackDao().insertTrack(track.copy(isFavourite = favourite))
				}
				Log.d(TAG, "Favourite=$favourite for $trackId")
				true
			} catch (e: Exception) {
				Log.e(TAG, "Failed to set favourite=$favourite for $trackId", e)
				false
			}
		}
	
	/** 全部曲目元数据，建目录树时用来取专辑/标题。 */
	suspend fun getAllTrackInfo(): List<me.ishan.jellyfin_saf.db.TrackEntity> =
		withContext(Dispatchers.IO) {
			val db = context?.let { me.ishan.jellyfin_saf.db.AppDatabase.getDatabase(it) }
				?: return@withContext emptyList()
			db.trackDao().getAllTracks()
		}
	
	/** 全部曲目的 (id, 服务器路径)，用于重建物理目录树。 */
	suspend fun getAllTrackPaths(): List<Pair<UUID, String>> = withContext(Dispatchers.IO) {
		val apiClient = getApiClient() ?: return@withContext emptyList()
		val userId = currentUserId ?: return@withContext emptyList()
		val result = ArrayList<Pair<UUID, String>>()
		var start = 0
		val pageSize = 500
		try {
			while (true) {
				val page = apiClient.itemsApi.getItems(
					userId = userId,
					includeItemTypes = setOf(BaseItemKind.AUDIO),
					recursive = true,
					fields = setOf(ItemFields.PATH),
					limit = pageSize,
					startIndex = start
				)
				val items = page.content.items
				items.forEach { item ->
					val path = item.path ?: item.mediaSources?.firstOrNull()?.path
					if (!path.isNullOrBlank()) result.add(item.id to path)
				}
				if (items.size < pageSize) break
				start += pageSize
			}
		} catch (e: Exception) {
			Log.e(TAG, "Failed to fetch track paths", e)
		}
		result
	}
	
	/** 远端收藏的全部曲目 id（分页拉取）。 */
	suspend fun getFavouriteTrackIds(): List<UUID> = withContext(Dispatchers.IO) {
		val apiClient = getApiClient() ?: return@withContext emptyList()
		val userId = currentUserId ?: return@withContext emptyList()
		val result = ArrayList<UUID>()
		var start = 0
		val pageSize = 500
		try {
			while (true) {
				val page = apiClient.itemsApi.getItems(
					userId = userId,
					includeItemTypes = setOf(BaseItemKind.AUDIO),
					recursive = true,
					filters = setOf(ItemFilter.IS_FAVORITE),
					limit = pageSize,
					startIndex = start
				)
				val items = page.content.items
				items.forEach { result.add(it.id) }
				if (items.size < pageSize) break
				start += pageSize
			}
		} catch (e: Exception) {
			Log.e(TAG, "Failed to fetch favourite tracks", e)
		}
		result
	}
	
	/**
	 * Logout and clear credentials
	 */
	/** 只清空密码，保留服务器地址和用户名方便重新登录。 */
	fun clearStoredPassword() {
		password = ""
		prefs?.edit { remove(KEY_PASSWORD) }
	}
	
	suspend fun logout() {
		// invalidate the access token on remote server
		getApiClient()?.sessionApi?.reportSessionEnded()
		
		accessToken = null
		currentUserId = null
		albumsCache = null
		
		prefs?.edit {
			remove(KEY_ACCESS_TOKEN)
			remove(KEY_USER_ID)
		}
	}
	
	/**
	 * Get authenticated API client
	 */
	fun getApiClient(): ApiClient? {
		if (api == null && isAuthenticated()) {
			initializeApi()
		}
		
		if (!isAuthenticated()) {
			Log.w(TAG, "Not authenticated")
			return null
		}
		return api
	}
	
	private fun initializeApi() {
		if (serverUrl.isEmpty() || accessToken.isNullOrEmpty()) return
		
		try {
			val jellyfinInstance = jellyfin ?: return
			api = jellyfinInstance.createApi(baseUrl = serverUrl)
			api!!.update(accessToken = accessToken!!)
			Log.d(TAG, "API re-initialized from stored credentials")
		} catch (e: Exception) {
			Log.e(TAG, "Failed to re-initialize API", e)
		}
	}
	
	/**
	 * Test connection to server
	 */
	/** 访问一次 /System/Info/Public，返回往返毫秒；失败返回 null。 */
	suspend fun measureLatency(): Long? = withContext(Dispatchers.IO) {
		val base = getUrl().trim().trimEnd('/')
		if (base.isBlank()) return@withContext null
		
		try {
			val request = Request.Builder()
				.url("$base/System/Info/Public")
				.header("Accept", "application/json")
				.build()
			val started = System.nanoTime()
			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext null
			}
			(System.nanoTime() - started) / 1_000_000
		} catch (e: Exception) {
			Log.w(TAG, "Latency check failed", e)
			null
		}
	}
	
	suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
		if (serverUrl.isEmpty()) return@withContext false
		
		try {
			val jellyfinInstance = jellyfin ?: return@withContext false
			val testApi = jellyfinInstance.createApi(baseUrl = serverUrl)
			
			// Use SDK's system API
			val systemInfo = testApi.systemApi.getPublicSystemInfo()
			Log.i(TAG, "Server connection successful: ${systemInfo.content.serverName}")
			true
		} catch (e: Exception) {
			Log.e(TAG, "Connection test failed", e)
			false
		}
	}
	
	/**
	 * Get library stats (total albums, total tracks)
	 */
	suspend fun getLibraryStats(): Pair<Int, Int> = withContext(Dispatchers.IO) {
		val apiClient = getApiClient() ?: return@withContext 0 to 0
		val userId = currentUserId ?: return@withContext 0 to 0
		
		try {
			val albumsResult = apiClient.itemsApi.getItems(
				userId = userId,
				includeItemTypes = setOf(BaseItemKind.MUSIC_ALBUM),
				recursive = true,
				limit = 1
			)
			val tracksResult = apiClient.itemsApi.getItems(
				userId = userId,
				includeItemTypes = setOf(BaseItemKind.AUDIO),
				recursive = true,
				limit = 1
			)
			
			val totalAlbums = albumsResult.content.totalRecordCount
			val totalTracks = tracksResult.content.totalRecordCount
			
			totalAlbums to totalTracks
		} catch (e: Exception) {
			Log.e(TAG, "Failed to get library stats", e)
			0 to 0
		}
	}
	
	/**
	 * Get albums from library
	 */
	suspend fun getAlbums(
		limit: Int? = null, startIndex: Int? = null
	): List<BaseItemDto> = withContext(Dispatchers.IO) {
		val apiClient = getApiClient() ?: return@withContext emptyList()
		val userId = currentUserId ?: return@withContext emptyList()
		
		val useCache = startIndex == null && limit != null
		if (useCache) {
			albumsCache?.let { (timestamp, cachedAlbums) ->
				if (System.currentTimeMillis() - timestamp < ALBUMS_CACHE_TTL_MS) {
					return@withContext cachedAlbums
				}
			}
		}
		
		try {
			val result = apiClient.itemsApi.getItems(
				userId = userId,
				includeItemTypes = setOf(BaseItemKind.MUSIC_ALBUM),
				recursive = true,
				limit = limit,
				startIndex = startIndex,
				fields = setOf(ItemFields.GENRES)
			)
			
			val items = result.content.items
			if (useCache && items.isNotEmpty()) {
				albumsCache = System.currentTimeMillis() to items
			}
			items
		} catch (e: InvalidStatusException) {
			Log.w(TAG, "getAlbums limit=$limit startIndex=$startIndex received ${e.status} code")
			if (e.status == 401) {
				accessToken = null
			}
			emptyList()
		} catch (e: Exception) {
			Log.e(TAG, "Failed to get albums", e)
			emptyList()
		}
	}
	
	/**
	 * Get tracks in an album.
	 *
	 * [persist] = false 时只返回实体、不写库，供批量扫描使用（避免一首先一个事务）。
	 */
	suspend fun getAlbumTracks(
		albumId: UUID, persist: Boolean = true
	): List<TrackEntity> = withContext(Dispatchers.IO) {
		val apiClient = getApiClient() ?: return@withContext emptyList()
		val userId = currentUserId ?: return@withContext emptyList()
		
		try {
			// 有些合集会超过 100 首（比如 216 首的 Listen 10），必须翻页
			val tracks = ArrayList<TrackEntity>()
			var startIndex = 0
			while (true) {
				val result = apiClient.itemsApi.getItems(
					userId = userId,
					parentId = albumId,
					includeItemTypes = setOf(BaseItemKind.AUDIO),
					limit = ALBUM_PAGE_SIZE,
					startIndex = startIndex,
					fields = setOf(
						ItemFields.GENRES, ItemFields.MEDIA_SOURCES, ItemFields.DATE_CREATED
					),
					enableImages = false,
				)
				val items = result.content.items
				if (items.isEmpty()) break
				tracks += items.mapNotNull { toTrackEntity(it) }
				startIndex += items.size
				if (items.size < ALBUM_PAGE_SIZE) break
			}
			
			if (persist && tracks.isNotEmpty()) {
				db?.trackDao()?.insertTracks(tracks)
			}
			tracks
		} catch (e: InvalidStatusException) {
			Log.w(TAG, "getAlbumTracks $albumId received ${e.status} code")
			if (e.status == 401) {
				accessToken = null
			}
			emptyList()
		} catch (e: Exception) {
			Log.e(TAG, "Failed to get album tracks", e)
			emptyList()
		}
	}
	
	/** 扫描模式。AUTO 用于登录后的首次同步，界面上的入口显式传 FULL / INCREMENTAL。 */
	enum class ScanMode { AUTO, FULL, INCREMENTAL }
	
	/** 扫描结果。full=true 表示跑了一次全量，tracks 是库内总数。 */
	data class LibrarySyncResult(
		val full: Boolean,
		val tracks: Int,
		val albums: Int,
		val removed: Int,
		val elapsedMs: Long
	)
	
	/**
	 * 同步资料库。
	 *
	 * - 库里没有数据、数量对不上服务器（可能有删除/漏项）或 [forceFull] 时：全量扫描，
	 *   4 路并发抓专辑、按专辑批量写库，最后删掉服务器上已不存在的曲目；
	 * - 否则走增量：`MinDateLastSaved` 只取上次同步之后有变更的曲目，通常几秒。
	 *
	 * 游标存服务器本地时间（Jellyfin 对不带时区的时间串按服务器本地时间解析），
	 * 留 [CURSOR_MARGIN_MINUTES] 分钟重叠防止扫描期间新写入的曲目被漏掉。
	 */
	suspend fun syncLibrary(
		mode: ScanMode = ScanMode.AUTO,
		shouldContinue: () -> Boolean = { true },
		onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
	): LibrarySyncResult = withContext(Dispatchers.IO) {
		val startedAt = System.currentTimeMillis()
		val dao = db?.trackDao()
		val stats = runCatching { getLibraryStats() }.getOrDefault(0 to 0)
		val dbCount = dao?.getTrackCount() ?: 0
		val cursor = prefs?.getString(KEY_SYNC_CURSOR, null)?.let {
			runCatching { LocalDateTime.parse(it) }.getOrNull()
		}
		
		// 服务器上总有极少数没有专辑归属的曲目（albumId=null），按专辑扫永远扫不到。
		// 只要差距在容忍范围内就当作“一致”，避免每次扫描都被迫全量重扫。
		val deficit = stats.second - dbCount
		val consistent = dbCount > 0 && deficit in 0..ORPHAN_TOLERANCE
		
		// 旧版本升级上来：库里已经是完整的，但还没有游标，不必重扫一遍
		if (mode != ScanMode.FULL && cursor == null && consistent) {
			saveSyncCursor()
			return@withContext LibrarySyncResult(
				full = false, tracks = 0, albums = 0, removed = 0,
				elapsedMs = System.currentTimeMillis() - startedAt
			)
		}
		
		val needFull = mode == ScanMode.FULL || cursor == null || dbCount == 0 ||
			(mode == ScanMode.AUTO && !consistent)
		if (needFull) {
			fullScan(dao, shouldContinue, onProgress, startedAt)
		} else {
			incrementalScan(dao, cursor!!, stats, shouldContinue, onProgress, startedAt)
		}
	}
	
	private suspend fun fullScan(
		dao: TrackDao?,
		shouldContinue: () -> Boolean,
		onProgress: suspend (Int, Int) -> Unit,
		startedAt: Long
	): LibrarySyncResult {
		val albums = getAlbums(limit = 100000)
		if (albums.isEmpty()) {
			return LibrarySyncResult(true, 0, 0, 0, System.currentTimeMillis() - startedAt)
		}
		
		val seen = Collections.synchronizedSet(HashSet<UUID>())
		val next = AtomicInteger(0)
		val done = AtomicInteger(0)
		val lastProgressAt = AtomicLong(0)
		
		coroutineScope {
			repeat(minOf(SYNC_WORKERS, albums.size)) {
				launch(Dispatchers.IO) {
					while (shouldContinue()) {
						val index = next.getAndIncrement()
						if (index >= albums.size) break
						
						val tracks = getAlbumTracks(albums[index].id, persist = false)
						if (tracks.isNotEmpty()) {
							dao?.insertTracks(tracks)
							seen.addAll(tracks.map { it.id })
						}
						
						val finished = done.incrementAndGet()
						val now = System.currentTimeMillis()
						if (now - lastProgressAt.get() > 250 || finished == albums.size) {
							lastProgressAt.set(now)
							onProgress(finished, albums.size)
						}
					}
				}
			}
		}
		
		var removed = 0
		if (shouldContinue() && dao != null && seen.isNotEmpty()) {
			val stale = dao.getAllTrackIds().filter { it !in seen }
			stale.chunked(400).forEach { dao.deleteTracksByIds(it) }
			removed = stale.size
		}
		if (shouldContinue()) saveSyncCursor()
		
		Log.i(TAG, "Full scan: albums=${albums.size} tracks=${dao?.getTrackCount()} removed=$removed")
		return LibrarySyncResult(
			full = true,
			tracks = dao?.getTrackCount() ?: 0,
			albums = albums.size,
			removed = removed,
			elapsedMs = System.currentTimeMillis() - startedAt
		)
	}
	
	private suspend fun incrementalScan(
		dao: TrackDao?,
		cursor: LocalDateTime,
		stats: Pair<Int, Int>,
		shouldContinue: () -> Boolean,
		onProgress: suspend (Int, Int) -> Unit,
		startedAt: Long
	): LibrarySyncResult {
		val apiClient = getApiClient()
			?: return LibrarySyncResult(false, 0, 0, 0, System.currentTimeMillis() - startedAt)
		val userId = currentUserId
			?: return LibrarySyncResult(false, 0, 0, 0, System.currentTimeMillis() - startedAt)
		
		var startIndex = 0
		var changed = 0
		var total = 0
		while (shouldContinue()) {
			val page = apiClient.itemsApi.getItems(
				GetItemsRequest(
					userId = userId,
					includeItemTypes = setOf(BaseItemKind.AUDIO),
					recursive = true,
					minDateLastSaved = cursor,
					startIndex = startIndex,
					limit = INCREMENTAL_PAGE_SIZE,
					fields = setOf(
						ItemFields.MEDIA_SOURCES, ItemFields.GENRES, ItemFields.DATE_CREATED
					),
					enableImages = false
				)
			).content
			
			val items = page.items
			if (items.isEmpty()) break
			total = page.totalRecordCount ?: items.size
			val tracks = items.mapNotNull { toTrackEntity(it) }
			if (tracks.isNotEmpty()) {
				dao?.insertTracks(tracks)
				changed += tracks.size
			}
			startIndex += items.size
			onProgress(minOf(startIndex, total), total)
			if (items.size < INCREMENTAL_PAGE_SIZE) break
		}
		
		// 增量看不到“删除”。本地比服务器还多，说明中间有删除，退回全量清理
		if (shouldContinue() && dao != null && dao.getTrackCount() > stats.second) {
			Log.i(TAG, "Incremental left more tracks than the server, falling back to full scan")
			return fullScan(dao, shouldContinue, onProgress, startedAt)
		}
		if (shouldContinue()) saveSyncCursor()
		
		Log.i(TAG, "Incremental scan: changed=$changed")
		return LibrarySyncResult(
			full = false,
			tracks = changed,
			albums = 0,
			removed = 0,
			elapsedMs = System.currentTimeMillis() - startedAt
		)
	}
	
	private fun saveSyncCursor() {
		prefs?.edit {
			putString(
				KEY_SYNC_CURSOR,
				LocalDateTime.now().minusMinutes(CURSOR_MARGIN_MINUTES.toLong()).toString()
			)
			putLong(KEY_LAST_SYNC, System.currentTimeMillis())
		}
	}
	
	/** BaseItemDto → TrackEntity，扫描与按需加载共用。 */
	private fun toTrackEntity(track: BaseItemDto): TrackEntity? {
		if (track.mediaSources.isNullOrEmpty()) return null
		val path = track.path ?: track.mediaSources?.firstOrNull()?.path
		
		val mimeType = if (path != null) {
			val ext = Path(path).extension
			MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "audio/$ext"
		} else {
			"null"
		}
		
		return TrackEntity(
			id = track.id,
			title = track.name.orEmpty(),
			artist = track.artists?.joinToString(";"),
			albumId = track.albumId,
			album = track.album ?: "Unknown Album",
			year = track.productionYear,
			duration = track.runTimeTicks?.div(10000) ?: 0L,
			size = track.mediaSources?.firstOrNull()?.size ?: 0L,
			genres = track.genres?.joinToString(";"),
			trackNumber = track.indexNumber,
			numTracks = track.indexNumberEnd ?: 0,
			discNumber = track.parentIndexNumber,
			dateModified = track.dateCreated?.toString(),
			mimeType = mimeType,
			albumArtist = track.albumArtist ?: "",
			dateCreated = track.dateCreated?.toString(),
			isFavourite = track.userData?.isFavorite ?: false,
			lastFetched = System.currentTimeMillis()
		)
	}
	
	/**
	 * Get single track metadata by ID
	 */
	suspend fun getTrackMetadata(trackId: UUID): TrackEntity? = withContext(Dispatchers.IO) {
		val apiClient = getApiClient() ?: return@withContext null
		val userId = currentUserId ?: return@withContext null
		
		val meta = db?.trackDao()?.getTrackById(trackId)
		val isStale =
			meta == null || (System.currentTimeMillis() - meta.lastFetched) >= 2 * 24 * 60 * 60 * 1000L
		if (meta != null && !isStale) {
			return@withContext meta
		}
		
		try {
			val result = apiClient.userLibraryApi.getItem(
				userId = userId, itemId = trackId
			)
			
			val item = result.content
			val path = item.path ?: item.mediaSources?.get(0)?.path
			
			val mimeType = if (path != null) {
				val ext = Path(path).extension
				MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "audio/$ext"
			} else {
				"null"
			}
			
			val entity = TrackEntity(
				id = trackId,
				title = item.name.orEmpty(),
				artist = item.artists?.joinToString(";"),
				albumId = item.albumId,
				album = item.album ?: "Unknown Album",
				year = item.productionYear,
				duration = item.runTimeTicks?.div(10000) ?: 0L,
				size = item.mediaSources?.firstOrNull()?.size ?: 0L,
				genres = item.genres?.joinToString(";"),
				trackNumber = item.indexNumber,
				numTracks = item.indexNumberEnd ?: 0,
				discNumber = item.parentIndexNumber,
				dateModified = item.dateCreated?.toString(),
				mimeType = mimeType,
				albumArtist = item.albumArtist ?: "",
				dateCreated = item.dateCreated?.toString(),
				isFavourite = item.userData?.isFavorite ?: false,
				lastFetched = System.currentTimeMillis()
			)
			
			db?.trackDao()?.insertTrack(entity)
			
			return@withContext entity
		} catch (e: InvalidStatusException) {
			Log.w(TAG, "getItem item=$trackId received ${e.status} code, returning stale data")
			if (e.status == 401) {
				accessToken = null
			}
			meta
		} catch (e: Exception) {
			Log.e(TAG, "Failed to get item, returning stale data", e)
			meta
		}
	}
	
	/**
	 * Download track using OkHttpClient for direct streaming
	 */
	suspend fun downloadTrack(
		trackId: UUID,
		outputFile: FileChannel,
		byteOffset: Long = 0L,
		byteLength: Long? = null,
		quality: AudioQuality = AudioQuality.LOSSLESS,
		expectedBytes: Long? = null,
		onProgress: ((Long) -> Unit)? = null,
	): Boolean = withContext(Dispatchers.IO) {
		val url = getStreamUrl(trackId, quality) ?: return@withContext false
		val token = accessToken ?: return@withContext false
		
		try {
			val requestBuilder = Request.Builder().url(url).header("X-Emby-Token", token)
			
			if (byteLength != null) {
				requestBuilder.header("Range", "bytes=$byteOffset-${byteOffset + byteLength - 1}")
			} else {
				requestBuilder.header("Range", "bytes=$byteOffset-")
			}
			
			val call = httpClient.newCall(requestBuilder.build())
			
			// Cancel okhttp download on coroutine cancellation
			coroutineContext[Job]?.invokeOnCompletion { cause ->
				if (cause != null) {
					call.cancel()
				}
			}
			
			call.execute().use { response ->
				if (!response.isSuccessful && response.code != 206) {
					Log.e(
						TAG,
						"[$trackId] Download failed: HTTP ${response.code} (offset=$byteOffset) (channelSize=${outputFile.size()})"
					)
					return@withContext false
				}
				
				// 服务器忽略了 Range，返回 200 全量内容：从中间写入会拼出坏文件
				if (response.code == 200 && byteOffset > 0L) {
					Log.e(TAG, "[$trackId] Server ignored Range at offset $byteOffset")
					return@withContext false
				}
				
				val body = response.body
				val input = body.byteStream()
				val buffer = ByteArray(32 * 1024)
				var bytesRead: Int
				var totalDownloaded = 0L
				var lastProgressBoundary = 0L
				val progressThreshold = 64 * 1024  // 64KB
				
				while (input.read(buffer).also { bytesRead = it } != -1) {
					outputFile.writeAt(buffer, byteOffset + totalDownloaded, 0, bytesRead)
					totalDownloaded += bytesRead
					
					val currentBoundary = totalDownloaded / progressThreshold
					if (currentBoundary > lastProgressBoundary) {
						onProgress?.invoke(byteOffset + totalDownloaded)
						lastProgressBoundary = currentBoundary
						
						// return if canceled
						if (!isActive) {
							Log.d(
								TAG,
								"[$trackId] Download interrupted. read $totalDownloaded starting from $byteOffset"
							)
							return@withContext false
						}
					}
				}
				
				// Final progress update to ensure 	we report completion
				onProgress?.invoke(byteOffset + totalDownloaded)
				
				// 服务器中途掐断转码时，read() 只会返回 -1；必须比对预期长度，
				// 否则半首歌会被当成“下载完成”，播放到断点就静默跳歌。
				if (expectedBytes != null && expectedBytes > 0L) {
					val minimum = expectedBytes * 85 / 100
					if (totalDownloaded < minimum) {
						Log.e(
							TAG,
							"[$trackId] Short download: got $totalDownloaded of ~$expectedBytes bytes"
						)
						return@withContext false
					}
				}
			}
			true
		} catch (e: InvalidStatusException) {
			Log.w(TAG, "[$trackId] HTTP ${e.status} at offset $byteOffset")
			if (e.status == 401) {
				accessToken = null
			}
			false
		} catch (e: Exception) {
			// log exception
			if (e !is java.util.concurrent.CancellationException) {
				Log.d(TAG, "[$trackId] Download interrupted: ${e.message}")
			}
			false
		}
	}
	
	/**
	 * Get track stream URL using SDK's URL builder
	 */
	fun getStreamUrl(
		trackId: UUID, quality: AudioQuality = AudioQuality.LOSSLESS
	): String? {
		val apiClient = getApiClient() ?: return null
		
		if (quality == AudioQuality.LOSSLESS) {
			// 原文件直出，SDK 的 URL 构造已经够用
			return apiClient.audioApi.getAudioStreamUrl(
				itemId = trackId,
				static = true,
				deviceId = getDeviceId(),
			)
		}
		
		// 转码：显式构造，避免 SDK 默认参数覆盖码率
		val bitrate = quality.bitrate ?: return null
		return buildString {
			append(serverUrl)
			append("/Audio/").append(trackId).append("/stream")
			append("?static=false")
			append("&audioCodec=").append(quality.codec ?: "aac")
			append("&audioBitrate=").append(bitrate)
			append("&deviceId=").append(getDeviceId())
			// 必须每次唯一，否则 Jellyfin 会复用同一条转码任务（换码率也会拿到旧输出）
			append("&playSessionId=").append(java.util.UUID.randomUUID().toString())
		}
	}
	
	/**
	 * Download album art using SDK's image API
	 */
	suspend fun downloadAlbumArt(
		itemId: UUID, outputFile: File, sizeHint: Point?,
	): Boolean = withContext(Dispatchers.IO) {
		val apiClient = getApiClient() ?: return@withContext false
		// Use SDK's image API
		val url = apiClient.imageApi.getItemImageUrl(
			itemId = itemId,
			imageType = ImageType.PRIMARY,
			maxWidth = sizeHint?.x ?: 1000,
			maxHeight = sizeHint?.y ?: 1000,
			quality = 90,
		)
		val token = accessToken ?: return@withContext false
		
		try {
			val requestBuilder = Request.Builder().url(url).header("X-Emby-Token", token)
			
			val call = httpClient.newCall(requestBuilder.build())
			call.execute().use { response ->
				if (!response.isSuccessful) {
					return@withContext false
				}
				
				response.body.source().use { source ->
					outputFile.sink().buffer().use { sink ->
						sink.writeAll(source)
						return@withContext true
					}
				}
			}
		} catch (e: InvalidStatusException) {
			Log.w(
				TAG, "downloadAlbumArt item=$itemId sizeHint=$sizeHint received ${e.status} code"
			)
			if (e.status == 401) {
				accessToken = null
			}
			false
		} catch (e: Exception) {
			// run this just in case it failed because parent dir was wiped out after "Clean Cache"
			outputFile.parentFile?.mkdirs()
			Log.e(TAG, "Failed to get Album Art for $itemId", e)
			false
		}
	}
	
	/**
	 * Download lyrics using SDK's lyrics API and format as LRC if synced
	 */
	@SuppressLint("DefaultLocale")
	suspend fun getLyrics(trackId: UUID): TrackLyricsEntity? {
		val apiClient = getApiClient() ?: return null
		
		val data = db?.trackDao()?.getTrackLyricsById(trackId)
		if (data != null && !data.isStale()) {
			return data
		}
		
		return try {
			val response = apiClient.lyricsApi.getLyrics(trackId)
			val lyricDto = response.content
			
			val isSynced = lyricDto.metadata.isSynced ?: (lyricDto.lyrics.any { it.start != null })
			
			val sb = StringBuilder()
			lyricDto.lyrics.forEach { line ->
				if (line.start != null) {
					val ticks = line.start!!
					val totalMilliseconds = ticks / 10_000
					val minutes = totalMilliseconds / 60_000
					val seconds = (totalMilliseconds % 60_000) / 1000
					val hundredths = (totalMilliseconds % 1000) / 10
					sb.append(
						String.format(
							"[%02d:%02d.%02d]%s\n", minutes, seconds, hundredths, line.text
						)
					)
				} else {
					sb.append(line.text).append("\n")
				}
			}
			val lyrics = sb.toString()
			
			val entity = TrackLyricsEntity(trackId, lyrics, isSynced, System.currentTimeMillis())
			db?.trackDao()?.insertTrackLyrics(entity)
			
			entity
		} catch (e: InvalidStatusException) {
			Log.w(
				TAG, "getLyrics track=$trackId received ${e.status} code, returning stale data"
			)
			if (e.status == 401) {
				accessToken = null
			}
			data
		} catch (e: Exception) {
			Log.e(TAG, "Failed to get lyrics for $trackId, returning stale data", e)
			data
		}
	}
	
	/**
	 * Report playback start using SDK
	 */
//    suspend fun reportPlaybackStart(
//        trackId: UUID, canSeek: Boolean = true
//    ) = withContext(Dispatchers.IO) {
//        val apiClient = getApiClient() ?: return@withContext
//
//        try {
//            apiClient.playStateApi.reportPlaybackStart(
//                playbackStartInfo = PlaybackStartInfo(
//                    itemId = trackId,
//                    canSeek = canSeek,
//                    isPaused = false,
//                    isMuted = false,
//                    repeatMode = RepeatMode.REPEAT_NONE
//                )
//            )
//            Log.d(TAG, "Reported playback start for track $trackId")
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to report playback start", e)
//        }
//    }
	
	/**
	//     * Report playback progress using SDK
	//     */
//    suspend fun reportPlaybackProgress(
//        trackId: UUID, positionTicks: Long, isPaused: Boolean = false, isMuted: Boolean = false
//    ) = withContext(Dispatchers.IO) {
//        val apiClient = getApiClient() ?: return@withContext
//
//        try {
//            apiClient.playStateApi.reportPlaybackProgress(
//                playbackProgressInfo = PlaybackProgressInfo(
//                    itemId = trackId,
//                    positionTicks = positionTicks,
//                    isPaused = isPaused,
//                    isMuted = isMuted,
//                    canSeek = true,
//                    repeatMode = RepeatMode.REPEAT_NONE
//                )
//            )
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to report playback progress", e)
//        }
//    }
//
//    /**
//     * Report playback stopped / Scrobble using SDK
//     */
//    suspend fun reportPlaybackStopped(
//        trackId: UUID, positionTicks: Long
//    ) = withContext(Dispatchers.IO) {
//        val apiClient = getApiClient() ?: return@withContext
//
//        try {
//            apiClient.playStateApi.reportPlaybackStopped(
//                playbackStopInfo = PlaybackStopInfo(
//                    itemId = trackId, positionTicks = positionTicks
//                )
//            )
//            Log.d(TAG, "Reported playback stopped for track $trackId")
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to report playback stopped", e)
//        }
//    }
//
//	suspend fun makeRequest(req: Request.Builder): Boolean = withContext(Dispatchers.IO) {
//		val token = accessToken ?: return@withContext false
//		val req = req.header("X-Emby-Token", token)
//
//		try {
//			val call = httpClient.newCall(req.build())
//
//			// Cancel okhttp download on coroutine cancellation
//			coroutineContext[Job]?.invokeOnCompletion { cause ->
//				if (cause != null) {
//					call.cancel()
//				}
//			}
//
//			call.execute().use { response ->
//				if (!response.isSuccessful) {
//					Log.e(
//						TAG,
//						"[$trackId] Download failed: HTTP ${response.code} (offset=$byteOffset) (channelSize=${outputFile.size()})"
//					)
//					return@withContext false
//				}
//
//				val body = response.body
//				val input = body.byteStream()
//				val buffer = ByteArray(32 * 1024)
//				var bytesRead: Int
//				var totalDownloaded = 0L
//				var lastProgressBoundary = 0L
//				val progressThreshold = 512 * 1024  // 512KB
//
//				while (input.read(buffer).also { bytesRead = it } != -1) {
//					outputFile.writeAt(buffer, byteOffset + totalDownloaded, 0, bytesRead)
//					totalDownloaded += bytesRead
//
//					val currentBoundary = totalDownloaded / progressThreshold
//					if (currentBoundary > lastProgressBoundary) {
//						onProgress?.invoke(totalDownloaded)
//						lastProgressBoundary = currentBoundary
//					}
//				}
//
//				// Final progress update to ensure we report completion
//				onProgress?.invoke(totalDownloaded)
//			}
//			true
//		} catch (e: InvalidStatusException) {
//			Log.w(TAG, "[$trackId] HTTP ${e.status} at offset $byteOffset")
//			if (e.status == 401) {
//				accessToken = null
//			}
//			false
//		} catch (e: Exception) {
//			// log if it's not a cancellation
//			if (e !is java.util.concurrent.CancellationException) {
//				Log.d(TAG, "[$trackId] Download interrupted: ${e.message}")
//			}
//			false
//		}
//	}
	private fun getDeviceId(): String {
		val deviceId = prefs?.getString(KEY_DEVICE_ID, null) ?: run {
			val id = UUID.randomUUID().toString()
			prefs?.edit { putString(KEY_DEVICE_ID, id) }
			id
		}
		return deviceId
	}
	
	companion object {
		private const val TAG = "JellyfinClientManager"
		
		private const val PREFS_NAME = "jellyfin_prefs"
		private const val KEY_SERVER_URL = "server_url"
		private const val KEY_USERNAME = "username"
		private const val KEY_PASSWORD = "password"
		private const val KEY_ACCESS_TOKEN = "access_token"
		private const val KEY_USER_ID = "user_id"
		private const val KEY_DEVICE_ID = "device_id"
		private const val KEY_MAX_CACHE_SIZE = "max_cache_size"
		private const val KEY_QUALITY = "audio_quality"
		private const val ALBUMS_CACHE_TTL_MS = 120_000L
		
		/** 并发抓专辑的 worker 数。这台服务器 ~3 请求/秒饱和，4 路刚好。 */
		private const val SYNC_WORKERS = 4
		private const val ALBUM_PAGE_SIZE = 100
		
		/** 没有专辑归属、按专辑扫永远扫不到的曲目最多容忍这么多。 */
		private const val ORPHAN_TOLERANCE = 20
		private const val INCREMENTAL_PAGE_SIZE = 500
		private const val KEY_SYNC_CURSOR = "library_sync_cursor"
		private const val KEY_LAST_SYNC = "library_last_sync"
		
		/** 游标回退量：覆盖扫描期间新写入的曲目，重复 upsert 无副作用。 */
		private const val CURSOR_MARGIN_MINUTES = 30
	}
}