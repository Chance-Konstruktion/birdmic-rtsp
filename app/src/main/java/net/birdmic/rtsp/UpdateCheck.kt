package net.birdmic.rtsp

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sieht nach, ob im Projekt eine neuere Fassung liegt.
 *
 * **Sie meldet nur.** Heruntergeladen und installiert wird von Hand ueber den
 * Browser. Das ist Absicht: Zum Installieren braeuchte die App
 * `REQUEST_INSTALL_PACKAGES` -- die heikelste Berechtigung, die Android kennt --
 * und der Gewinn waere ein Fingertipp. Vor allem aber ersetzt eine Installation
 * die laufende App und beendet damit den Dienst. Bei einem Mikrofon, das seit
 * Tagen sendet, entscheidet darueber niemand ausser dem Menschen davor.
 *
 * Gefragt wird ausschliesslich Chris' eigenes GitLab, anonym lesbar. Kein
 * Token in der App, kein fremder Dienst, keine Kennzahlen nach draussen.
 */
object UpdateCheck {

    /** Anonym lesbar -- die Instanz ist bewusst offen. */
    const val VERSION_URL =
        "https://gitlab.schanz.ipv64.net/chance-konstruktion/birdmic-rtsp/-/raw/main/version.json"

    private const val DOWNLOAD_BASE =
        "https://gitlab.schanz.ipv64.net/chance-konstruktion/birdmic-rtsp/-/raw/main/"

    /** Genug fuer die Datei, klein genug, dass eine falsche Antwort nichts kostet. */
    private const val MAX_BYTES = 8192
    private const val TIMEOUT_MS = 8000

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val apk: String,
        val notes: String,
    ) {
        val downloadUrl: String get() = DOWNLOAD_BASE + apk
    }

    /**
     * Liest die Versionsdatei. null, wenn sie fehlt, kaputt oder unplausibel ist.
     *
     * Der Dateiname wird streng geprueft: nur ein einfacher Name, der auf `.apk`
     * endet. Ohne diese Pruefung koennte eine veraenderte Versionsdatei den
     * Knopf auf eine beliebige Adresse zeigen lassen -- und der Mensch davor
     * sieht nur „Neue Version".
     */
    fun parse(json: String): Release? = runCatching {
        val root = JSONObject(json)
        val code = root.getInt("versionCode")
        val name = root.getString("versionName").trim()
        val apk = root.getString("apk").trim()

        if (code <= 0 || name.isEmpty()) return null
        if (!isPlainApkName(apk)) return null

        Release(code, name, apk, root.optString("notes").trim())
    }.getOrNull()

    private fun isPlainApkName(name: String): Boolean =
        name.endsWith(".apk", ignoreCase = true) &&
            name.length <= 64 &&
            name.none { it == '/' || it == '\\' || it == ':' || it.isWhitespace() } &&
            !name.contains("..")

    /** Die Fassung, wenn sie neuer ist als [currentCode] -- sonst null. */
    fun newerThan(release: Release?, currentCode: Int): Release? =
        release?.takeIf { it.versionCode > currentCode }

    /**
     * Holt die Versionsdatei. Laeuft im Aufrufer-Thread, also niemals auf dem
     * Hauptthread aufrufen.
     */
    fun fetch(url: String = VERSION_URL): Release? {
        // Ausschliesslich HTTPS. Ueber Klartext koennte jeder im Netz eine
        // eigene Antwort unterschieben.
        if (!url.startsWith("https://")) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.use { stream ->
                val buffer = ByteArray(MAX_BYTES)
                var read = 0
                while (read < MAX_BYTES) {
                    val n = stream.read(buffer, read, MAX_BYTES - read)
                    if (n <= 0) break
                    read += n
                }
                String(buffer, 0, read, Charsets.UTF_8)
            }
            parse(body)
        } catch (_: IOException) {
            null // Kein Netz, GitLab nicht erreichbar, Zeitueberschreitung -- alles kein Fehler.
        } catch (_: SecurityException) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
