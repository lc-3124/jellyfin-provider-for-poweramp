package me.ishan.jellyfin_saf.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun JellyfinSaf(
	style: AppStyle,
	content: @Composable () -> Unit
) {
	MaterialTheme(
		colorScheme = style.colorScheme,
		typography = Typography,
		content = content
	)
}
