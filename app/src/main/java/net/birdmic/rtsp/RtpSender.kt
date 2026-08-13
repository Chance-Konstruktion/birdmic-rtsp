package net.birdmic.rtsp

import android.util.Log
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

/** Wohin die RTP/RTCP-Pakete gehen -- UDP oder in die RTSP-Verbindung eingebettet. */
interface RtpTransport {
    fun sendRtp(data: ByteArray, length: Int)
    fun sendRtcp(data: ByteArray, length: Int)
    fun close()
}

/**
 * Klassischer Weg: zwei UDP-Ports beim Client (client_port aus dem SETUP).
 *
 * RTP und RTCP brauchen jeweils einen eigenen gebundenen Socket auf
 * aufeinanderfolgenden Ports, RTP auf dem geraden (RFC 3550, Abschnitt 11).
 * Ein einzelner Socket, dessen Nachbarport im SETUP nur behauptet wird, laesst
 * strengere Clients die RTCP-Pakete verwerfen, weil der Quellport nicht passt.
 */
class UdpTransport(
    private val address: InetAddress,
    private val rtpPort: Int,
    private val rtcpPort: Int,
) : RtpTransport {

    private val rtpSocket: DatagramSocket
    private val rtcpSocket: DatagramSocket

    init {
        var rtp: DatagramSocket? = null
        var rtcp: DatagramSocket? = null
        // Ein freies Paar suchen: gerader Port fuer RTP, der naechste fuer RTCP.
        for (attempt in 0 until PORT_ATTEMPTS) {
            val candidate = (PORT_MIN + Random.nextInt((PORT_MAX - PORT_MIN) / 2) * 2)
            try {
                val a = DatagramSocket(candidate)
                val b = try {
                    DatagramSocket(candidate + 1)
                } catch (e: Exception) {
                    a.close()
                    continue
                }
                rtp = a
                rtcp = b
                break
            } catch (e: Exception) {
                continue
            }
        }
        // Notfalls beliebige Ports -- besser ein funktionierender RTP-Strom als gar keiner.
        rtpSocket = rtp ?: DatagramSocket()
        rtcpSocket = rtcp ?: DatagramSocket()
    }

    val serverRtpPort: Int get() = rtpSocket.localPort
    val serverRtcpPort: Int get() = rtcpSocket.localPort

    override fun sendRtp(data: ByteArray, length: Int) {
        rtpSocket.send(DatagramPacket(data, length, address, rtpPort))
    }

    override fun sendRtcp(data: ByteArray, length: Int) {
        rtcpSocket.send(DatagramPacket(data, length, address, rtcpPort))
    }

    override fun close() {
        rtpSocket.close()
        rtcpSocket.close()
    }

    private companion object {
        const val PORT_MIN = 20000
        const val PORT_MAX = 40000
        const val PORT_ATTEMPTS = 20
    }
}

/**
 * RTP interleaved ueber dieselbe TCP-Verbindung (RFC 2326, Abschnitt 10.12).
 * ffmpeg mit `-rtsp_transport tcp` nutzt diesen Weg -- und er ist im WLAN die
 * robustere Variante, weil nichts an NAT oder Paketverlust scheitert.
 */
class TcpInterleavedTransport(
    private val out: OutputStream,
    private val rtpChannel: Int,
    private val rtcpChannel: Int,
) : RtpTransport {
    private val header = ByteArray(4)

    private fun send(channel: Int, data: ByteArray, length: Int) {
        synchronized(out) {
            header[0] = '$'.code.toByte()
            header[1] = channel.toByte()
            header[2] = (length shr 8).toByte()
            header[3] = length.toByte()
            out.write(header)
            out.write(data, 0, length)
            out.flush()
        }
    }

    override fun sendRtp(data: ByteArray, length: Int) = send(rtpChannel, data, length)
    override fun sendRtcp(data: ByteArray, length: Int) = send(rtcpChannel, data, length)
    override fun close() {}
}

/**
 * Paketiert L16-Audio nach RFC 3551 und verschickt es. Ein Sender pro Client.
 */
class RtpSender(
    private val transport: RtpTransport,
    private val payloadType: Int = 96,
) {
    val ssrc: Int = Random.nextInt()
    val startSequence: Int = Random.nextInt(0, 0xFFFF)
    // Positiv halten: der Wert landet als Dezimalzahl im RTP-Info-Header, und ein
    // negatives rtptime bringt manche Clients aus dem Tritt.
    val startTimestamp: Int = Random.nextInt(0, Int.MAX_VALUE / 2)

    private var sequence = startSequence
    private var timestamp = startTimestamp
    /** Zeitstempel des zuletzt gesendeten Pakets -- das gehoert in den Sender Report. */
    private var lastSentTimestamp = startTimestamp
    private var packetCount = 0L
    private var octetCount = 0L
    private var lastRtcpNanos = 0L

    @Volatile var failed: Boolean = false
        private set

    private var buffer = ByteArray(12 + 4096)

    fun send(payload: ByteArray, frames: Int) {
        if (failed) return
        val total = 12 + payload.size
        if (buffer.size < total) buffer = ByteArray(total)

        // --- RTP-Header (RFC 3550) ---
        buffer[0] = 0x80.toByte()                                  // V=2, kein Padding/Extension
        buffer[1] = payloadType.toByte()                           // M=0, PT
        buffer[2] = (sequence shr 8).toByte()
        buffer[3] = sequence.toByte()
        buffer[4] = (timestamp shr 24).toByte()
        buffer[5] = (timestamp shr 16).toByte()
        buffer[6] = (timestamp shr 8).toByte()
        buffer[7] = timestamp.toByte()
        buffer[8] = (ssrc shr 24).toByte()
        buffer[9] = (ssrc shr 16).toByte()
        buffer[10] = (ssrc shr 8).toByte()
        buffer[11] = ssrc.toByte()
        System.arraycopy(payload, 0, buffer, 12, payload.size)

        try {
            transport.sendRtp(buffer, total)
        } catch (e: Exception) {
            Log.w(TAG, "RTP-Versand fehlgeschlagen, Client wird verworfen", e)
            failed = true
            return
        }

        lastSentTimestamp = timestamp
        sequence = (sequence + 1) and 0xFFFF
        timestamp += frames
        packetCount++
        octetCount += payload.size

        val now = System.nanoTime()
        if (now - lastRtcpNanos > RTCP_INTERVAL_NANOS) {
            lastRtcpNanos = now
            sendSenderReport()
        }
    }

    /** RTCP Sender Report -- gibt dem Empfaenger die Wanduhr-Referenz zum RTP-Timestamp. */
    private fun sendSenderReport() {
        val sr = ByteArray(28)
        sr[0] = 0x80.toByte()   // V=2, RC=0
        sr[1] = 200.toByte()    // PT=SR
        sr[2] = 0; sr[3] = 6    // Laenge in 32-Bit-Woertern minus eins

        writeInt(sr, 4, ssrc)

        // NTP-Zeitstempel: Sekunden seit 1900 + Bruchteil.
        val millis = System.currentTimeMillis()
        val ntpSeconds = millis / 1000L + NTP_EPOCH_OFFSET
        val ntpFraction = ((millis % 1000L) * (1L shl 32) / 1000L)
        writeInt(sr, 8, ntpSeconds.toInt())
        writeInt(sr, 12, ntpFraction.toInt())
        writeInt(sr, 16, lastSentTimestamp)
        writeInt(sr, 20, packetCount.toInt())
        writeInt(sr, 24, octetCount.toInt())

        try {
            transport.sendRtcp(sr, sr.size)
        } catch (e: Exception) {
            Log.d(TAG, "RTCP nicht zustellbar (unkritisch)", e)
        }
    }

    fun close() = transport.close()

    private fun writeInt(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value shr 24).toByte()
        b[offset + 1] = (value shr 16).toByte()
        b[offset + 2] = (value shr 8).toByte()
        b[offset + 3] = value.toByte()
    }

    companion object {
        private const val TAG = "BirdMic/Rtp"
        private const val RTCP_INTERVAL_NANOS = 5_000_000_000L
        /** Sekunden zwischen 1900-01-01 und 1970-01-01. */
        private const val NTP_EPOCH_OFFSET = 2_208_988_800L
    }
}
