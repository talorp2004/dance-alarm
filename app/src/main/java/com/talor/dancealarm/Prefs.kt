package com.talor.dancealarm

import android.content.Context
import android.content.SharedPreferences

data class Song(val uri: String, val name: String)

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
}
