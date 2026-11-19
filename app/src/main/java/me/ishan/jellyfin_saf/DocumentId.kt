package me.ishan.jellyfin_saf

import org.jellyfin.sdk.model.UUID

/**
 * 文档 id 是自描述的：SAF 的同步调用（getSize/openDocument）需要的大小、时长
 * 全部编码在 id 里，避免再查一次数据库或发一次网络请求。
 *
 * 两棵树共用这套编码：
 *  - 在线库：jellyfin_root -> alb/trk
 *  - 离线缓存：jellyfin_cache_root -> calb/ctrk
 *
 * 前缀刻意分开，这样 openDocument() 能区分“去服务器拉流”和“必须已经在本地”。
 */
object DocumentId {
	private const val SEP = ";"
	
	const val ROOT_ID = "jellyfin_root"
	const val FOLDERS_ROOT_ID = "jellyfin_folders_root"
	const val CACHE_ROOT_ID = "jellyfin_cache_root"
	
	/** 服务器元数据里没有专辑的缓存歌曲，归到这个虚拟专辑。 */
	val UNKNOWN_ALBUM_ID = UUID.fromString("00000000-0000-0000-0000-000000000000")
	
	// prefixes for each type
	private const val P_ALB = "alb"
	private const val P_TRK = "trk"
	private const val P_THM = "thm"
	private const val P_LYR = "lyr"
	private const val P_CALB = "calb"
	private const val P_CTRK = "ctrk"
	private const val P_DIR = "dir"
	
	// builders
	fun forRoot() = ROOT_ID
	fun forFoldersRoot() = FOLDERS_ROOT_ID
	fun forFolder(relativePath: String): String {
		val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
			.encodeToString(relativePath.toByteArray(Charsets.UTF_8))
		return "$P_DIR$SEP$encoded"
	}
	fun forCacheRoot() = CACHE_ROOT_ID
	fun forAlbum(albumId: UUID) = "$P_ALB$SEP$albumId"
	fun forTrack(albumId: UUID, trackId: UUID, sizeBytes: Long, durationMs: Long) =
		"$P_TRK$SEP$albumId$SEP$trackId$SEP$sizeBytes$SEP$durationMs"
	
	fun forCacheAlbum(albumId: UUID) = "$P_CALB$SEP$albumId"
	fun forCacheTrack(albumId: UUID, trackId: UUID, sizeBytes: Long, durationMs: Long) =
		"$P_CTRK$SEP$albumId$SEP$trackId$SEP$sizeBytes$SEP$durationMs"
	
	fun forThumb(albumId: UUID) = "$P_THM$SEP$albumId"
	fun forLyrics(trackId: UUID) = "$P_LYR$SEP$trackId"
	
	// parsers
	sealed class Type {
		object Root : Type()
		object FoldersRoot : Type()
		object CacheRoot : Type()
		data class Folder(val path: String) : Type()
		data class Album(val albumId: UUID) : Type()
		data class CacheAlbum(val albumId: UUID) : Type()
		data class Track(
			val albumId: UUID, val trackId: UUID, val sizeBytes: Long, val durationMs: Long
		) : Type()
		
		data class CacheTrack(
			val albumId: UUID, val trackId: UUID, val sizeBytes: Long, val durationMs: Long
		) : Type()
		
		data class Thumb(val albumId: UUID) : Type()
		data class Lyric(val trackId: UUID) : Type()
	}
	
	fun parse(id: String): Type? {
		if (id == ROOT_ID) return Type.Root
		if (id == FOLDERS_ROOT_ID) return Type.FoldersRoot
		if (id == CACHE_ROOT_ID) return Type.CacheRoot
		
		try {
			val firstSep = id.indexOf(SEP)
			if (firstSep == -1) return null
			
			val prefix = id.substring(0, firstSep)
			val rest = id.substring(firstSep + 1)
			
			return when (prefix) {
				P_ALB -> Type.Album(UUID.fromString(rest))
				
				P_CALB -> Type.CacheAlbum(UUID.fromString(rest))
				
				P_THM -> Type.Thumb(UUID.fromString(rest))
				
				P_LYR -> Type.Lyric(UUID.fromString(rest))
				
				P_TRK -> parseTrack(rest)?.let {
					Type.Track(it.albumId, it.trackId, it.sizeBytes, it.durationMs)
				}
				
				P_CTRK -> parseTrack(rest)?.let {
					Type.CacheTrack(it.albumId, it.trackId, it.sizeBytes, it.durationMs)
				}
				
				P_DIR -> Type.Folder(
					String(
						java.util.Base64.getUrlDecoder().decode(rest),
						Charsets.UTF_8
					)
				)
				
				else -> null
			}
		} catch (e: Exception) {
			return null
		}
	}
	
	private data class ParsedTrack(
		val albumId: UUID, val trackId: UUID, val sizeBytes: Long, val durationMs: Long
	)
	
	private fun parseTrack(rest: String): ParsedTrack? {
		val parts = rest.split(SEP)
		if (parts.size != 4) return null
		return ParsedTrack(
			albumId = UUID.fromString(parts[0]),
			trackId = UUID.fromString(parts[1]),
			sizeBytes = parts[2].toLong(),
			durationMs = parts[3].toLong()
		)
	}
}
