package net.birdmic.rtsp

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Entkoppelt den Aufnahme-Thread vom Netzwerk.
 *
 * Ohne diese Warteschlange wuerde [AudioEngine] die Pakete direkt in die
 * Client-Sockets schreiben. Ein einziger langsamer TCP-Empfaenger koennte dort
 * blockieren, damit den Aufnahme-Thread anhalten, den AudioRecord-Puffer
 * ueberlaufen lassen -- und so Luecken fuer *alle* Empfaenger erzeugen.
 *
 * Deshalb: fester Ringpuffer je Client. Kommt ein Empfaenger nicht mit, wird
 * sein aeltestes Paket verworfen. Ein schlechter Client kostet dann nur seine
 * eigene Audioqualitaet, nicht die der Aufnahme.
 *
 * Alle Pakete haben dieselbe Groesse, deshalb reichen vorbelegte Puffer ohne
 * jede Allokation im Dauerbetrieb.
 */
class PacketQueue(private val capacity: Int, packetBytes: Int) {

    private val slots = Array(capacity) { ByteArray(packetBytes) }
    private val lengths = IntArray(capacity)

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    private var head = 0
    private var count = 0
    private var closed = false

    /** Wie viele Pakete verworfen wurden, weil der Empfaenger zu langsam war. */
    @Volatile var dropped: Long = 0L
        private set

    private val slotSize = packetBytes

    /** Legt ein Paket ab. Blockiert nie -- notfalls faellt das aelteste heraus. */
    fun offer(data: ByteArray, length: Int) {
        // Defensiv: ein Block, der nicht in einen Slot passt, wuerde sonst
        // beim Kopieren eine Ausnahme auf dem Aufnahme-Thread ausloesen.
        val usable = minOf(length, slotSize)
        if (usable <= 0) return
        lock.lock()
        try {
            if (closed) return
            if (count == capacity) {
                head = (head + 1) % capacity
                count--
                dropped++
            }
            val index = (head + count) % capacity
            System.arraycopy(data, 0, slots[index], 0, usable)
            lengths[index] = usable
            count++
            notEmpty.signal()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Holt das aelteste Paket nach [dest] und liefert dessen Laenge,
     * oder -1 bei Zeitablauf beziehungsweise geschlossener Warteschlange.
     */
    fun take(dest: ByteArray, timeoutMs: Long): Int {
        lock.lock()
        try {
            if (count == 0 && !closed) {
                notEmpty.await(timeoutMs, TimeUnit.MILLISECONDS)
            }
            if (closed || count == 0) return -1
            val length = lengths[head]
            System.arraycopy(slots[head], 0, dest, 0, length)
            head = (head + 1) % capacity
            count--
            return length
        } finally {
            lock.unlock()
        }
    }

    fun close() {
        lock.lock()
        try {
            closed = true
            count = 0
            notEmpty.signalAll()
        } finally {
            lock.unlock()
        }
    }
}
