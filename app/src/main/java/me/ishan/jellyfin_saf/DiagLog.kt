package me.ishan.jellyfin_saf

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 持久化诊断日志：写到 files/diag.log，并可从 ⓘ 导出到 Download。
 * 只记录播放链路（打开文档、HTTP 状态、下载字节数、异常），不记录 token。
 */
object DiagLog {
	private const val TAG = "DiagLog"
	private const val FILE_NAME = "diag.log"
	private const val MAX_BYTES = 512 * 1024L
	private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
	
	@Synchronized
	fun i(tag: String, message: String) {
		val line = "${stamp.format(Date())} [$tag] $message"
		Log.i(TAG, line)
		val context = JellyfinApp.context ?: return
		try {
			val file = File(context.filesDir, FILE_NAME)
			if (file.length() > MAX_BYTES) {
				val old = File(context.filesDir, "$FILE_NAME.1")
				old.delete()
				file.renameTo(old)
			}
			file.appendText(line + "\n")
		} catch (_: Exception) {
		}
	}
	
	fun read(context: Context): String = try {
		File(context.filesDir, FILE_NAME).readText()
	} catch (e: Exception) {
		"无法读取日志: ${e.message}\n"
	}
	
	/** 导出到系统 Download 目录，返回可读路径；失败返回 null。 */
	fun export(context: Context): String? {
		val content = buildString {
			append("Jellyfin Provider diagnostics\n")
			append("time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append("\n")
			append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n")
			append("android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
			append("app: ").append(context.getString(R.string.app_version)).append("\n")
			append("---- log ----\n")
			append(read(context))
		}
		val name = "JellyfinProvider-diag-${System.currentTimeMillis()}.txt"
		return try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				val values = ContentValues().apply {
					put(MediaStore.Downloads.DISPLAY_NAME, name)
					put(MediaStore.Downloads.MIME_TYPE, "text/plain")
					put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
				}
				val uri = context.contentResolver.insert(
					MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
				) ?: return null
				context.contentResolver.openOutputStream(uri)?.use {
					it.write(content.toByteArray())
				}
				"Download/$name"
			} else {
				val dir = context.getExternalFilesDir(null) ?: context.filesDir
				val file = File(dir, name)
				file.writeText(content)
				file.absolutePath
			}
		} catch (e: Exception) {
			Log.w(TAG, "export failed", e)
			null
		}
	}
}
