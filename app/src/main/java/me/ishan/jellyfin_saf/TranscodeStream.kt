package me.ishan.jellyfin_saf

import android.util.Log
import org.jellyfin.sdk.model.UUID
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.max
import kotlin.math.min

/**
 * 转码档的“边下边播”句柄。
 *
 * openDocument() 立刻返回一个 ProxyFileDescriptor，后台把转码流写进 .part 文件；
 * 播放器读某段数据时，这里阻塞到对应字节落盘为止（阻塞的是播放器的读取线程，
 * 不是 Poweramp 的 UI 线程，所以库界面不会卡死）。
 *
 * 总长度是估算值（码率 × 时长），下载完成后用真实长度修正缓存状态。
 */
class TranscodeStream(
	val trackId: UUID,
	val file: File,
	val totalSize: Long
) {
	private val lock = Object()
	private val readChannel: FileChannel = FileChannel.open(
		file.toPath(),
		StandardOpenOption.READ
	)
	
	@Volatile
	private var downloaded: Long = 0L
	
	@Volatile
	private var finished: Boolean = false
	
	@Volatile
	private var closed: Boolean = false
	
	@Volatile
	private var failed: Boolean = false
	
	private val readCount = java.util.concurrent.atomic.AtomicInteger(0)
	private val openedAt = System.currentTimeMillis()
	
	fun onProgress(position: Long) {
		synchronized(lock) {
			downloaded = max(downloaded, position)
			lock.notifyAll()
		}
	}
	
	fun onFinished(success: Boolean) {
		synchronized(lock) {
			finished = true
			failed = !success
			lock.notifyAll()
		}
	}
	
	fun read(data: ByteArray, offset: Long, size: Int): Int {
		if (closed) return 0
		
		val n = readCount.incrementAndGet()
		if (n <= 10) {
			Log.d(
				TAG,
				"[$trackId] read#$n offset=$offset size=$size downloaded=$downloaded " +
					"total=$totalSize t=${System.currentTimeMillis() - openedAt}ms"
			)
		}
		
		// 播放器常见的“读文件尾探测容器”行为：转码流是顺序的，等它下到末尾
		// 就等于等整首。ADTS AAC 不需要尾部元数据，直接给 EOF 即可。
		if (!finished && offset > downloaded && size <= TAIL_PROBE_SIZE &&
			offset >= totalSize - TAIL_PROBE_WINDOW
		) {
			Log.d(TAG, "[$trackId] tail probe at $offset, answering EOF immediately")
			return 0
		}
		
		synchronized(lock) {
			val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
			while (!closed && !finished && downloaded < offset + size) {
				val remaining = deadline - System.currentTimeMillis()
				if (remaining <= 0) break
				try {
					lock.wait(remaining)
				} catch (_: InterruptedException) {
					break
				}
			}
		}
		
		if (closed) return 0
		// 用下载进度而不是 file.length()：文件被 rename 之后路径已不存在
		val available = downloaded
		if (offset >= available) {
			if (finished) {
				if (failed) throw IOException("transcode failed for $trackId")
				return 0
			}
			// 等不到数据不能伪装成 EOF，否则播放器会静默跳下一首
			throw IOException("timed out waiting for transcode data at $offset")
		}
		val toRead = min(size.toLong(), available - offset).toInt()
		if (toRead <= 0) return 0
		return try {
			val buffer = java.nio.ByteBuffer.wrap(data, 0, toRead)
			var total = 0
			while (buffer.hasRemaining()) {
				val read = readChannel.read(buffer, offset + total)
				if (read <= 0) break
				total += read
			}
			total
		} catch (e: Exception) {
			Log.w(TAG, "[$trackId] read failed: ${e.message}")
			0
		}
	}
	
	fun isClosed(): Boolean = closed
	
	fun hasFailed(): Boolean = failed
	
	fun close() {
		if (closed) return
		closed = true
		synchronized(lock) { lock.notifyAll() }
		try {
			readChannel.close()
		} catch (_: Exception) {
		}
	}
	
	companion object {
		private const val TAG = "TranscodeStream"
		private const val READ_TIMEOUT_MS = 20_000L
		private const val TAIL_PROBE_SIZE = 32 * 1024
		private const val TAIL_PROBE_WINDOW = 64 * 1024L
	}
}
