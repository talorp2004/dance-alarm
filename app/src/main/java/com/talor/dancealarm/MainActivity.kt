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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
        refreshPermissions()
    }

    // ---------- time ----------

    private fun showTimePicker() {
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
        timeText.text = String.format("%02d:%02d", Prefs.hour(this), Prefs.minute(this))
    }

    private fun refreshNext() {
        if (!Prefs.isEnabled(this)) {
            nextText.text = "Alarm is off"
            return
        }
        val mins = (AlarmScheduler.nextTrigger(this) - System.currentTimeMillis()) / 60_000
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
