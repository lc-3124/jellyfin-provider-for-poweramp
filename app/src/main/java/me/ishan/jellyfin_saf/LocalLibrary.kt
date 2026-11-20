package me.ishan.jellyfin_saf

import android.content.Context
import me.ishan.jellyfin_saf.db.AppDatabase
import me.ishan.jellyfin_saf.db.TrackEntity
import org.jellyfin.sdk.model.UUID

/**
 * 两个 SAF 根的同一份数据源：Room 快照。
 *
 * 扫描负责写库；Jellyfin Music 按 albumId 分组，Jellyfin Folders 用
 * FolderLibrary 按物理路径分组，二者只是投影不同，曲目集合永远一致。
 * 浏览时不再访问服务器，扫描/写入后调用 [invalidate]。
 */
object LocalLibrary {
	data class Album(
		val id: UUID,
		val name: String,
		val artist: String,
		val tracks: List<TrackEntity>
	)
	
	@Volatile
	private var cached: List<Album>? = null
	
	fun invalidate() {
		cached = null
	}
	
	fun albums(context: Context): List<Album> {
		cached?.let { return it }
		
		val tracks = AppDatabase.getDatabase(context).trackDao().getAllTracks()
		val grouped = LinkedHashMap<UUID, MutableList<TrackEntity>>()
		tracks.forEach { track ->
			val key = track.albumId ?: DocumentId.UNKNOWN_ALBUM_ID
			grouped.getOrPut(key) { mutableListOf() }.add(track)
		}
		
		val albums = grouped.map { (id, list) ->
			val sorted = list.sortedWith(
				compareBy<TrackEntity> { it.trackNumber ?: Int.MAX_VALUE }
					.thenBy { it.title.orEmpty().lowercase() }
			)
			val first = sorted.first()
			Album(
				id = id,
				name = first.album?.takeIf { it.isNotBlank() } ?: "Unknown Album",
				artist = first.albumArtist.orEmpty(),
				tracks = sorted
			)
		}.sortedBy { it.name.lowercase() }
		
		cached = albums
		return albums
	}
	
	fun album(context: Context, albumId: UUID): Album? =
		albums(context).firstOrNull { it.id == albumId }
}
