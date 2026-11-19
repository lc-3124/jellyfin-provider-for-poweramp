package me.ishan.jellyfin_saf

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 目录视图：第一层按服务器上的物理目录（专辑艺术家 / 厂牌），
 * 第二层按 Jellyfin 的专辑聚合，显示专辑名，第三层是曲目。
 *
 * 这样同一个厂牌不会因为专辑艺术家标签写法不同而被拆成多个。
 */
object FolderLibrary {
	private const val TAG = "FolderLibrary"
	private const val FILE_NAME = "library_folders.json"
	private const val SCHEMA = 3
	
	data class TrackRef(val trackId: UUID, val title: String)
	data class AlbumNode(val key: String, val name: String, val tracks: List<TrackRef>)
	data class ArtistNode(val key: String, val name: String, val albums: List<AlbumNode>)
	
	data class Tree(val artists: List<ArtistNode>) {
		fun artist(key: String): ArtistNode? = artists.firstOrNull { it.key == key }
		
		fun album(artistKey: String, albumKey: String): AlbumNode? =
			artist(artistKey)?.albums?.firstOrNull { it.key == albumKey }
	}
	
	@Volatile
	private var cached: Tree? = null
	
	fun get(context: Context): Tree? {
		cached?.let { return it }
		return load(context)?.also { cached = it }
	}
	
	fun invalidate() {
		cached = null
	}
	
	/** 重新抓路径/元数据并建树，返回曲目数。 */
	suspend fun rebuild(context: Context, client: JellyfinClientManager): Int =
		withContext(Dispatchers.IO) {
			val paths = client.getAllTrackPaths()
			if (paths.isEmpty()) return@withContext 0
			
			val metadata = client.getAllTrackInfo().associateBy { it.id }
			val artists = LinkedHashMap<String, MutableArtist>()
			
			// 先剥掉所有路径的公共目录前缀（服务器上的挂载点），否则第一段永远是 "home"
			val allSegments = paths.map { (_, path) ->
				path.replace('\\', '/').split('/').filter { it.isNotBlank() }
			}
			val commonPrefix = commonDirectoryPrefix(allSegments)
			
			paths.forEach { (trackId, path) ->
				val segments = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
				val relative = segments.drop(commonPrefix.size)
				val meta = metadata[trackId]
				
				val artistKey = when {
					relative.size >= 2 -> relative.first()
					meta?.albumArtist?.isNotBlank() == true -> meta.albumArtist
					else -> "Unknown Artist"
				}
				val albumKey = meta?.albumId?.toString()
					?: "name:${meta?.album ?: "Unknown Album"}"
				val albumName = meta?.album ?: "Unknown Album"
				val title = meta?.title ?: relative.lastOrNull() ?: trackId.toString()
				
				val artist = artists.getOrPut(artistKey) {
					MutableArtist(artistKey, artistKey)
				}
				val album = artist.albums.getOrPut(albumKey) {
					MutableAlbum(albumKey, albumName)
				}
				album.tracks.add(TrackRef(trackId, title))
			}
			
			val tree = Tree(
				artists.values.map { artist ->
					ArtistNode(
						key = artist.key,
						name = artist.name,
						albums = artist.albums.values.map { album ->
							AlbumNode(
								key = album.key,
								name = album.name,
								tracks = album.tracks.sortedBy { it.title.lowercase() }
							)
						}.sortedBy { it.name.lowercase() }
					)
				}.sortedBy { it.name.lowercase() }
			)
			save(context, tree)
			cached = tree
			Log.i(TAG, "Folder tree: ${tree.artists.size} artists, ${paths.size} tracks")
			paths.size
		}
	
	private fun commonDirectoryPrefix(paths: List<List<String>>): List<String> {
		val dirs = paths.map { it.dropLast(1) }.filter { it.isNotEmpty() }
		if (dirs.isEmpty()) return emptyList()
		val first = dirs.first()
		var commonLength = first.size
		for (dir in dirs.drop(1)) {
			var i = 0
			while (i < commonLength && i < dir.size && dir[i] == first[i]) i++
			commonLength = i
		}
		return first.take(commonLength)
	}
	
	private data class MutableArtist(
		val key: String,
		val name: String,
		val albums: LinkedHashMap<String, MutableAlbum> = LinkedHashMap()
	)
	
	private data class MutableAlbum(
		val key: String,
		val name: String,
		val tracks: MutableList<TrackRef> = mutableListOf()
	)
	
	private fun file(context: Context) = File(context.filesDir, FILE_NAME)
	
	private fun save(context: Context, tree: Tree) {
		try {
			val root = JSONObject()
			root.put("schema", SCHEMA)
			val artists = JSONArray()
			tree.artists.forEach { artist ->
				val artistObj = JSONObject()
				artistObj.put("key", artist.key)
				artistObj.put("name", artist.name)
				val albums = JSONArray()
				artist.albums.forEach { album ->
					val albumObj = JSONObject()
					albumObj.put("key", album.key)
					albumObj.put("name", album.name)
					val tracks = JSONArray()
					album.tracks.forEach { ref ->
						tracks.put(JSONArray().apply {
							put(ref.trackId.toString())
							put(ref.title)
						})
					}
					albumObj.put("tracks", tracks)
					albums.put(albumObj)
				}
				artistObj.put("albums", albums)
				artists.put(artistObj)
			}
			root.put("artists", artists)
			file(context).writeText(root.toString())
		} catch (e: Exception) {
			Log.e(TAG, "Cannot save folder tree", e)
		}
	}
	
	private fun load(context: Context): Tree? {
		return try {
			val f = file(context)
			if (!f.exists()) return null
			val root = JSONObject(f.readText())
			if (root.optInt("schema", 0) != SCHEMA) return null
			val artistsArray = root.getJSONArray("artists")
			val artists = (0 until artistsArray.length()).map { i ->
				val artistObj = artistsArray.getJSONObject(i)
				val albumsArray = artistObj.getJSONArray("albums")
				val albums = (0 until albumsArray.length()).map { j ->
					val albumObj = albumsArray.getJSONObject(j)
					val tracksArray = albumObj.getJSONArray("tracks")
					val tracks = (0 until tracksArray.length()).map { k ->
						val pair = tracksArray.getJSONArray(k)
						TrackRef(UUID.fromString(pair.getString(0)), pair.getString(1))
					}
					AlbumNode(albumObj.getString("key"), albumObj.getString("name"), tracks)
				}
				ArtistNode(
					artistObj.getString("key"), artistObj.getString("name"), albums
				)
			}
			Tree(artists)
		} catch (e: Exception) {
			Log.e(TAG, "Cannot load folder tree", e)
			null
		}
	}
}
