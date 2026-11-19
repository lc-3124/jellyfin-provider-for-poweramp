# v0.3.0-preview — Jellyfin Provider for Poweramp

> **Unofficial fork / 非官方 fork**
>
> This release is built from a personal fork of
> [ishanjain28/jellyfin-saf-provider](https://github.com/ishanjain28/jellyfin-saf-provider).
> **The original author has not reviewed, authorised, or endorsed it.** All credit for the original
> project belongs to them. Upstream carries no licence file, so this fork claims no additional
> rights and is provided strictly for technical study and personal testing.
>
> 本项目是原作者项目的个人 fork，**未获得原作者授权或背书**，原始项目的功劳归原作者。上游没有
> 许可证文件，本 fork 不主张任何额外权利，仅供技术学习与个人测试使用。

## Distribution scope

This build is published **only** on this GitHub repository. It is not on any app store, not
mirrored anywhere else, not bundled, and not sold. Please do not redistribute it outside GitHub.
No commercial use.

本构建**仅在 GitHub 本仓库发布**：不上架应用商店、不镜像到其他站点、不打包进任何合集、不售卖。

## Signing & security

The APK is signed with the **default Android debug certificate** (`CN=Android Debug`). It is **not**
signed with a maintainer release key and has **not** been security-audited.

- Installing it means trusting an unvetted build. Use at your own risk.
- Verify the SHA-256 below before installing.
- No warranty of any kind is provided.

APK 使用 **Android 默认 debug 证书**签名，并非维护者正式发布证书，也未经任何安全审计。安装即表示
你接受这一风险，不提供任何担保。

## What's new in this preview

**Renamed** to *Jellyfin Provider for Poweramp* (launcher label and project name).

**Audio quality tiers** — choose in the app settings:

| Tier | Request | Cached as |
| --- | --- | --- |
| Lossless | original file (`static=true`) | `<id>.cache` |
| Medium 128k | AAC transcoded | `<id>.aac128.cache` |
| Low 64k | AAC transcoded | `<id>.aac64.cache` |

- One cached copy per track, highest tier wins; lowering the setting never deletes a better copy.
- Transcoded tiers are delivered through a proxy file descriptor: playback starts while the file is
  still being written, and the track is promoted to the cache when the download finishes.
- Requests carry a unique `playSessionId`; without it Jellyfin reuses an existing transcode job and
  silently ignores the requested bitrate.

**Poweramp likes → Jellyfin favourites**

- Reads Poweramp's content provider (`rating`, `file_path`); the path embeds this provider's
  document id, so the mapping to the Jellyfin track UUID is exact.
- Any positive rating (2–5 stars) counts as a like, 1 is unlike, 0 is left untouched.
- After syncing, favourited tracks are downloaded at the selected tier.

**Fixes**

- Crash after sync: favourite count query ran on the main thread.
- Favourite API returned 404: `markFavoriteItem(itemId, userId)` argument order.
- Cache index was wiped by `INSERT OR REPLACE` cascading into `track_state`; now `@Upsert` plus a
  startup recovery for orphaned cache files.
- Eviction was not LRU, deleted metadata and fired its callback twice.
- Rows disappeared when the caller used a narrow column projection.
- Reader-count race on tracks opened by several consumers.
- Plaintext password kept after logout.

## Artifact

| Field | Value |
| --- | --- |
| File | `jellyfin-provider-for-poweramp-0.3.0-preview-arm64-v8a_x86_64.apk` |
| Version | 0.3.0-preview (versionCode 20) |
| Size | 3.0 MB |
| ABIs | `arm64-v8a`, `x86_64` |
| minSdk / targetSdk | 26 / 36 |
| SHA-256 | `8bc7ab43ec2ecf32377ed8f0a88613d44a7957c980b08cf27af7e4afb1c1b3d5` |

## Install

```bash
adb install -r jellyfin-provider-for-poweramp-0.3.0-preview-arm64-v8a_x86_64.apk
```

Or copy the APK to the device and open it (allow "install unknown apps" for the file manager).
Because the signature is the shared Android debug key, it can replace an earlier build of this fork
without uninstalling; cached music is preserved.

## Verify

```bash
sha256sum jellyfin-provider-for-poweramp-0.3.0-preview-arm64-v8a_x86_64.apk
# must print 8bc7ab43ec2ecf32377ed8f0a88613d44a7957c980b08cf27af7e4afb1c1b3d5
```

## Known issues

1. Background playback may pause when both the provider and the player are backgrounded (Android
   battery optimisation). Set the provider's battery usage to **Unrestricted**.
2. Transcoding tiers need server-side ffmpeg. A first play waits ~1 s for the transcoder to start,
   then plays while downloading; the waveform appears once the whole track has arrived.
3. Files stored on a flaky network mount may fail to transcode (the server logs `FFmpeg exited with
   code 183`); the app falls back to an existing cache or the original stream.
4. The first browse of a large library fetches the album list from the server; it is cached for two
   minutes afterwards.

## 中文摘要

- 项目更名为 **Jellyfin Provider for Poweramp**。
- 新增三档音质：无损 / Medium 128k AAC / Low 64k AAC，可在设置里切换；每首歌只保留一份缓存，
  降档不会删掉更高质量的副本。
- 转码档边下边播、下完自动转正为缓存；修复了 Jellyfin 复用转码任务导致码率不生效的问题。
- Poweramp 的点赞（2–5 星）会同步为 Jellyfin 收藏并自动下载这些收藏曲目；1 星取消收藏，0 星不动。
- 修复了同步后崩溃、收藏接口 404、缓存索引被级联删除、淘汰非 LRU 等问题。
- 本版本为 **preview**，使用 Android 默认 debug 证书签名，未经安全审计，请自行核对 SHA-256。
