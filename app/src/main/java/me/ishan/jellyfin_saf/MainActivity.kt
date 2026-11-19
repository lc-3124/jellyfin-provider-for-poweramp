package me.ishan.jellyfin_saf

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.StrictMode
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
	var showDeleteMenu by remember { mutableStateOf(false) }
	var showResetConfirm by remember { mutableStateOf(false) }
	var isScanning by remember { mutableStateOf(false) }
	var scanProgress by remember { mutableStateOf("") }
	var libraryStats by remember { mutableStateOf<Pair<Int, Int>?>(null) }
	
	val performScan = {
		isScanning = true
		scanProgress = "Starting scan..."
		scope.launch(Dispatchers.IO) {
			try {
				val albums = manager.getAlbums(limit = 100000)
				var tracksScanned = 0
				for ((index, album) in albums.withIndex()) {
					if (!isScanning) break // allow cancellation
					
					withContext(Dispatchers.Main) {
						scanProgress = "Scanning album ${index + 1}/${albums.size}: ${album.name}"
					}
					val tracks = manager.getAlbumTracks(album.id)
					for (track in tracks) {
						manager.getTrackMetadata(track.id)
						tracksScanned++
					}
				}
				withContext(Dispatchers.Main) {
					scanProgress =
						"Scan complete! Cached metadata for ${albums.size} albums, $tracksScanned tracks."
					Toast.makeText(context, scanProgress, Toast.LENGTH_LONG).show()
					isScanning = false
					
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
					scanProgress = "Scan failed: ${e.message}"
					Toast.makeText(context, scanProgress, Toast.LENGTH_LONG).show()
					isScanning = false
				}
			}
		}
	}
	
	// 启动时先把 Poweramp 的点赞同步过来，再读统计
	LaunchedEffect(Unit) {
		scope.launch(Dispatchers.IO) {
			if (manager.isAuthenticated()) {
				runCatching { PowerampSync.sync(context, manager) }
			}
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
		Text(
			text = "Jellyfin Provider Configuration",
			style = MaterialTheme.typography.headlineMedium,
			modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
		)
		
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
						text = "✓ Connected",
						style = MaterialTheme.typography.titleLarge,
						color = MaterialTheme.colorScheme.primary
					)
					Text(
						text = "User: ${manager.getUsername()}",
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
				Text("Logout")
			}
		} else {
			OutlinedTextField(
				value = url,
				onValueChange = { url = it },
				label = { Text("Jellyfin Server URL") },
				placeholder = { Text("https://jellyfin.example.com") },
				modifier = Modifier.fillMaxWidth(),
				singleLine = true
			)
			
			Spacer(modifier = Modifier.height(12.dp))
			
			OutlinedTextField(
				value = username,
				onValueChange = { username = it },
				label = { Text("Username") },
				modifier = Modifier.fillMaxWidth(),
				singleLine = true
			)
			
			Spacer(modifier = Modifier.height(12.dp))
			
			OutlinedTextField(
				value = password,
				onValueChange = { password = it },
				label = { Text("Password") },
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
								context, "Login Successful! Starting scan...", Toast.LENGTH_SHORT
							).show()
							
							performScan()
						} else {
							Toast.makeText(
								context, "Login Failed. Check your credentials.", Toast.LENGTH_LONG
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
					Text("Connecting...")
				} else {
					Text("Save and Login")
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
							if (success) "Connection OK!" else "Connection Failed!",
							Toast.LENGTH_SHORT
						).show()
					}
				}, enabled = !isLoading && url.isNotBlank(), modifier = Modifier.fillMaxWidth()
			) {
				Text("Test Connection")
			}
		}
		
		HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))
		
		Text(
			text = "App Settings",
			style = MaterialTheme.typography.titleMedium,
			modifier = Modifier.align(Alignment.Start)
		)
		
		Spacer(modifier = Modifier.height(16.dp))
		
		if (isAuthenticated) {
			libraryStats?.let { stats ->
				Text(
					text = "Server Library: ${stats.first} Albums, ${stats.second} Tracks",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.padding(bottom = 8.dp)
						.align(Alignment.Start)
				)
			} ?: Text(
				text = "Fetching library stats...",
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
				Text("Scan Library")
			}
		}
		
		Spacer(modifier = Modifier.height(16.dp))
		
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
			label = { Text("Max Cache Size (MB)") },
			modifier = Modifier.fillMaxWidth(),
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
			singleLine = true
		)
		
		Spacer(modifier = Modifier.height(16.dp))
		
		cacheBreakdown?.let { breakdown ->
			Column(modifier = Modifier.fillMaxWidth()) {
				Text(
					text = "Liked on Jellyfin: $favouriteCount songs",
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.padding(bottom = 4.dp)
				)
				Text(
					text = "Currently cached: ${
						String.format(
							"%.2f MB", breakdown.totalSize / (1024.0 * 1024.0)
						)
					}  / ${breakdown.completeSongsNum + breakdown.partialSongsNum} Songs",
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.padding(bottom = 4.dp)
				)
				Text(
					text = "  • Partial files: ${
						String.format(
							"%.2f MB", breakdown.partialFilesSize / (1024.0 * 1024.0)
						)
					}  / ${breakdown.partialSongsNum} Songs",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				Text(
					text = "  • Favourite files: ${
						String.format(
							"%.2f MB", breakdown.favouriteFilesSize / (1024.0 * 1024.0)
						)
					} / ${breakdown.favouriteSongsNum} Songs",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				Text(
					text = "  • Complete files: ${
						String.format(
							"%.2f MB", breakdown.completeFilesSize / (1024.0 * 1024.0)
						)
					} / ${breakdown.completeSongsNum} Songs",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				Text(
					text = "  • Album arts: ${
						String.format(
							"%.2f MB", breakdown.albumArtsSize / (1024.0 * 1024.0)
						)
					}",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				Text(
					text = "  • Database: ${
						String.format(
							"%.2f MB", breakdown.databaseSize / (1024.0 * 1024.0)
						)
					}",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		} ?: Text(
			text = "Calculating cache size...",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		
		Spacer(modifier = Modifier.height(4.dp))
		
		OutlinedButton(
			onClick = {
				scope.launch(Dispatchers.IO) {
					// 刷新时顺带同步一次，避免“点了赞但界面没变”
					if (manager.isAuthenticated()) {
						runCatching { PowerampSync.sync(context, manager) }
					}
					val breakdown = playbackManager.getCacheSizeBreakdown()
					val favourites = AppDatabase.getDatabase(context).trackDao().getFavouriteCount()
					withContext(Dispatchers.Main) {
						cacheBreakdown = breakdown
						favouriteCount = favourites
						Toast.makeText(context, "Cache stats refreshed", Toast.LENGTH_SHORT).show()
					}
				}
			}, modifier = Modifier.fillMaxWidth()
		) {
			Text("Refresh Cache Stats")
		}
		
		Spacer(modifier = Modifier.height(8.dp))
		
		// Complete partially downloaded tracks
		Button(
			onClick = {
				isCompleting = true
				completeProgress = "Preparing..."
				scope.launch {
					val result = playbackManager.completePartialTracks(manager) { done, total, title ->
						withContext(Dispatchers.Main) {
							completeProgress = if (total > 0) {
								"Completing $done/$total${if (title.isNotBlank()) ": $title" else ""}"
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
						"Completed ${result.completed}, failed ${result.failed}, skipped ${result.skipped}",
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
					completeProgress.ifBlank { "Completing partial downloads..." },
					maxLines = 1
				)
			} else {
				Text("Complete Partial Downloads")
			}
		}
		
		Spacer(modifier = Modifier.height(8.dp))
		
		// Poweramp 点赞 -> Jellyfin 收藏，然后主动把这些收藏下完整
		Button(
			onClick = {
				isSyncing = true
				syncProgress = "Reading Poweramp..."
				scope.launch {
					val result = PowerampSync.sync(context, manager)
					if (result.needsPermission) {
						isSyncing = false
						Toast.makeText(
							context,
							"Grant Poweramp data access, then tap Sync again",
							Toast.LENGTH_LONG
						).show()
						return@launch
					}
					if (result.error != null) {
						isSyncing = false
						Toast.makeText(
							context, "Sync failed: ${result.error}", Toast.LENGTH_LONG
						).show()
						return@launch
					}
					syncProgress =
						"Synced ${result.liked + result.unliked} likes, downloading favourites..."
					val dl = playbackManager.downloadFavouriteTracks(manager) { done, total, title ->
						withContext(Dispatchers.Main) {
							syncProgress = "Downloading $done/$total${
								if (title.isNotBlank()) ": $title" else ""
							}"
						}
					}
					val breakdown = withContext(Dispatchers.IO) {
						playbackManager.getCacheSizeBreakdown()
					}
					cacheBreakdown = breakdown
					favouriteCount =
						AppDatabase.getDatabase(context).trackDao().getFavouriteCount()
					isSyncing = false
					Toast.makeText(
						context,
						"Likes: ${result.liked} added, ${result.unliked} removed. " +
							"Downloads: ${dl.downloaded} new, ${dl.skipped} cached, ${dl.failed} failed",
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
				Text(syncProgress.ifBlank { "Syncing..." }, maxLines = 1)
			} else {
				Text("Sync Poweramp Likes")
			}
		}
		
		Spacer(modifier = Modifier.height(8.dp))
		
		// 所有破坏性操作统一收进这个入口，避免误触
		Button(
			onClick = { showDeleteMenu = true },
			modifier = Modifier.fillMaxWidth(),
			enabled = !isEvicting && !isCompleting && !isSyncing,
			colors = ButtonDefaults.buttonColors(
				containerColor = MaterialTheme.colorScheme.errorContainer,
				contentColor = MaterialTheme.colorScheme.onErrorContainer
			)
		) {
			if (isEvicting) {
				CircularProgressIndicator(
					modifier = Modifier.size(24.dp),
					color = MaterialTheme.colorScheme.onErrorContainer
				)
				Spacer(modifier = Modifier.width(8.dp))
				Text("Trimming cache...")
			} else {
				Text("Delete")
			}
		}
	}
	
	if (showDeleteMenu) {
		AlertDialog(
			onDismissRequest = { showDeleteMenu = false },
			title = { Text("Delete") },
			text = {
				Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
					DeleteOption(
						title = "Trim to cache limit",
						subtitle = "Evict least-recently-used tracks",
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
											context, "Evicted $count tracks", Toast.LENGTH_SHORT
										).show()
									}
								}
							}
						}
					)
					DeleteOption(
						title = "Unfinished downloads",
						subtitle = "Partial files, favourites kept",
						onClick = {
							showDeleteMenu = false
							scope.launch(Dispatchers.IO) {
								playbackManager.deletePartialFiles(excludeFavourites = true)
								val breakdown = playbackManager.getCacheSizeBreakdown()
								withContext(Dispatchers.Main) {
									cacheBreakdown = breakdown
									Toast.makeText(
										context,
										"Unfinished downloads removed (favourites kept)",
										Toast.LENGTH_SHORT
									).show()
								}
							}
						}
					)
					DeleteOption(
						title = "All downloaded tracks",
						subtitle = "Favourites kept",
						danger = true,
						onClick = {
							showDeleteMenu = false
							scope.launch(Dispatchers.IO) {
								playbackManager.deleteAllTracks(excludeFavourites = true)
								val breakdown = playbackManager.getCacheSizeBreakdown()
								withContext(Dispatchers.Main) {
									cacheBreakdown = breakdown
									Toast.makeText(
										context, "All tracks deleted (favourites kept)",
										Toast.LENGTH_SHORT
									).show()
								}
							}
						}
					)
					DeleteOption(
						title = "Favourite tracks",
						subtitle = "Downloaded favourites only",
						danger = true,
						onClick = {
							showDeleteMenu = false
							scope.launch(Dispatchers.IO) {
								playbackManager.deleteFavouriteTracks()
								val breakdown = playbackManager.getCacheSizeBreakdown()
								withContext(Dispatchers.Main) {
									cacheBreakdown = breakdown
									Toast.makeText(
										context, "Favourite tracks deleted", Toast.LENGTH_SHORT
									).show()
								}
							}
						}
					)
					DeleteOption(
						title = "Reset everything",
						subtitle = "Metadata, cache state, audio and artwork",
						danger = true,
						onClick = {
							showDeleteMenu = false
							showResetConfirm = true
						}
					)
				}
			},
			confirmButton = {
				TextButton(onClick = { showDeleteMenu = false }) { Text("Cancel") }
			}
		)
	}
	
	if (showResetConfirm) {
		AlertDialog(
			onDismissRequest = { showResetConfirm = false },
			title = { Text("Reset everything?") },
			text = {
				Text("This deletes all metadata, cache state, downloaded audio and artwork. It cannot be undone.")
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
									context, "Database reset & cache cleared", Toast.LENGTH_SHORT
								).show()
							}
						}
					}
				) {
					Text("Reset", color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = {
				TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
			}
		)
	}
}

@Composable
private fun DeleteOption(
	title: String, subtitle: String? = null, danger: Boolean = false, onClick: () -> Unit
) {
	OutlinedButton(
		onClick = onClick,
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 2.dp),
		colors = if (danger) ButtonDefaults.outlinedButtonColors(
			contentColor = MaterialTheme.colorScheme.error
		) else ButtonDefaults.outlinedButtonColors()
	) {
		Column(
			modifier = Modifier.fillMaxWidth(),
			horizontalAlignment = Alignment.Start
		) {
			Text(title, style = MaterialTheme.typography.bodyLarge)
			subtitle?.let {
				Text(
					it,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
	}
}
