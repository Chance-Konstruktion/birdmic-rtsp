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
 * Gefragt wird GitHub, anonym lesbar. Kein Token in der App, kein
 * Anmeldekonto, keine Kennzahlen nach draussen.
 *
 * **Warum nicht mehr das eigene GitLab.** Bis 2.8 zeigten beide Adressen
 * hierhin auf `gitlab.schanz.ipv64.net`. Solange die App nur auf den
 * eigenen Geraeten lief, war das richtig: kurzer Weg, eine Quelle, nichts
 * Fremdes. Sobald die APK aber oeffentlich liegt, klopft **jedes fremde
 * Telefon** regelmaessig an einen privaten Heimserver -- die Adresse
 * stuende in jeder Installation, und die Last traefe eine Maschine, die
 * dafuer nicht da ist. Deshalb liegen Versionsdatei und Release jetzt
 * dort, wo die Oeffentlichkeit ohnehin hinsieht.
 *
 * Die `version.json` bleibt dieselbe Datei mit demselben Aufbau und
 * derselben strengen Pruefung -- es wechselt nur, wer sie ausliefert.
 */
object UpdateCheck {

    /** Die Versionsdatei im oeffentlichen Spiegel, roh und ohne Anmeldung. */
    const val VERSION_URL =
        "https://raw.githubusercontent.com/chance-konstruktion/birdmic-rtsp/main/version.json"

    /**
     * Die Release-Anhaenge, nicht das Repository.
     *
     * Der Grund von 2.7 gilt unveraendert: Jede eingecheckte APK bleibt fuer
     * immer in der Historie -- 2,6 MB je Version, bei einem Projekt mit 4000
     * Zeilen Quelltext. Job-Artefakte waeren die naheliegende Alternative,
     * laufen aber ab, und eine Adresse, die nach vier Wochen ins Leere zeigt,
     * ist als Verweis in einer App schlimmer als gar keiner. Ein Release
     * laeuft nicht ab.
     *
     * Die Marke traegt ein `v` davor (`v2.8`) -- so heissen die Marken seit
     * 2.7 auch im GitLab, der Spiegel uebernimmt sie unveraendert.
     */
    private const val DOWNLOAD_BASE =
        "https://github.com/chance-konstruktion/birdmic-rtsp/releases/download/"

    /** Genug fuer die Datei, klein genug, dass eine falsche Antwort nichts kostet. */
    private const val MAX_BYTES = 8192
    private const val TIMEOUT_MS = 8000

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val apk: String,
        val notes: String,
    ) {
        val downloadUrl: String get() = "${DOWNLOAD_BASE}v$versionName/$apk"
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

        if (code <= 0 || !isPlainVersion(name)) return null
        if (!isPlainApkName(apk)) return null

        Release(code, name, apk, root.optString("notes").trim())
    }.getOrNull()

    /** Nur Ziffern und Punkte -- der Name geht als Pfadteil in die Adresse ein. */
    private fun isPlainVersion(name: String): Boolean =
        name.isNotEmpty() && name.length <= 16 && name.all { it.isDigit() || it == '.' }

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
            null // Kein Netz, GitHub nicht erreichbar, Zeitueberschreitung -- alles kein Fehler.
        } catch (_: SecurityException) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
