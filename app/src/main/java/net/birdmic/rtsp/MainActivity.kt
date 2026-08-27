package net.birdmic.rtsp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var log: EventLog

    private lateinit var statusView: TextView
    private lateinit var urlView: TextView
    private lateinit var detailView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var peakView: TextView
    private lateinit var spectrogram: SpectrogramView
    private lateinit var clientsView: TextView
    private lateinit var statsView: TextView
    private lateinit var logView: TextView
    private lateinit var toggleButton: Button

    private lateinit var sourceSpinner: Spinner
    private lateinit var sourceHint: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var sampleRateSpinner: Spinner
    private lateinit var channelSpinner: Spinner
    private lateinit var highPassSpinner: Spinner
    private lateinit var gainField: EditText
    private lateinit var portField: EditText
    private lateinit var pathField: EditText
    private lateinit var mdnsBox: CheckBox
    private lateinit var mdnsNameField: EditText
    private lateinit var webBox: CheckBox
    private lateinit var webPortField: EditText
    private lateinit var udpTimeoutField: EditText
    private lateinit var maxClientsField: EditText
    private lateinit var maxPerIpField: EditText
    private lateinit var authUserField: EditText
    private lateinit var authPasswordField: EditText
    private lateinit var autostartBox: CheckBox
    private lateinit var amoledBox: CheckBox
    private lateinit var batteryWarnField: EditText
    private lateinit var updateCheckBox: CheckBox
    private lateinit var updateBox: android.view.View
    private lateinit var updateText: TextView

    private val sourceChoices = SourceChoice.entries
    private var inputChoices: List<InputChoice> = emptyList()

    // Spektrogramm: FFT und Puffer werden einmal angelegt, nicht je Bild.
    private val fft = Fft(AudioEngine.ANALYSIS_SIZE)
    private val analysisBuffer = ShortArray(AudioEngine.ANALYSIS_SIZE)
    private val magnitudes = FloatArray(AudioEngine.ANALYSIS_SIZE / 2)

    private var cachedIp: String? = null
    private var ipCachedAt = 0L
    private var lastLogFingerprint = ""

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            startService()
        } else {
            Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        applyNightMode()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        log = EventLog.of(this)

        bindViews()
        loadSettingsIntoFields()
        setupSpinners()

        toggleButton.setOnClickListener {
            if (RtspService.State.running) stopService() else onStartRequested()
        }
        findViewById<Button>(R.id.copy_url).setOnClickListener { copyUrl() }
        findViewById<Button>(R.id.battery).setOnClickListener { openBatterySettings() }
        findViewById<Button>(R.id.clear_log).setOnClickListener {
            log.clear()
            lastLogFingerprint = ""
            renderLog()
        }
        findViewById<Button>(R.id.export_log).setOnClickListener {
            createDocument.launch(Diagnostics.fileName())
        }
    }

    /**
     * Speichern ueber den Systemdialog (Storage Access Framework). Der Vorteil
     * gegenueber einem eigenen Ordner: keine Speicherberechtigung noetig, und
     * die Datei landet dort, wo sie sich auch teilen laesst -- Downloads,
     * Google Drive, ein angestecktes USB-Laufwerk.
     */
    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val report = Diagnostics.report(this, prefs, log)
        val result = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
                ?: error("kein Schreibzugriff")
        }
        val message = if (result.isSuccess) {
            getString(R.string.export_done, report.length / 1024 + 1)
        } else {
            getString(R.string.export_failed, result.exceptionOrNull()?.message ?: "?")
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Nur setzen, wenn sich der Modus wirklich aendert -- ein erneutes Setzen
     * legt die Activity neu an und wuerde sonst in einer Schleife enden.
     */
    private fun applyNightMode() {
        val wanted = if (prefs.amoledBlack) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != wanted) {
            AppCompatDelegate.setDefaultNightMode(wanted)
        }
    }

    private fun bindViews() {
        statusView = findViewById(R.id.status)
        urlView = findViewById(R.id.url)
        detailView = findViewById(R.id.detail)
        levelBar = findViewById(R.id.level)
        peakView = findViewById(R.id.peak_info)
        spectrogram = findViewById(R.id.spectrogram)
        clientsView = findViewById(R.id.clients)
        statsView = findViewById(R.id.stats)
        logView = findViewById(R.id.log)
        toggleButton = findViewById(R.id.toggle)

        sourceSpinner = findViewById(R.id.source)
        sourceHint = findViewById(R.id.source_hint)
        deviceSpinner = findViewById(R.id.device)
        sampleRateSpinner = findViewById(R.id.samplerate)
        channelSpinner = findViewById(R.id.channels)
        highPassSpinner = findViewById(R.id.highpass)
        gainField = findViewById(R.id.gain)
        portField = findViewById(R.id.port)
        pathField = findViewById(R.id.path)
        mdnsBox = findViewById(R.id.mdns)
        mdnsNameField = findViewById(R.id.mdns_name)
        webBox = findViewById(R.id.web)
        webPortField = findViewById(R.id.web_port)
        udpTimeoutField = findViewById(R.id.udp_timeout)
        maxClientsField = findViewById(R.id.max_clients)
        maxPerIpField = findViewById(R.id.max_per_ip)
        authUserField = findViewById(R.id.auth_user)
        authPasswordField = findViewById(R.id.auth_password)
        autostartBox = findViewById(R.id.autostart)
        amoledBox = findViewById(R.id.amoled)
        batteryWarnField = findViewById(R.id.battery_warn)
        updateCheckBox = findViewById(R.id.update_check)
        updateBox = findViewById(R.id.update_box)
        updateText = findViewById(R.id.update_text)
    }

    private fun loadSettingsIntoFields() {
        gainField.setText(prefs.gain.toString())
        portField.setText(prefs.port.toString())
        pathField.setText(prefs.path)
        mdnsBox.isChecked = prefs.mdnsEnabled
        mdnsNameField.setText(prefs.mdnsName)
        webBox.isChecked = prefs.webEnabled
        webPortField.setText(prefs.webPort.toString())
        udpTimeoutField.setText(prefs.udpTimeoutSeconds.toString())
        maxClientsField.setText(prefs.maxClients.toString())
        maxPerIpField.setText(prefs.maxClientsPerIp.toString())
        authUserField.setText(prefs.authUser)
        authPasswordField.setText(prefs.authPassword)
        autostartBox.isChecked = prefs.autostart
        batteryWarnField.setText(prefs.batteryWarnCelsius.toString())
        updateCheckBox.isChecked = prefs.updateCheck
        updateCheckBox.setOnCheckedChangeListener { _, checked ->
            prefs.updateCheck = checked
            if (checked) checkForUpdate(force = true) else updateBox.visibility = android.view.View.GONE
        }

        autostartBox.setOnCheckedChangeListener { _, checked -> prefs.autostart = checked }

        // Das Thema wirkt erst nach dem Neuaufbau der Activity -- den loest
        // setDefaultNightMode selbst aus. Der Stream laeuft im Dienst weiter.
        amoledBox.isChecked = prefs.amoledBlack
        amoledBox.setOnCheckedChangeListener { _, checked ->
            if (checked != prefs.amoledBlack) {
                prefs.amoledBlack = checked
                applyNightMode()
            }
        }
    }

    private fun setupSpinners() {
        sourceSpinner.adapter = adapterOf(sourceChoices.map { it.label })
        val currentSource = SourceChoice.fromValue(prefs.audioSource)
        sourceSpinner.setSelection(sourceChoices.indexOf(currentSource).coerceAtLeast(0))
        sourceHint.text = currentSource.hint
        sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                sourceHint.text = sourceChoices[pos].hint
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        sampleRateSpinner.adapter = adapterOf(
            Prefs.SAMPLE_RATES.map {
                if (it == 48000) getString(R.string.samplerate_recommended)
                else getString(R.string.samplerate_value, it)
            },
        )
        sampleRateSpinner.setSelection(
            Prefs.SAMPLE_RATES.indexOf(prefs.sampleRate).coerceAtLeast(0),
        )

        channelSpinner.adapter = adapterOf(listOf(getString(R.string.mono), getString(R.string.stereo)))
        channelSpinner.setSelection(if (prefs.channels == 2) 1 else 0)

        highPassSpinner.adapter = adapterOf(
            Prefs.HIGH_PASS_OPTIONS.map {
                if (it == 0) getString(R.string.highpass_off) else getString(R.string.highpass_value, it)
            },
        )
        highPassSpinner.setSelection(
            Prefs.HIGH_PASS_OPTIONS.indexOf(prefs.highPassHz).coerceAtLeast(0),
        )
    }

    private fun adapterOf(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    override fun onResume() {
        super.onResume()
        checkForUpdate(force = false)
        // Geraeteliste neu aufbauen: ein USB-Mikrofon kann zwischenzeitlich
        // an- oder abgesteckt worden sein.
        setupDeviceSpinner()
        spectrogram.sampleRate = prefs.sampleRate
        lastLogFingerprint = ""
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun setupDeviceSpinner() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        inputChoices = AudioDevices.list(audioManager)
        deviceSpinner.adapter = adapterOf(inputChoices.map { it.label })
        val index = inputChoices.indexOfFirst { it.key == prefs.inputDeviceKey }
        deviceSpinner.setSelection(if (index >= 0) index else 0)
    }

    private fun onStartRequested() {
        if (!saveSettings()) return

        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startService() else requestPermissions.launch(missing.toTypedArray())
    }

    /** Uebernimmt die Felder in die Einstellungen; false, wenn etwas unplausibel ist. */
    private fun saveSettings(): Boolean {
        val port = portField.text.toString().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            portField.error = getString(R.string.bad_port)
            return false
        }
        val gain = gainField.text.toString().replace(',', '.').toFloatOrNull()
        if (gain == null || gain !in 0.1f..20f) {
            gainField.error = getString(R.string.bad_gain)
            return false
        }
        val webPort = webPortField.text.toString().ifBlank { "0" }.toIntOrNull()
        if (webPort == null || webPort !in 1024..65535) {
            webPortField.error = getString(R.string.bad_port)
            return false
        }
        if (webBox.isChecked && webPort == port) {
            webPortField.error = getString(R.string.bad_web_port_same)
            return false
        }
        val timeout = udpTimeoutField.text.toString().ifBlank { "0" }.toIntOrNull()
        if (timeout == null || (timeout != 0 && timeout !in 30..3600)) {
            udpTimeoutField.error = getString(R.string.bad_timeout)
            return false
        }
        val warnTemp = batteryWarnField.text.toString().ifBlank { "0" }.toIntOrNull()
        if (warnTemp == null || (warnTemp != 0 && warnTemp !in 30..60)) {
            batteryWarnField.error = getString(R.string.bad_temp)
            return false
        }
        val maxClients = maxClientsField.text.toString().ifBlank { "0" }.toIntOrNull()
        if (maxClients == null || maxClients !in 0..32) {
            maxClientsField.error = getString(R.string.bad_limit)
            return false
        }
        val maxPerIp = maxPerIpField.text.toString().ifBlank { "0" }.toIntOrNull()
        if (maxPerIp == null || maxPerIp !in 0..32) {
            maxPerIpField.error = getString(R.string.bad_limit)
            return false
        }
        val user = authUserField.text.toString().trim()
        val password = authPasswordField.text.toString()
        if (user.isNotBlank() != password.isNotBlank()) {
            authPasswordField.error = getString(R.string.bad_auth)
            return false
        }

        prefs.port = port
        prefs.gain = gain
        prefs.udpTimeoutSeconds = timeout
        prefs.maxClients = maxClients
        prefs.maxClientsPerIp = maxPerIp
        prefs.batteryWarnCelsius = warnTemp
        prefs.authUser = user
        prefs.authPassword = password
        prefs.path = pathField.text.toString()
        prefs.audioSource = sourceChoices[sourceSpinner.selectedItemPosition].value
        prefs.inputDeviceKey = inputChoices.getOrNull(deviceSpinner.selectedItemPosition)?.key ?: ""
        prefs.sampleRate = Prefs.SAMPLE_RATES[sampleRateSpinner.selectedItemPosition]
        prefs.channels = if (channelSpinner.selectedItemPosition == 1) 2 else 1
        prefs.highPassHz = Prefs.HIGH_PASS_OPTIONS[highPassSpinner.selectedItemPosition]
        prefs.mdnsEnabled = mdnsBox.isChecked
        prefs.mdnsName = mdnsNameField.text.toString()
        prefs.webEnabled = webBox.isChecked
        prefs.webPort = webPort

        spectrogram.sampleRate = prefs.sampleRate
        spectrogram.clear()
        return true
    }

    private fun startService() {
        ContextCompat.startForegroundService(
            this, Intent(this, RtspService::class.java).setAction(RtspService.ACTION_START),
        )
    }

    private fun stopService() {
        startService(Intent(this, RtspService::class.java).setAction(RtspService.ACTION_STOP))
    }

    private fun copyUrl() {
        val url = RtspService.State.url.ifBlank { previewUrl() }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("RTSP", url))
        Toast.makeText(this, getString(R.string.copied, url), Toast.LENGTH_SHORT).show()
    }

    private fun isBatteryOptimized(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return !power.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * Der Akku-Optimierer ist der haeufigste Grund, warum der Stream nachts
     * abreisst. Erst den Systemdialog versuchen, sonst die App-Einstellungen.
     */
    private fun openBatterySettings() {
        if (!isBatteryOptimized()) {
            Toast.makeText(this, R.string.battery_already, Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("BatteryLife")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts("package", packageName, null))
        if (runCatching { startActivity(direct) }.isSuccess) return

        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
        runCatching { startActivity(fallback) }
            .onFailure { Toast.makeText(this, R.string.no_settings, Toast.LENGTH_SHORT).show() }
    }

    /**
     * Fragt im Hintergrund nach einer neueren Fassung und blendet oben einen
     * Hinweis ein.
     *
     * Laeuft nur im Vordergrund und hoechstens alle [UPDATE_INTERVAL_MS]. Kein
     * Hintergrunddienst, kein WorkManager: Eine App, die im Dauerbetrieb ein
     * Mikrofon sendet, soll nicht zusaetzlich nachts aufwachen, um nach sich
     * selbst zu sehen.
     */
    private fun checkForUpdate(force: Boolean) {
        if (!prefs.updateCheck) {
            updateBox.visibility = android.view.View.GONE
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - prefs.lastUpdateCheck < UPDATE_INTERVAL_MS) return
        prefs.lastUpdateCheck = now

        Thread({
            val release = UpdateCheck.newerThan(UpdateCheck.fetch(), BuildConfig.VERSION_CODE)
            handler.post {
                // Die Activity kann waehrend der Abfrage verschwunden sein.
                if (isFinishing || isDestroyed) return@post
                showUpdate(release)
            }
        }, "birdmic-update").apply { isDaemon = true }.start()
    }

    private fun showUpdate(release: UpdateCheck.Release?) {
        if (release == null) {
            updateBox.visibility = android.view.View.GONE
            return
        }
        updateBox.visibility = android.view.View.VISIBLE
        updateText.text = buildString {
            append(getString(R.string.update_available, release.versionName, BuildConfig.VERSION_NAME))
            if (release.notes.isNotBlank()) append("\n").append(release.notes)
        }
        findViewById<Button>(R.id.update_download).setOnClickListener {
            val browse = Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl))
            runCatching { startActivity(browse) }
                .onFailure { Toast.makeText(this, R.string.no_settings, Toast.LENGTH_SHORT).show() }
        }
    }

    /**
     * Die Interface-Liste zu durchlaufen ist zu teuer fuer den Anzeigetakt,
     * also nur alle paar Sekunden neu bestimmen.
     */
    private fun cachedIp(): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - ipCachedAt > 5000) {
            cachedIp = Prefs.localIpv4()
            ipCachedAt = now
        }
        return cachedIp
    }

    private fun previewUrl(): String {
        val ip = cachedIp() ?: "<handy-ip>"
        val port = portField.text.toString().toIntOrNull() ?: prefs.port
        val path = pathField.text.toString().ifBlank { Prefs.DEFAULT_PATH }
        return "rtsp://$ip:$port/$path"
    }

    private fun render() {
        val state = RtspService.State
        val running = state.running

        toggleButton.setText(if (running) R.string.stop else R.string.start)
        setInputsEnabled(!running)

        urlView.text = if (running) state.url else previewUrl()

        val error = state.error
        statusView.text = when {
            error != null && !running -> getString(R.string.status_error, error)
            !running -> getString(R.string.status_stopped)
            state.clients == 0 -> getString(R.string.status_waiting)
            else -> resources.getQuantityString(R.plurals.status_streaming, state.clients, state.clients)
        }

        detailView.text = if (running) {
            buildString {
                append(getString(R.string.detail, state.source))
                state.mdnsName?.let { append(getString(R.string.detail_mdns, it)) }
                state.webUrl?.let { append(getString(R.string.detail_web, it)) }
                // Das System meldet ab Android 9, welches Mikrofon wirklich aktiv ist.
                state.activeMicrophone?.let { append(getString(R.string.detail_active_mic, it)) }
                if (state.restarts > 0) append(getString(R.string.detail_restarts, state.restarts))
            }
        } else {
            getString(R.string.detail_idle, prefs.sampleRate)
        }

        renderClients(running, state)
        renderMeters(state)
        renderSpectrogram(running)
        renderLog()
    }

    private fun renderClients(running: Boolean, state: RtspService.State) {
        val names = state.clientNames
        clientsView.text = when {
            !running -> getString(R.string.clients_idle)
            names.isEmpty() -> getString(R.string.clients_none)
            else -> resources.getQuantityString(R.plurals.clients_count, names.size, names.size) +
                getString(R.string.clients_list, names.joinToString("\n"))
        }

        statsView.text = if (running) {
            getString(
                R.string.stats,
                formatDuration(state.uptimeSeconds),
                formatBytes(state.bytesSent),
                state.batterySummary,
            )
        } else {
            ""
        }
    }

    private fun renderMeters(state: RtspService.State) {
        // Wurzel spreizt die leisen Bereiche -- Vogelrufe liegen oft bei wenigen Prozent.
        levelBar.progress = (sqrt(state.level.toDouble()) * 100).roundToInt().coerceIn(0, 100)

        val clippedText = if (state.clipped > 0) {
            getString(R.string.peak_clipped, state.clipped)
        } else {
            getString(R.string.peak_clean)
        }
        peakView.text = getString(
            R.string.peak_info,
            (state.peakHold * 100).roundToInt().coerceIn(0, 100),
            clippedText,
        )
    }

    private fun renderSpectrogram(running: Boolean) {
        if (!running) return
        val engine = RtspService.State.engine ?: return
        if (engine.analysisSnapshot(analysisBuffer)) {
            fft.magnitudes(analysisBuffer, magnitudes)
            spectrogram.push(magnitudes)
        }
    }

    private fun renderLog() {
        val entries = log.recent(LOG_LINES)
        // Nur neu setzen, wenn sich etwas geaendert hat -- sonst springt die
        // Textauswahl bei jeder Aktualisierung weg. Die Groesse allein genuegt
        // dafuer nicht: eine wiederholte Meldung erhoeht nur ihren Zaehler.
        val fingerprint = entries.firstOrNull()
            ?.let { "${entries.size}:${it.lastTimestamp}:${it.repeats}" } ?: "0"
        if (fingerprint == lastLogFingerprint) return
        lastLogFingerprint = fingerprint
        logView.text = if (entries.isEmpty()) {
            getString(R.string.log_empty)
        } else {
            entries.joinToString("\n") { it.format() }
        }
    }

    private fun setInputsEnabled(enabled: Boolean) {
        val views = listOf<android.view.View>(
            sourceSpinner, deviceSpinner, sampleRateSpinner, channelSpinner, highPassSpinner,
            gainField, portField, pathField, mdnsBox, mdnsNameField, webBox, webPortField,
            udpTimeoutField,
            maxClientsField, maxPerIpField, authUserField, authPasswordField, batteryWarnField,
        )
        for (view in views) view.isEnabled = enabled
    }

    private fun formatDuration(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            days > 0 -> "%dd %02d:%02d".format(days, hours, minutes)
            else -> "%02d:%02d:%02d".format(hours, minutes, secs)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.0f kB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    private companion object {
        const val REFRESH_MS = 120L
        const val LOG_LINES = 15

        /** Sechs Stunden -- oefter braucht niemand zu wissen, dass es nichts Neues gibt. */
        const val UPDATE_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
