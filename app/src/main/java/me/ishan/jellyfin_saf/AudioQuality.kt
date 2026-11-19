package me.ishan.jellyfin_saf

/**
 * 音质档位。
 *
 * 缓存文件命名约定（每首歌只保留一份，档位写在文件名里）：
 *   <trackId>.cache            -> LOSSLESS（沿用旧命名，老缓存天然归为无损）
 *   <trackId>.aac128.cache     -> MEDIUM
 *   <trackId>.aac64.cache      -> LOW
 */
enum class AudioQuality(
	val rank: Int,
	val label: String,
	val bitrate: Int?
) {
	LOW(1, "Low (64k)", 64_000),
	MEDIUM(2, "Medium (128k)", 128_000),
	LOSSLESS(3, "Lossless", null);
	
	/** 缓存文件名后缀，不含 trackId。 */
	val fileNameSuffix: String
		get() = when (this) {
			LOSSLESS -> ".cache"
			MEDIUM -> ".aac128.cache"
			LOW -> ".aac64.cache"
		}
	
	/** 统计里用的短标签。 */
	val shortLabel: String
		get() = when (this) {
			LOSSLESS -> "Lossless"
			MEDIUM -> "128k"
			LOW -> "64k"
		}
	
	/** 请求 Jellyfin 时用的参数。 */
	val codec: String? get() = if (this == LOSSLESS) null else "aac"
	
	companion object {
		fun fromName(fileName: String): AudioQuality = when {
			fileName.endsWith(".aac128.cache") -> MEDIUM
			fileName.endsWith(".aac64.cache") -> LOW
			else -> LOSSLESS
		}
		
		/** 从文件名里剥掉质量后缀，得到 trackId 字符串。 */
		fun trackIdPart(fileName: String): String =
			fileName.removeSuffix(".cache")
				.removeSuffix(".aac128")
				.removeSuffix(".aac64")
	}
}
