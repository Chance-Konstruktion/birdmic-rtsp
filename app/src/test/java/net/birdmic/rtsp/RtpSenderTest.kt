package net.birdmic.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueft den RTP-Paketaufbau gegen RFC 3550: Kopf, Sequenznummer,
 * Zeitstempel, SSRC, Ueberlauf der Sequenznummer.
 */
class RtpSenderTest {

    /** Sammelt statt zu senden. */
    private class Capture : RtpTransport {
        val rtp = mutableListOf<ByteArray>()
        val rtcp = mutableListOf<ByteArray>()
        override fun sendRtp(data: ByteArray, length: Int) { rtp.add(data.copyOf(length)) }
        override fun sendRtcp(data: ByteArray, length: Int) { rtcp.add(data.copyOf(length)) }
        override fun close() = Unit
    }

    private fun readShort(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun readInt(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    @Test
    fun `schreibt einen gueltigen RTP-Kopf`() {
        val capture = Capture()
        val sender = RtpSender(capture)
        sender.send(ByteArray(1920), 960)

        val packet = capture.rtp.single()
        assertEquals("Version 2, keine Erweiterungen", 0x80.toByte(), packet[0])
        assertEquals("Payload Type 96, kein Marker", 96, packet[1].toInt())
        assertEquals("Kopf plus Nutzlast", 12 + 1920, packet.size)
    }

    @Test
    fun `zaehlt Sequenznummer und Zeitstempel hoch`() {
        val capture = Capture()
        val sender = RtpSender(capture)
        repeat(3) { sender.send(ByteArray(1920), 960) }

        val seq0 = readShort(capture.rtp[0], 2)
        val seq1 = readShort(capture.rtp[1], 2)
        val seq2 = readShort(capture.rtp[2], 2)
        assertEquals((seq0 + 1) and 0xFFFF, seq1)
        assertEquals((seq0 + 2) and 0xFFFF, seq2)

        val ts0 = readInt(capture.rtp[0], 4)
        val ts1 = readInt(capture.rtp[1], 4)
        assertEquals("Zeitstempel zaehlt Frames, nicht Bytes", 960, ts1 - ts0)
    }

    @Test
    fun `beginnt bei der angekuendigten Sequenznummer und Zeit`() {
        val capture = Capture()
        val sender = RtpSender(capture)
        sender.send(ByteArray(320), 160)

        // Diese Werte meldet der Server im RTP-Info-Kopf der PLAY-Antwort.
        assertEquals(sender.startSequence, readShort(capture.rtp[0], 2))
        assertEquals(sender.startTimestamp, readInt(capture.rtp[0], 4))
    }

    @Test
    fun `liefert die Position des naechsten Pakets fuer erneutes PLAY`() {
        val sender = RtpSender(Capture())
        val initial = sender.nextPacketPosition()
        assertEquals(sender.startSequence, initial.first)
        assertEquals(sender.startTimestamp.toLong(), initial.second)

        sender.send(ByteArray(320), 160)
        val next = sender.nextPacketPosition()
        assertEquals((sender.startSequence + 1) and 0xFFFF, next.first)
        assertEquals(sender.startTimestamp.toLong() + 160L, next.second)
    }

    @Test
    fun `RTP-Zeitstempel bleibt nach 32-Bit-Ueberlauf vorzeichenlos`() {
        val sender = RtpSender(Capture())
        sender.send(ByteArray(4), Int.MAX_VALUE)

        val timestamp = sender.nextPacketPosition().second
        assertTrue("Zeitstempel muss als unsigned 32-bit dargestellt werden", timestamp > Int.MAX_VALUE.toLong())
        assertTrue("Zeitstempel darf 2^32 nicht erreichen", timestamp in 0L..0xFFFF_FFFFL)
    }

    @Test
    fun `haelt die SSRC ueber alle Pakete konstant`() {
        val capture = Capture()
        val sender = RtpSender(capture)
        repeat(5) { sender.send(ByteArray(320), 160) }
        assertTrue(capture.rtp.all { readInt(it, 8) == sender.ssrc })
    }

    @Test
    fun `laesst die Sequenznummer bei 65535 ueberlaufen`() {
        val capture = Capture()
        val sender = RtpSender(capture)
        // Genug Pakete, um den 16-Bit-Bereich sicher einmal zu durchlaufen.
        repeat(70000) { sender.send(ByteArray(4), 2) }

        val numbers = capture.rtp.map { readShort(it, 2) }
        assertTrue("Sequenznummer muss im 16-Bit-Bereich bleiben", numbers.all { it in 0..0xFFFF })
        assertTrue("Ueberlauf muss vorgekommen sein",
            numbers.zipWithNext().any { (a, b) -> a == 0xFFFF && b == 0 })
    }

    @Test
    fun `der Startzeitstempel ist nicht negativ`() {
        // Ein negativer Wert landet als Dezimalzahl im RTP-Info-Kopf und
        // bringt manche Clients aus dem Tritt.
        repeat(200) { assertTrue(RtpSender(Capture()).startTimestamp >= 0) }
    }
}
