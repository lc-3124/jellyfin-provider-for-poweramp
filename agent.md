# Agent 交接：Jellyfin Provider for Poweramp

> 把一次长会话的结论浓缩给下一个 agent。项目路径：`~/work/project/jellyfin-saf-provider`。

## 1. 项目是什么

把 Jellyfin 音乐库通过 Android SAF 暴露成文件夹，给 Poweramp 等只认本地文件的播放器使用。
三个根：`Jellyfin Music`（按专辑）、`Jellyfin Folders`（按物理路径）、`Jellyfin Cache`（离线）。

- 包名 `me.ishan.jellyfin_saf`，authority `jellyfin.music`
- 构建：JDK 21 + AGP 9.2.1 + Gradle 9.4.1，`./gradlew assembleRelease`
- APK 用 **debug 证书**签名；只在 GitHub 发布，不发商店
- 协作约定：push / release / 写远端前必须让用户确认；本地 commit 可以

## 2. 当前状态（2026-11-20）

| 项 | 值 |
| --- | --- |
| 分支 | `master` |
| 版本 | 1.0.2（versionCode 48） |
| 已发布 | 只有 GitHub `v1.0.0` 和 APK |
| 未发布 | 1.0.1（MIME 修复）、1.0.2（URL 清理 + 诊断）已进 master，未发 Release |

本地还有一个 `rollback-1.0.0` 分支和一个 stash：
`lossy experiments 1.0.1-1.1.4 (unverified)`，含 MP3 转码、下载后播、取消下载等实验，**未经真机验证，不要直接用于发布**。

## 3. 这次会话解决/发现的问题

### 3.1 有损（中等/低）播放失败 —— 根因：服务器地址带了换行符

朋友的设备上：无损能播，中等/低全部瞬间失败，服务器端没有任何 ffmpeg 记录。
诊断日志给出：

```
[HTTP] … exception IllegalArgumentException: Invalid URL host: "music.lc3124.xin\n"
```

原因：用户从聊天记录粘贴的地址末尾带 `\n`，保存进 SharedPreferences。
- 无损走 Jellyfin SDK 的 URL 构造，SDK 做了容错；
- 有损走手工拼接 `serverUrl + "/Audio/..."`，OkHttp 直接判非法，请求从未发出。

修复（1.0.2）：保存、加载、拼接三处都清理所有空白字符与末尾 `/`。

### 3.2 HyperOS / ColorOS 冻结 provider —— 会表现为“连无损也不能播”

报告日志里的证据：

```
ContentProviderHelper: security reject start jellyfin.music ...
Failed to find provider info for jellyfin.music
FuseAppLoop: java.io.FileNotFoundException: No content provider
```

解决：系统设置里给应用 **自启动** + **省电策略无限制**；OEM 的 AppOps/冻结会拦截 Poweramp 拉起 provider。
若用户报“全部播不了”，先查这个，不要先怀疑转码。

### 3.3 转码文档的 MIME（1.0.1）

转码输出是 AAC，但数据库里存的是源文件 MIME（flac）。provider 之前把文档 MIME 报成 `audio/flac`，
部分 Poweramp 构建会按 MIME 选错解码器。现在非无损档报 `audio/aac`，缓存文件按真实后缀判断。

### 3.4 诊断能力（1.0.2 新增）

- `DiagLog`：写 `files/diag.log`，记录 openDocument、HTTP 状态码、异常、字节数、转码完成情况；
- ⓘ 弹窗 → **导出诊断日志** → 写到系统 `Download/JellyfinProvider-diag-*.txt`；
- 不记录 token/密码。R8 会删 `Log.d/v`，所以关键路径统一用 `DiagLog`（文件日志）。

## 4. 还没解决的问题

### 4.1 下载中途 `SocketException: Software caused connection abort`

1.0.1 的日志里，URL 已正确、服务器每次 `ffmpeg exited with code 0`，但客户端下到一半被 reset：

```
[HTTP] … code=200 length=-1 type=audio/aac
[HTTP] … exception SocketException: Software caused connection abort
[TRANSCODE] … finished success=false bytes=786432 total=1505872
```

重试一次通常能成功（日志里 `ef839f4b` 第二次下载 20s 后 `success=true`）。
怀疑：服务器走 FRP 隧道、Wi-Fi/5G 切换、HyperOS 后台冻结。
**未实现**自动重试；`rollback-1.0.0` 上曾写过 3 次重试的补丁，未完成也未验证。

### 4.2 实验分支里的其他方案（未验证）

- 有损改用 MP3（服务器验证过 `audio/mpeg` 输出、大小估算更准）；
- “先返回 FD、首次 read 等待下载完成”的下载后播；
- 用户切歌时取消下载。
这些都在 stash 里，恢复前先和用户确认。

## 5. 排查手册

### 5.1 抓日志（真机）

```bash
adb logcat -c
# 复现
adb logcat -d -v threadtime > full.txt
adb logcat -d -v threadtime -s JellyfinMusicProvider:* TrackPlaybackManager:* JellyfinClientManager:* TranscodeStream:* > tags.txt
```

注意：Windows PowerShell 的 `>` 输出是 **UTF-16**，分析前要转码：
`iconv -f UTF-16 -t UTF-8 full.txt > full.utf8.txt`，或用 Python decode。

### 5.2 应用内诊断

ⓘ → 导出诊断日志 → `Download/JellyfinProvider-diag-*.txt`。
关键行：`[OPEN]`、`[HTTP] start/code/exception`、`[TRANSCODE] finished`。

### 5.3 常见结论对照

| 现象 | 结论 |
| --- | --- |
| 全部播不了，`No content provider` | OEM 冻结，开自启动 + 省电无限制 |
| 只有中等/低失败，`Invalid URL host` | 服务器地址有空白字符 |
| 只有中等/低失败，`code=401` | token/账号问题 |
| 只有中等/低失败，`SocketException` | 隧道/网络中途断流，重试或换网络 |
| 服务器日志无 ffmpeg | 请求没到服务器：URL/token/网络 |
| 服务器 `ffmpeg code 0` 但客户端失败 | 客户端下载/读取被中断 |

## 6. 代码地图（关键文件）

| 文件 | 职责 |
| --- | --- |
| `JellyfinMusicProvider.kt` | DocumentsProvider：三个根、文档树、ProxyFD |
| `JellyfinClientManager.kt` | 认证、扫描（全量/增量）、音频 URL、下载 |
| `TrackPlaybackManager.kt` | 分块缓存、转码流、淘汰、统计 |
| `TranscodeStream.kt` | 转码档 ProxyFD 后端 |
| `LocalLibrary.kt` | Music 根的 Room 快照投影 |
| `FolderLibrary.kt` | Folders 根的物理路径树 |
| `PowerampSync.kt` / `PowerampProvider.kt` | 收藏拉取/推送 |
| `DiagLog.kt` | 持久化诊断日志 + 导出 |
| `MainActivity.kt` | Compose 设置页 |

## 7. 给下一个 agent 的建议

1. 先问用户“哪台设备、什么现象、有没有报错原文”，不要先改代码；
2. 让用户装 **1.0.2** 并导出诊断日志，比猜快得多；
3. 涉及 HyperOS/ColorOS 时，先排除系统冻结；
4. 推送/发 Release 前必须得到用户明确同意；
5. 不要再往 `SIZE`/MIME 上猜——1.0.2 的日志已经能直接看到 HTTP 状态和异常。
