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
 * 按服务器上的物理路径重建目录树。
 *
 * Jellyfin 的界面按标签聚合，但每个音频条目都保留了原始 Path。这里把所有
 * Path 拉下来、剥掉公共前缀，做成 目录 -> 子目录/曲目 的树，供 SAF 的
 * "Jellyfin Folders" 根使用。
 */
object FolderLibrary {
	private const val TAG = "FolderLibrary"
	private const val FILE_NAME = "library_folders.json"
	
	data class TrackRef(val trackId: UUID, val fileName: String)
	
	data class Node(
		val path: String,
		val name: String,
		val dirs: List<String>,
		val tracks: List<TrackRef>
	)
	
	data class Tree(val roots: List<String>, val nodes: Map<String, Node>) {
		fun node(path: String): Node? = nodes[path]
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
	
	/** 重新抓取路径并建树，返回索引到的曲目数。 */
	suspend fun rebuild(context: Context, client: JellyfinClientManager): Int =
		withContext(Dispatchers.IO) {
			val items = client.getAllTrackPaths()
			if (items.isEmpty()) return@withContext 0
			
			val split = items.map { (id, path) ->
				id to path.replace('\\', '/').split('/').filter { it.isNotBlank() }
			}.filter { it.second.isNotEmpty() }
			if (split.isEmpty()) return@withContext 0
			
			val common = commonDirectoryPrefix(split.map { it.second })
			
			val nodes = HashMap<String, MutableNode>()
			nodes[""] = MutableNode("", "Jellyfin Folders")
			
			split.forEach { (id, segments) ->
				val relative = segments.drop(common.size)
				if (relative.isEmpty()) return@forEach
				val fileName = relative.last()
				val dirSegments = relative.dropLast(1)
				
				var parentPath = ""
				dirSegments.forEach { segment ->
					val childPath =
						if (parentPath.isEmpty()) segment else "$parentPath/$segment"
					val parent = nodes.getOrPut(parentPath) {
						MutableNode(parentPath, if (parentPath.isEmpty()) "Jellyfin Folders" else parentPath.substringAfterLast('/'))
					}
					if (childPath !in parent.dirs) parent.dirs.add(childPath)
					nodes.getOrPut(childPath) { MutableNode(childPath, segment) }
					parentPath = childPath
				}
				
				val leaf = nodes.getOrPut(parentPath) {
					MutableNode(parentPath, if (parentPath.isEmpty()) "Jellyfin Folders" else parentPath.substringAfterLast('/'))
				}
				leaf.tracks.add(TrackRef(id, fileName))
			}
			
			val finalNodes = nodes.mapValues { (_, node) ->
				Node(
					path = node.path,
					name = node.name,
					dirs = node.dirs.sorted(),
					tracks = node.tracks.sortedBy { it.fileName.lowercase() }
				)
			}
			val tree = Tree(finalNodes[""]?.dirs ?: emptyList(), finalNodes)
			save(context, tree)
			cached = tree
			Log.i(TAG, "Folder tree: ${finalNodes.size} dirs, ${items.size} tracks")
			items.size
		}
	
	private fun commonDirectoryPrefix(paths: List<List<String>>): List<String> {
		val dirs = paths.map { it.dropLast(1) }
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
	
	private data class MutableNode(
		val path: String,
		val name: String,
		val dirs: MutableList<String> = mutableListOf(),
		val tracks: MutableList<TrackRef> = mutableListOf()
	)
	
	private fun file(context: Context) = File(context.filesDir, FILE_NAME)
	
	private fun save(context: Context, tree: Tree) {
		try {
			val root = JSONObject()
			root.put("roots", JSONArray(tree.roots))
			val nodes = JSONObject()
			tree.nodes.forEach { (path, node) ->
				val obj = JSONObject()
				obj.put("name", node.name)
				obj.put("dirs", JSONArray(node.dirs))
				val tracks = JSONArray()
				node.tracks.forEach { ref ->
					tracks.put(JSONArray().apply {
						put(ref.trackId.toString())
						put(ref.fileName)
					})
				}
				obj.put("tracks", tracks)
				nodes.put(path, obj)
			}
			root.put("nodes", nodes)
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
			val rootsArray = root.getJSONArray("roots")
			val roots = (0 until rootsArray.length()).map { rootsArray.getString(it) }
			val nodesObj = root.getJSONObject("nodes")
			val nodes = HashMap<String, Node>()
			nodesObj.keys().forEach { path ->
				val obj = nodesObj.getJSONObject(path)
				val dirsArray = obj.getJSONArray("dirs")
				val dirs = (0 until dirsArray.length()).map { dirsArray.getString(it) }
				val tracksArray = obj.getJSONArray("tracks")
				val tracks = (0 until tracksArray.length()).map { i ->
					val pair = tracksArray.getJSONArray(i)
					TrackRef(UUID.fromString(pair.getString(0)), pair.getString(1))
				}
				nodes[path] = Node(path, obj.getString("name"), dirs, tracks)
			}
			Tree(roots, nodes)
		} catch (e: Exception) {
			Log.e(TAG, "Cannot load folder tree", e)
			null
		}
	}
}
