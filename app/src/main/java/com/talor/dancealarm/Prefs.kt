package com.talor.dancealarm

import android.content.Context
import android.content.SharedPreferences

data class Song(val uri: String, val name: String)

const val DEFAULT_SPOTIFY_CLIENT_ID = "a88890a7e1d8475b8ab4a599e4fe635d"

object Prefs {
    private fun p(c: Context): SharedPreferences =
        c.getSharedPreferences("dance_alarm", Context.MODE_PRIVATE)

    fun hour(c: Context) = p(c).getInt("hour", 7)
    fun minute(c: Context) = p(c).getInt("minute", 0)
    fun setTime(c: Context, h: Int, m: Int) = p(c).edit().putInt("hour", h).putInt("minute", m).apply()

    fun isEnabled(c: Context) = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("enabled", v).apply()

    /** Seconds of real dancing needed to stop the alarm. */
    fun danceSeconds(c: Context) = p(c).getInt("dance_seconds", 30)
    fun setDanceSeconds(c: Context, v: Int) = p(c).edit().putInt("dance_seconds", v).apply()

    /** 0 = chill, 1 = normal, 2 = hard */
    fun difficulty(c: Context) = p(c).getInt("difficulty", 1)
    fun setDifficulty(c: Context, v: Int) = p(c).edit().putInt("difficulty", v).apply()

    fun lastSong(c: Context): String? = p(c).getString("last_song", null)
    fun setLastSong(c: Context, uri: String) = p(c).edit().putString("last_song", uri).apply()

    fun songs(c: Context): List<Song> {
        val raw = p(c).getString("songs", "") ?: ""
        return raw.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("\t", limit = 2)
            if (parts.size == 2) Song(parts[0], parts[1]) else null
        }
    }

    fun setSongs(c: Context, songs: List<Song>) {
        val raw = songs.joinToString("\n") {
            it.uri + "\t" + it.name.replace("\n", " ").replace("\t", " ")
        }
        p(c).edit().putString("songs", raw).apply()
    }

    fun addSongs(c: Context, newSongs: List<Song>): Int {
        val current = songs(c).toMutableList()
        val known = current.map { it.uri }.toHashSet()
        var added = 0
        for (s in newSongs) if (known.add(s.uri)) { current.add(s); added++ }
        setSongs(c, current)
        return added
    }

    // ---------- alarm source ----------
    /** true = ring together with the phone's own (Clock app) morning alarm */
    fun followPhone(c: Context) = p(c).getBoolean("follow_phone", false)
    fun setFollowPhone(c: Context, v: Boolean) = p(c).edit().putBoolean("follow_phone", v).apply()

    /** When the dance alarm is actually set to go off next (0 = not set). */
    fun scheduledAt(c: Context) = p(c).getLong("scheduled_at", 0L)
    fun setScheduledAt(c: Context, v: Long) = p(c).edit().putLong("scheduled_at", v).apply()

    // ---------- music source ----------
    fun useSpotify(c: Context) = p(c).getBoolean("use_spotify", false)
    fun setUseSpotify(c: Context, v: Boolean) = p(c).edit().putBoolean("use_spotify", v).apply()

    fun spotifyClientId(c: Context) = p(c).getString("sp_client_id", DEFAULT_SPOTIFY_CLIENT_ID) ?: ""
    fun setSpotifyClientId(c: Context, v: String) = p(c).edit().putString("sp_client_id", v.trim()).apply()

    fun spotifyVerifier(c: Context) = p(c).getString("sp_verifier", "") ?: ""
    fun setSpotifyVerifier(c: Context, v: String) = p(c).edit().putString("sp_verifier", v).apply()

    fun spotifyAccess(c: Context) = p(c).getString("sp_access", "") ?: ""
    fun spotifyRefresh(c: Context) = p(c).getString("sp_refresh", "") ?: ""
    fun spotifyExpiry(c: Context) = p(c).getLong("sp_expiry", 0L)
    fun setSpotifyTokens(c: Context, access: String, refresh: String?, expiresInSec: Long) {
        val e = p(c).edit()
            .putString("sp_access", access)
            .putLong("sp_expiry", System.currentTimeMillis() + (expiresInSec - 60) * 1000)
        if (!refresh.isNullOrBlank()) e.putString("sp_refresh", refresh)
        e.apply()
    }
    fun clearSpotifyTokens(c: Context) =
        p(c).edit().remove("sp_access").remove("sp_refresh").remove("sp_expiry").apply()

    fun spotifyPlaylistUri(c: Context) = p(c).getString("sp_pl_uri", "") ?: ""
    fun spotifyPlaylistName(c: Context) = p(c).getString("sp_pl_name", "") ?: ""
    /** Number of songs, or -1 if Spotify doesn't tell us (playlists you only follow). */
    fun spotifyPlaylistCount(c: Context) = p(c).getInt("sp_pl_count", -1)
    fun setSpotifyPlaylist(c: Context, uri: String, name: String, count: Int) =
        p(c).edit().putString("sp_pl_uri", uri).putString("sp_pl_name", name).putInt("sp_pl_count", count).apply()
}
