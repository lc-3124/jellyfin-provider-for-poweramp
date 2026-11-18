# Jellyfin SAF Provider

This project exposes Music libraries in [Jellyfin](https://jellyfin.org) over Android's Storage Access Framework(SAF).

With this, you can use players that support SAF but not jellyfin. This should work for all such apps but I have built
it specifically to work the best with [Poweramp](https://powerampapp.com/).


# How to download

This is not published on Play Store yet. You can grab the latest APK from [releases section](https://github.com/ishanjain28/jellyfin-saf-provider/releases).


# Offline cache root (this fork)

In addition to the streaming root ("Jellyfin Music"), the provider exposes a second
root called **Jellyfin Cache**. It only lists tracks that are fully downloaded and
serves them straight from disk, so it keeps working with no network and no login.
Track tags, album art, lyrics and favourite flags come from the local database, so
the cache root looks like a normal tagged music library to Poweramp.

To use it: Poweramp → Settings → Libraries → Folders/Libraries → Add → pick
"Jellyfin Cache".

# Known issues

1. Playback pauses when the application and the audio player both are in background. This is due to Battery optimisations in Android. Set the SAF provider's
battery optimisation level to "Unoptimised". The application does not use any wakelocks or background jobs and it is only active during playback.

2. It does not prune tracks after breaching the specified limit. You need to do it manually using "Evict Old Tracks" option.

# Screenshots

<table>
    <tr>
        <td><img src="static/MainActivity.png" alt="Main Activity" width="200"/></td>
        <td><img src="static/MainActivity2.png" alt="Main Activity2" width="200"/></td>
    </tr>
</table>
