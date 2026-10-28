package com.talor.dancealarm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify login (OAuth with PKCE — no secret needed) and the Web API call that lists your
 * playlists. Network functions block, so call them off the main thread.
 */
object SpotifyApi {
    const val REDIRECT_URI = "dancealarm://spotify-callback"
    private const val SCOPES =
        "playlist-read-private playlist-read-collaborative app-remote-control " +
            "user-modify-playback-state user-read-playback-state"

    data class Playlist(val name: String, val uri: String, val total: Int, val owner: String)

    fun isLoggedIn(c: Context) = Prefs.spotifyRefresh(c).isNotBlank()

    private fun b64url(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** Browser intent that opens Spotify's login page, or null if no Client ID is set. */
    fun loginIntent(c: Context): Intent? {
        val clientId = Prefs.spotifyClientId(c)
        if (clientId.isBlank()) return null
        val raw = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = b64url(raw)
        Prefs.setSpotifyVerifier(c, verifier)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPES)
            .build()
        return Intent(Intent.ACTION_VIEW, uri)
    }

    /** Finishes login from the redirect. Returns an error message, or null on success. */
    fun handleRedirect(c: Context, uri: Uri): String? {
        uri.getQueryParameter("error")?.let { return "Spotify said: $it" }
        val code = uri.getQueryParameter("code") ?: return "No login code from Spotify"
        return try {
            val json = post(
                "https://accounts.spotify.com/api/token", mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "client_id" to Prefs.spotifyClientId(c),
                    "code_verifier" to Prefs.spotifyVerifier(c),
                )
            )
            Prefs.setSpotifyTokens(
                c, json.getString("access_token"),
                json.optString("refresh_token"), json.optLong("expires_in", 3600)
            )
            null
        } catch (e: Exception) {
            "Login failed: ${e.message}"
        }
    }

    private fun accessToken(c: Context): String {
        val current = Prefs.spotifyAccess(c)
        if (current.isNotBlank() && System.currentTimeMillis() < Prefs.spotifyExpiry(c)) return current
        val refresh = Prefs.spotifyRefresh(c)
        if (refresh.isBlank()) throw IllegalStateException("Not logged in to Spotify")
        val json = post(
            "https://accounts.spotify.com/api/token", mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to Prefs.spotifyClientId(c),
            )
        )
        val access = json.getString("access_token")
        Prefs.setSpotifyTokens(c, access, json.optString("refresh_token"), json.optLong("expires_in", 3600))
        return access
    }

    /** All playlists in your library (owned and followed). */
    fun playlists(c: Context): List<Playlist> {
        val token = accessToken(c)
        val result = ArrayList<Playlist>()
        var next: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        var pages = 0
        while (next != null && pages < 10) {
            val json = get(next, token)
            val items = json.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val p = items.optJSONObject(i) ?: continue
                // Field was renamed from "tracks" to "items" in 2026; read either.
                val countObj = p.optJSONObject("items") ?: p.optJSONObject("tracks")
                val total = countObj?.optInt("total", -1) ?: -1
                result.add(
                    Playlist(
                        name = p.optString("name", "Untitled"),
                        uri = p.optString("uri"),
                        total = total,
                        owner = p.optJSONObject("owner")?.optString("display_name", "") ?: "",
                    )
                )
            }
            next = json.optString("next").takeIf { it.isNotBlank() && it != "null" }
            pages++
        }
        return result.filter { it.uri.startsWith("spotify:playlist:") }
    }

    // ---------- tiny HTTP helpers ----------

    private fun post(url: String, form: Map<String, String>): JSONObject {
        val body = form.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray()) }
        return readJson(conn)
    }

    private fun get(url: String, token: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        return readJson(conn)
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
            val msg = try {
                val j = JSONObject(text)
                j.optJSONObject("error")?.optString("message")
                    ?: j.optString("error_description").ifBlank { j.optString("error") }
            } catch (_: Exception) { text.take(200) }
            throw RuntimeException("HTTP $code: $msg")
        }
        return JSONObject(text)
    }
}
