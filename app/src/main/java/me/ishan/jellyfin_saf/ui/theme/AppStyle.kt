package me.ishan.jellyfin_saf.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 四套固定的页面风格。按钮只用拼色图标展示，所以不带宽窄/文案。
 * 渐变两端给得比纯色板更“冲”一点，卡片上的渐变才明显。
 */
enum class AppStyle(val id: String, val gradient: List<Color>) {
	DARK_BLUE(
		id = "dark_blue",
		gradient = listOf(Color(0xFF005E8F), Color(0xFF8B2FC9))
	),
	DARK_GREEN(
		id = "dark_green",
		gradient = listOf(Color(0xFF0B6B3A), Color(0xFFD97706))
	),
	LIGHT_BLUE(
		id = "light_blue",
		gradient = listOf(Color(0xFF0667A8), Color(0xFFA33FC4))
	),
	LIGHT_GREEN(
		id = "light_green",
		gradient = listOf(Color(0xFF1F7A46), Color(0xFFE08A1E))
	);
	
	val colorScheme: ColorScheme
		get() = when (this) {
			DARK_BLUE -> darkBlueScheme
			DARK_GREEN -> darkGreenScheme
			LIGHT_BLUE -> lightBlueScheme
			LIGHT_GREEN -> lightGreenScheme
		}
	
	companion object {
		fun fromId(id: String?): AppStyle? = entries.firstOrNull { it.id == id }
	}
}

private val darkBlueScheme = darkColorScheme(
	primary = Color(0xFF6EC6F0),
	onPrimary = Color(0xFF00344A),
	primaryContainer = Color(0xFF0B4A6B),
	onPrimaryContainer = Color(0xFFC9E9FA),
	secondary = Color(0xFFD8B4F8),
	onSecondary = Color(0xFF3D1B57),
	secondaryContainer = Color(0xFF4B2A66),
	onSecondaryContainer = Color(0xFFF0DBFF),
	tertiary = Color(0xFFFFB4AB),
	onTertiary = Color(0xFF690005),
	tertiaryContainer = Color(0xFF93000A),
	onTertiaryContainer = Color(0xFFFFDAD6),
	background = Color(0xFF0B0F14),
	onBackground = Color(0xFFE6EBF3),
	surface = Color(0xFF0B0F14),
	onSurface = Color(0xFFE6EBF3),
	surfaceVariant = Color(0xFF161D26),
	onSurfaceVariant = Color(0xFFAEB8C7),
	outline = Color(0xFF3C4657),
	error = Color(0xFFFFB4AB),
	onError = Color(0xFF690005),
	errorContainer = Color(0xFF93000A),
	onErrorContainer = Color(0xFFFFDAD6)
)

private val darkGreenScheme = darkColorScheme(
	primary = Color(0xFF8FD9A8),
	onPrimary = Color(0xFF00391C),
	primaryContainer = Color(0xFF0F5132),
	onPrimaryContainer = Color(0xFFC8F5D8),
	secondary = Color(0xFFF0D878),
	onSecondary = Color(0xFF3B2F00),
	secondaryContainer = Color(0xFF564500),
	onSecondaryContainer = Color(0xFFFBEFA8),
	tertiary = Color(0xFFFFB68A),
	onTertiary = Color(0xFF4F1F00),
	tertiaryContainer = Color(0xFF713400),
	onTertiaryContainer = Color(0xFFFFDCC6),
	background = Color(0xFF0B100D),
	onBackground = Color(0xFFE5EFE6),
	surface = Color(0xFF0B100D),
	onSurface = Color(0xFFE5EFE6),
	surfaceVariant = Color(0xFF17201A),
	onSurfaceVariant = Color(0xFFAFBFAF),
	outline = Color(0xFF43584A),
	error = Color(0xFFFFB4AB),
	onError = Color(0xFF690005),
	errorContainer = Color(0xFF93000A),
	onErrorContainer = Color(0xFFFFDAD6)
)

private val lightBlueScheme = lightColorScheme(
	primary = Color(0xFF0B6FA8),
	onPrimary = Color(0xFFFFFFFF),
	primaryContainer = Color(0xFFCFE8FA),
	onPrimaryContainer = Color(0xFF003049),
	secondary = Color(0xFF7B3FA0),
	onSecondary = Color(0xFFFFFFFF),
	secondaryContainer = Color(0xFFEFD8FF),
	onSecondaryContainer = Color(0xFF2E0B47),
	tertiary = Color(0xFFB3261E),
	onTertiary = Color(0xFFFFFFFF),
	tertiaryContainer = Color(0xFFF9DEDC),
	onTertiaryContainer = Color(0xFF410E0B),
	background = Color(0xFFF5F8FC),
	onBackground = Color(0xFF101418),
	surface = Color(0xFFF5F8FC),
	onSurface = Color(0xFF101418),
	surfaceVariant = Color(0xFFE8EEF7),
	onSurfaceVariant = Color(0xFF44505E),
	outline = Color(0xFF7A8494),
	error = Color(0xFFB3261E),
	onError = Color(0xFFFFFFFF),
	errorContainer = Color(0xFFF9DEDC),
	onErrorContainer = Color(0xFF410E0B)
)

private val lightGreenScheme = lightColorScheme(
	primary = Color(0xFF2E7D4F),
	onPrimary = Color(0xFFFFFFFF),
	primaryContainer = Color(0xFFC6F0D3),
	onPrimaryContainer = Color(0xFF00391C),
	secondary = Color(0xFF9A6B00),
	onSecondary = Color(0xFFFFFFFF),
	secondaryContainer = Color(0xFFFBE7A6),
	onSecondaryContainer = Color(0xFF2F2100),
	tertiary = Color(0xFFC25A1A),
	onTertiary = Color(0xFFFFFFFF),
	tertiaryContainer = Color(0xFFFFDCC6),
	onTertiaryContainer = Color(0xFF4F1F00),
	background = Color(0xFFF5FAF5),
	onBackground = Color(0xFF0F1511),
	surface = Color(0xFFF5FAF5),
	onSurface = Color(0xFF0F1511),
	surfaceVariant = Color(0xFFE5F0E6),
	onSurfaceVariant = Color(0xFF445247),
	outline = Color(0xFF6F8274),
	error = Color(0xFFB3261E),
	onError = Color(0xFFFFFFFF),
	errorContainer = Color(0xFFF9DEDC),
	onErrorContainer = Color(0xFF410E0B)
)
