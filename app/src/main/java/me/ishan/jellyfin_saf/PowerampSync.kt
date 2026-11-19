package me.ishan.jellyfin_saf

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * 两个方向的同步，语义由用户定义：
 *
 * A. pushServerFavourites —— 只在扫描库后跑：
 *    把 Jellyfin 远端收藏逐首写进 Poweramp（rating=5，已有 1-5 不动）。
 *    只增不减，不下载。
 *
 * B. mirrorToJellyfin —— 只在用户手动同步时跑：
 *    Poweramp 里 rating 1-5 的曲目全部设为 Jellyfin 收藏；
 *    Jellyfin 已收藏但 Poweramp 未喜欢（且 Poweramp 认识这首歌）的 → 取消收藏。
 *    然后由调用方下载收藏。
 */
object PowerampSync {
	private const val TAG = "PowerampSync"
	private const val PUSHED_FILE = "poweramp_pushed.json"
	
	data class MirrorResult(
		val added: Int = 0,
		val removed: Int = 0,
		val needsPermission: Boolean = false,
		val error: String? = null
	) {
		val total: Int get() = added + removed
	}
	
	/** A：远端收藏 → Poweramp。返回写入数量；-1 表示没有权限。 */
	suspend fun pushServerFavourites(
		context: Context,
		client: JellyfinClientManager
	): Int = withContext(Dispatchers.IO) {
		val favourites = client.getFavouriteTrackIds()
		if (favourites.isEmpty()) return@withContext 0
		
		val rows = PowerampProvider.queryRows(context) ?: return@withContext -1
		val byTrack = rows.mapNotNull { row ->
			row.trackId?.let { it to row }
		}.toMap()
		val pushed = loadPushed(context)
		var written = 0
		
		favourites.forEach { trackId ->
			val row = byTrack[trackId] ?: return@forEach
			if (row.rating == 0) {
				if (PowerampProvider.setRating(context, row.rowId, 5)) {
					written++
					pushed.add(trackId.toString())
				}
			} else {
				// 已经是喜欢，记下来，后面取消同步时才知道这首歌归我们管
				pushed.add(trackId.toString())
			}
		}
		
		savePushed(context, pushed)
		if (written > 0) PowerampProvider.reloadData(context)
		Log.i(TAG, "Pushed $written remote favourites to Poweramp")
		written
	}
	
	/** B：Poweramp 喜欢集合 → 镜像到 Jellyfin。 */
	suspend fun mirrorToJellyfin(
		context: Context,
		client: JellyfinClientManager
	): MirrorResult = withContext(Dispatchers.IO) {
		val rows = PowerampProvider.queryRows(context)
			?: return@withContext MirrorResult(needsPermission = true)
		
		val known = rows.mapNotNull { it.trackId }.toSet()
		val liked = rows.filter { it.rating >= 1 }.mapNotNull { it.trackId }.toSet()
		val serverFavourites = client.getFavouriteTrackIds().toSet()
		
		var added = 0
		var removed = 0
		
		(liked - serverFavourites).forEach { trackId ->
			if (client.setFavourite(trackId, true)) added++
		}
		(serverFavourites - liked).forEach { trackId ->
			// 只处理 Poweramp 曲库里确实存在的歌，避免误删它没扫描到的远端收藏
			if (trackId in known && client.setFavourite(trackId, false)) removed++
		}
		
		val pushed = loadPushed(context)
		liked.forEach { pushed.add(it.toString()) }
		(serverFavourites - liked).forEach { pushed.remove(it.toString()) }
		savePushed(context, pushed)
		
		Log.i(TAG, "Mirror: added=$added removed=$removed liked=${liked.size}")
		MirrorResult(added, removed)
	}
	
	private fun pushedFile(context: Context) = File(context.filesDir, PUSHED_FILE)
	
	private fun loadPushed(context: Context): MutableSet<String> {
		return try {
			val file = pushedFile(context)
			if (!file.exists()) return mutableSetOf()
			val array = JSONArray(file.readText())
			val set = mutableSetOf<String>()
			for (i in 0 until array.length()) set.add(array.getString(i))
			set
		} catch (e: Exception) {
			Log.w(TAG, "Cannot read pushed set", e)
			mutableSetOf()
		}
	}
	
	private fun savePushed(context: Context, set: Set<String>) {
		try {
			val array = JSONArray()
			set.forEach { array.put(it) }
			pushedFile(context).writeText(array.toString())
		} catch (e: Exception) {
			Log.w(TAG, "Cannot save pushed set", e)
		}
	}
}
