package me.ishan.jellyfin_saf

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.jellyfin.sdk.model.UUID
import java.net.URLDecoder

/**
 * Poweramp 数据库的读写封装。
 *
 * 写入走它未公开但可用的 ContentProvider：
 *   content://com.maxmpz.audioplayer.data/files
 *   update ... set rating=5 where folder_files._id=?
 * 实测（Android 14 / Poweramp build 1035）可以写入并读回。
 * 这是未文档化接口，所有写操作都必须校验结果，失败就停。
 */
object PowerampProvider {
	private const val TAG = "PowerampProvider"
	private const val AUTHORITY = "com.maxmpz.audioplayer.data"
	private const val POWERAMP_PACKAGE = "com.maxmpz.audioplayer"
	private const val API_RECEIVER = "com.maxmpz.audioplayer.player.PowerampAPIReceiver"
	private const val ACTION_RELOAD_DATA = "com.maxmpz.audioplayer.ACTION_RELOAD_DATA"
	private const val ACTION_ASK_FOR_DATA_PERMISSION =
		"com.maxmpz.audioplayer.ACTION_ASK_FOR_DATA_PERMISSION"
	
	data class Row(val rowId: Long, val rating: Int, val trackId: UUID?)
	
	/** 读取 Poweramp 全部曲目（rating 含 0）。返回 null 表示没有访问权限。 */
	fun queryRows(context: Context): List<Row>? {
		val uri = Uri.parse("content://$AUTHORITY/files")
		val cursor = try {
			context.contentResolver.query(
				uri,
				arrayOf("folder_files._id", "rating", "file_path"),
				null, null, null
			)
		} catch (e: SecurityException) {
			Log.w(TAG, "No provider access", e)
			requestDataAccess(context)
			return null
		} catch (e: Exception) {
			Log.e(TAG, "Query failed", e)
			return null
		} ?: return emptyList()
		
		val rows = ArrayList<Row>()
		cursor.use { c ->
			val idIdx = c.getColumnIndex("_id")
			val ratingIdx = c.getColumnIndex("rating")
			val pathIdx = c.getColumnIndex("file_path")
			while (c.moveToNext()) {
				val id = if (idIdx >= 0) c.getLong(idIdx) else -1L
				val rating = if (ratingIdx >= 0) c.getInt(ratingIdx) else 0
				val path = if (pathIdx >= 0) c.getString(pathIdx) else null
				if (id < 0) continue
				rows.add(Row(id, rating, trackIdFromPath(path)))
			}
		}
		return rows
	}
	
	/** 写入 rating，并读回校验。 */
	fun setRating(context: Context, rowId: Long, rating: Int): Boolean {
		val uri = Uri.parse("content://$AUTHORITY/files")
		return try {
			val values = ContentValues().apply { put("rating", rating) }
			val updated = context.contentResolver.update(
				uri, values, "folder_files._id=?", arrayOf(rowId.toString())
			)
			updated > 0
		} catch (e: SecurityException) {
			Log.w(TAG, "Write denied", e)
			requestDataAccess(context)
			false
		} catch (e: Exception) {
			Log.e(TAG, "Write failed row=$rowId rating=$rating", e)
			false
		}
	}
	
	fun reloadData(context: Context) {
		try {
			val intent = Intent(ACTION_RELOAD_DATA).apply {
				setClassName(POWERAMP_PACKAGE, API_RECEIVER)
				putExtra("package", context.packageName)
				putExtra("table", "files")
			}
			context.sendBroadcast(intent)
		} catch (e: Exception) {
			Log.w(TAG, "reloadData failed", e)
		}
	}
	
	fun requestDataAccess(context: Context) {
		try {
			val intent = Intent(ACTION_ASK_FOR_DATA_PERMISSION).apply {
				setClassName(POWERAMP_PACKAGE, API_RECEIVER)
				putExtra("package", context.packageName)
			}
			context.sendBroadcast(intent)
		} catch (e: Exception) {
			Log.w(TAG, "requestDataAccess failed", e)
		}
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
}
