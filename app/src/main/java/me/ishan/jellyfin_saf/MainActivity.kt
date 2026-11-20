package me.ishan.jellyfin_saf

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.StrictMode
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ishan.jellyfin_saf.db.AppDatabase
import me.ishan.jellyfin_saf.ui.theme.AppStyle
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
			val uiPrefs = remember { getSharedPreferences("ui", Context.MODE_PRIVATE) }
			val systemDark = isSystemInDarkTheme()
			var style by remember {
				mutableStateOf(
					AppStyle.fromId(uiPrefs.getString("style", null))
						?: if (systemDark) AppStyle.DARK_BLUE else AppStyle.LIGHT_BLUE
				)
			}
			JellyfinSaf(style = style) {
				Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
					JellyfinSettings(
						modifier = Modifier.padding(innerPadding),
						style = style,
						onStyleChange = { selected ->
							style = selected
							uiPrefs.edit().putString("style", selected.id).apply()
						}
					)
				}
			}
		}
	}
}

@SuppressLint("DefaultLocale")
@Composable
fun JellyfinSettings(
	modifier: Modifier = Modifier,
	style: AppStyle = AppStyle.LIGHT_BLUE,
	onStyleChange: (AppStyle) -> Unit = {}
) {
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
	var showScanDialog by remember { mutableStateOf(false) }
	var showSyncDialog by remember { mutableStateOf(false) }
	var isTestingLatency by remember { mutableStateOf(false) }
	var latencyMs by remember { mutableStateOf<Long?>(null) }
	var latencyFailed by remember { mutableStateOf(false) }
	var isPlanningPush by remember { mutableStateOf(false) }
	var pendingPush by remember { mutableStateOf<PowerampSync.PushPlan?>(null) }
	var favouriteCount by remember { mutableStateOf(0) }
	var quality by remember { mutableStateOf(manager.getQuality()) }
	var showDeleteDialog by remember { mutableStateOf(false) }
	var showResetConfirm by remember { mutableStateOf(false) }
	var isScanning by remember { mutableStateOf(false) }
	var scanProgress by remember { mutableStateOf("") }
	var libraryStats by remember { mutableStateOf<Pair<Int, Int>?>(null) }
	
	val performScan: (JellyfinClientManager.ScanMode) -> Unit = { mode ->
		isScanning = true
		scanProgress = t.scanStarting
		scope.launch {
			try {
				val result = manager.syncLibrary(
					mode = mode,
					shouldContinue = { isScanning },
					onProgress = { done, total ->
						withContext(Dispatchers.Main) {
							scanProgress = t.scanProgress(done, total)
						}
					}
				)
				withContext(Dispatchers.Main) {
					scanProgress = if (result.full) {
						t.scanComplete(result.albums, result.tracks)
					} else {
						t.scanIncremental(result.tracks)
					}
					Toast.makeText(context, scanProgress, Toast.LENGTH_LONG).show()
					isScanning = false
				}
				
				// 扫描完成后重建目录树；收藏同步（拉取/推送）改为手动入口
				val indexed = withContext(Dispatchers.IO) {
					FolderLibrary.rebuild(context)
				}
				val breakdown = withContext(Dispatchers.IO) {
					playbackManager.getCacheSizeBreakdown()
				}
				withContext(Dispatchers.Main) {
					if (indexed > 0) {
						Toast.makeText(
							context, t.foldersIndexed(indexed), Toast.LENGTH_SHORT
						).show()
					}
					cacheBreakdown = breakdown
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
	
	// 拉取：Jellyfin 收藏 → Poweramp（增量、只增不减、不下载）
	val startPull: () -> Unit = {
		isSyncing = true
		syncProgress = t.readingJellyfin
		scope.launch {
			val result = withContext(Dispatchers.IO) {
				PowerampSync.pullRemoteFavourites(context, manager)
			}
			isSyncing = false
			val message = when {
				result.needsPermission -> t.grantAccess
				result.error != null -> t.syncFailed(result.error ?: "")
				result.written > 0 -> t.pushedToPoweramp(result.written)
				else -> t.pullUpToDate
			}
			Toast.makeText(context, message, Toast.LENGTH_LONG).show()
		}
	}
	
	// 推送第一步：只算计划，弹确认框
	val startPush: () -> Unit = {
		isPlanningPush = true
		syncProgress = t.readingPoweramp
		scope.launch {
			val plan = withContext(Dispatchers.IO) { PowerampSync.planPush(context, manager) }
			isPlanningPush = false
			if (plan == null) {
				Toast.makeText(context, t.grantAccess, Toast.LENGTH_LONG).show()
			} else {
				pendingPush = plan
			}
		}
	}
	
	// 推送第二步：用户确认后才增减 Jellyfin 收藏，然后下载收藏曲目
	val runPush: (PowerampSync.PushPlan) -> Unit = { plan ->
		isSyncing = true
		syncProgress = t.syncing
		scope.launch {
			val result = withContext(Dispatchers.IO) {
				PowerampSync.applyPush(context, manager, plan)
			}
			if (result.error != null) {
				isSyncing = false
				Toast.makeText(
					context, t.syncFailed(result.error ?: ""), Toast.LENGTH_LONG
				).show()
			} else {
				syncProgress = t.syncedDownloading(result.added + result.removed)
				val dl = playbackManager.downloadFavouriteTracks(
					manager, manager.getQuality()
				) { done, total, title ->
					withContext(Dispatchers.Main) {
						syncProgress = t.downloadingProgress(done, total, title)
					}
				}
				val breakdown = withContext(Dispatchers.IO) {
					playbackManager.getCacheSizeBreakdown()
				}
				val favourites = withContext(Dispatchers.IO) {
					AppDatabase.getDatabase(context).trackDao().getFavouriteCount()
				}
				cacheBreakdown = breakdown
				favouriteCount = favourites
				isSyncing = false
				Toast.makeText(
					context,
					t.mirrorSummary(result.added, result.removed, dl.downloaded, dl.failed),
					Toast.LENGTH_LONG
				).show()
			}
		}
	}
	
	val refreshStats: () -> Unit = {
		scope.launch(Dispatchers.IO) {
			val breakdown = playbackManager.getCacheSizeBreakdown()
			val favourites = AppDatabase.getDatabase(context).trackDao().getFavouriteCount()
			withContext(Dispatchers.Main) {
				cacheBreakdown = breakdown
				favouriteCount = favourites
				Toast.makeText(context, t.statsRefreshed, Toast.LENGTH_SHORT).show()
			}
		}
	}
	
	LaunchedEffect(Unit) {
		scope.launch(Dispatchers.IO) {
			// 目录树还没建过就补一次（不需要完整扫描）
			if (manager.isAuthenticated() && FolderLibrary.get(context) == null) {
				runCatching { FolderLibrary.rebuild(context) }
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
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 12.dp, bottom = 8.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			FitTitle(
				text = t.brand,
				modifier = Modifier
					.weight(1f)
					.padding(end = 16.dp)
			)
			TextButton(
				onClick = { showInfo = true },
				modifier = Modifier.size(40.dp),
				contentPadding = PaddingValues(0.dp)
			) {
				Text(text = "ⓘ", style = MaterialTheme.typography.titleLarge)
			}
		}
		
		if (isAuthenticated) {
			Card(
				modifier = Modifier.fillMaxWidth(),
				shape = RoundedCornerShape(24.dp),
				colors = CardDefaults.cardColors(containerColor = Color.Transparent)
			) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.background(
							Brush.linearGradient(style.gradient)
						)
						.padding(horizontal = 18.dp, vertical = 16.dp)
				) {
					Row(verticalAlignment = Alignment.Top) {
						Column(modifier = Modifier.weight(1f)) {
							Text(
								text = "✓ " + t.connected,
								style = MaterialTheme.typography.titleLarge,
								fontWeight = FontWeight.SemiBold,
								color = Color.White
							)
							Text(
								text = "${t.user}: ${manager.getUsername()}",
								style = MaterialTheme.typography.bodyMedium,
								color = Color.White.copy(alpha = 0.85f),
								modifier = Modifier.padding(top = 4.dp)
							)
							Text(
								text = manager.getUrl(),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
							)
						}
						
						Column(horizontalAlignment = Alignment.End) {
							TextButton(
								onClick = {
									scope.launch {
										manager.logout()
										// 不在 SharedPreferences 里留明文密码
										manager.clearStoredPassword()
										password = ""
										latencyMs = null
										latencyFailed = false
										isAuthenticated = false
									}
								},
								contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
							) {
								Text(
									text = t.logout,
									style = MaterialTheme.typography.labelLarge,
									color = Color.White
								)
							}
							OutlinedButton(
								onClick = {
									isTestingLatency = true
									latencyFailed = false
									scope.launch {
										val ms = manager.measureLatency()
										isTestingLatency = false
										latencyMs = ms
										latencyFailed = ms == null
										Toast.makeText(
											context,
											if (ms == null) t.offline else t.latencyToast(ms),
											Toast.LENGTH_SHORT
										).show()
									}
								},
								enabled = !isTestingLatency,
								contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
								border = BorderStroke(1.dp, Color.White.copy(alpha = 0.65f)),
								colors = ButtonDefaults.outlinedButtonColors(
									contentColor = Color.White
								)
							) {
								if (isTestingLatency) {
									CircularProgressIndicator(
										modifier = Modifier.size(12.dp),
										strokeWidth = 2.dp,
										color = Color.White
									)
								} else {
									Text(t.speedTest, style = MaterialTheme.typography.labelMedium)
								}
							}

						}
					}
				}
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
							
							performScan(JellyfinClientManager.ScanMode.AUTO)
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
		
		HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))
		
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
		
		// 扫描入口：点开弹窗选全量 / 增量
		EntryCard(
			title = t.scanLibrary,
			enabled = !isScanning && !isEvicting && isAuthenticated,
			busy = isScanning,
			progress = scanProgress,
			onClick = { showScanDialog = true }
		)
		
		Spacer(modifier = Modifier.height(16.dp))
		
				Text(
			text = t.audioQuality,
			style = MaterialTheme.typography.titleSmall,
			color = MaterialTheme.colorScheme.primary,
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
		
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 4.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(
				text = t.maxCacheSize,
				style = MaterialTheme.typography.bodyMedium,
				modifier = Modifier.weight(1f)
			)
			BasicTextField(
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
				singleLine = true,
				textStyle = MaterialTheme.typography.bodyMedium.copy(
					color = MaterialTheme.colorScheme.onSurface
				),
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
				modifier = Modifier
					.width(150.dp)
					.border(
						1.dp,
						MaterialTheme.colorScheme.outline,
						RoundedCornerShape(10.dp)
					)
					.padding(horizontal = 12.dp, vertical = 9.dp)
			)
		}
		
		Spacer(modifier = Modifier.height(16.dp))
		
		cacheBreakdown?.let { breakdown ->
			CacheStatsTable(
				t = t,
				breakdown = breakdown,
				favouriteCount = favouriteCount,
				onRefresh = refreshStats
			)
		} ?: Text(
			text = t.calculating,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		
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
		
		// 同步点赞：点开弹窗选推送 / 拉取
		EntryCard(
			title = t.syncLikes,
			enabled = !isSyncing && !isPlanningPush && isAuthenticated,
			busy = isSyncing || isPlanningPush,
			progress = syncProgress.ifBlank { t.syncing },
			onClick = { showSyncDialog = true }
		)
		
		Spacer(modifier = Modifier.height(8.dp))
		
		// 破坏性操作：点开弹窗
		EntryCard(
			title = t.delete,
			enabled = !isEvicting,
			busy = false,
			progress = "",
			danger = true,
			onClick = { showDeleteDialog = true }
		)
	}
	
	if (showScanDialog) {
		AlertDialog(
			onDismissRequest = { showScanDialog = false },
			title = { Text(t.scanLibrary) },
			text = {
				Column {
					MenuOption(
						title = t.scanFull,
						subtitle = t.scanFullDesc,
						enabled = !isScanning && !isEvicting && isAuthenticated,
						onClick = {
							showScanDialog = false
							performScan(JellyfinClientManager.ScanMode.FULL)
						}
					)
					HorizontalDivider()
					MenuOption(
						title = t.scanIncrementalOnly,
						subtitle = t.scanIncrementalOnlyDesc,
						enabled = !isScanning && !isEvicting && isAuthenticated,
						onClick = {
							showScanDialog = false
							performScan(JellyfinClientManager.ScanMode.INCREMENTAL)
						}
					)
				}
			},
			confirmButton = {
				TextButton(onClick = { showScanDialog = false }) { Text(t.close) }
			}
		)
	}
	
	if (showSyncDialog) {
		AlertDialog(
			onDismissRequest = { showSyncDialog = false },
			title = { Text(t.syncLikes) },
			text = {
				Column {
					MenuOption(
						title = t.push,
						subtitle = t.pushDesc,
						enabled = !isSyncing && !isPlanningPush && isAuthenticated,
						onClick = {
							showSyncDialog = false
							startPush()
						}
					)
					HorizontalDivider()
					MenuOption(
						title = t.pull,
						subtitle = t.pullDesc,
						enabled = !isSyncing && !isPlanningPush && isAuthenticated,
						onClick = {
							showSyncDialog = false
							startPull()
						}
					)
				}
			},
			confirmButton = {
				TextButton(onClick = { showSyncDialog = false }) { Text(t.close) }
			}
		)
	}
	
	if (showDeleteDialog) {
		AlertDialog(
			onDismissRequest = { showDeleteDialog = false },
			title = { Text(t.delete) },
			text = {
				Column {
					MenuOption(
						title = t.trim,
						subtitle = t.trimDesc,
						onClick = {
							showDeleteDialog = false
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
					MenuOption(
						title = t.unfinished,
						subtitle = t.unfinishedDesc,
						onClick = {
							showDeleteDialog = false
							scope.launch(Dispatchers.IO) {
								playbackManager.deletePartialFiles(excludeFavourites = true)
								val breakdown = playbackManager.getCacheSizeBreakdown()
								withContext(Dispatchers.Main) {
									cacheBreakdown = breakdown
									Toast.makeText(
										context, t.partialDeleted, Toast.LENGTH_SHORT
									).show()
								}
							}
						}
					)
					HorizontalDivider()
					MenuOption(
						title = t.allTracks,
						subtitle = t.allTracksDesc,
						danger = true,
						onClick = {
							showDeleteDialog = false
							scope.launch(Dispatchers.IO) {
								playbackManager.deleteAllTracks(excludeFavourites = true)
								val breakdown = playbackManager.getCacheSizeBreakdown()
								withContext(Dispatchers.Main) {
									cacheBreakdown = breakdown
									Toast.makeText(
										context, t.allDeleted, Toast.LENGTH_SHORT
									).show()
								}
							}
						}
					)
					HorizontalDivider()
					MenuOption(
						title = t.favouriteTracks,
						subtitle = t.favouriteDesc,
						danger = true,
						onClick = {
							showDeleteDialog = false
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
					MenuOption(
						title = t.resetAll,
						subtitle = t.resetDesc,
						danger = true,
						onClick = {
							showDeleteDialog = false
							showResetConfirm = true
						}
					)
				}
			},
			confirmButton = {
				TextButton(onClick = { showDeleteDialog = false }) { Text(t.close) }
			}
		)
	}
	
	if (showInfo) {
		AppInfoDialog(
			t = t,
			language = language,
			style = style,
			onStyleChange = onStyleChange,
			onLanguageChange = { selected ->
				language = selected
				uiPrefs.edit().putString("language", selected.id).apply()
			},
			onDismiss = { showInfo = false }
		)
	}
	
	// 推送确认框：只有点了“推送”并算完计划后才出现
	val plan = pendingPush
	if (plan != null) {
		AlertDialog(
			onDismissRequest = { pendingPush = null },
			title = { Text(t.pushConfirmTitle) },
			text = {
				Text(
					if (plan.total == 0) t.pushConfirmNone
					else t.pushConfirmBody(plan.added.size, plan.removed.size)
				)
			},
			confirmButton = {
				TextButton(
					onClick = {
						pendingPush = null
						// 没有变化时这个按钮只是“关闭”，不能顺手触发下载
						if (plan.total > 0) runPush(plan)
					}
				) {
					Text(if (plan.total == 0) t.close else t.push)
				}
			},
			dismissButton = if (plan.total > 0) {
				{
					TextButton(onClick = { pendingPush = null }) { Text(t.cancel) }
				}
			} else null
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

/** 标题自动缩放，保证一行显示完整（不省略）。 */
@Composable
private fun FitTitle(text: String, modifier: Modifier = Modifier) {
	val measurer = rememberTextMeasurer()
	BoxWithConstraints(modifier) {
		// 留一点余量，避免和右上角按钮贴住
		val available = (constraints.maxWidth - 24).coerceAtLeast(1)
		val probe = measurer.measure(
			text = AnnotatedString(text),
			style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
		)
		val scale = if (probe.size.width > 0) available.toFloat() / probe.size.width else 1f
		val resolved = (24f * scale * 0.98f).coerceIn(11f, 22f)
		Text(
			text = text,
			fontSize = resolved.sp,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
			softWrap = false,
			color = MaterialTheme.colorScheme.onSurface
		)
	}
}

@Composable
private fun EntryCard(
	title: String,
	enabled: Boolean = true,
	busy: Boolean = false,
	progress: String = "",
	danger: Boolean = false,
	onClick: () -> Unit
) {
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
					.clickable(enabled = enabled, onClick = onClick)
					.padding(horizontal = 16.dp, vertical = 12.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleMedium,
					color = if (danger) MaterialTheme.colorScheme.error
					else MaterialTheme.colorScheme.onSurface
				)
				Spacer(modifier = Modifier.weight(1f))
				if (busy) {
					CircularProgressIndicator(
						modifier = Modifier.size(16.dp),
						strokeWidth = 2.dp
					)
				} else {
					Text(
						text = "›",
						style = MaterialTheme.typography.titleMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}
			if (busy && progress.isNotBlank()) {
				Text(
					text = progress,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
					modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
				)
			}
		}
	}
}

@Composable
private fun MenuOption(
	title: String,
	subtitle: String? = null,
	danger: Boolean = false,
	enabled: Boolean = true,
	onClick: () -> Unit
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = enabled, onClick = onClick)
			.padding(vertical = 10.dp)
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.bodyLarge,
			color = when {
				!enabled -> MaterialTheme.colorScheme.onSurfaceVariant
				danger -> MaterialTheme.colorScheme.error
				else -> MaterialTheme.colorScheme.onSurface
			}
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
	favouriteCount: Int,
	onRefresh: () -> Unit
) {
	val audioBytes = breakdown.partialFilesSize + breakdown.completeFilesSize
	val songs = breakdown.completeSongsNum + breakdown.partialSongsNum
	Card(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onRefresh),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceVariant
		)
	) {
		Column {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 14.dp, vertical = 6.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = t.cachedTotal(formatGib(audioBytes), songs),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Bold,
					modifier = Modifier.weight(1f)
				)
				Icon(
					imageVector = Icons.Filled.Refresh,
					contentDescription = t.statsRefreshed,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(18.dp)
				)
			}
			HorizontalDivider()
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 6.dp)
			) {
				listOf(AudioQuality.LOSSLESS, AudioQuality.MEDIUM, AudioQuality.LOW).forEach { q ->
					Column(
						modifier = Modifier.weight(1f),
						horizontalAlignment = Alignment.CenterHorizontally
					) {
						Text(
							text = (breakdown.qualityCounts[q] ?: 0).toString(),
							style = MaterialTheme.typography.titleMedium,
							fontWeight = FontWeight.SemiBold,
							color = when (q) {
								AudioQuality.LOSSLESS -> MaterialTheme.colorScheme.primary
								AudioQuality.MEDIUM -> MaterialTheme.colorScheme.tertiary
								AudioQuality.LOW -> MaterialTheme.colorScheme.secondary
							}
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
					.padding(horizontal = 14.dp, vertical = 5.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Row(
					modifier = Modifier.weight(1f),
					verticalAlignment = Alignment.CenterVertically
				) {
					Text(
						text = t.favouritesLabel,
						style = MaterialTheme.typography.bodyMedium
					)
					Spacer(modifier = Modifier.width(6.dp))
					Text(
						text = favouriteCount.toString(),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.primary
					)
				}
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = t.partialLabel,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
					Spacer(modifier = Modifier.width(6.dp))
					Text(
						text = breakdown.partialSongsNum.toString(),
						style = MaterialTheme.typography.bodyMedium
					)
				}
			}
			HorizontalDivider()
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 14.dp, vertical = 5.dp)
			) {
				Text(
					text = "${t.artwork} ${formatMb(breakdown.albumArtsSize)}",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.weight(1f)
				)
				Text(
					text = "${t.database} ${formatMb(breakdown.databaseSize)}",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
	}
}

@Composable
private fun StyleSwatch(style: AppStyle, selected: Boolean, onClick: () -> Unit) {
	val shape = RoundedCornerShape(12.dp)
	val scheme = style.colorScheme
	Box(
		modifier = Modifier
			.size(46.dp)
			.clip(shape)
			.background(scheme.background)
			.border(
				width = if (selected) 3.dp else 1.dp,
				color = if (selected) MaterialTheme.colorScheme.onSurface
				else MaterialTheme.colorScheme.outline,
				shape = shape
			)
			.clickable(onClick = onClick)
			.padding(6.dp)
	) {
		Row(
			modifier = Modifier.fillMaxSize(),
			horizontalArrangement = Arrangement.spacedBy(3.dp)
		) {
			listOf(scheme.primary, scheme.secondary, scheme.tertiary).forEach { color ->
				Box(
					modifier = Modifier
						.weight(1f)
						.fillMaxHeight()
						.clip(RoundedCornerShape(4.dp))
						.background(color)
				)
			}
		}
	}
}

@Composable
private fun AppInfoDialog(
	t: UiStrings,
	language: AppLanguage,
	style: AppStyle,
	onStyleChange: (AppStyle) -> Unit,
	onLanguageChange: (AppLanguage) -> Unit,
	onDismiss: () -> Unit
) {
	val uriHandler = LocalUriHandler.current
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
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
				Text(t.pageStyle, style = MaterialTheme.typography.titleSmall)
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 10.dp, bottom = 4.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp)
				) {
					AppStyle.entries.forEach { option ->
						StyleSwatch(
							style = option,
							selected = option == style,
							onClick = { onStyleChange(option) }
						)
					}
				}
				HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
				TextButton(
					onClick = {
						scope.launch {
							val path = withContext(Dispatchers.IO) { DiagLog.export(context) }
							Toast.makeText(
								context,
								if (path != null) "${t.exportDiag}: $path" else t.exportFailed,
								Toast.LENGTH_LONG
							).show()
						}
					},
					contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
				) {
					Text(t.exportDiag)
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
