package net.birdmic.rtsp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Haelt Mikrofon und RTSP-Server am Leben, solange die App laeuft. Als
 * Foreground-Service mit Typ "microphone", weil Android sonst nach kurzer Zeit
 * die Aufnahme im Hintergrund stumm schaltet.
 */
class RtspService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var log: EventLog
    private var audio: AudioEngine? = null
    private var server: RtspServer? = null
    private var mdns: MdnsAdvertiser? = null
    private var battery: BatteryWatch? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private val notificationRefresh = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        log = EventLog(this)
        State.log = log
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startStreaming()
        }
        return START_STICKY
    }

    private fun startStreaming() {
        if (State.running) {
            updateNotification()
            return
        }

        // Der Foreground-Status muss stehen, bevor AudioRecord startet -- sonst
        // liefert Android auf 9+ stillschweigend Nullen.
        if (!startForegroundCompat()) {
            // Ab Android 14 darf ein Mikrofon-Vordergrunddienst nicht aus
            // BOOT_COMPLETED heraus starten. Statt still zu scheitern: melden.
            notifyAutostartBlocked()
            State.error = getString(R.string.autostart_blocked_short)
            log.add(EventLog.Kind.ERROR, getString(R.string.autostart_blocked_short))
            stopSelf()
            return
        }

        val emit: (EventLog.Kind, String) -> Unit = { kind, message -> log.add(kind, message) }

        val engine = AudioEngine(
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager,
            sampleRate = prefs.sampleRate,
            gain = prefs.gain,
            sourcePreference = prefs.audioSource,
            inputDeviceKey = prefs.inputDeviceKey,
            requestedChannels = prefs.channels,
            highPassHz = prefs.highPassHz,
            onEvent = emit,
        )
        val credentials = DigestAuth(prefs.authUser, prefs.authPassword).takeIf { it.enabled }
        val rtsp = RtspServer(
            port = prefs.port,
            audio = engine,
            auth = credentials,
            udpTimeoutSeconds = prefs.udpTimeoutSeconds,
            maxClients = prefs.maxClients,
            maxClientsPerIp = prefs.maxClientsPerIp,
            onEvent = emit,
        )

        if (!rtsp.start()) {
            State.error = rtsp.lastError ?: "RTSP-Server konnte nicht starten"
            log.add(EventLog.Kind.ERROR, State.error ?: "Start fehlgeschlagen")
            stopSelf()
            return
        }
        if (!engine.start()) {
            State.error = engine.lastError ?: "Mikrofon nicht verfuegbar"
            log.add(EventLog.Kind.ERROR, State.error ?: "Mikrofon nicht verfügbar")
            rtsp.stop()
            stopSelf()
            return
        }

        acquireLocks()

        battery = BatteryWatch(this, prefs.batteryWarnCelsius, emit).also { it.start() }

        if (prefs.mdnsEnabled) {
            mdns = MdnsAdvertiser(this).also { it.register(prefs.mdnsName, prefs.port, emit) }
        }

        audio = engine
        server = rtsp
        State.engine = engine
        State.rtsp = rtsp
        State.battery = battery
        State.mdns = mdns
        State.running = true
        State.error = null
        State.url = buildUrl()
        State.startedAtElapsed = SystemClock.elapsedRealtime()
        prefs.wasRunning = true

        log.add(EventLog.Kind.START, "Stream gestartet auf ${State.url}")
        handler.post(notificationRefresh)
        Log.i(TAG, "Stream laeuft: ${State.url} (${engine.sourceDescription})")
    }

    private fun stopStreaming() {
        if (State.running) log.add(EventLog.Kind.STOP, "Stream gestoppt")
        handler.removeCallbacks(notificationRefresh)
        mdns?.unregister()
        battery?.stop()
        server?.stop()
        audio?.stop()
        mdns = null
        battery = null
        server = null
        audio = null
        releaseLocks()
        State.running = false
        State.engine = null
        State.rtsp = null
        State.battery = null
        State.mdns = null
        prefs.wasRunning = false
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun buildUrl(): String {
        val ip = Prefs.localIpv4() ?: "<handy-ip>"
        return "rtsp://$ip:${prefs.port}/${prefs.path}"
    }

    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BirdMic::stream").apply {
            setReferenceCounted(false)
            acquire()
        }
        // Ohne High-Performance-WLAN-Lock parkt der Chip im Standby und der
        // Stream bekommt Aussetzer im Sekundenbereich.
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BirdMic::wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "Mikrofon-Stream", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Zeigt an, dass das Mikrofon per RTSP gesendet wird"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RtspService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val clients = server?.playingClients ?: 0
        val warning = if (battery?.tooHot == true) {
            "\n⚠ Akku ${"%.1f".format(battery?.temperatureCelsius ?: 0f)} °C"
        } else {
            ""
        }
        val text = buildString {
            append(if (clients > 0) "$clients Empfänger" else "Wartet auf Verbindung")
            append(" · ").append(State.url)
            append(warning)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BirdMic sendet")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stoppen", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /** false, wenn Android den Start des Vordergrunddienstes verweigert hat. */
    private fun startForegroundCompat(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground verweigert", e)
            false
        }
    }

    /**
     * Eigene Benachrichtigung, wenn der Autostart nicht durfte. Ein Tipp darauf
     * oeffnet die App -- von dort aus ist der Start als Nutzeraktion erlaubt.
     */
    private fun notifyAutostartBlocked() {
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = getString(R.string.autostart_blocked_text)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.autostart_blocked_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_AUTOSTART, notification)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Zustand fuer die Oberflaeche. Ein Service, ein Stream -- ein Singleton reicht.
     * Die Activity liest Pegel und Clientzahl direkt an Engine und Server ab, damit
     * die Anzeige fluessig bleibt und nicht am Notification-Takt haengt.
     */
    object State {
        @Volatile var running = false
        @Volatile var url: String = ""
        @Volatile var error: String? = null
        @Volatile var startedAtElapsed: Long = 0L
        @Volatile var log: EventLog? = null
        @Volatile internal var engine: AudioEngine? = null
        @Volatile internal var rtsp: RtspServer? = null
        @Volatile internal var battery: BatteryWatch? = null
        @Volatile internal var mdns: MdnsAdvertiser? = null

        val level: Float get() = engine?.level ?: 0f
        val peak: Float get() = engine?.peak ?: 0f
        val peakHold: Float get() = engine?.peakHold ?: 0f
        val clipped: Long get() = engine?.clippedSamples ?: 0L
        val source: String get() = engine?.sourceDescription ?: "-"
        val clients: Int get() = rtsp?.playingClients ?: 0
        val clientNames: List<String> get() = rtsp?.clientList() ?: emptyList()
        val restarts: Int get() = engine?.restarts ?: 0
        val activeMicrophone: String? get() = engine?.activeMicrophone
        val bytesSent: Long get() = rtsp?.totalBytesSent ?: 0L
        val batterySummary: String get() = battery?.summary() ?: "-"
        val batteryTooHot: Boolean get() = battery?.tooHot == true
        val mdnsName: String? get() = mdns?.registeredName

        /** Laufzeit in Sekunden seit dem Start des Streams. */
        val uptimeSeconds: Long
            get() = if (running && startedAtElapsed > 0) {
                (SystemClock.elapsedRealtime() - startedAtElapsed) / 1000
            } else {
                0
            }
    }

    companion object {
        private const val TAG = "BirdMic/Service"
        private const val CHANNEL_ID = "birdmic_stream"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_ID_AUTOSTART = 2
        const val ACTION_START = "net.birdmic.rtsp.START"
        const val ACTION_STOP = "net.birdmic.rtsp.STOP"
    }
}
