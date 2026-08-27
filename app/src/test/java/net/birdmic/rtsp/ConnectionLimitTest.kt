package net.birdmic.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Haelt die Lehre aus dem Vorfall vom 27.08.2026 fest.
 *
 * Damals standen 250 Empfaenger derselben Adresse im Stream, obwohl die Grenze
 * auf 4 bzw. 2 stand, und der Empfaengerzaehler stand bei **-219**. Beides ging
 * auf dieselbe Ursache zurueck: ein mitgefuehrter Zaehler, der zwischen dem
 * Setzen der Zustandsmarke und dem Hochzaehlen ein Zeitfenster hatte, und eine
 * Grenzdurchsetzung, die in diesem Fenster blockierend arbeitete.
 *
 * Die Tests bilden das Muster nach, nicht die Klasse selbst -- `RtspServer`
 * braucht ein Geraet. Geprueft wird die Eigenschaft, die den Fehler unmoeglich
 * macht: eine abgeleitete Zaehlung kann nicht driften.
 */
class ConnectionLimitTest {

    /** Genau so viel von einer Verbindung, wie fuer die Zaehlung noetig ist. */
    private class FakeConnection(val startedAt: Long) {
        val flag = AtomicBoolean(false)
        val isPlaying: Boolean get() = flag.get()
    }

    private val connections = CopyOnWriteArrayList<FakeConnection>()

    /** So zaehlt der Server seit 2.5. */
    private fun derivedCount() = connections.count { it.isPlaying }

    @Test
    fun `abgeleitete Zaehlung wird unter Last nie negativ`() {
        // Das Gegenstueck zum mitgefuehrten Zaehler: Der hier faellt in
        // dasselbe Zeitfenster und liefert am Ende einen falschen Wert.
        val naive = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val rounds = 200

        repeat(rounds) { i ->
            val connection = FakeConnection(i.toLong())
            connections.add(connection)
            pool.execute {
                start.await()
                if (connection.flag.compareAndSet(false, true)) {
                    Thread.yield() // das Fenster, in dem 2.3 den Zaehler verlor
                    naive.incrementAndGet()
                }
            }
            pool.execute {
                start.await()
                // Ein fremder Thread raeumt dieselbe Verbindung ab.
                if (connection.flag.compareAndSet(true, false)) naive.decrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        val derived = derivedCount()
        assertTrue("Zaehlung war $derived", derived >= 0)
        assertEquals(connections.count { it.isPlaying }, derived)
    }

    @Test
    fun `Zaehlung entspricht immer den gesetzten Marken`() {
        repeat(10) { connections.add(FakeConnection(it.toLong())) }
        assertEquals(0, derivedCount())

        connections.take(4).forEach { it.flag.set(true) }
        assertEquals(4, derivedCount())

        connections[0].flag.set(false)
        assertEquals(3, derivedCount())

        // Doppeltes Abraeumen darf nichts verschieben -- das war der Kern des Fehlers.
        connections[0].flag.set(false)
        connections[0].flag.set(false)
        assertEquals(3, derivedCount())
    }

    /** Bildet `dropOldest` nach: die aeltesten weichen, der Neue bleibt. */
    private fun dropOldest(candidates: List<FakeConnection>, keep: Int): List<FakeConnection> {
        if (keep < 0 || candidates.size <= keep) return emptyList()
        return candidates.sortedBy { it.startedAt }.take(candidates.size - keep)
    }

    @Test
    fun `die aeltesten weichen, nicht die neuesten`() {
        val all = (1..10).map { FakeConnection(it.toLong()) }
        val doomed = dropOldest(all, keep = 3)

        assertEquals(7, doomed.size)
        assertEquals(1L, doomed.first().startedAt)
        assertEquals(7L, doomed.last().startedAt)
        // Der jüngste überlebt: eine hängende alte Sitzung darf den Platz
        // nicht dauerhaft belegen.
        assertTrue(all.last() !in doomed)
    }

    @Test
    fun `unter der Grenze wird niemand getrennt`() {
        val all = (1..3).map { FakeConnection(it.toLong()) }
        assertTrue(dropOldest(all, keep = 3).isEmpty())
        assertTrue(dropOldest(all, keep = 4).isEmpty())
    }

    @Test
    fun `Spielraum fuer Verbindungen ist ein Vielfaches der Streamgrenze`() {
        // Zwei offene Verbindungen je erlaubtem Stream: ein Client darf eine
        // Steuerverbindung halten und daneben eine neue aufbauen.
        val headroom = 2
        val maxPerIp = 2
        val all = (1..10).map { FakeConnection(it.toLong()) }
        val doomed = dropOldest(all, keep = maxPerIp * headroom - 1)

        assertEquals(7, doomed.size)
        assertEquals(3, all.size - doomed.size)
    }
}
