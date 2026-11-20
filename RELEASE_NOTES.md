# v1.0.0 — Jellyfin Provider for Poweramp

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
> 仅在 GitHub 本仓库发布，不上架应用商店、不镜像、不售卖，也不用于商业用途。

## What's new in 1.0.0

**One data source, two views**

- `Jellyfin Music` and `Jellyfin Folders` now render from the same Room snapshot; browsing no
  longer hits the server, and both roots always expose exactly the same tracks.
- Physical paths are stored in the database (Room v3 with a migration); the folder tree is rebuilt
  locally instead of paginating the server.

**Scanning**

- Full scan: 4 concurrent album fetches, batch upserts, album pagination (albums with 100+ tracks),
  and stale-track cleanup — about 1.5 minutes for 2k albums / 18.7k tracks.
- Incremental scan: `MinDateLastSaved` cursor, usually under a second.
- Scan is now a dialog with explicit **Full** / **Incremental** entries; library updates no longer
  pull favourites automatically.

**Favourites**

- “Sync Likes” opens a dialog with **Push** and **Pull**.
- Pull writes `rating = 5` whenever Poweramp shows 0, so a rebuilt Poweramp library is restored.
- Push shows a confirmation with the number of additions/removals before touching Jellyfin.

**Playback**

- Transcode streaming no longer guesses EOF from the estimated length, no longer marks a slightly
  smaller transcode as failed, and waits longer before giving up — this removes most first-play
  skips on non-lossless quality.

**Interface**

- New logo and adaptive launcher icon.
- Four colour styles (dark/light × blue-purple-red and green-yellow-orange), chosen with colour
  swatches in the ⓘ dialog.
- Connected card is a gradient with logout and a speed test (latency toast); cache stats card is
  tappable to refresh and shows favourites plus partially cached tracks.

## Signing & verification

The APK is signed with the **default Android debug certificate** (`CN=Android Debug`). It is **not**
signed with a maintainer release key and has **not** been security-audited. Installing it means
trusting an unvetted build; verify the SHA-256 published with the release. No warranty of any kind.
