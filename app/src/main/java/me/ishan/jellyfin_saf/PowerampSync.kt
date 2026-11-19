package me.ishan.jellyfin_saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.UUID
import java.net.URLDecoder

/**
 * Poweramp 的点赞 → Jellyfin 收藏。
 *
 * Poweramp 通过 content provider 暴露自己的曲库：
 *   content://com.maxmpz.audioplayer.data/files
 * 列里有 rating（0..5）和 file_path。file_path 是 SAF 路径，例如：
 *   @jellyfin.music/jellyfin_cache_root/ctrk%3B<album>%3B<track>%3B<size>%3B<dur>
 * URL 解码后正好是我们的文档 id，所以能精确反解出 Jellyfin 的 track UUID。
 *
 * rating: 0 未评分, 1 unlike, 5 like, 2..4 为星级
 * 映射: >=4 → 收藏；==1 → 取消收藏；其余跳过（尤其 0 不能当成“取消”，否则会清掉
 * Jellyfin 里已有的收藏）。
 */
object PowerampSync {
	private const val TAG = "PowerampSync"
	private const val AUTHORITY = "com.maxmpz.audioplayer.data"
	private const val POWERAMP_PACKAGE = "com.maxmpz.audioplayer"
	private const val API_RECEIVER = "com.maxmpz.audioplayer.player.PowerampAPIReceiver"
	private const val ACTION_ASK_FOR_DATA_PERMISSION =
		"com.maxmpz.audioplayer.ACTION_ASK_FOR_DATA_PERMISSION"
	
	data class Result(
		val liked: Int = 0,
		val unliked: Int = 0,
		val skipped: Int = 0,
		val failed: Int = 0,
		val needsPermission: Boolean = false,
		val error: String? = null
	) {
		val total: Int get() = liked + unliked
	}
	
	suspend fun sync(context: Context, client: JellyfinClientManager): Result =
		withContext(Dispatchers.IO) {
			val uri = Uri.parse("content://$AUTHORITY/files")
			val cursor = try {
				context.contentResolver.query(
					uri, arrayOf("rating", "file_path"), "rating>0", null, null
				)
			} catch (e: SecurityException) {
				Log.w(TAG, "No access to Poweramp provider, asking for it")
				requestDataAccess(context)
				return@withContext Result(needsPermission = true)
			} catch (e: Exception) {
				Log.e(TAG, "Query failed", e)
				return@withContext Result(error = e.message ?: "query failed")
			} ?: return@withContext Result(error = "Poweramp provider returned no cursor")
			
			var liked = 0
			var unliked = 0
			var skipped = 0
			var failed = 0
			
			cursor.use { c ->
				val ratingIdx = c.getColumnIndex("rating")
				val pathIdx = c.getColumnIndex("file_path")
				while (c.moveToNext()) {
					val rating = if (ratingIdx >= 0) c.getInt(ratingIdx) else 0
					val path = if (pathIdx >= 0) c.getString(pathIdx) else null
					
					val favourite = when {
						// 只要在 Poweramp 里给过正分（2-5 星 / 赞）都算收藏
						rating >= 2 -> true
						rating == 1 -> false
						else -> {
							skipped++
							continue
						}
					}
					val trackId = trackIdFromPath(path)
					if (trackId == null) {
						skipped++
						continue
					}
					if (client.setFavourite(trackId, favourite)) {
						if (favourite) liked++ else unliked++
					} else {
						failed++
					}
				}
			}
			
			Log.i(TAG, "Synced: liked=$liked unliked=$unliked skipped=$skipped failed=$failed")
			Result(liked, unliked, skipped, failed)
		}
	
	private fun trackIdFromPath(path: String?): UUID? {
		if (path.isNullOrBlank()) return null
		val lastSegment = path.substringAfterLast('/')
		val decoded = try {
			URLDecoder.decode(lastSegment, "UTF-8")
		} catch (e: Exception) {
			return null
		}
		return when (val id = DocumentId.parse(decoded)) {
			is DocumentId.Type.Track -> id.trackId
			is DocumentId.Type.CacheTrack -> id.trackId
			else -> null
		}
	}
	
	private fun requestDataAccess(context: Context) {
		try {
			val intent = Intent(ACTION_ASK_FOR_DATA_PERMISSION).apply {
				setClassName(POWERAMP_PACKAGE, API_RECEIVER)
				putExtra("package", context.packageName)
			}
			context.sendBroadcast(intent)
		} catch (e: Exception) {
			Log.w(TAG, "Could not ask Poweramp for data access", e)
		}
	}
}
