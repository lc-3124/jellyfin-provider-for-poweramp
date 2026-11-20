# Agent 交接：Jellyfin Provider for Poweramp 开发经验

> 这份文档浓缩项目的**设计决策、踩过的坑、构建发布流程和诊断方法**，给下一个接手的人/agent。
> 项目路径：`~/work/project/jellyfin-saf-provider`。

---

## 1. 项目定位

把 Jellyfin 音乐库通过 Android **Storage Access Framework（SAF）** 暴露成文件夹，让 Poweramp
这类只认本地文件的播放器可以浏览、流式播放、离线缓存。

- 包名 `me.ishan.jellyfin_saf`，authority `jellyfin.music`
- fork 自 `ishanjain28/jellyfin-saf-provider`，上游无 LICENSE，README/Release 已声明仅技术用途
- 三个根：
  - **Jellyfin Music**：按专辑浏览，在线流式 + 分块缓存
  - **Jellyfin Folders**：按服务器物理目录（厂牌/专辑艺术家 → 专辑 → 曲目）
  - **Jellyfin Cache**：只列完整缓存的曲目，纯本地

## 2. 技术栈与构建

| 项 | 值 |
| --- | --- |
| 语言 | Kotlin 2.3.x |
| UI | Compose + Material3，单 Activity |
| 构建 | AGP 9.2.1 / Gradle 9.4.1 / JDK 21（`~/.local/jdks/temurin-21`） |
| SDK | compileSdk 36、minSdk 26 |
| 网络 | Jellyfin Kotlin SDK + OkHttp |
| 存储 | Room（DB v3）+ SharedPreferences + Keystore |

```bash
cd ~/work/project/jellyfin-saf-provider
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # R8，debug 证书签名
```

发布包用 **Android 默认 debug 证书**签名，只能侧载；README 与 Release 都写明了这一点。

## 3. 架构与数据流

```
Poweramp ── SAF ──> JellyfinMusicProvider (DocumentsProvider)
                         │
        ┌────────────────┼─────────────────┐
        ▼                ▼                 ▼
  JellyfinClientManager  TrackPlaybackManager  Room
  (SDK、扫描、URL)        (流式、缓存、淘汰)      (元数据、分块位图、歌词)
        │                │
        ▼                ▼
   Jellyfin 服务器   files/jellyfin_tracks/*.cache
```

### 3.1 关键设计决策

1. **自描述文档 id**：`trk;专辑;曲目;大小;时长`（在线）/ `ctrk;…`（离线）。
   SAF 的 `getSize()` 是同步调用，把 size/duration 编进 id 可省掉数据库或网络往返。
2. **稀疏文件 + 分块位图**：每首歌一个稀疏文件，256 KiB 一块，Room 里用 BitSet 记录已落盘块，
   读缺块时阻塞等待，从而支持边下边播、任意 seek、断点续传。
3. **ProxyFileDescriptor**：未完整缓存时返回 ProxyFD，避免“先下完才能播”。
4. **一份快照，两个投影**（0.9 起）：Music 与 Folders 都从 Room 读，Music 按 `albumId` 分组，
   Folders 按物理路径分组；浏览不再打服务器。扫描是唯一写入口。
5. **物理路径入库**：DB v3 新增 `path` 列，目录树从数据库重建，不再分页请求服务器（旧实现要跑
   38 次慢查询）。迁移用 `ALTER TABLE`，不丢数据。
6. **扫描游标**：`MinDateLastSaved` + 服务器本地时间、回退 30 分钟；重复写入幂等。
7. **孤儿容忍**：服务器上 `albumId=null` 的曲目按专辑扫不到，用 `ORPHAN_TOLERANCE=20` 容忍，
   避免每次都被迫全量；本地数量多于服务器时自动退回全量清理。

### 3.2 扫描策略

- **全量**：4 路并发按专辑抓取（服务器 `/Items` 约 3–6 请求/秒），每张专辑批量 upsert；
  专辑内翻页（有 216 首的合辑，limit=100 会截断）；完成后删除服务器已不存在的曲目。
  实测 2k 专辑 / 18.7k 曲目约 80–210 秒（取决于是否更新已有行）。
- **增量**：只抓 `MinDateLastSaved` 之后的变更，通常 1 秒内。
- **升级兼容**：旧库没有 `path` 列时自动强制一次全量补路径；路径不足 90% 时不覆盖旧目录树。

### 3.3 收藏同步

- 入口“同步点赞”弹窗，两个方向都手动：
  - **拉取**：Jellyfin 收藏 → Poweramp `rating=5`；**只要 rating 是 0 就写**，
    这样 Poweramp 资料库重建/清空后还能恢复；
  - **推送**：先算差异弹确认框（新增 X / 取消 Y），确认后才镜像到 Jellyfin，并下载收藏曲目。
- 扫描资料库不再自动同步收藏。
- 写入 Poweramp 用它**未公开**的 content provider，每次写完校验，失败就请求授权并停止。

### 3.4 UI 设计

- 固定四套配色（暗色/亮色 × 蓝紫红 / 绿黄橙），不跟随壁纸动态取色；ⓘ 里用三色条纹图标切换。
- 标题自动缩放保证单行完整；设置卡片尽量紧凑。
- 扫描/同步点赞/删除都是**弹窗**，不就地展开（展开会撑高布局）。
- 已连接卡片：渐变背景、退出登录、测速（延迟用 Toast）。
- 缓存统计卡片：点按刷新、刷新图标、“收藏”和“半缓存”并排。

## 4. 踩坑清单（按项目历史排序）

1. **Room 主线程崩溃**：`getFavouriteCount()` 曾在主线程调用直接崩，必须 `withContext(IO)`。
2. **Jellyfin 收藏 API 参数顺序**：`markFavoriteItem(itemId, userId)` 用位置参数写反会 404。
3. **`INSERT OR REPLACE` 级联**：REPLACE 会先删父行，触发 `track_state` 的 ON DELETE CASCADE，
   把缓存位图清空。必须用 `@Upsert`，并兼容孤儿缓存文件恢复。
4. **转码截断当成 EOF**：服务器中途断流时 `read()` 返回 -1 曾被当作正常结束；现在必须比对长度。
5. **`callTimeout(60s)`** 会掐断长转码，已移除。
6. **转码码率不生效**：Jellyfin 按 `deviceId + playSessionId` 复用任务，每次请求必须带唯一
   `playSessionId`。
7. **SAF 树后代校验**：Android 用**树根**对每个文档调 `isChildDocument`；`FoldersRoot` 必须接受
   Artist/FolderAlbum/Track 三种后代，否则 Poweramp 扫描抛
   `SecurityException: ... is not a descendant of jellyfin_folders_root`，扫完 0 文件。
8. **公共路径前缀**：物理路径必须先剥离最长公共目录前缀，否则第一层永远是 `home`，所有专辑平铺。
9. **OEM 后台限制**：
   - 一加/ColorOS：`OplusAppStartupManager` 拦截 provider；
   - 小米/HyperOS：`security reject start jellyfin.music`、`Failed to find provider info`、
     `FuseAppLoop: FileNotFoundException: No content provider`。
   - 表现是“连无损都播不了”。解决：系统设置里开 **自启动** + **省电策略无限制**；
     重装后要复查。
10. **两个根同时加进 Poweramp 会双份**：同一首歌在 Music 和 Folders 下路径不同，播放器无法去重。
    只能二选一；这不是 bug。
11. **Poweramp 按 MIME 选解码器**：转码输出是 AAC，但旧代码把文档 MIME 报成源文件类型
    （flac），部分构建会拿 FLAC 解码器解 AAC。非无损档必须报 `audio/aac`。
12. **服务器地址带空白/换行**：用户从聊天记录粘贴的地址可能以 `\n` 结尾。SDK 构造的无损 URL
    能容错，手工拼接的转码 URL 会抛 `Invalid URL host`，请求根本发不出去。
    保存、加载、拼接三处都要清空白。
13. **Poweramp 扫描时 provider 不可用会丢行**：应用更新/被冻结期间，播放器会把该根的记录清掉
    再只补回一部分（曾出现 18,671 首只剩 13,074 首）。重新触发一次扫描即可恢复；
    报告里看到的“音乐根 2 倍、文件夹根变少”通常是这个 + 双根叠加。
14. **专辑曲目超过 100 首**：`getAlbumTracks` 原来 limit=100，216 首的合辑会少 116 首；
    必须翻页。
15. **`albumId=null` 的孤儿曲目**：按专辑扫描永远扫不到（目前服务器有 1–2 首），
    用容忍阈值避免反复全量。
16. **R8 会删 `Log.d/v`**：release 包里关键路径日志会消失，排查必须用文件日志（`DiagLog`）。
17. **Windows 重定向的日志是 UTF-16**：`adb logcat > x.txt` 在 PowerShell 下变成 UTF-16，
    grep 会全部失效，分析前要转码。
18. **模拟器无音频**：`-no-audio` 时 Poweramp 会报 `Bluetooth audio disconnected`，
    无法验证播放听感；只能验证 provider/流。
19. **Poweramp 私有数据库列名**：`files` 表没有 `title`/`date_added`，只有 `folder_files._id`、
    `rating`、`file_path` 等；用错列名会 SQLiteException。

## 5. 诊断方法（真机/远程）

### 5.1 应用内诊断（1.0.2 起）

- 所有关键路径写 `files/diag.log`：打开文档、HTTP 状态、异常、下载字节数、转码完成；
- ⓘ → **导出诊断日志** → `Download/JellyfinProvider-diag-*.txt`，可直接发给维护者；
- 不记录 token/密码。

### 5.2 adb

```bash
adb devices -l
adb -s <serial> logcat -c
# 复现
adb -s <serial> logcat -d -v threadtime > full.txt
adb -s <serial> logcat -d -v threadtime \
  -s JellyfinMusicProvider:* TrackPlaybackManager:* JellyfinClientManager:* TranscodeStream:* > tags.txt
```

### 5.3 触发 Poweramp 扫描 / 统计

```bash
adb shell 'am broadcast -a com.maxmpz.audioplayer.ACTION_SCAN_DIRS \
  -n com.maxmpz.audioplayer/.player.PowerampAPIReceiver \
  --es path "@jellyfin.music/jellyfin_folders_root/"'

adb shell 'content query --uri content://com.maxmpz.audioplayer.data/files \
  --projection file_path' | grep -c jellyfin_folders_root
```

### 5.4 现象 → 结论对照

| 现象 | 结论 |
| --- | --- |
| 全部播不了，`No content provider` | OEM 冻结，开自启动 + 省电无限制 |
| 只有中等/低失败，`Invalid URL host` | 服务器地址有空白字符 |
| 只有中等/低失败，`code=401` | token/账号问题 |
| 只有中等/低失败，`SocketException: connection abort` | 隧道/网络中途断流，重试或换网络 |
| 服务器日志没有 ffmpeg | 请求没到服务器：URL/token/网络 |
| 服务器 `ffmpeg code 0` 但客户端失败 | 客户端下载/读取被中断 |
| 某个根曲目数偏少 | 该根最近一次扫描被中断，重扫 |

## 6. 测试环境

- **模拟器**：
  - `jf34`：AOSP x86_64 Android 14，用来看 UI；
  - `pa34`：Play Store x86_64（带 ARM 转译），装了 Poweramp build 1035，用来跑播放/扫描。
  - 启动示例：`$ANDROID_HOME/emulator/emulator -avd pa34 -port 5556 -no-window -gpu swiftshader_indirect`
  - `-no-audio` 时无法验证实际出声。
- **真机**：一加（ColorOS）与红米 K90（HyperOS），adb 序列号不同；两台机器的坐标/分辨率不可混用。
- 应用内 `DiagLog` 是首选证据；其次才是 logcat。

## 7. 构建与发布流程

1. 改 `app/build.gradle.kts` 的 `versionName` 和 `versionCode`；
2. `./gradlew assembleRelease`；
3. 本地提交（Git 身份：`lc-3124 <lc-3124@users.noreply.github.com>`）；
4. **push / Release / 写远端前必须让用户确认**；
5. 推送到 `origin`（优先 SSH：`git push git@github.com:lc-3124/jellyfin-saf-provider.git master`）；
6. 需要发 Release 时：打 tag、用 GitHub API 创建 release 并上传 APK，正文含 SHA-256；
7. 局域网主机（10.53.93.9）可用 `sshpass scp` 传 APK，同样需用户同意。

> 注意：`origin` 的 remote-tracking ref 在显式用 URL push 时不会更新，核对远端要用
> `git ls-remote`，不要只看 `origin/master`。

## 8. 当前状态与未完成（2026-11-20）

- **版本 1.0.2（versionCode 48）**，已推 master；GitHub 只发布过 `v1.0.0`。
- 1.0.2 内容：URL 空白清理、持久化诊断日志与导出、转码 MIME 修正。
- **未解决**：
  1. 下载中途 `SocketException: connection abort`（疑隧道/网络切换）；自动重试补丁写过但未验证；
  2. 有损模式的长期方案（MP3、下载后播、切歌取消下载）在 stash
     `lossy experiments 1.0.1-1.1.4 (unverified)` 和 `rollback-1.0.0` 分支里，**不要直接发布**；
  3. 多碟/多物理目录合并、Shizuku、库管理页等产品需求未开工。

## 9. 代码地图

| 文件 | 职责 |
| --- | --- |
| `JellyfinMusicProvider.kt` | DocumentsProvider：三个根、文档树、ProxyFD、MIME |
| `JellyfinClientManager.kt` | 认证、扫描（全量/增量）、音频 URL、下载 |
| `TrackPlaybackManager.kt` | 分块缓存、转码流、淘汰、统计 |
| `TranscodeStream.kt` | 转码档 ProxyFD 后端 |
| `LocalLibrary.kt` | Music 根的 Room 快照投影 |
| `FolderLibrary.kt` | Folders 根的物理路径树 |
| `PowerampSync.kt` / `PowerampProvider.kt` | 收藏拉取/推送 |
| `DiagLog.kt` | 文件诊断日志 + 导出 |
| `MainActivity.kt` | Compose 设置页 |
| `db/` | Room entity/DAO/迁移 |

## 10. 协作约定

- 用户用中文，偏好直接、有结论；不喜欢无意义追问。
- 大改动先讲方案，确认后再动手；小修可以直接做。
- **外部写入（push/release/scp/改服务器）必须先得到明确同意。**
- 用户会亲自测试真机并反馈现象；需要日志时优先用应用内导出。
- 解释实现时顺带讲原理——用户在学 Android。
