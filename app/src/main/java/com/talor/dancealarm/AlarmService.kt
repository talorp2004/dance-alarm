package com.talor.dancealarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Rings the alarm: plays random songs from the playlist at full alarm volume, vibrates,
 * and keeps pushing the dance screen back to the front until the dance is done.
 */
class AlarmService : Service() {

    companion object {
        private const val TAG = "DanceAlarm"
        private const val CHANNEL_ID = "alarm_ringing"
        private const val NOTIF_ID = 42
        private const val WATCHDOG_MS = 1500L
        private const val MAX_RING_MS = 45 * 60 * 1000L // safety stop after 45 min

        @Volatile var isRinging = false
            private set
        @Volatile var currentSong: String = ""
            private set

        fun start(c: Context) {
            ContextCompat.startForegroundService(c, Intent(c, AlarmService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, AlarmService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var failures = 0
    private var usingFallback = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var originalVolume = -1
    private var originalMusicVolume = -1
    private var spotify: SpotifyPlayer? = null
    private var spotifyMode = false
    private var musicVolumeTouched = false
    private var startedAt = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            if (!isRinging) return
            forceMaxVolume()
            if (System.currentTimeMillis() - startedAt > MAX_RING_MS) {
                stopSelf()
                return
            }
            if (!DanceActivity.isVisible) launchDanceScreen()
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (isRinging) return START_STICKY

        isRinging = true
        startedAt = System.currentTimeMillis()

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DanceAlarm:ring")
            .apply { acquire(MAX_RING_MS + 60_000) }

        val am = getSystemService(AudioManager::class.java)
        originalVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        originalMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        forceMaxVolume()

        startMusic()
        startVibration()
        launchDanceScreen()
        handler.postDelayed(watchdog, WATCHDOG_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        isRinging = false
        currentSong = ""
        handler.removeCallbacksAndMessages(null)
        spotify?.stop()
        spotify = null
        releasePlayer()
        try { getSystemService(Vibrator::class.java)?.cancel() } catch (_: Exception) {}
        try {
            val am = getSystemService(AudioManager::class.java)
            if (originalVolume >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
            if (musicVolumeTouched && originalMusicVolume >= 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0)
            }
        } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---------- notification / foreground ----------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Ringing alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while the alarm is ringing"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }

        val danceIntent = PendingIntent.getActivity(
            this, 2, danceIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Wake up! 💃")
            .setContentText("Dance to turn off the alarm")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(danceIntent)
            .setFullScreenIntent(danceIntent, true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }

    private fun danceIntent() = Intent(this, DanceActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    /** Works from the background when "Display over other apps" is allowed. */
    private fun launchDanceScreen() {
        try {
            startActivity(danceIntent())
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch dance screen", e)
        }
    }

    // ---------- sound ----------

    private fun forceMaxVolume() {
        try {
            val am = getSystemService(AudioManager::class.java)
            // Spotify plays on the media stream, our own songs on the alarm stream.
            val streams = if (spotifyMode) intArrayOf(AudioManager.STREAM_ALARM, AudioManager.STREAM_MUSIC)
            else intArrayOf(AudioManager.STREAM_ALARM)
            if (spotifyMode) musicVolumeTouched = true
            for (stream in streams) {
                val max = am.getStreamMaxVolume(stream)
                if (am.getStreamVolume(stream) < max) am.setStreamVolume(stream, max, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Volume change failed", e)
        }
    }

    /** Spotify playlist if set up, otherwise (or if Spotify fails) your own songs. */
    private fun startMusic() {
        if (!(Prefs.useSpotify(this) && SpotifyPlayer.isReady(this))) {
            playNext()
            return
        }
        spotifyMode = true
        forceMaxVolume()
        currentSong = "Starting Spotify…"
        var started = false
        val giveUp = Runnable {
            if (!started && isRinging) {
                Log.w(TAG, "Spotify didn't start in time, using local songs")
                fallBackToLocal()
            }
        }
        handler.postDelayed(giveUp, 20_000)
        spotify = SpotifyPlayer(
            this,
            onSong = { currentSong = it },
            onPlaying = {
                started = true
                handler.removeCallbacks(giveUp)
            },
            onFailed = {
                handler.removeCallbacks(giveUp)
                if (isRinging) fallBackToLocal()
            },
        ).also { it.start() }
    }

    private fun fallBackToLocal() {
        spotify?.stop()
        spotify = null
        spotifyMode = false
        playNext()
    }

    private fun pickSong(songs: List<Song>): Song {
        if (songs.size == 1) return songs[0]
        val last = Prefs.lastSong(this)
        return songs.filter { it.uri != last }.random()
    }

    private fun fallbackUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    private fun playNext() {
        if (!isRinging) return
        releasePlayer()

        val songs = Prefs.songs(this)
        val uri: Uri
        if (songs.isEmpty() || failures > songs.size + 3) {
            if (usingFallback && failures > songs.size + 6) {
                Log.e(TAG, "Nothing playable; vibration only")
                return
            }
            usingFallback = true
            uri = fallbackUri() ?: return
            currentSong = "Default alarm sound"
        } else {
            val song = pickSong(songs)
            Prefs.setLastSong(this, song.uri)
            uri = Uri.parse(song.uri)
            currentSong = song.name
        }

        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(this, uri)
            mp.isLooping = usingFallback
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { failures = 0; playNext() }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Playback error $what/$extra for $uri")
                failures++
                handler.post { playNext() }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot play $uri", e)
            failures++
            handler.postDelayed({ playNext() }, 200)
        }
    }

    private fun releasePlayer() {
        player?.let {
            try { it.reset() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
    }

    // ---------- vibration ----------

    private fun startVibration() {
        try {
            @Suppress("DEPRECATION")
            val v = getSystemService(Vibrator::class.java) ?: return
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
            v.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }
}
