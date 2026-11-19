# v0.5.0-preview — Jellyfin Provider for Poweramp

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

## What's new

**Bidirectional favourites with Poweramp** (preview, needs real-device validation):

- *Scan Library* now pushes every Jellyfin favourite into Poweramp by writing `rating = 5` through
  Poweramp's content provider (existing 1–5 ratings are left untouched; nothing is deleted; nothing
  is downloaded).
- *Sync Poweramp Likes* mirrors Poweramp back to Jellyfin: every track rated 1–5 becomes a Jellyfin
  favourite, and Jellyfin favourites that Poweramp knows about but does not like are removed. The
  favourites are then downloaded at the selected quality tier.
- Writes are verified per row; if Poweramp refuses access the app asks for data permission via
  `ACTION_ASK_FOR_DATA_PERMISSION` and stops instead of guessing.

**Localized UI** — English / 中文 / 日本語. Auto follows the system locale; the ⓘ button in the top
right lets you switch language and shows credits for ishanjain28 (original project) and lc3124
(this fork), each linking to GitHub.

**Cache panel** is now a table: bold total (GiB + song count), three equal-width quality columns,
the favourite count, then artwork and database sizes.

**Fixes carried over** — truncated transcodes are no longer treated as complete or as EOF (the cause
of tracks skipping mid-playback); OkHttp's 60 s whole-call timeout was removed; the provider update
path is only used for `rating`, never for structural tables.

## Artifact

| Field | Value |
| --- | --- |
| File | `jellyfin-provider-for-poweramp-0.5.0-preview-arm64-v8a_x86_64.apk` |
| Version | 0.5.0-preview (versionCode 23) |
| Size | 3.0 MB |
| ABIs | `arm64-v8a`, `x86_64` |
| minSdk / targetSdk | 26 / 36 |
| SHA-256 | `8cd7ca5d34a8ef11b21ab4c70d2c5ef5f4a61c97171dbc0db5c26d8d6ded385e` |

## Install

```bash
adb install -r jellyfin-provider-for-poweramp-0.5.0-preview-arm64-v8a_x86_64.apk
```

## Verify

```bash
sha256sum jellyfin-provider-for-poweramp-0.5.0-preview-arm64-v8a_x86_64.apk
# must print 8cd7ca5d34a8ef11b21ab4c70d2c5ef5f4a61c97171dbc0db5c26d8d6ded385e
```

## Known issues

1. Writing ratings into Poweramp uses its **undocumented** content provider. It was verified on
   Poweramp build 1035 in an emulator; a future Poweramp build could change or reject it. The app
   verifies each write and aborts safely.
2. The removal half of the mirror only applies to tracks Poweramp has scanned, so favourites for
   tracks it has never seen are kept.
3. Background playback may pause when both the provider and the player are backgrounded (Android
   battery optimisation). Set the provider's battery usage to **Unrestricted**.
4. Transcoding tiers need server-side ffmpeg; a first play waits for the transcoder to start, then
   plays while downloading.

## 中文摘要

- **收藏双向同步**（预览）：扫描库后把 Jellyfin 收藏逐首写成 Poweramp 的 5 星喜欢，只增不减、
  不下载；手动同步则把 Poweramp 的喜欢镜像回 Jellyfin（1–5 全算喜欢，Poweramp 未喜欢的远端收藏
  会被取消，然后按当前音质下载收藏）。
- 写入走 Poweramp 未公开的 content provider，逐条校验；被拒绝时请求授权并停止。
- 界面支持英文 / 中文 / 日文，自动跟随系统，右上角 ⓘ 可切换并查看原作者与修改者署名。
- 缓存统计改为表格：总量（GiB + 首数）、三档音质等宽列、收藏数、封面与数据库。
- 修复转码中断被当成“下载完成/文件结束”导致的播放中跳歌。
