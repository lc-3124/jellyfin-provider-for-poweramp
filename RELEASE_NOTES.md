# v0.1.2 — Offline cache root & partial download completion

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

This build is published **only** on this GitHub repository
(`github.com/lc-3124/jellyfin-saf-provider`). It is not on any app store, not mirrored anywhere
else, not bundled, and not sold. Please do not redistribute it outside GitHub. No commercial use.

本构建**仅在 GitHub 本仓库发布**：不上架应用商店、不镜像到其他站点、不打包进任何合集、不售卖。
请勿在 GitHub 之外分发，也请勿用于商业用途。

## Signing & security

The APK is signed with the **default Android debug certificate** (`CN=Android Debug`) — the same
kind of key upstream's CI uses. It is **not** signed with a maintainer release key and has **not**
been security-audited.

- Installing it means trusting an unvetted build. Use at your own risk.
- The signature only guarantees that this file was built from this source tree; it guarantees
  nothing about security, correctness, or fitness for any purpose.
- Verify the SHA-256 below before installing.
- No warranty of any kind is provided.

APK 使用 **Android 默认 debug 证书**签名，并非维护者正式发布证书，也未经任何安全审计。安装即表示
你接受这一风险；签名只说明它由本仓库源码构建，不代表安全或可用性有任何保证。不提供任何担保。

## Highlights

- **Jellyfin Cache** — a second SAF root that lists only fully downloaded tracks and serves them
  from disk: works offline, without login, with tags, lyrics, album art and favourite flags.
- **Complete Partial Downloads** — fetches only the missing byte ranges of every partially cached
  track, with resumable progress.
- **Automatic LRU eviction** — runs when the cache exceeds the configured limit, skips tracks in
  use and never evicts favourites.
- Album art is prefetched when a track completes, so the offline root has covers.

## Fixes

- Cache index being silently wiped: metadata used `INSERT OR REPLACE`, whose implicit `DELETE`
  triggered `ON DELETE CASCADE` on `track_state`, leaving orphaned `.cache` files. Now `@Upsert`,
  plus a one-time recovery of existing orphaned files based on real allocated blocks.
- Eviction was not LRU (no ordering, no access time) and could delete metadata, with a doubled
  completion callback.
- Cache database size always reported 0.
- Rows disappeared when a caller requested a narrower column projection.
- Reader-count race when several consumers opened the same track.
- Plaintext password retained after logout.
- Album listing cached for two minutes to reduce binder-thread stalls.

## Artifact

| Field | Value |
| --- | --- |
| File | `jellyfin-saf-provider-0.1.2-arm64-v8a_x86_64.apk` |
| Version | 0.1.2 (versionCode 10) |
| Size | 2.9 MB |
| ABIs | `arm64-v8a`, `x86_64` |
| minSdk / targetSdk | 26 / 36 |
| SHA-256 | `c932c7beeede06cf56a86f7364f570a4205bd04ba5c59d91605617e2de48be59` |

## Install

```bash
adb install -r jellyfin-saf-provider-0.1.2-arm64-v8a_x86_64.apk
```

Or copy the APK to the device and open it (allow "install unknown apps" for the file manager).

Because the signature is the shared Android debug key, this APK can replace an earlier debug build
of this fork without uninstalling; cached music is preserved.

## Verify

```bash
sha256sum jellyfin-saf-provider-0.1.2-arm64-v8a_x86_64.apk
# must print c932c7beeede06cf56a86f7364f570a4205bd04ba5c59d91605617e2de48be59
```

## Known issues

1. Background playback may pause when both the provider and the player are backgrounded (Android
   battery optimisation). Set the provider's battery usage to **Unrestricted**.
2. The first browse of a large library fetches the album list from the server; it is cached for two
   minutes afterwards.
3. Album art is only available offline after being fetched once online, or after the track
   completed.
