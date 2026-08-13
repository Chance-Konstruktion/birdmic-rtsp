package net.birdmic.rtsp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Meldet den Stream per mDNS im Netz an.
 *
 * Damit loest sich das Problem der festen IP-Adresse: der Pi erreicht das
 * Telefon unter einem Namen statt unter einer Nummer, und ein Adresswechsel
 * durch den Router macht die Konfiguration nicht mehr kaputt. Auf Raspberry Pi
 * OS laeuft Avahi standardmaessig, die Aufloesung von `.local` funktioniert dort
 * also ohne Zutun.
 */
class MdnsAdvertiser(context: Context) {

    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager?
    private var listener: NsdManager.RegistrationListener? = null

    /** Der Name, unter dem der Dienst tatsaechlich registriert wurde. */
    @Volatile var registeredName: String? = null
        private set

    fun register(serviceName: String, port: Int, onEvent: ((EventLog.Kind, String) -> Unit)? = null) {
        val nsd = manager ?: return
        if (listener != null) return

        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            // Standardtyp fuer RTSP-Dienste; Avahi und Bonjour kennen ihn.
            serviceType = SERVICE_TYPE
            this.port = port
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // Android haengt bei Namenskollisionen eine Nummer an.
                registeredName = info.serviceName
                Log.i(TAG, "mDNS registriert als ${info.serviceName}")
                onEvent?.invoke(EventLog.Kind.START, "mDNS aktiv: ${info.serviceName}$SERVICE_TYPE")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS-Registrierung fehlgeschlagen: $errorCode")
                registeredName = null
                onEvent?.invoke(EventLog.Kind.WARNING, "mDNS fehlgeschlagen (Code $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registeredName = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS-Abmeldung fehlgeschlagen: $errorCode")
            }
        }

        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onSuccess { listener = registration }
            .onFailure { Log.w(TAG, "mDNS nicht verfuegbar", it) }
    }

    fun unregister() {
        val nsd = manager ?: return
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
        registeredName = null
    }

    companion object {
        private const val TAG = "BirdMic/Mdns"
        private const val SERVICE_TYPE = "_rtsp._tcp."
    }
}
