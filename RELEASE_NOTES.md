# v1.0.4 — Jellyfin Provider for Poweramp

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

## Highlights

**Universal APK** — this build ships `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`, so it runs on
32-bit ARM phones, modern ARM64 phones and emulators alike.

**One data source, two views** — `Jellyfin Music` and `Jellyfin Folders` render from the same Room
snapshot; browsing no longer hits the server and both roots always expose the same tracks.

**Scanning** — full scan uses four concurrent album fetches, batch upserts, album pagination and
stale-track cleanup; incremental scan uses `MinDateLastSaved` and usually finishes in under a second.

**Favourites** — “Sync Likes” opens a dialog with **Pull** and **Push**. Pull restores `rating = 5`
whenever Poweramp shows 0. Push previews the change count before touching Jellyfin. **Neither
direction downloads audio any more** — caching is left to playback or the “complete downloads”
action.

**Playback** — transcoded documents advertise `audio/aac`; transcoded playback starts after the
first 32 KiB is buffered instead of waiting for a full read.

**Diagnostics** — a persistent `diag.log` records the playback chain (HTTP status, exceptions,
bytes), exportable from the ⓘ dialog as `Download/JellyfinProvider-diag-*.txt`.

**Fixes since 1.0.0** — server URLs containing whitespace (for example a trailing newline from a
pasted link) are sanitized, which fixes “lossless works but medium/low fails instantly”.

## Signing & verification

The APK is signed with the **default Android debug certificate** (`CN=Android Debug`). It is **not**
signed with a maintainer release key and has **not** been security-audited. Installing it means
trusting an unvetted build; verify the SHA-256 published with the release. No warranty of any kind.
