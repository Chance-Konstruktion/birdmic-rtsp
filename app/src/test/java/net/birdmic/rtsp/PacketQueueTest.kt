package net.birdmic.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Die Warteschlange ist das Bauteil, das verhindert, dass ein langsamer Client
 * die Aufnahme anhaelt. Entsprechend wichtig ist, dass sie nie blockiert und
 * beim Ueberlauf das aelteste Paket verwirft.
 */
class PacketQueueTest {

    private fun packet(marker: Byte, size: Int = 4) = ByteArray(size) { marker }

    @Test
    fun `liefert Pakete in der Reihenfolge des Eingangs`() {
        val queue = PacketQueue(4, 4)
        queue.offer(packet(1), 4)
        queue.offer(packet(2), 4)

        val dest = ByteArray(4)
        assertEquals(4, queue.take(dest, 10))
        assertEquals(1.toByte(), dest[0])
        assertEquals(4, queue.take(dest, 10))
        assertEquals(2.toByte(), dest[0])
    }

    @Test
    fun `verwirft beim Ueberlauf das aelteste Paket`() {
        val queue = PacketQueue(2, 4)
        queue.offer(packet(1), 4)
        queue.offer(packet(2), 4)
        queue.offer(packet(3), 4) // 1 faellt heraus

        assertEquals(1L, queue.dropped)

        val dest = ByteArray(4)
        queue.take(dest, 10)
        assertEquals("Aeltestes Paket haette wegfallen muessen", 2.toByte(), dest[0])
        queue.take(dest, 10)
        assertEquals(3.toByte(), dest[0])
    }

    @Test
    fun `offer blockiert auch bei vollem Puffer nicht`() {
        val queue = PacketQueue(2, 4)
        val done = CountDownLatch(1)
        Thread {
            repeat(1000) { queue.offer(packet(1), 4) }
            done.countDown()
        }.start()
        // Ohne Verbraucher: muss trotzdem sofort durchlaufen.
        assert(done.await(2, TimeUnit.SECONDS)) { "offer hat blockiert" }
    }

    /**
     * Die entscheidende Eigenschaft: nach einer langen Blockade darf der
     * Empfaenger nicht in der Vergangenheit weiterspielen. Er muss die
     * juengsten Pakete bekommen, alles aeltere wird verworfen.
     */
    @Test
    fun `holt nach einer Blockade wieder zur Gegenwart auf`() {
        val capacity = 5
        val queue = PacketQueue(capacity, 1)

        // 200 Pakete ohne Verbraucher -- entspricht 4 s Blockade bei 20 ms.
        for (i in 1..200) queue.offer(byteArrayOf(i.toByte()), 1)

        val dest = ByteArray(1)
        val received = mutableListOf<Int>()
        // Vorzeichenlos lesen: 200 waere als Byte sonst -56.
        while (queue.take(dest, 10) > 0) received.add(dest[0].toInt() and 0xFF)

        assertEquals("Nur die Puffertiefe darf uebrig sein", capacity, received.size)
        assertEquals("Das juengste Paket muss dabei sein", 200, received.last())
        assertEquals("Der Rueckstand darf hoechstens die Puffertiefe betragen",
            196, received.first())
        assertEquals(195L, queue.dropped)
    }

    @Test
    fun `der Rueckstand waechst nie ueber die Puffertiefe`() {
        val capacity = 5
        val queue = PacketQueue(capacity, 4)
        val dest = ByteArray(4)
        // Produzent doppelt so schnell wie der Verbraucher.
        var latest = 0
        repeat(500) {
            queue.offer(packet((++latest % 128).toByte()), 4)
            queue.offer(packet((++latest % 128).toByte()), 4)
            queue.take(dest, 1)
        }
        // Egal wie lange das laeuft: nie mehr als capacity Pakete Rueckstand.
        var backlog = 0
        while (queue.take(dest, 1) > 0) backlog++
        assertTrue("Rueckstand war $backlog, erlaubt sind hoechstens $capacity",
            backlog <= capacity)
    }

    @Test
    fun `kappt zu grosse Bloecke statt abzustuerzen`() {
        val queue = PacketQueue(2, 4)
        queue.offer(ByteArray(64) { 7 }, 64)
        val dest = ByteArray(4)
        assertEquals(4, queue.take(dest, 10))
    }

    @Test
    fun `take liefert minus eins bei Zeitablauf`() {
        val queue = PacketQueue(2, 4)
        assertEquals(-1, queue.take(ByteArray(4), 20))
    }

    @Test
    fun `take kehrt nach dem Schliessen zurueck`() {
        val queue = PacketQueue(2, 4)
        val returned = CountDownLatch(1)
        Thread {
            queue.take(ByteArray(4), 60_000)
            returned.countDown()
        }.start()
        Thread.sleep(50)
        queue.close()
        assert(returned.await(2, TimeUnit.SECONDS)) { "take haengt nach close()" }
    }

    @Test
    fun `nach Pause kann die Warteschlange wieder Audio liefern`() {
        val queue = PacketQueue(2, 4)
        queue.offer(packet(1), 4)
        queue.close()
        queue.offer(packet(2), 4) // waehrend der Pause ignoriert

        queue.reopen()
        queue.offer(packet(3), 4)

        val dest = ByteArray(4)
        assertEquals(4, queue.take(dest, 10))
        assertEquals("Altes Audio aus vor der Pause darf nicht nachlaufen", 3.toByte(), dest[0])
        assertEquals(0L, queue.dropped)
        assertEquals(-1, queue.take(dest, 10))
    }

    @Test
    fun `nach dem Schliessen wird nichts mehr angenommen`() {
        val queue = PacketQueue(2, 4)
        queue.close()
        queue.offer(packet(1), 4)
        assertEquals(-1, queue.take(ByteArray(4), 10))
    }
}
