# Jellyfin Provider for Poweramp

[English](README.en.md) | **中文**

把 [Jellyfin](https://jellyfin.org) 音乐库通过 Android 的 **Storage Access Framework（SAF）**
暴露成文件夹，让只认本地文件的播放器（主要针对 [Poweramp](https://powerampapp.com/)）也能浏览、
播放、离线缓存你的服务器音乐。

仓库提供两个入口：

| 入口 | 用途 |
| --- | --- |
| **Jellyfin Music** | 在线库。按需流式播放，边下边缓存，可随意拖动进度。 |
| **Jellyfin Cache** | 离线库。只列出已完整下载的曲目，断网也能播，标签、封面、歌词齐全。 |

## 声明

> 本项目是 [ishanjain28/jellyfin-saf-provider](https://github.com/ishanjain28/jellyfin-saf-provider)
> 的个人 fork。**原作者并未审阅、授权或背书本项目**，原始设计与实现的所有功劳归原作者；上游未附
> 许可证文件，本 fork 不主张任何额外权利，仅用于技术学习与个人测试。
>
> 本项目**只在 GitHub 本仓库发布**，不上架任何应用商店，不在其他站点、网盘或任何付费渠道分发，
> 也请勿用于商业用途。
>
> 发布的 APK 使用 **Android 默认 debug 证书**签名，并非维护者正式发布证书，未经任何安全审计。
> 请自行核对 SHA-256，安装与使用风险自负。

## 截图

<table>
  <tr>
    <td align="center"><img src="static/screenshot-zh-main.png" width="260"/><br/>连接状态与音质选择</td>
    <td align="center"><img src="static/screenshot-zh-table.png" width="260"/><br/>缓存统计表格</td>
  </tr>
  <tr>
    <td align="center"><img src="static/screenshot-zh-delete.png" width="260"/><br/>可展开的删除区域</td>
    <td align="center"><img src="static/screenshot-zh-about.png" width="260"/><br/>语言切换与署名</td>
  </tr>
</table>

## 功能

- **两种 SAF 根目录**：在线库支持边下边播；离线库只暴露已下完整、纯本地读取的曲目。
- **三档音质**：无损 / 中等（AAC 128k）/ 低（AAC 64k）。可以在线转码播放，也可以只用于离线下载。
- **收藏双向同步**（依赖 Poweramp 的 content provider）：
  - 扫描资料库时，把 Jellyfin 的收藏**增量**写入 Poweramp（只增不减、不下载）；
  - 手动点“同步 Poweramp 点赞”时，把 Poweramp 的喜欢**镜像**回 Jellyfin，并下载收藏曲目。
- **完整的元数据**：标题、艺术家、专辑艺术家、年份、音轨/碟号、流派、收藏标记、歌词（含 LRC
  时间轴）、封面，全部通过 SAF 游标提供给播放器，离线库同样可用。
- **缓存管理**：自动按最近最少使用淘汰、补全未完成的下载、按档位统计、可展开的删除区域。
- **多语言**：自动跟随系统语言，右上角 ⓘ 可手动切换 English / 中文 / 日本語，并查看原作者与修改者。
- **凭据安全**：密码与 access token 使用 Android Keystore 中的 AES/GCM 密钥加密后保存。

## 工作原理

```
Poweramp ── SAF (content://) ──> JellyfinMusicProvider (DocumentsProvider)
                                      │
                    ┌─────────────────┼──────────────────┐
                    ▼                 ▼                  ▼
          JellyfinClientManager  TrackPlaybackManager   Room
          (Jellyfin SDK + OkHttp) (流式与缓存)           (元数据、分块位图、歌词)
                    │                 │
                    ▼                 ▼
              Jellyfin 服务器    files/jellyfin_tracks/*.cache
```

- **自描述文档 id**：`trk;专辑;曲目;大小;时长`（在线）与 `ctrk;...`（离线）。SAF 的 `getSize()`
  是同步调用，把大小和时长编码进 id 可以省掉额外的数据库或网络往返。
- **无损档**：每首歌一个稀疏文件，按 256 KiB 分块下载，用 Room 里的位图记录哪些块已落盘，读请求
  缺块时阻塞等待，播放器可以边下边播、任意拖动。
- **转码档**：通过 ProxyFileDescriptor 边转边播，下载完成后原子替换为缓存文件；文件长度用
  码率 × 时长估算。
- **完整缓存命中**：直接返回真实文件描述符，零网络、零代理开销。

## 安装与使用

1. 从本仓库的 [Releases](https://github.com/lc-3124/jellyfin-saf-provider/releases) 下载 APK 安装；
2. 打开应用，填写服务器地址、用户名、密码，登录后会自动扫描资料库；
3. 在 Poweramp 中：*设置 → 资料库 → 文件夹/资料库 → 添加文件夹*，勾选 **Jellyfin Music**
   或 **Jellyfin Cache**；
4. 播放歌曲即可，边听边缓存；已完整的曲目会出现在离线库中。

## 音质分级

| 档位 | 播放方式 | 缓存文件 |
| --- | --- | --- |
| 无损 | 原文件直出（`static=true`） | `<id>.cache` |
| 中等 | AAC 128 kbps 转码 | `<id>.aac128.cache` |
| 低 | AAC 64 kbps 转码 | `<id>.aac64.cache` |

- 每首歌只保留一份缓存，**优先保留更高质量**；降档不会删除已有高质量缓存。
- 播放时若缓存档位 ≥ 当前设置，直接播缓存；否则按当前档位重新转码/下载。
- 转码请求会带唯一的 `playSessionId`，避免服务器复用旧任务导致码率不生效。

## 收藏同步

**扫描资料库（Jellyfin → Poweramp）**

- 拉取远端全部收藏，逐首把 Poweramp 里 `rating == 0` 的写成 5；
- 只增不减、不下载、不覆盖已有的 1–5 评分。

**手动同步（Poweramp → Jellyfin）**

- Poweramp 里 `rating` 为 1–5 的曲目，全部设为 Jellyfin 收藏；
- Jellyfin 已收藏、但 Poweramp 未喜欢且 Poweramp 曲库中存在的曲目，取消收藏；
- 同步完成后按当前音质下载收藏曲目。

> 写入 Poweramp 使用的是它**未公开**的 content provider（`com.maxmpz.audioplayer.data/files`），
> 已在 Poweramp build 1035 上验证可读写；未来版本可能变化，应用会逐条校验并在失败时停止。

## 缓存管理

- **刷新缓存统计**：重新计算表格数据；
- **补全未完成的缓存**：只补缺失的分块，失败保留进度；
- **删除（可展开）**：压缩到上限、未完成的下载、全部已下载、收藏曲目、重置全部（二次确认）。

## 构建

需要 JDK 21、Android SDK `android-36.1` 与 build-tools 36：

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # 开启 R8，使用 debug 证书签名
```

## 已知问题

1. 后台播放可能被系统暂停：请把本应用的电池优化设为“不限制”。
2. 转码档首次播放需要等待服务器启动 ffmpeg（约 1 秒），随后边下边播；波形图在整首下载完后出现。
3. 收藏同步的“取消”只作用于 Poweramp 已经扫描到的曲目，未扫描的远端收藏会被保留。
4. 缓存淘汰、删除、统计都以“真正播放过”的曲目为准，预取不会写入磁盘。

## 语言

默认跟随系统语言（中文 / 日本語 / 其他为英文），也可以在右上角 ⓘ 中手动指定并持久保存。
