package net.birdmic.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Versionsdatei kommt aus dem Netz. Alles, was von dort kommt, ist erst
 * einmal unglaubwuerdig -- deshalb liegt der Schwerpunkt hier auf dem, was die
 * Pruefung ablehnen muss, nicht auf dem Normalfall.
 */
class UpdateCheckTest {

    private val valid = """
        {"versionCode": 10, "versionName": "2.6", "apk": "BirdMic-2.6.apk", "notes": "Neues"}
    """.trimIndent()

    @Test
    fun `liest eine gueltige Versionsdatei`() {
        val release = UpdateCheck.parse(valid)
        assertNotNull(release)
        assertEquals(10, release!!.versionCode)
        assertEquals("2.6", release.versionName)
        assertEquals("Neues", release.notes)
        assertTrue(release.downloadUrl.startsWith("https://"))
        // Adresse eines Release-Anhangs: .../releases/download/v<version>/<datei>
        assertEquals(
            "https://github.com/chance-konstruktion/birdmic-rtsp/releases/download/v2.6/BirdMic-2.6.apk",
            release.downloadUrl,
        )
    }

    @Test
    fun `notes duerfen fehlen`() {
        val release = UpdateCheck.parse("""{"versionCode":10,"versionName":"2.6","apk":"a.apk"}""")
        assertNotNull(release)
        assertEquals("", release!!.notes)
    }

    @Test
    fun `Unsinn ergibt keine Fassung`() {
        assertNull(UpdateCheck.parse(""))
        assertNull(UpdateCheck.parse("kein json"))
        assertNull(UpdateCheck.parse("<html>404</html>"))
        assertNull(UpdateCheck.parse("{}"))
    }

    @Test
    fun `unplausible Versionsnummern werden abgelehnt`() {
        assertNull(UpdateCheck.parse("""{"versionCode":0,"versionName":"x","apk":"a.apk"}"""))
        assertNull(UpdateCheck.parse("""{"versionCode":-5,"versionName":"x","apk":"a.apk"}"""))
        assertNull(UpdateCheck.parse("""{"versionCode":10,"versionName":"","apk":"a.apk"}"""))
    }

    @Test
    fun `der Versionsname darf nicht woandershin zeigen`() {
        // Er geht als Pfadteil in die Downloadadresse ein -- also dieselbe
        // Strenge wie beim Dateinamen.
        // "2.6 " fehlt hier bewusst: Leerraum wird abgeschnitten, das ist
        // beabsichtigt und aendert die Adresse nicht.
        val bad = listOf("../../andere", "2.6/../../x", "zwei-komma-sechs", "2.6;rm", "2.6?x=1")
        for (name in bad) {
            val json = """{"versionCode":10,"versionName":"$name","apk":"a.apk"}"""
            assertNull("$name haette abgelehnt werden muessen", UpdateCheck.parse(json))
        }
        assertNotNull(UpdateCheck.parse("""{"versionCode":10,"versionName":"2.6","apk":"a.apk"}"""))
    }

    @Test
    fun `der Dateiname darf nicht woandershin zeigen`() {
        // Ohne diese Pruefung koennte eine veraenderte Versionsdatei den Knopf
        // auf eine beliebige Adresse lenken -- sichtbar waere nur "Neue Version".
        val bad = listOf(
            "../../../etc/passwd.apk",
            "http://boese.example/schad.apk",
            "unter/ordner/a.apk",
            "a.apk?x=1 b.apk",
            "keine-endung",
            "schad.exe",
        )
        for (name in bad) {
            val json = """{"versionCode":10,"versionName":"2.6","apk":"$name"}"""
            assertNull("$name haette abgelehnt werden muessen", UpdateCheck.parse(json))
        }
    }

    @Test
    fun `nur echte Nachfolger werden gemeldet`() {
        val release = UpdateCheck.parse(valid)
        assertNull("gleiche Version ist kein Update", UpdateCheck.newerThan(release, 10))
        assertNull("aeltere Version ist kein Update", UpdateCheck.newerThan(release, 11))
        assertNotNull(UpdateCheck.newerThan(release, 9))
        assertNull(UpdateCheck.newerThan(null, 1))
    }

    @Test
    fun `Klartext-HTTP wird nicht abgefragt`() {
        // Ueber unverschluesseltes HTTP koennte jeder im Netz eine eigene
        // Antwort unterschieben. Kein Netzzugriff noetig: die Pruefung greift davor.
        assertNull(UpdateCheck.fetch("http://raw.githubusercontent.com/x/version.json"))
        assertNull(UpdateCheck.fetch("ftp://example.invalid/version.json"))
        assertNull(UpdateCheck.fetch("version.json"))
    }

    @Test
    fun `die eingebaute Adresse ist verschluesselt`() {
        assertTrue(UpdateCheck.VERSION_URL.startsWith("https://"))
    }

    @Test
    fun `keine Adresse zeigt auf den privaten Heimserver`() {
        // Die Tuer, die diese Aenderung festhaelt. Solange die APK oeffentlich
        // liegt, darf KEINE eingebaute Adresse auf die private Instanz zeigen:
        // Sonst klopft jedes fremde Telefon regelmaessig an einen Heimserver,
        // und dessen Adresse stuende in jeder Installation.
        //
        // Geprueft wird die Adresse, die die App wirklich abfragt, UND die
        // Adresse, die hinter dem Knopf liegt -- beide kamen aus derselben
        // Quelle und muessen beide umgezogen sein.
        val release = UpdateCheck.parse(valid)
        assertNotNull(release)
        for (adresse in listOf(UpdateCheck.VERSION_URL, release!!.downloadUrl)) {
            assertFalse(
                "Adresse zeigt auf die private Instanz: $adresse",
                adresse.contains("schanz.ipv64.net"),
            )
            assertTrue(
                "Adresse gehoert nicht zum oeffentlichen Spiegel: $adresse",
                adresse.startsWith("https://github.com/") ||
                    adresse.startsWith("https://raw.githubusercontent.com/"),
            )
        }
    }
}
