package com.talor.dancealarm

import android.content.Context
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlin.random.Random

/**
 * Plays the chosen playlist through the Spotify app, starting from a random song with
 * shuffle + repeat on, and un-pauses it if someone tries to pause it during the alarm.
 */
class SpotifyPlayer(
    private val context: Context,
    private val onSong: (String) -> Unit,
    private val onPlaying: () -> Unit,
    private val onFailed: (String) -> Unit,
) {
    companion object {
        private const val TAG = "DanceAlarm"
        private const val REPEAT_ALL = 1

        fun isReady(c: Context) =
            Prefs.spotifyClientId(c).isNotBlank() &&
                Prefs.spotifyPlaylistUri(c).isNotBlank() &&
                SpotifyAppRemote.isSpotifyInstalled(c)

        fun params(c: Context, showAuth: Boolean): ConnectionParams =
            ConnectionParams.Builder(Prefs.spotifyClientId(c))
                .setRedirectUri(SpotifyApi.REDIRECT_URI)
                .showAuthView(showAuth)
                .build()

        /** One-time approval inside the Spotify app, done while setting up (screen on). */
        fun authorize(c: Context, done: (String?) -> Unit) {
            if (!SpotifyAppRemote.isSpotifyInstalled(c)) {
                done("The Spotify app isn't installed")
                return
            }
            SpotifyAppRemote.connect(c, params(c, true), object : Connector.ConnectionListener {
                override fun onConnected(remote: SpotifyAppRemote) {
                    SpotifyAppRemote.disconnect(remote)
                    done(null)
                }
                override fun onFailure(t: Throwable) {
                    done(t.message ?: t.javaClass.simpleName)
                }
            })
        }
    }

    private var remote: SpotifyAppRemote? = null
    @Volatile private var active = true
    private var playing = false

    fun start() {
        val uri = Prefs.spotifyPlaylistUri(context)
        SpotifyAppRemote.connect(context, params(context, false), object : Connector.ConnectionListener {
            override fun onConnected(r: SpotifyAppRemote) {
                if (!active) {
                    SpotifyAppRemote.disconnect(r)
                    return
                }
                remote = r
                val player = r.playerApi
                player.setShuffle(true)
                player.setRepeat(REPEAT_ALL)

                val count = Prefs.spotifyPlaylistCount(context)
                if (count > 1) {
                    player.skipToIndex(uri, Random.nextInt(count))
                        .setErrorCallback { player.play(uri) }
                } else {
                    // Shuffle is on, so Spotify picks a random starting song.
                    player.play(uri).setErrorCallback { e -> fail("Couldn't play: ${e.message}") }
                }

                player.subscribeToPlayerState().setEventCallback { state ->
                    if (!active) return@setEventCallback
                    val track = state.track
                    if (track != null) {
                        val artist = track.artist?.name
                        onSong(if (artist.isNullOrBlank()) track.name else "${track.name} — $artist")
                    }
                    if (!state.isPaused && track != null && !playing) {
                        playing = true
                        onPlaying()
                    }
                    // No pausing your way out of it.
                    if (state.isPaused && playing) player.resume()
                }
            }

            override fun onFailure(t: Throwable) {
                fail(t.message ?: t.javaClass.simpleName)
            }
        })
    }

    private fun fail(reason: String) {
        if (!active) return
        Log.w(TAG, "Spotify failed: $reason")
        stop()
        onFailed(reason)
    }

    fun stop() {
        active = false
        val r = remote ?: return
        remote = null
        try {
            r.playerApi.pause().setResultCallback { SpotifyAppRemote.disconnect(r) }
        } catch (_: Exception) {}
        // Make sure we disconnect even if the pause callback never comes.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { SpotifyAppRemote.disconnect(r) } catch (_: Exception) {}
        }, 3000)
    }
}
