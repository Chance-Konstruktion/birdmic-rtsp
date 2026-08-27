package net.birdmic.rtsp

import org.junit.Assert.assertEquals
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
        assertTrue(release.downloadUrl.endsWith("/BirdMic-2.6.apk"))
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
        assertNull(UpdateCheck.fetch("http://gitlab.schanz.ipv64.net/x/version.json"))
        assertNull(UpdateCheck.fetch("ftp://example.invalid/version.json"))
        assertNull(UpdateCheck.fetch("version.json"))
    }

    @Test
    fun `die eingebaute Adresse ist verschluesselt`() {
        assertTrue(UpdateCheck.VERSION_URL.startsWith("https://"))
    }
}
