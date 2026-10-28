package com.talor.dancealarm

import android.Manifest
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var timeText: TextView
    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var nextText: TextView
    private lateinit var songsContainer: LinearLayout
    private lateinit var songsEmpty: TextView
    private lateinit var permsContainer: LinearLayout
    private lateinit var timeHint: TextView
    private lateinit var spotifySection: LinearLayout
    private lateinit var spotifyStatus: TextView
    private lateinit var clientIdEdit: EditText

    private val pickSongs =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) addSongs(uris)
        }
    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) addFolder(uri)
        }
    private val requestPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        timeText = findViewById(R.id.timeText)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        nextText = findViewById(R.id.nextText)
        songsContainer = findViewById(R.id.songsContainer)
        songsEmpty = findViewById(R.id.songsEmpty)
        permsContainer = findViewById(R.id.permsContainer)
        timeHint = findViewById(R.id.timeHint)
        spotifySection = findViewById(R.id.spotifySection)
        spotifyStatus = findViewById(R.id.spotifyStatus)
        clientIdEdit = findViewById(R.id.spotifyClientId)

        // --- alarm source ---
        val alarmSourceGroup = findViewById<RadioGroup>(R.id.alarmSourceGroup)
        alarmSourceGroup.check(if (Prefs.followPhone(this)) R.id.rbPhoneAlarm else R.id.rbOwnTime)
        alarmSourceGroup.setOnCheckedChangeListener { _, id ->
            Prefs.setFollowPhone(this, id == R.id.rbPhoneAlarm)
            AlarmScheduler.schedule(this)
            refreshTime()
            refreshNext()
        }

        // --- music source ---
        val musicGroup = findViewById<RadioGroup>(R.id.musicSourceGroup)
        musicGroup.check(if (Prefs.useSpotify(this)) R.id.rbSpotify else R.id.rbLocal)
        musicGroup.setOnCheckedChangeListener { _, id ->
            Prefs.setUseSpotify(this, id == R.id.rbSpotify)
            refreshSpotify()
        }
        clientIdEdit.setText(Prefs.spotifyClientId(this))
        findViewById<Button>(R.id.btnSpotifyLogin).setOnClickListener { spotifyLogin() }
        findViewById<Button>(R.id.btnChoosePlaylist).setOnClickListener { choosePlaylist() }
        handleSpotifyRedirect(intent)

        timeText.setOnClickListener { showTimePicker() }

        enabledSwitch.isChecked = Prefs.isEnabled(this)
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setEnabled(this, checked)
            AlarmScheduler.schedule(this)
            refreshNext()
        }

        findViewById<Button>(R.id.btnAddSongs).setOnClickListener {
            pickSongs.launch(arrayOf("audio/*"))
        }
        findViewById<Button>(R.id.btnAddFolder).setOnClickListener { pickFolder.launch(null) }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            Prefs.setSongs(this, emptyList())
            refreshSongs()
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testAlarm() }

        val durationGroup = findViewById<RadioGroup>(R.id.durationGroup)
        durationGroup.check(
            when (Prefs.danceSeconds(this)) {
                15 -> R.id.rb15
                60 -> R.id.rb60
                else -> R.id.rb30
            }
        )
        durationGroup.setOnCheckedChangeListener { _, id ->
            Prefs.setDanceSeconds(
                this, when (id) {
                    R.id.rb15 -> 15
                    R.id.rb60 -> 60
                    else -> 30
                }
            )
        }

        val difficultyGroup = findViewById<RadioGroup>(R.id.difficultyGroup)
        difficultyGroup.check(
            when (Prefs.difficulty(this)) {
                0 -> R.id.rbEasy
                2 -> R.id.rbHard
                else -> R.id.rbNormal
            }
        )
        difficultyGroup.setOnCheckedChangeListener { _, id ->
            Prefs.setDifficulty(
                this, when (id) {
                    R.id.rbEasy -> 0
                    R.id.rbHard -> 2
                    else -> 1
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        AlarmScheduler.schedule(this)
        refreshTime()
        refreshNext()
        refreshSongs()
        refreshSpotify()
        refreshPermissions()
    }

    override fun onPause() {
        saveClientId()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSpotifyRedirect(intent)
    }

    // ---------- Spotify ----------

    private fun saveClientId() {
        val id = clientIdEdit.text.toString().trim()
        if (id.isNotBlank() && id != Prefs.spotifyClientId(this)) {
            Prefs.setSpotifyClientId(this, id)
            Prefs.clearSpotifyTokens(this)
        }
    }

    private fun spotifyLogin() {
        saveClientId()
        val i = SpotifyApi.loginIntent(this)
        if (i == null) {
            Toast.makeText(this, "Enter your Spotify Client ID first", Toast.LENGTH_LONG).show()
            return
        }
        try { startActivity(i) } catch (e: Exception) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSpotifyRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "dancealarm" || data.host != "spotify-callback") return
        setIntent(Intent(this, MainActivity::class.java)) // don't handle it twice
        spotifyStatus.text = "Finishing Spotify login…"
        Thread {
            val error = SpotifyApi.handleRedirect(this, data)
            runOnUiThread {
                refreshSpotify()
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Logged in to Spotify ✅", Toast.LENGTH_SHORT).show()
                    choosePlaylist()
                }
            }
        }.start()
    }

    private fun choosePlaylist() {
        if (!SpotifyApi.isLoggedIn(this)) {
            spotifyLogin()
            return
        }
        spotifyStatus.text = "Loading your playlists…"
        Thread {
            val result = try {
                Result.success(SpotifyApi.playlists(this))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                refreshSpotify()
                result.onFailure {
                    Toast.makeText(this, "Couldn't load playlists: ${it.message}", Toast.LENGTH_LONG).show()
                }
                result.onSuccess { lists ->
                    if (lists.isEmpty()) {
                        Toast.makeText(this, "No playlists found in your library", Toast.LENGTH_LONG).show()
                        return@onSuccess
                    }
                    val labels = lists.map { pl ->
                        if (pl.total >= 0) "${pl.name}  (${pl.total} songs)" else pl.name
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Pick the wake-up playlist")
                        .setItems(labels) { _, which -> onPlaylistPicked(lists[which]) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }.start()
    }

    private fun onPlaylistPicked(pl: SpotifyApi.Playlist) {
        Prefs.setSpotifyPlaylist(this, pl.uri, pl.name, pl.total)
        Prefs.setUseSpotify(this, true)
        findViewById<RadioGroup>(R.id.musicSourceGroup).check(R.id.rbSpotify)
        refreshSpotify()
        // One-time "allow Dance Alarm to control Spotify" approval, while you're awake to tap it.
        SpotifyPlayer.authorize(this) { error ->
            runOnUiThread {
                if (error == null) {
                    Toast.makeText(this, "Spotify is ready 🎉 Try \"Test the alarm now\"", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Spotify app connection failed: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshSpotify() {
        val on = Prefs.useSpotify(this)
        spotifySection.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        val playlist = Prefs.spotifyPlaylistName(this)
        spotifyStatus.text = when {
            !SpotifyAppRemote_isInstalled() -> "⚠️ Install the Spotify app first"
            !SpotifyApi.isLoggedIn(this) -> "Not logged in yet"
            playlist.isBlank() -> "Logged in ✅ — now choose a playlist"
            else -> "Playlist: $playlist"
        }
    }

    @Suppress("FunctionName")
    private fun SpotifyAppRemote_isInstalled() =
        com.spotify.android.appremote.api.SpotifyAppRemote.isSpotifyInstalled(this)

    // ---------- time ----------

    private fun showTimePicker() {
        if (Prefs.followPhone(this)) {
            Toast.makeText(this, "Set this time in your Clock app — I follow it automatically", Toast.LENGTH_LONG).show()
            return
        }
        TimePickerDialog(
            this,
            { _, h, m ->
                Prefs.setTime(this, h, m)
                Prefs.setEnabled(this, true)
                enabledSwitch.isChecked = true
                AlarmScheduler.schedule(this)
                refreshTime()
                refreshNext()
            },
            Prefs.hour(this), Prefs.minute(this), DateFormat.is24HourFormat(this)
        ).show()
    }

    private fun refreshTime() {
        if (Prefs.followPhone(this)) {
            val phone = AlarmScheduler.phoneMorningAlarm(this)
            if (phone == null) {
                timeText.text = "--:--"
                timeHint.text = "No morning alarm (3:00–11:59) is next in your Clock app"
            } else {
                timeText.text = DateFormat.getTimeFormat(this).format(java.util.Date(phone))
                timeHint.text = "taken from your phone's alarm — change it in the Clock app"
            }
        } else {
            timeText.text = String.format("%02d:%02d", Prefs.hour(this), Prefs.minute(this))
            timeHint.text = "tap the time to change it"
        }
    }

    private fun refreshNext() {
        if (!Prefs.isEnabled(this)) {
            nextText.text = "Alarm is off"
            return
        }
        val at = Prefs.scheduledAt(this)
        if (at <= System.currentTimeMillis()) {
            nextText.text = if (Prefs.followPhone(this)) "Waiting for a morning alarm in your Clock app" else ""
            return
        }
        val mins = (at - System.currentTimeMillis()) / 60_000
        nextText.text = "Rings in ${mins / 60} h ${mins % 60} min"
    }

    // ---------- playlist ----------

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.let { name -> return name }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "Song"
    }

    private fun addSongs(uris: List<Uri>) {
        val songs = uris.map { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            Song(uri.toString(), displayName(uri))
        }
        val added = Prefs.addSongs(this, songs)
        Toast.makeText(this, "Added $added song(s)", Toast.LENGTH_SHORT).show()
        refreshSongs()
    }

    private fun addFolder(tree: Uri) {
        try {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        val root = DocumentFile.fromTreeUri(this, tree) ?: return
        val found = ArrayList<Song>()
        fun scan(dir: DocumentFile, depth: Int) {
            for (f in dir.listFiles()) {
                if (f.isDirectory && depth < 3) scan(f, depth + 1)
                else if (f.isFile && (f.type?.startsWith("audio/") == true)) {
                    found.add(Song(f.uri.toString(), f.name ?: "Song"))
                }
            }
        }
        scan(root, 0)
        val added = Prefs.addSongs(this, found.sortedBy { it.name.lowercase() })
        Toast.makeText(this, "Added $added song(s) from the folder", Toast.LENGTH_SHORT).show()
        refreshSongs()
    }

    private fun refreshSongs() {
        val songs = Prefs.songs(this)
        songsContainer.removeAllViews()
        songsEmpty.visibility = if (songs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        songs.forEachIndexed { i, song ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(this).apply {
                text = "${i + 1}. ${song.name}"
                setTextColor(0xFFFFFFFF.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val remove = TextView(this).apply {
                text = "✕"
                textSize = 18f
                setPadding(24, 12, 12, 12)
                setTextColor(0xFFB9AED0.toInt())
                setOnClickListener {
                    Prefs.setSongs(this@MainActivity, Prefs.songs(this@MainActivity).filter { it.uri != song.uri })
                    refreshSongs()
                }
            }
            row.addView(name)
            row.addView(remove)
            songsContainer.addView(row)
        }
    }

    // ---------- permissions ----------

    private fun settingsIntent(action: String) =
        Intent(action, Uri.parse("package:$packageName"))

    private fun refreshPermissions() {
        permsContainer.removeAllViews()
        var allGood = true

        fun item(label: String, ok: Boolean, fix: () -> Unit) {
            if (ok) return
            allGood = false
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = "⚠️  $label"
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "Allow"
                setOnClickListener {
                    try { fix() } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Open Settings > Apps > Dance Alarm", Toast.LENGTH_LONG).show()
                    }
                }
            })
            permsContainer.addView(row)
        }

        item(
            "Camera — needed to see you dance",
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) { requestPerm.launch(Manifest.permission.CAMERA) }

        if (Build.VERSION.SDK_INT >= 33) {
            item(
                "Notifications — so the alarm can show up",
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) { requestPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }

        if (Build.VERSION.SDK_INT >= 31) {
            item("Alarms & reminders — to ring on time", AlarmScheduler.canScheduleExact(this)) {
                startActivity(settingsIntent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }

        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            item("Full-screen alarm — to cover the lock screen", nm.canUseFullScreenIntent()) {
                startActivity(settingsIntent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT))
            }
        }

        item(
            "Display over other apps — pulls you back to the dance screen if you try to leave",
            Settings.canDrawOverlays(this)
        ) { startActivity(settingsIntent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }

        if (allGood) {
            permsContainer.addView(TextView(this).apply {
                text = "✅  All set"
                setTextColor(0xFFFFFFFF.toInt())
            })
        }
    }

    // ---------- test ----------

    private fun testAlarm() {
        if (Prefs.songs(this).isEmpty()) {
            Toast.makeText(this, "No songs yet — playing the default alarm sound", Toast.LENGTH_SHORT).show()
        }
        AlarmService.start(this)
    }
}
