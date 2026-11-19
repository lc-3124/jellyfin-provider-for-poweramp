package me.ishan.jellyfin_saf

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.StrictMode
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ishan.jellyfin_saf.db.AppDatabase
import me.ishan.jellyfin_saf.ui.theme.JellyfinSaf

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		// TODO: remove later ?
		StrictMode.setVmPolicy(
			StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog()
				.penaltyDropBox().build()
		)
		
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			JellyfinSaf {
				Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
					JellyfinSettings(
						modifier = Modifier.padding(innerPadding)
					)
				}
			}
		}
	}
}

@SuppressLint("DefaultLocale")
@Composable
fun JellyfinSettings(modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val manager = remember { JellyfinClientManager(context) }
	val playbackManager = remember { TrackPlaybackManagerSingleton.getInstance(context) }
	val uiPrefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
	var language by remember {
		mutableStateOf(AppLanguage.fromId(uiPrefs.getString("language", null)))
	}
	val t = remember(language) { AppLanguage.strings(language) }
	var showInfo by remember { mutableStateOf(false) }
	val scope = rememberCoroutineScope()
	
	var url by remember { mutableStateOf(manager.getUrl()) }
	var username by remember { mutableStateOf(manager.getUsername()) }
	var password by remember { mutableStateOf(manager.getPassword()) }
	var maxCacheSize by remember { mutableStateOf(manager.getMaxCacheSize().toString()) }
	var isLoading by remember { mutableStateOf(false) }
	var isAuthenticated by remember { mutableStateOf(manager.isAuthenticated()) }
	var cacheBreakdown by remember { mutableStateOf<TrackPlaybackManager.CacheSizeBreakdown?>(null) }
	var isEvicting by remember { mutableStateOf(false) }
	var isCompleting by remember { mutableStateOf(false) }
	var completeProgress by remember { mutableStateOf("") }
	var isSyncing by remember { mutableStateOf(false) }
	var syncProgress by remember { mutableStateOf("") }
	var favouriteCount by remember { mutableStateOf(0) }
	var quality by remember { mutableStateOf(manager.getQuality()) }
	var showDeleteMenu by remember { mutableStateOf(false) }
	var showResetConfirm by remember { mutableStateOf(false) }
	var isScanning by remember { mutableStateOf(false) }
	var scanProgress by remember { mutableStateOf("") }
	var libraryStats by remember { mutableStateOf<Pair<Int, Int>?>(null) }
	
	val performScan = {
		isScanning = true
		scanProgress = t.scanStarting
		scope.launch(Dispatchers.IO) {
			try {
				val albums = manager.getAlbums(limit = 100000)
				var tracksScanned = 0
				for ((index, album) in albums.withIndex()) {
					if (!isScanning) break // allow cancellation
					
					withContext(Dispatchers.Main) {
						scanProgress = t.scanningAlbum(index + 1, albums.size, album.name ?: "")
					}
					val tracks = manager.getAlbumTracks(album.id)
					for (track in tracks) {
						manager.getTrackMetadata(track.id)
						tracksScanned++
					}
				}
				withContext(Dispatchers.Main) {
					scanProgress = t.scanComplete(albums.size, tracksScanned)
					Toast.makeText(context, scanProgress, Toast.LENGTH_LONG).show()
					isScanning = false
					
					// 扫描完成后把远端收藏增量写进 Poweramp（只增不减、不下载）
					scope.launch(Dispatchers.IO) {
						val pushed = PowerampSync.pushServerFavourites(context, manager)
						if (pushed > 0) {
							withContext(Dispatchers.Main) {
								Toast.makeText(
									context, t.pushedToPoweramp(pushed), Toast.LENGTH_LONG
								).show()
							}
						}
					}
					
					// trigger a cache breakdown refresh
					scope.launch(Dispatchers.IO) {
						val breakdown = playbackManager.getCacheSizeBreakdown()
						withContext(Dispatchers.Main) {
							cacheBreakdown = breakdown
						}
					}
				}
			} catch (e: Exception) {
				withContext(Dispatchers.Main) {
					scanProgress = t.scanFailed(e.message ?: "")
					Toast.makeText(context, scanProgress, Toast.LENGTH_LONG).show()
					isScanning = false
				}
			}
		}
	}
	
	LaunchedEffect(Unit) {
		scope.launch(Dispatchers.IO) {
			val breakdown = playbackManager.getCacheSizeBreakdown()
			val favourites = AppDatabase.getDatabase(context).trackDao().getFavouriteCount()
			withContext(Dispatchers.Main) {
				cacheBreakdown = breakdown
				favouriteCount = favourites
			}
		}
	}
	
	LaunchedEffect(isAuthenticated) {
		if (isAuthenticated) {
			scope.launch(Dispatchers.IO) {
				val stats = manager.getLibraryStats()
				withContext(Dispatchers.Main) {
					libraryStats = stats
				}
			}
		} else {
			libraryStats = null
		}
	}
	
	Column(
		modifier = modifier
			.fillMaxSize()
			.padding(16.dp)
			.verticalScroll(rememberScrollState()),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Top
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 16.dp, bottom = 16.dp),
			verticalAlignment = Alignment.Top
		) {
			Text(
				text = t.brand,
				style = MaterialTheme.typography.headlineMedium,
				modifier = Modifier.weight(1f)
			)
			TextButton(onClick = { showInfo = true }) {
				Text(text = "ⓘ", style = MaterialTheme.typography.headlineSmall)
			}
		}
		
		if (isAuthenticated) {
			Card(
				modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
					containerColor = MaterialTheme.colorScheme.primaryContainer
				)
			) {
				Column(
					modifier = Modifier.padding(16.dp),
				) {
					Text(
						text = "✓ " + t.connected,
						style = MaterialTheme.typography.titleLarge,
						color = MaterialTheme.colorScheme.primary
					)
					Text(
						text = "${t.user}: ${manager.getUsername()}",
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.padding(top = 8.dp)
					)
					Text(
						text = manager.getUrl(),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}
			
			Spacer(modifier = Modifier.height(16.dp))
			
			Button(
				onClick = {
					scope.launch {
						manager.logout()
						// 不在 SharedPreferences 里留明文密码
						manager.clearStoredPassword()
						password = ""
						isAuthenticated = false
					}
				}, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(
					containerColor = MaterialTheme.colorScheme.error
				)
			) {
				Text(t.logout)
			}
		} else {
			OutlinedTextField(
				value = url,
				onValueChange = { url = it },
				label = { Text(t.urlLabel) },
				placeholder = { Text("https://jellyfin.example.com") },
				modifier = Modifier.fillMaxWidth(),
				singleLine = true
			)
			
			Spacer(modifier = Modifier.height(12.dp))
			
			OutlinedTextField(
				value = username,
				onValueChange = { username = it },
				label = { Text(t.usernameLabel) },
				modifier = Modifier.fillMaxWidth(),
				singleLine = true
			)
			
			Spacer(modifier = Modifier.height(12.dp))
			
			OutlinedTextField(
				value = password,
				onValueChange = { password = it },
				label = { Text(t.passwordLabel) },
				modifier = Modifier.fillMaxWidth(),
				visualTransformation = PasswordVisualTransformation(),
				singleLine = true
			)
			
			Spacer(modifier = Modifier.height(24.dp))
			
			Button(
				onClick = {
					isLoading = true
					manager.updateCredentials(url, username, password)
					
					scope.launch {
						val success = manager.login()
						isLoading = false
						
						if (success) {
							isAuthenticated = true
							
							context.contentResolver.notifyChange(
								DocumentsContract.buildRootsUri(
									context.applicationContext.getString(
										R.string.documents_authority
									)
								), null
							)
							
							Toast.makeText(
								context, t.loginSuccess, Toast.LENGTH_SHORT
							).show()
							
							performScan()
						} else {
							Toast.makeText(
								context, t.loginFailed, Toast.LENGTH_LONG
							).show()
						}
					}
				},
				enabled = !isLoading && url.isNotBlank() && username.isNotBlank(),
				modifier = Modifier.fillMaxWidth()
			) {
				if (isLoading) {
					CircularProgressIndicator(
						modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary
					)
					Spacer(modifier = Modifier.width(8.dp))
					Text(t.connecting)
				} else {
					Text(t.saveLogin)
				}
			}
			
			Spacer(modifier = Modifier.height(12.dp))
			
			OutlinedButton(
				onClick = {
					isLoading = true
					scope.launch {
						val success = manager.testConnection()
						isLoading = false
						
						Toast.makeText(
							context,
							if (success) t.connOk else t.connFail,
							Toast.LENGTH_SHORT
						).show()
					}
				}, enabled = !isLoading && url.isNotBlank(), modifier = Modifier.fillMaxWidth()
			) {
				Text(t.testConnection)
			}
		}
		
		HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))
		
		Text(
			text = t.appSettings,
			style = MaterialTheme.typography.titleMedium,
			modifier = Modifier.align(Alignment.Start)
		)
		
		Spacer(modifier = Modifier.height(16.dp))
		
		if (isAuthenticated) {
			libraryStats?.let { stats ->
				Text(
					text = t.libraryStats(stats.first, stats.second),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.padding(bottom = 8.dp)
						.align(Alignment.Start)
				)
			} ?: Text(
				text = t.fetchingStats,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier
					.padding(bottom = 8.dp)
					.align(Alignment.Start)
			)
		}
		
		// Scan Library Button
		Button(
			onClick = { performScan() },
			modifier = Modifier.fillMaxWidth(),
			enabled = !isScanning && !isEvicting && isAuthenticated,
			colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
		) {
			if (isScanning) {
				CircularProgressIndicator(
					modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary
				)
				Spacer(modifier = Modifier.width(8.dp))
				Text(scanProgress)
			} else {
				Text(t.scanLibrary)
			}
		}
		
		Spacer(modifier = Modifier.height(16.dp))
		
				Text(
			text = t.audioQuality,
			style = MaterialTheme.typography.titleSmall,
			modifier = Modifier.align(Alignment.Start)
		)
		Spacer(modifier = Modifier.height(6.dp))
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			AudioQuality.entries.reversed().forEach { option ->
				val selected = quality == option
				val label = when (option) {
					AudioQuality.LOSSLESS -> t.lossless
					AudioQuality.MEDIUM -> t.medium
					AudioQuality.LOW -> t.low
				}
				val onClick = {
					quality = option
					manager.setQuality(option)
				}
				if (selected) {
					Button(
						onClick = onClick,
						modifier = Modifier.weight(1f),
						contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
					) {
						Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
					}
				} else {
					OutlinedButton(
						onClick = onClick,
						modifier = Modifier.weight(1f),
						contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
					) {
						Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
					}
				}
			}
		}
		Text(
			text = when (quality) {
				AudioQuality.LOSSLESS -> t.qualityLossless
				AudioQuality.MEDIUM -> t.qualityMedium
				AudioQuality.LOW -> t.qualityLow
			},
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier
				.align(Alignment.Start)
				.padding(top = 4.dp)
		)
		
		Spacer(modifier = Modifier.height(12.dp))
		
		OutlinedTextField(
			value = maxCacheSize,
			onValueChange = {
				if (it.isEmpty() || it.all { char -> char.isDigit() }) {
					maxCacheSize = it
					it.toLongOrNull()?.let { size ->
						manager.setMaxCacheSize(size)
						playbackManager.setMaxCacheSizeMB(size)
					}
				}
			},
			label = { Text(t.maxCacheSize) },
			modifier = Modifier.fillMaxWidth(),
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
			singleLine = true
		)
		
		Spacer(modifier = Modifier.height(16.dp))
		
		cacheBreakdown?.let { breakdown ->
			CacheStatsTable(t = t, breakdown = breakdown, favouriteCount = favouriteCount)
		} ?: Text(
			text = t.calculating,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		
		Spacer(modifier = Modifier.height(4.dp))
		
		OutlinedButton(
			onClick = {
				scope.launch(Dispatchers.IO) {
					val breakdown = playbackManager.getCacheSizeBreakdown()
					val favourites = AppDatabase.getDatabase(context).trackDao().getFavouriteCount()
					withContext(Dispatchers.Main) {
						cacheBreakdown = breakdown
						favouriteCount = favourites
						Toast.makeText(context, t.statsRefreshed, Toast.LENGTH_SHORT).show()
					}
				}
			}, modifier = Modifier.fillMaxWidth()
		) {
			Text(t.refreshStats)
		}
		
		Spacer(modifier = Modifier.height(8.dp))
		
		// Complete partially downloaded tracks
		Button(
			onClick = {
				isCompleting = true
				completeProgress = t.preparing
				scope.launch {
					val result = playbackManager.completePartialTracks(manager) { done, total, title ->
						withContext(Dispatchers.Main) {
							completeProgress = if (total > 0) {
								t.completing(done, total, title)
							} else ""
						}
					}
					val breakdown = withContext(Dispatchers.IO) {
						playbackManager.getCacheSizeBreakdown()
					}
					cacheBreakdown = breakdown
					isCompleting = false
					Toast.makeText(
						context,
						t.completedSummary(result.completed, result.failed, result.skipped),
						Toast.LENGTH_LONG
					).show()
				}
			},
			modifier = Modifier.fillMaxWidth(),
			enabled = !isCompleting && !isEvicting && !isSyncing && isAuthenticated,
			colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
		) {
			if (isCompleting) {
				CircularProgressIndicator(
					modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onSecondary
				)
				Spacer(modifier = Modifier.width(8.dp))
				Text(
					completeProgress.ifBlank { t.completingShort },
					maxLines = 1
				)
			} else {
				Text(t.completePartial)
			}
		}
		
		Spacer(modifier = Modifier.height(8.dp))
		
		// Poweramp 点赞 -> Jellyfin 收藏，然后主动把这些收藏下完整
		Button(
			onClick = {
				isSyncing = true
				syncProgress = t.readingPoweramp
				scope.launch {
					val result = PowerampSync.mirrorToJellyfin(context, manager)
					if (result.needsPermission) {
						isSyncing = false
						Toast.makeText(context, t.grantAccess, Toast.LENGTH_LONG).show()
						return@launch
					}
					if (result.error != null) {
						isSyncing = false
						Toast.makeText(
							context, t.syncFailed(result.error ?: ""), Toast.LENGTH_LONG
						).show()
						return@launch
					}
					syncProgress = t.syncedDownloading(result.total)
					val dl = playbackManager.downloadFavouriteTracks(manager, manager.getQuality()) { done, total, title ->
						withContext(Dispatchers.Main) {
							syncProgress = t.downloadingProgress(done, total, title)
						}
					}
					val breakdown = withContext(Dispatchers.IO) {
						playbackManager.getCacheSizeBreakdown()
					}
					cacheBreakdown = breakdown
					favouriteCount = withContext(Dispatchers.IO) {
						AppDatabase.getDatabase(context).trackDao().getFavouriteCount()
					}
					isSyncing = false
					Toast.makeText(
						context,
						t.mirrorSummary(result.added, result.removed, dl.downloaded, dl.failed),
						Toast.LENGTH_LONG
					).show()
				}
			},
			modifier = Modifier.fillMaxWidth(),
			enabled = !isSyncing && !isCompleting && !isEvicting && isAuthenticated,
			colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
		) {
			if (isSyncing) {
				CircularProgressIndicator(
					modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary
				)
				Spacer(modifier = Modifier.width(8.dp))
				Text(syncProgress.ifBlank { t.syncing }, maxLines = 1)
			} else {
				Text(t.syncLikes)
			}
		}
		
		Spacer(modifier = Modifier.height(8.dp))
		
		// 破坏性操作收进一个可展开的区域，而不是一个按钮
		Card(
			modifier = Modifier.fillMaxWidth(),
			colors = CardDefaults.cardColors(
				containerColor = MaterialTheme.colorScheme.surfaceVariant
			)
		) {
			Column {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clickable { showDeleteMenu = !showDeleteMenu }
						.padding(horizontal = 16.dp, vertical = 14.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					Text(
						text = t.delete,
						style = MaterialTheme.typography.titleMedium,
						color = MaterialTheme.colorScheme.error
					)
					Spacer(modifier = Modifier.weight(1f))
					Text(
						text = if (showDeleteMenu) "▴" else "▾",
						style = MaterialTheme.typography.titleMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
				
				AnimatedVisibility(visible = showDeleteMenu) {
					Column(modifier = Modifier.padding(horizontal = 16.dp)) {
						DeleteOption(
							title = t.trim,
							subtitle = t.trimDesc,
							onClick = {
								showDeleteMenu = false
								isEvicting = true
								playbackManager.performEviction { count ->
									isEvicting = false
									scope.launch(Dispatchers.IO) {
										val breakdown = playbackManager.getCacheSizeBreakdown()
										withContext(Dispatchers.Main) {
											cacheBreakdown = breakdown
											Toast.makeText(
												context, t.evicted(count), Toast.LENGTH_SHORT
											).show()
										}
									}
								}
							}
						)
						HorizontalDivider()
						DeleteOption(
							title = t.unfinished,
							subtitle = t.unfinishedDesc,
							onClick = {
								showDeleteMenu = false
								scope.launch(Dispatchers.IO) {
									playbackManager.deletePartialFiles(excludeFavourites = true)
									val breakdown = playbackManager.getCacheSizeBreakdown()
									withContext(Dispatchers.Main) {
										cacheBreakdown = breakdown
										Toast.makeText(
											context,
											t.partialDeleted,
											Toast.LENGTH_SHORT
										).show()
									}
								}
							}
						)
						HorizontalDivider()
						DeleteOption(
							title = t.allTracks,
							subtitle = t.allTracksDesc,
							danger = true,
							onClick = {
								showDeleteMenu = false
								scope.launch(Dispatchers.IO) {
									playbackManager.deleteAllTracks(excludeFavourites = true)
									val breakdown = playbackManager.getCacheSizeBreakdown()
									withContext(Dispatchers.Main) {
										cacheBreakdown = breakdown
										Toast.makeText(
											context, t.allDeleted,
											Toast.LENGTH_SHORT
										).show()
									}
								}
							}
						)
						HorizontalDivider()
						DeleteOption(
							title = t.favouriteTracks,
							subtitle = t.favouriteDesc,
							danger = true,
							onClick = {
								showDeleteMenu = false
								scope.launch(Dispatchers.IO) {
									playbackManager.deleteFavouriteTracks()
									val breakdown = playbackManager.getCacheSizeBreakdown()
									withContext(Dispatchers.Main) {
										cacheBreakdown = breakdown
										Toast.makeText(
											context, t.favDeleted, Toast.LENGTH_SHORT
										).show()
									}
								}
							}
						)
						HorizontalDivider()
						DeleteOption(
							title = t.resetAll,
							subtitle = t.resetDesc,
							danger = true,
							onClick = {
								showDeleteMenu = false
								showResetConfirm = true
							}
						)
						Spacer(modifier = Modifier.height(6.dp))
					}
				}
			}
		}
	}
	
	if (showInfo) {
		AppInfoDialog(
			t = t,
			language = language,
			onLanguageChange = { selected ->
				language = selected
				uiPrefs.edit().putString("language", selected.id).apply()
			},
			onDismiss = { showInfo = false }
		)
	}
	
	if (showResetConfirm) {
		AlertDialog(
			onDismissRequest = { showResetConfirm = false },
			title = { Text(t.resetTitle) },
			text = {
				Text(t.resetBody)
			},
			confirmButton = {
				TextButton(
					onClick = {
						showResetConfirm = false
						scope.launch(Dispatchers.IO) {
							val db = AppDatabase.getDatabase(context)
							playbackManager.deleteAllTracks(excludeFavourites = false)
							playbackManager.deleteAllAlbumArts()
							db.clearAllTables()
							val breakdown = playbackManager.getCacheSizeBreakdown()
							withContext(Dispatchers.Main) {
								cacheBreakdown = breakdown
								Toast.makeText(
									context, t.resetDone, Toast.LENGTH_SHORT
								).show()
							}
						}
					}
				) {
					Text(t.reset, color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = {
				TextButton(onClick = { showResetConfirm = false }) { Text(t.cancel) }
			}
		)
	}
}

@Composable
private fun DeleteOption(
	title: String, subtitle: String? = null, danger: Boolean = false, onClick: () -> Unit
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 10.dp)
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.bodyLarge,
			color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
		)
		subtitle?.let {
			Text(
				text = it,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
	}
}

private fun formatMb(bytes: Long): String =
	String.format("%.1f MB", bytes / (1024.0 * 1024.0))


@Composable
private fun CacheStatsTable(
	t: UiStrings,
	breakdown: TrackPlaybackManager.CacheSizeBreakdown,
	favouriteCount: Int
) {
	val audioBytes = breakdown.partialFilesSize + breakdown.completeFilesSize
	val songs = breakdown.completeSongsNum + breakdown.partialSongsNum
	Card(
		modifier = Modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceVariant
		)
	) {
		Column {
			Text(
				text = t.cachedTotal(formatGib(audioBytes), songs),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
			)
			HorizontalDivider()
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 12.dp)
			) {
				listOf(AudioQuality.LOSSLESS, AudioQuality.MEDIUM, AudioQuality.LOW).forEach { q ->
					Column(
						modifier = Modifier.weight(1f),
						horizontalAlignment = Alignment.CenterHorizontally
					) {
						Text(
							text = (breakdown.qualityCounts[q] ?: 0).toString(),
							style = MaterialTheme.typography.titleMedium,
							fontWeight = FontWeight.SemiBold
						)
						Text(
							text = when (q) {
								AudioQuality.LOSSLESS -> t.lossless
								AudioQuality.MEDIUM -> t.medium
								AudioQuality.LOW -> t.low
							},
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}
			}
			HorizontalDivider()
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 10.dp)
			) {
				Text(
					text = t.favouritesLabel,
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.weight(1f)
				)
				Text(text = favouriteCount.toString(), style = MaterialTheme.typography.bodyMedium)
			}
			HorizontalDivider()
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 10.dp)
			) {
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = t.artwork,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
					Text(
						text = formatMb(breakdown.albumArtsSize),
						style = MaterialTheme.typography.bodyMedium
					)
				}
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = t.database,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
					Text(
						text = formatMb(breakdown.databaseSize),
						style = MaterialTheme.typography.bodyMedium
					)
				}
			}
		}
	}
}

@Composable
private fun AppInfoDialog(
	t: UiStrings,
	language: AppLanguage,
	onLanguageChange: (AppLanguage) -> Unit,
	onDismiss: () -> Unit
) {
	val uriHandler = LocalUriHandler.current
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(t.about) },
		text = {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				Text(t.language, style = MaterialTheme.typography.titleSmall)
				AppLanguage.entries.forEach { option ->
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable { onLanguageChange(option) },
						verticalAlignment = Alignment.CenterVertically
					) {
						RadioButton(
							selected = language == option,
							onClick = { onLanguageChange(option) }
						)
						Text(
							text = when (option) {
								AppLanguage.AUTO -> t.auto
								AppLanguage.ENGLISH -> t.english
								AppLanguage.CHINESE -> t.chinese
								AppLanguage.JAPANESE -> t.japanese
							}
						)
					}
				}
				HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
				Text(
					text = t.originalAuthor,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier
						.fillMaxWidth()
						.clickable {
							uriHandler.openUri("https://github.com/ishanjain28/jellyfin-saf-provider")
						}
						.padding(vertical = 6.dp)
				)
				Text(
					text = t.forkMaintainer,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier
						.fillMaxWidth()
						.clickable { uriHandler.openUri("https://github.com/lc-3124") }
						.padding(vertical = 6.dp)
				)
				Text(
					text = t.creditsNote,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = 8.dp)
				)
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text(t.close) }
		}
	)
}

private fun formatGib(bytes: Long): String =
	String.format("%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
