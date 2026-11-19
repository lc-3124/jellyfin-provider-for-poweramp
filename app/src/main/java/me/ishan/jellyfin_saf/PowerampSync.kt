package me.ishan.jellyfin_saf

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.UUID
import org.json.JSONArray
import java.io.File

/**
 * 两个手动触发的同步方向：
 *
 * 拉取 pullRemoteFavourites：
 *   把 Jellyfin 远端收藏逐首写进 Poweramp（rating=5）。只增不减，不下载。
 *   增量含义：已经在 poweramp_pushed.json 里记过的曲目跳过，避免把用户在
 *   Poweramp 里手动取消的喜欢又加回去。
 *
 * 推送 planPush / applyPush：
 *   Poweramp 里 rating 1-5 的曲目全部设为 Jellyfin 收藏；
 *   Jellyfin 已收藏、Poweramp 认识却未喜欢的 → 取消收藏。
 *   planPush 只计算不写入，界面弹确认框；确认后 applyPush 才真正执行。
 *
 * 更新资料库（扫描）不会再自动跑任何一个方向。
 */
object PowerampSync {
	private const val TAG = "PowerampSync"
	private const val PUSHED_FILE = "poweramp_pushed.json"
	
	/** 拉取结果。 */
	data class PullResult(
		val written: Int = 0,
		val totalRemote: Int = 0,
		val needsPermission: Boolean = false,
		val error: String? = null
	)
	
	/** 推送计划：预览与执行共用。只有 counts 会显示在确认框里。 */
	data class PushPlan(
		val added: List<UUID> = emptyList(),   // Poweramp 喜欢、Jellyfin 未收藏
		val removed: List<UUID> = emptyList(), // Jellyfin 收藏、Poweramp 认识但未喜欢
		val liked: Set<UUID> = emptySet()      // Poweramp 里 rating>=1 的全部曲目
	) {
		val total: Int get() = added.size + removed.size
	}
	
	data class PushResult(
		val added: Int = 0,
		val removed: Int = 0,
		val failed: Int = 0,
		val needsPermission: Boolean = false,
		val error: String? = null
	)
	
	/**
	 * 拉取：远端收藏 → Poweramp（rating=5）。
	 * 只处理还没同步过的曲目；Poweramp 里已被用户取消的喜欢不会强行加回。
	 */
	suspend fun pullRemoteFavourites(
		context: Context, client: JellyfinClientManager
	): PullResult = withContext(Dispatchers.IO) {
		val favourites = client.getFavouriteTrackIds()
		if (favourites.isEmpty()) {
			return@withContext PullResult(totalRemote = 0)
		}
		
		val rows = PowerampProvider.queryRows(context)
			?: return@withContext PullResult(needsPermission = true)
		val byTrack = rows.mapNotNull { row -> row.trackId?.let { it to row } }.toMap()
		val pushed = loadPushed(context)
		var written = 0
		
		favourites.forEach { trackId ->
			val row = byTrack[trackId] ?: return@forEach
			val key = trackId.toString()
			when {
				row.rating > 0 -> pushed.add(key) // 已经喜欢，记下来即可
				key !in pushed -> {
					if (PowerampProvider.setRating(context, row.rowId, 5)) {
						written++
						pushed.add(key)
					}
				}
				// rating == 0 且 key in pushed：用户手动取消过，不强行加回
			}
		}
		
		savePushed(context, pushed)
		if (written > 0) PowerampProvider.reloadData(context)
		Log.i(TAG, "Pulled $written of ${favourites.size} remote favourites")
		PullResult(written = written, totalRemote = favourites.size)
	}
	
	/** 计算推送计划，不写入任何东西。返回 null 表示没有 Poweramp 访问权限。 */
	suspend fun planPush(
		context: Context, client: JellyfinClientManager
	): PushPlan? = withContext(Dispatchers.IO) {
		val rows = PowerampProvider.queryRows(context) ?: return@withContext null
		
		val known = rows.mapNotNull { it.trackId }.toSet()
		val liked = rows.filter { it.rating >= 1 }.mapNotNull { it.trackId }.toSet()
		val serverFavourites = client.getFavouriteTrackIds().toSet()
		
		val plan = PushPlan(
			added = (liked - serverFavourites).sortedBy { it.toString() },
			// 只处理 Poweramp 曲库里确实存在的歌，避免误删它没扫描到的远端收藏
			removed = (serverFavourites - liked).filter { it in known }.sortedBy { it.toString() },
			liked = liked
		)
		Log.i(TAG, "Push plan: +${plan.added.size} -${plan.removed.size} liked=${liked.size}")
		plan
	}
	
	/** 执行计划：写入 Jellyfin 收藏，并维护 poweramp_pushed 记录。 */
	suspend fun applyPush(
		context: Context, client: JellyfinClientManager, plan: PushPlan
	): PushResult = withContext(Dispatchers.IO) {
		var added = 0
		var removed = 0
		var failed = 0
		
		plan.added.forEach { trackId ->
			if (client.setFavourite(trackId, true)) added++ else failed++
		}
		plan.removed.forEach { trackId ->
			if (client.setFavourite(trackId, false)) removed++ else failed++
		}
		
		val pushed = loadPushed(context)
		plan.liked.forEach { pushed.add(it.toString()) }
		plan.removed.forEach { pushed.remove(it.toString()) }
		savePushed(context, pushed)
		
		Log.i(TAG, "Push applied: +$added -$removed failed=$failed")
		PushResult(added = added, removed = removed, failed = failed)
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
