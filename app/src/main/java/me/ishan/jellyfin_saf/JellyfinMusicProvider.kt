package me.ishan.jellyfin_saf

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.StrictMode
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ishan.jellyfin_saf.DocumentId.CACHE_ROOT_ID
import me.ishan.jellyfin_saf.DocumentId.ROOT_ID
import me.ishan.jellyfin_saf.db.TrackEntity
import me.ishan.jellyfin_saf.db.TrackLyricsEntity
import me.ishan.jellyfin_saf.db.TrackStateEntity
import java.io.FileNotFoundException
import java.io.IOException


// TODO: Add FLAG_SUPPORTS_METADATA
class JellyfinMusicProvider : DocumentsProvider() {
	
	private lateinit var jellyfinClient: JellyfinClientManager
	private lateinit var playbackManager: TrackPlaybackManager
	
	private val proxyThreads = mutableListOf<HandlerThread>()
	private val proxyHandlers = mutableListOf<Handler>()
	private val nextHandlerIndex = java.util.concurrent.atomic.AtomicInteger(0)
	
	companion object {
		private const val TAG = "JellyfinMusicProvider"
		
		private val DEFAULT_ROOT_PROJECTION = arrayOf(
			Root.COLUMN_ROOT_ID,
			Root.COLUMN_MIME_TYPES,
			Root.COLUMN_FLAGS,
			Root.COLUMN_ICON,
			Root.COLUMN_TITLE,
			Root.COLUMN_SUMMARY,
			Root.COLUMN_AVAILABLE_BYTES,
			Root.COLUMN_DOCUMENT_ID
		)
		
		private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
			Document.COLUMN_DOCUMENT_ID,
			Document.COLUMN_MIME_TYPE,
			Document.COLUMN_DISPLAY_NAME,
			Document.COLUMN_LAST_MODIFIED,
			Document.COLUMN_FLAGS,
			Document.COLUMN_SIZE,
			Document.COLUMN_SUMMARY,
			
			// Poweramp metadata
			MediaStore.Audio.Media.TITLE,
			MediaStore.Audio.Media.ARTIST,
			MediaStore.Audio.Media.ALBUM,
			MediaStore.Audio.Media.DURATION,
			MediaStore.Audio.Media.YEAR,
			MediaStore.Audio.Media.TRACK,
			MediaStore.Audio.Media.DISC_NUMBER,
			MediaStore.Audio.Media.IS_MUSIC,
			MediaStore.Audio.Media.DATE_ADDED,
			MediaStore.Audio.Media.IS_FAVORITE,
			MediaStore.Audio.Media.ALBUM_ARTIST,
			MediaStore.Audio.Media.GENRE,
			
			// Lyrics
			"lyrics",
			"lyrics_synced",
			
			// Poweramp flags
			"com.maxmpz.poweramp.provider.COLUMN_FLAGS"
		)
		
		private const val COLUMN_TRACK_LYRICS = "lyrics"
		private const val COLUMN_TRACK_LYRICS_SYNCED = "lyrics_synced"
		private const val COLUMN_POWERAMP_FLAGS = "com.maxmpz.poweramp.provider.COLUMN_FLAGS"
	}
	
	override fun onCreate(): Boolean {
		// TODO: remove later?
		StrictMode.setVmPolicy(
			StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog()
				.penaltyDropBox().build()
		)
		
		context?.let { ctx ->
			jellyfinClient = JellyfinClientManager(
				context = ctx,
			)
			
			playbackManager = TrackPlaybackManagerSingleton.getInstance(context = ctx)
			// 后台认领没有索引的缓存文件（老版本级联删除留下的）
			playbackManager.recoverCacheIndexAsync()
			
			// Initialize proxy thread pool
			synchronized(proxyThreads) {
				if (proxyThreads.isEmpty()) {
					for (i in 0 until 4) {
						val thread = HandlerThread("JellyfinProxy-$i")
						thread.start()
						proxyThreads.add(thread)
						proxyHandlers.add(Handler(thread.looper))
					}
				}
			}
			
			Log.i(TAG, "JellyfinMusicProvider initialized with server: ${jellyfinClient.getUrl()}")
			
			return true
		}
		return false
	}
	
	// ---------------------------------------------------------------- roots --
	
	override fun queryRoots(projection: Array<out String>?): Cursor {
		val requested = projection ?: DEFAULT_ROOT_PROJECTION
		val cols = requested.toSet()
		val result = MatrixCursor(requested)
		
		// 1) 在线库：边下边播
		result.newRow().apply {
			putIf(cols, Root.COLUMN_ROOT_ID, ROOT_ID)
			putIf(cols, Root.COLUMN_MIME_TYPES, "audio/*")
			putIf(cols, Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
			putIf(cols, Root.COLUMN_TITLE, "Jellyfin Music")
			putIf(cols, Root.COLUMN_SUMMARY, "Stream from your Jellyfin server")
			putIf(cols, Root.COLUMN_DOCUMENT_ID, ROOT_ID)
		}
		
		// 2) 离线缓存：只列已完整下载的曲目，完全走本地数据库
		val usableBytes = context?.filesDir?.usableSpace ?: 0L
		result.newRow().apply {
			putIf(cols, Root.COLUMN_ROOT_ID, CACHE_ROOT_ID)
			putIf(cols, Root.COLUMN_MIME_TYPES, "audio/*")
			putIf(cols, Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
			putIf(cols, Root.COLUMN_TITLE, "Jellyfin Cache")
			putIf(cols, Root.COLUMN_SUMMARY, "Downloaded tracks, playable offline")
			putIf(cols, Root.COLUMN_AVAILABLE_BYTES, usableBytes)
			putIf(cols, Root.COLUMN_DOCUMENT_ID, CACHE_ROOT_ID)
		}
		
		return result
	}
	
	// ------------------------------------------------------------ documents --
	
	override fun queryDocument(docId: String, projection: Array<out String>?): MatrixCursor {
		val documentId = DocumentId.parse(docId)
			?: throw FileNotFoundException("Unknown document id: $docId")
		val requested = projection ?: DEFAULT_DOCUMENT_PROJECTION
		val cols = requested.toSet()
		val result = MatrixCursor(requested)
		Log.d(TAG, "queryDocument $documentId")
		
		when (documentId) {
			is DocumentId.Type.Root -> addDirectory(
				result, cols, ROOT_ID, "Jellyfin Music", Document.FLAG_DIR_PREFERS_GRID
			)
			
			is DocumentId.Type.CacheRoot -> addDirectory(
				result, cols, CACHE_ROOT_ID, "Jellyfin Cache",
				Document.FLAG_DIR_PREFERS_GRID or Document.FLAG_SUPPORTS_THUMBNAIL
			)
			
			is DocumentId.Type.Album -> throw IOException("queryDocument called on type album? $documentId")
			
			is DocumentId.Type.CacheAlbum -> {
				val name = playbackManager.getCachedEntries()
					.firstOrNull { (it.first.albumId ?: DocumentId.UNKNOWN_ALBUM_ID) == documentId.albumId }
					?.first?.album ?: "Unknown Album"
				addDirectory(
					result, cols, docId, name,
					Document.FLAG_DIR_PREFERS_GRID or Document.FLAG_SUPPORTS_THUMBNAIL
				)
			}
			
			is DocumentId.Type.Track -> {
				try {
					val (meta, lyrics) = runBlocking {
						val p1 = async { jellyfinClient.getTrackMetadata(documentId.trackId) }
						
						// Fetch lyrics only when the caller actually asked for them
						val p2 = if (requested.contains(COLUMN_TRACK_LYRICS) ||
							requested.contains(COLUMN_TRACK_LYRICS_SYNCED)
						) {
							async { jellyfinClient.getLyrics(documentId.trackId) }
						} else {
							async { null }
						}
						
						p1.await() to p2.await()
					}
					val metadata = meta ?: return result
					addTrackRow(
						result, cols, docId, metadata,
						size = metadata.size ?: documentId.sizeBytes,
						lastModified = metadata.lastFetched,
						lyrics = lyrics
					)
				} catch (e: Exception) {
					Log.e(TAG, "Error querying track", e)
				}
			}
			
			is DocumentId.Type.CacheTrack -> {
				val entry = playbackManager.getCachedEntry(documentId.trackId)
					?: throw FileNotFoundException("Track not cached: $docId")
				val (metadata, state) = entry
				if (state.state != ContentState.COMPLETE) {
					throw FileNotFoundException("Track not fully cached: $docId")
				}
				// 离线层次：只读本地数据库，不碰网络
				addTrackRow(
					result, cols, docId, metadata,
					size = state.sizeBytes,
					lastModified = metadata.lastFetched,
					lyrics = playbackManager.getCachedLyrics(documentId.trackId)
				)
			}
			
			is DocumentId.Type.Thumb -> throw IOException("queryDocument called on thumb")
			
			is DocumentId.Type.Lyric -> throw IOException("queryDocument called on lyrics")
		}
		
		return result
	}
	
	override fun queryChildDocuments(
		parentDocumentId: String, projection: Array<out String>?, sortOrder: String?
	): Cursor {
		val parentId = DocumentId.parse(parentDocumentId)
			?: throw FileNotFoundException("Unknown parent id: $parentDocumentId")
		val requested = projection ?: DEFAULT_DOCUMENT_PROJECTION
		val cols = requested.toSet()
		val result = MatrixCursor(requested)
		Log.d(TAG, "queryChildDocument $parentId (sort=$sortOrder)")
		
		try {
			when (parentId) {
				is DocumentId.Type.Root -> {
					val albums = runBlocking { jellyfinClient.getAlbums(limit = 100000) }
					
					albums.forEach { album ->
						result.newRow().apply {
							putIf(cols, Document.COLUMN_DOCUMENT_ID, DocumentId.forAlbum(album.id))
							putIf(cols, Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
							putIf(cols, Document.COLUMN_DISPLAY_NAME, album.name)
							putIf(cols, Document.COLUMN_SUMMARY, "${album.songCount} Songs")
							putIf(
								cols, Document.COLUMN_FLAGS,
								Document.FLAG_SUPPORTS_THUMBNAIL or Document.FLAG_SUPPORTS_METADATA
							)
						}
					}
				}
				
				is DocumentId.Type.Album -> {
					val tracks = runBlocking {
						jellyfinClient.getAlbumTracks(parentId.albumId)
					}
					
					tracks.forEach { track ->
						val docId = DocumentId.forTrack(
							parentId.albumId, track.id, track.size ?: 0L, track.duration ?: 0L
						)
						addTrackRow(
							result, cols, docId, track,
							size = track.size, lastModified = track.lastFetched, lyrics = null
						)
					}
				}
				
				is DocumentId.Type.CacheRoot -> {
					// 虚拟专辑：只包含完整缓存的曲目，元数据全部来自本地库
					val cached = playbackManager.getCachedEntries()
						.filter { it.second.state == ContentState.COMPLETE }
					
					cached
						.groupBy { it.first.albumId ?: DocumentId.UNKNOWN_ALBUM_ID }
						.entries
						.sortedBy { (it.value.first().first.album ?: "").lowercase() }
						.forEach { (albumId, tracks) ->
							val albumName = tracks.first().first.album ?: "Unknown Album"
							val albumArtist = tracks.first().first.albumArtist.orEmpty()
							val bytes = tracks.sumOf { it.second.sizeBytes }
							result.newRow().apply {
								putIf(
									cols, Document.COLUMN_DOCUMENT_ID,
									DocumentId.forCacheAlbum(albumId)
								)
								putIf(cols, Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
								putIf(cols, Document.COLUMN_DISPLAY_NAME, albumName)
								putIf(
									cols, Document.COLUMN_SUMMARY,
									buildString {
										append("${tracks.size} cached songs · ${formatBytes(bytes)}")
										if (albumArtist.isNotBlank()) append(" · $albumArtist")
									})
								putIf(
									cols, Document.COLUMN_FLAGS,
									Document.FLAG_SUPPORTS_THUMBNAIL or Document.FLAG_SUPPORTS_METADATA
								)
							}
						}
				}
				
				is DocumentId.Type.CacheAlbum -> {
					playbackManager.getCachedEntries()
						.filter {
							it.second.state == ContentState.COMPLETE &&
								(it.first.albumId ?: DocumentId.UNKNOWN_ALBUM_ID) == parentId.albumId
						}
						.sortedWith(
							compareBy<Pair<TrackEntity, TrackStateEntity>> {
								it.first.trackNumber ?: Int.MAX_VALUE
							}.thenBy { it.first.title.orEmpty().lowercase() }
						)
						.forEach { (metadata, state) ->
							val sizeBytes = state.sizeBytes.takeIf { it > 0L }
								?: metadata.size ?: 0L
							val durationMs = state.durationMs.takeIf { it > 0L }
								?: metadata.duration ?: 0L
							val docId = DocumentId.forCacheTrack(
								parentId.albumId, metadata.id, sizeBytes, durationMs
							)
							addTrackRow(
								result, cols, docId, metadata,
								size = state.sizeBytes,
								lastModified = metadata.lastFetched,
								lyrics = playbackManager.getCachedLyrics(metadata.id)
							)
						}
				}
				
				is DocumentId.Type.Track -> throw IOException("Tracks have no children")
				is DocumentId.Type.CacheTrack -> throw IOException("Tracks have no children")
				is DocumentId.Type.Lyric -> throw IOException("Lyrics have no children")
				is DocumentId.Type.Thumb -> throw IOException("Thumbs have no children")
				else -> throw IOException("Unknown branch in queryChildDocuments")
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error querying children of $parentId", e)
		}
		
		return result
	}
	
	/*
	openDocument is only ever used for audio content
	 */
	override fun openDocument(
		docId: String, mode: String, signal: CancellationSignal?
	): ParcelFileDescriptor {
		val documentId = DocumentId.parse(docId)
			?: throw FileNotFoundException("Unknown document id: $docId")
		
		when (documentId) {
			is DocumentId.Type.Track -> {
				playbackManager.getCachedTrack(documentId.trackId)?.let { file ->
					Log.d(TAG, "[${documentId.trackId}] Opening document from disk")
					return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
				}
				
				Log.d(TAG, "[${documentId.trackId}] Opening document for streaming")
				val trackStream = playbackManager.openTrackForStreaming(
					documentId.trackId,
					documentId.sizeBytes,
					documentId.durationMs,
					jellyfinClient
				)
				signal?.setOnCancelListener {
					Log.d(TAG, "[${documentId.trackId}] Cancellation fired during openDocument")
					trackStream.close()
				}
				
				if (signal?.isCanceled == true) {
					trackStream.close()
					throw OperationCanceledException("received cancellation signal")
				}
				
				return openViaProxyFd(documentId, trackStream)
			}
			
			is DocumentId.Type.CacheTrack -> {
				// 离线层次绝不允许回落成流式：没缓存就是没有。
				val file = playbackManager.getCachedTrack(documentId.trackId)
					?: throw FileNotFoundException("Track not cached: $docId")
				Log.d(TAG, "[${documentId.trackId}] Opening cached document ${file.length()} bytes")
				return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
			}
			
			else -> throw IOException(
				"openDocument is not supported for this type! type=$documentId"
			)
		}
	}
	
	private fun openViaProxyFd(
		documentId: DocumentId.Type.Track, trackStream: TrackStream
	): ParcelFileDescriptor {
		val storageManager = context?.getSystemService(android.content.Context.STORAGE_SERVICE) as StorageManager
		
		val index = nextHandlerIndex.getAndIncrement() % proxyHandlers.size
		val handler = proxyHandlers[index]
		Log.d(TAG, "[${documentId.trackId}] ProxyFD opened on thread $index")
		
		return storageManager.openProxyFileDescriptor(
			ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
				override fun onGetSize(): Long = documentId.sizeBytes
				
				override fun onRead(offset: Long, size: Int, data: ByteArray): Int =
					trackStream.read(data, offset, size)
				
				override fun onRelease() {
					Log.d(TAG, "[${documentId.trackId}] ProxyFD released")
					trackStream.close()
				}
			}, handler
		)
	}
	
	override fun openDocumentThumbnail(
		documentId: String, sizeHint: Point?, signal: CancellationSignal?
	): AssetFileDescriptor {
		val albumId = when (val docId = DocumentId.parse(documentId)) {
			is DocumentId.Type.Album -> docId.albumId
			is DocumentId.Type.CacheAlbum -> docId.albumId
			is DocumentId.Type.Track -> docId.albumId
			is DocumentId.Type.CacheTrack -> docId.albumId
			else -> throw IOException(
				"openDocumentThumbnail is only supported for albums & tracks. Got $docId"
			)
		}
		
		var thumbFile = playbackManager.getCachedThumbnail(albumId)
		
		if (thumbFile == null) {
			// 离线时不要为了封面去阻塞 binder 线程做一次注定失败的网络请求
			if (!hasNetwork()) {
				throw FileNotFoundException("No cached thumbnail for $albumId and no network")
			}
			Log.d(TAG, "[$albumId] Downloading thumbnail")
			thumbFile = runBlocking {
				playbackManager.downloadThumbnail(albumId, jellyfinClient, sizeHint)
			} ?: throw FileNotFoundException("Thumbnail not available")
		}
		return AssetFileDescriptor(
			ParcelFileDescriptor.open(thumbFile, ParcelFileDescriptor.MODE_READ_ONLY),
			0,
			AssetFileDescriptor.UNKNOWN_LENGTH
		)
	}
	
	override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
		if (parentDocumentId == documentId) return true
		
		val parentId = DocumentId.parse(parentDocumentId) ?: return false
		val docId = DocumentId.parse(documentId) ?: return false
		
		return when (parentId) {
			is DocumentId.Type.Root -> true
			is DocumentId.Type.Album -> docId is DocumentId.Type.Track
			is DocumentId.Type.CacheRoot -> docId is DocumentId.Type.CacheAlbum ||
				docId is DocumentId.Type.CacheTrack
			
			is DocumentId.Type.CacheAlbum -> docId is DocumentId.Type.CacheTrack
			else -> false
		}
	}
	
	override fun shutdown() {
		playbackManager.shutdown()
		synchronized(proxyThreads) {
			proxyThreads.forEach { it.quitSafely() }
			proxyThreads.clear()
			proxyHandlers.clear()
		}
		super.shutdown()
	}
	
	// -------------------------------------------------------------- helpers --
	
	private fun addDirectory(
		cursor: MatrixCursor,
		cols: Set<String>,
		docId: String,
		displayName: String,
		flags: Int
	) {
		cursor.newRow().apply {
			putIf(cols, Document.COLUMN_DOCUMENT_ID, docId)
			putIf(cols, Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
			putIf(cols, Document.COLUMN_DISPLAY_NAME, displayName)
			putIf(cols, Document.COLUMN_FLAGS, flags)
		}
	}
	
	private fun addTrackRow(
		cursor: MatrixCursor,
		cols: Set<String>,
		docId: String,
		track: TrackEntity,
		size: Long?,
		lastModified: Long?,
		lyrics: TrackLyricsEntity?
	) {
		cursor.newRow().apply {
			putIf(cols, Document.COLUMN_DOCUMENT_ID, docId)
			putIf(cols, Document.COLUMN_MIME_TYPE, track.mimeType ?: "audio/*")
			putIf(cols, Document.COLUMN_DISPLAY_NAME, track.title)
			putIf(cols, Document.COLUMN_SIZE, size)
			putIf(cols, Document.COLUMN_LAST_MODIFIED, lastModified)
			putIf(cols, Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL)
			
			// Poweramp metadata — the whole point of the cache root is that these
			// are present offline too.
			putIf(cols, MediaStore.Audio.Media.TITLE, track.title)
			putIf(cols, MediaStore.Audio.Media.ARTIST, track.artist)
			putIf(cols, MediaStore.Audio.Media.ALBUM, track.album)
			putIf(cols, MediaStore.Audio.Media.DURATION, track.duration)
			putIf(cols, MediaStore.Audio.Media.YEAR, track.year)
			putIf(cols, MediaStore.Audio.Media.TRACK, track.trackNumber)
			putIf(cols, MediaStore.Audio.AudioColumns.TRACK, track.trackNumber)
			putIf(cols, MediaStore.Audio.Media.DISC_NUMBER, track.discNumber)
			putIf(cols, MediaStore.Audio.Media.DATE_ADDED, track.dateCreated)
			putIf(cols, MediaStore.Audio.Media.IS_FAVORITE, track.isFavourite)
			putIf(cols, MediaStore.Audio.Media.ALBUM_ARTIST, track.albumArtist)
			putIf(cols, MediaStore.Audio.Media.GENRE, track.genres)
			putIf(cols, MediaStore.Audio.Media.IS_MUSIC, 1)
			
			lyrics?.let { lyric ->
				putIf(cols, COLUMN_TRACK_LYRICS, lyric.content)
				if (lyric.isSynced) putIf(cols, COLUMN_TRACK_LYRICS_SYNCED, lyric.content)
			}
			
			// Poweramp flags (0x1 = FLAG_SUPPORTS_THUMBNAIL)
			putIf(cols, COLUMN_POWERAMP_FLAGS, 0x1)
		}
	}
	
	private fun hasNetwork(): Boolean {
		val cm = context?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
			as? ConnectivityManager ?: return false
		val network = cm.activeNetwork ?: return false
		val caps = cm.getNetworkCapabilities(network) ?: return false
		return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
	}
}

private fun MatrixCursor.RowBuilder.putIf(cols: Set<String>, column: String, value: Any?) {
	if (column in cols) add(column, value)
}

private fun formatBytes(bytes: Long): String {
	if (bytes <= 0) return "0 MB"
	val mb = bytes / (1024.0 * 1024.0)
	return if (mb >= 1024) String.format("%.2f GB", mb / 1024) else String.format("%.1f MB", mb)
}
