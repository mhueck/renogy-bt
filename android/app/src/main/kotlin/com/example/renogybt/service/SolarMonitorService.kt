package com.example.renogybt.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.renogybt.MainActivity
import com.example.renogybt.ble.BleEspClient
import com.example.renogybt.ble.DCChargerClient
import com.example.renogybt.ble.DometicClient
import com.example.renogybt.ble.EcoWorthyClient
import com.example.renogybt.ble.SolarBleGattServer
import com.example.renogybt.model.AppConfig
import com.example.renogybt.model.ConfigRepository
import com.example.renogybt.model.ConnectionPhase
import com.example.renogybt.model.DeviceHealth
import com.example.renogybt.model.SolarMonitorState
import com.example.renogybt.network.InfluxDb2Client
import com.example.renogybt.network.WeatherClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class SolarMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.example.renogybt.ACTION_START"
        const val ACTION_STOP = "com.example.renogybt.ACTION_STOP"
        const val ACTION_RESTART = "com.example.renogybt.ACTION_RESTART"
        const val ACTION_RETRY_NOW = "com.example.renogybt.ACTION_RETRY_NOW"

        private const val CHANNEL_ID = "solar_monitor_channel"
        private const val NOTIFICATION_ID = 1001

        private val _state = MutableStateFlow(SolarMonitorState())
        val state: StateFlow<SolarMonitorState> = _state.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, SolarMonitorService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SolarMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun retryNow(context: Context) {
            val intent = Intent(context, SolarMonitorService::class.java).apply {
                action = ACTION_RETRY_NOW
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retrySignal = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private var monitorJob: Job? = null

    private var gattServer: SolarBleGattServer? = null
    private var chargerClient: DCChargerClient? = null
    private var batteryClient: EcoWorthyClient? = null
    private var gpsClient: BleEspClient? = null
    private var fridgeClient: DometicClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoringAndService()
                return START_NOT_STICKY
            }
            ACTION_RETRY_NOW -> {
                if (_state.value.serviceRunning) {
                    AppLogger.info("Manual retry requested — waking polling loops immediately")
                    retrySignal.tryEmit(Unit)
                } else {
                    startForegroundWithNotification("Solar BLE Monitor running")
                    startMonitoring()
                }
            }
            ACTION_RESTART, ACTION_START, null -> {
                startForegroundWithNotification("Solar BLE Monitor running")
                startMonitoring()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoringInternal()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Holds a short bounded CPU wake lock only during active BLE poll bursts so Android Doze
     * cannot suspend the CPU mid-GATT write/notification, releasing immediately before sleep.
     */
    private suspend fun <T> withPollWakeLock(timeoutMs: Long = 90_000L, block: suspend () -> T): T {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RenogyBT:PollWakeLock")?.apply {
            setReferenceCounted(false)
        }
        try {
            wakeLock?.acquire(timeoutMs)
        } catch (_: Exception) {
        }
        return try {
            block()
        } finally {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Sleeps up to [durationMs], but wakes immediately if [retrySignal] is emitted.
     */
    private suspend fun interruptibleDelay(durationMs: Long) {
        if (durationMs <= 0L) return
        withTimeoutOrNull(durationMs) {
            retrySignal.first()
        }
    }

    private fun startForegroundWithNotification(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshStatusNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val s = _state.value
        val warnings = mutableListOf<String>()
        if (s.chargerHealth.phase == ConnectionPhase.ERROR) {
            warnings.add("Charger unreachable (${s.chargerHealth.consecutiveFailures}x)")
        }
        if (s.batteryHealth.phase == ConnectionPhase.ERROR) {
            warnings.add("Battery unreachable (${s.batteryHealth.consecutiveFailures}x)")
        }
        if (s.fridgeHealth.phase == ConnectionPhase.ERROR) {
            warnings.add("Fridge unreachable (${s.fridgeHealth.consecutiveFailures}x)")
        }

        val batSummary = if (s.batteryData.isNotEmpty()) {
            val pct = (s.batteryData["percentage"] as? Number)?.toDouble() ?: 0.0
            val volt = (s.batteryData["voltage"] as? Number)?.toDouble() ?: 0.0
            val pwr = (s.batteryData["power"] as? Number)?.toDouble() ?: 0.0
            "Bat: ${pct.toInt()}% (${String.format(Locale.US, "%.1f", volt)}V, ${pwr.toInt()}W)"
        } else {
            "Solar BLE Monitor running"
        }

        val summary = if (warnings.isNotEmpty()) {
            "⚠️ ${warnings.joinToString(", ")} • $batSummary"
        } else {
            batSummary
        }
        nm.notify(NOTIFICATION_ID, buildNotification(summary))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val retryIntent = Intent(this, SolarMonitorService::class.java).apply {
            action = ACTION_RETRY_NOW
        }
        val pendingRetry = PendingIntent.getService(
            this,
            2,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SolarMonitorService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("RenogyBT Solar Monitor")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_popup_sync, "Retry", pendingRetry)
            .addAction(android.R.drawable.ic_media_pause, "Stop", pendingStop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Solar BLE Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background BLE polling, GATT Server for ESP32 display, and InfluxDB2 logging"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun stopMonitoringAndService() {
        stopMonitoringInternal()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            runMonitorPipeline()
        }
    }

    private fun stopMonitoringInternal() {
        monitorJob?.cancel()
        monitorJob = null
        try {
            gattServer?.stop()
        } catch (_: Exception) {
        }
        gattServer = null

        val oldCharger = chargerClient
        val oldBattery = batteryClient
        val oldGps = gpsClient
        val oldFridge = fridgeClient
        chargerClient = null
        batteryClient = null
        gpsClient = null
        fridgeClient = null

        serviceScope.launch {
            try { oldCharger?.disconnect(refreshCache = false) } catch (_: Exception) {}
            try { oldBattery?.disconnect(refreshCache = false) } catch (_: Exception) {}
            try { oldGps?.disconnect(refreshCache = false) } catch (_: Exception) {}
            try { oldFridge?.disconnect(refreshCache = false) } catch (_: Exception) {}
        }

        _state.update {
            it.copy(
                serviceRunning = false,
                bleServer = it.bleServer.copy(advertising = false, connectedDevicesCount = 0),
                chargerHealth = if (it.chargerHealth.phase == ConnectionPhase.DISABLED) it.chargerHealth else it.chargerHealth.copy(phase = ConnectionPhase.IDLE),
                batteryHealth = if (it.batteryHealth.phase == ConnectionPhase.DISABLED) it.batteryHealth else it.batteryHealth.copy(phase = ConnectionPhase.IDLE),
                fridgeHealth = if (it.fridgeHealth.phase == ConnectionPhase.DISABLED) it.fridgeHealth else it.fridgeHealth.copy(phase = ConnectionPhase.IDLE),
                gpsHealth = if (it.gpsHealth.phase == ConnectionPhase.DISABLED) it.gpsHealth else it.gpsHealth.copy(phase = ConnectionPhase.IDLE)
            )
        }
    }

    private suspend fun CoroutineScope.runMonitorPipeline() {
        val repo = ConfigRepository(applicationContext)
        val config = repo.loadConfig()

        repo.loadSavedGpsCoords()?.let { (lat, lon) ->
            _state.update { it.copy(currentLat = lat, currentLon = lon, gpsSource = "Last Known Good") }
            AppLogger.info("Loaded last known good GPS coordinates: lat=$lat, lon=$lon")
        }

        val chargerEnabled = config.charger.enabled && config.charger.macAddr.isNotBlank()
        val batteryEnabled = config.battery.enabled && config.battery.macAddr.isNotBlank()
        val gpsEnabled = config.gps.enabled && config.gps.macAddr.isNotBlank()
        val fridgeEnabled = config.fridge.enabled && config.fridge.macAddr.isNotBlank()

        _state.update {
            it.copy(
                serviceRunning = true,
                chargerHealth = DeviceHealth(phase = if (chargerEnabled) ConnectionPhase.IDLE else ConnectionPhase.DISABLED),
                batteryHealth = DeviceHealth(phase = if (batteryEnabled) ConnectionPhase.IDLE else ConnectionPhase.DISABLED),
                fridgeHealth = DeviceHealth(phase = if (fridgeEnabled) ConnectionPhase.IDLE else ConnectionPhase.DISABLED),
                gpsHealth = DeviceHealth(phase = if (gpsEnabled) ConnectionPhase.STANDBY else ConnectionPhase.DISABLED)
            )
        }
        AppLogger.info("Starting SolarMonitorService...")

        // 1. Start BLE GATT Server if enabled
        if (config.bleServer.enabled) {
            try {
                val server = SolarBleGattServer(applicationContext, config.bleServer.name)
                gattServer = server
                launch {
                    server.state.collect { serverState ->
                        _state.update { it.copy(bleServer = serverState) }
                    }
                }
                server.start()
            } catch (e: Exception) {
                AppLogger.error("Failed to start BLE GATT Server", e)
            }
        } else {
            AppLogger.info("BLE GATT Server is disabled in settings.")
        }

        // 2. Initialize BLE device clients
        val charger = if (chargerEnabled) {
            DCChargerClient(applicationContext, config.charger).also { chargerClient = it }
        } else null

        val battery = if (batteryEnabled) {
            EcoWorthyClient(applicationContext, config.battery).also { batteryClient = it }
        } else null

        val gps = if (gpsEnabled) {
            BleEspClient(applicationContext, config.gps).also { gpsClient = it }
        } else null

        val fridge = if (fridgeEnabled) {
            DometicClient(applicationContext, config.fridge).also { fridgeClient = it }
        } else null

        try {
            if (config.enablePolling) {
                val jobs = mutableListOf<Job>()
                jobs.add(launch { weatherPollLoop() })
                jobs.add(launch { gpsLoop(gps, config, repo) })
                if (charger != null || battery != null) {
                    jobs.add(launch { chargerBatteryLoop(charger, battery, config) })
                }
                if (fridge != null) {
                    jobs.add(launch { fridgeLoop(fridge, config) })
                }
                jobs.forEach { it.join() }
            } else {
                // Single-shot execution (non-fatal per device)
                withPollWakeLock {
                    pollChargerAndBatteryOnce(charger, battery, config)
                    pollGpsOnce(gps, config, repo)
                    if (fridge != null) {
                        pollFridgeOnce(fridge, config, initialWaitMs = 5_000L)
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.error("Monitor pipeline error", e)
        }
    }

    private suspend fun chargerBatteryLoop(
        charger: DCChargerClient?,
        battery: EcoWorthyClient?,
        config: AppConfig
    ) {
        while (currentCoroutineContext().isActive) {
            val startMs = System.currentTimeMillis()
            val allOk = try {
                withPollWakeLock {
                    pollChargerAndBatteryOnce(charger, battery, config)
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                AppLogger.error("Unexpected error in charger/battery poll loop", e)
                false
            }
            val elapsedMs = System.currentTimeMillis() - startMs
            // Match Python's 60s normal cadence; retry in 25s if one device failed
            val targetIntervalMs = if (allOk) 60_000L else 25_000L
            val sleepMs = max(500L, targetIntervalMs - elapsedMs)
            interruptibleDelay(sleepMs)
        }
    }

    private suspend fun pollChargerAndBatteryOnce(
        charger: DCChargerClient?,
        battery: EcoWorthyClient?,
        config: AppConfig
    ): Boolean {
        var chargerOk = true
        var batteryOk = true
        val server = gattServer

        if (charger != null) {
            val attemptAt = System.currentTimeMillis()
            _state.update {
                it.copy(
                    chargerHealth = it.chargerHealth.copy(
                        phase = ConnectionPhase.CONNECTING,
                        lastAttemptMillis = attemptAt
                    )
                )
            }
            try {
                AppLogger.info("Reading charger (${config.charger.alias})...")
                withTimeout(45_000L) { charger.connect() }
                val cData = withTimeout(20_000L) { charger.read() }
                val now = System.currentTimeMillis()
                _state.update {
                    it.copy(
                        chargerData = cData,
                        chargerUpdatedAt = now,
                        chargerHealth = DeviceHealth(
                            phase = ConnectionPhase.CONNECTED,
                            lastSuccessMillis = now,
                            lastAttemptMillis = attemptAt,
                            consecutiveFailures = 0,
                            lastError = null
                        )
                    )
                }
                if (server != null) {
                    val pvV = (cData["pv_voltage"] as? Number)?.toDouble() ?: 0.0
                    val pvI = (cData["pv_current"] as? Number)?.toDouble() ?: 0.0
                    val altV = (cData["alternator_voltage"] as? Number)?.toDouble() ?: 0.0
                    val altI = (cData["alternator_current"] as? Number)?.toDouble() ?: 0.0
                    server.updateCharger(pvV, pvI, altV, altI)
                }
                processData(cData, config)
            } catch (e: Exception) {
                // Distinguish true scope cancellation from inner withTimeout() expiration!
                currentCoroutineContext().ensureActive()
                chargerOk = false
                val errMsg = e.message ?: e.javaClass.simpleName
                val failures = _state.value.chargerHealth.consecutiveFailures + 1
                AppLogger.warn("Charger (${config.charger.alias}) poll failed: $errMsg")
                try { charger.disconnect(refreshCache = failures % 3 == 0) } catch (_: Exception) {}
                _state.update {
                    val prev = it.chargerHealth
                    it.copy(
                        chargerHealth = prev.copy(
                            phase = ConnectionPhase.ERROR,
                            lastAttemptMillis = attemptAt,
                            consecutiveFailures = failures,
                            lastError = errMsg
                        )
                    )
                }
            }
        }

        if (battery != null) {
            val attemptAt = System.currentTimeMillis()
            _state.update {
                it.copy(
                    batteryHealth = it.batteryHealth.copy(
                        phase = ConnectionPhase.CONNECTING,
                        lastAttemptMillis = attemptAt
                    )
                )
            }
            try {
                AppLogger.info("Reading battery (${config.battery.alias})...")
                withTimeout(45_000L) { battery.connect() }
                val bData = withTimeout(20_000L) { battery.read() }
                val pct = (bData["percentage"] as? Number)?.toDouble() ?: 0.0
                val pwr = (bData["power"] as? Number)?.toDouble() ?: 0.0
                val volt = (bData["voltage"] as? Number)?.toDouble() ?: 0.0
                val temp = (bData["temperature"] as? Number)?.toDouble() ?: 0.0
                val pctChange = server?.updateBattery(pct, pwr, volt, temp) ?: 0.0
                val now = System.currentTimeMillis()

                // Update state to CONNECTED immediately before any slow InfluxDB HTTP write
                _state.update {
                    it.copy(
                        batteryData = bData,
                        batteryPctChange1h = pctChange,
                        batteryUpdatedAt = now,
                        batteryHealth = DeviceHealth(
                            phase = ConnectionPhase.CONNECTED,
                            lastSuccessMillis = now,
                            lastAttemptMillis = attemptAt,
                            consecutiveFailures = 0,
                            lastError = null
                        )
                    )
                }
                processData(bData, config)
            } catch (e: Exception) {
                // Distinguish true scope cancellation from inner withTimeout() expiration!
                currentCoroutineContext().ensureActive()
                batteryOk = false
                val errMsg = e.message ?: e.javaClass.simpleName
                val failures = _state.value.batteryHealth.consecutiveFailures + 1
                AppLogger.warn("Battery (${config.battery.alias}) poll failed: $errMsg")
                try { battery.disconnect(refreshCache = failures % 3 == 0) } catch (_: Exception) {}
                _state.update {
                    val prev = it.batteryHealth
                    it.copy(
                        batteryHealth = prev.copy(
                            phase = ConnectionPhase.ERROR,
                            lastAttemptMillis = attemptAt,
                            consecutiveFailures = failures,
                            lastError = errMsg
                        )
                    )
                }
            }
        }

        refreshStatusNotification()
        return chargerOk && batteryOk
    }

    private suspend fun gpsLoop(
        gps: BleEspClient?,
        config: AppConfig,
        repo: ConfigRepository
    ) {
        if (gps == null) return
        while (currentCoroutineContext().isActive) {
            val startMs = System.currentTimeMillis()
            val ok = try {
                withPollWakeLock(timeoutMs = 30_000L) {
                    pollGpsOnce(gps, config, repo)
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                AppLogger.debug("GPS loop check skipped: ${e.message}")
                false
            }
            if (!ok) {
                interruptibleDelay(45_000L)
            } else {
                val elapsedMs = System.currentTimeMillis() - startMs
                val sleepMs = max(100L, 12_000L - elapsedMs)
                interruptibleDelay(sleepMs)
            }
        }
    }

    private suspend fun pollGpsOnce(
        gps: BleEspClient?,
        config: AppConfig,
        repo: ConfigRepository
    ): Boolean {
        if (gps == null) return true

        val attemptAt = System.currentTimeMillis()
        // Only show CONNECTING when already active; when parked (STANDBY), check quietly
        _state.update {
            if (it.gpsHealth.phase == ConnectionPhase.STANDBY) {
                it.copy(gpsHealth = it.gpsHealth.copy(lastAttemptMillis = attemptAt))
            } else {
                it.copy(
                    gpsHealth = it.gpsHealth.copy(
                        phase = ConnectionPhase.CONNECTING,
                        lastAttemptMillis = attemptAt
                    )
                )
            }
        }

        return try {
            withTimeout(18_000L) { gps.connect() }
            val data = withTimeout(12_000L) { gps.read() }
            val lat = (data["lat"] as? Number)?.toDouble()
            val lon = (data["lon"] as? Number)?.toDouble()
            val now = System.currentTimeMillis()

            if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                repo.saveGpsCoords(lat, lon)
                _state.update {
                    it.copy(
                        currentLat = lat,
                        currentLon = lon,
                        gpsSource = "BLE_ESP (${config.gps.alias})",
                        gpsEnvData = data,
                        gpsUpdatedAt = now,
                        gpsHealth = DeviceHealth(
                            phase = ConnectionPhase.CONNECTED,
                            lastSuccessMillis = now,
                            lastAttemptMillis = attemptAt,
                            consecutiveFailures = 0,
                            lastError = null
                        )
                    )
                }
            } else {
                // Connected over BLE, but ESP32 GPS has no satellite lock yet: keep last known good coordinates
                _state.update {
                    it.copy(
                        gpsSource = if (it.currentLat != null && it.currentLon != null) {
                            "Last Known Good (BLE_ESP no GPS lock)"
                        } else {
                            "BLE_ESP (no GPS lock)"
                        },
                        gpsEnvData = data,
                        gpsUpdatedAt = now,
                        gpsHealth = DeviceHealth(
                            phase = ConnectionPhase.CONNECTED,
                            lastSuccessMillis = now,
                            lastAttemptMillis = attemptAt,
                            consecutiveFailures = 0,
                            lastError = null
                        )
                    )
                }
            }
            processData(data, config)
            true
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val bleError = e.message ?: e.javaClass.simpleName
            // Normal when van is parked / ignition off — log at DEBUG and stay in calm STANDBY
            AppLogger.debug("GPS BLE not active ($bleError) — retaining last known good location")
            try { gps.disconnect(refreshCache = false) } catch (_: Exception) {}
            _state.update {
                val prev = it.gpsHealth
                it.copy(
                    gpsSource = if (it.currentLat != null && it.currentLon != null) {
                        "Last Known Good"
                    } else {
                        "Standby"
                    },
                    gpsHealth = DeviceHealth(
                        phase = ConnectionPhase.STANDBY,
                        lastSuccessMillis = prev.lastSuccessMillis,
                        lastAttemptMillis = attemptAt,
                        consecutiveFailures = 0,
                        lastError = null
                    )
                )
            }
            false
        }
    }

    private suspend fun fridgeLoop(
        fridge: DometicClient,
        config: AppConfig
    ) {
        var backoffMs = 15_000L
        while (currentCoroutineContext().isActive) {
            val startMs = System.currentTimeMillis()
            val ok = try {
                withPollWakeLock {
                    pollFridgeOnce(fridge, config, initialWaitMs = 0L)
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                AppLogger.error("Unexpected error in fridge loop", e)
                false
            }
            if (!ok) {
                interruptibleDelay(backoffMs)
                backoffMs = min(backoffMs * 2, 300_000L)
                continue
            }
            backoffMs = 15_000L
            val elapsedMs = System.currentTimeMillis() - startMs
            interruptibleDelay(max(100L, 60_000L - elapsedMs))
        }
    }

    private suspend fun pollFridgeOnce(
        fridge: DometicClient,
        config: AppConfig,
        initialWaitMs: Long
    ): Boolean {
        val attemptAt = System.currentTimeMillis()
        _state.update {
            it.copy(
                fridgeHealth = it.fridgeHealth.copy(
                    phase = ConnectionPhase.CONNECTING,
                    lastAttemptMillis = attemptAt
                )
            )
        }
        return try {
            withTimeout(60_000L) { fridge.connect() }
            if (initialWaitMs > 0L) {
                delay(initialWaitMs)
            }
            val data = withTimeout(15_000L) { fridge.read() }
            val now = System.currentTimeMillis()
            _state.update {
                it.copy(
                    fridgeData = data,
                    fridgeUpdatedAt = now,
                    fridgeHealth = DeviceHealth(
                        phase = ConnectionPhase.CONNECTED,
                        lastSuccessMillis = now,
                        lastAttemptMillis = attemptAt,
                        consecutiveFailures = 0,
                        lastError = null
                    )
                )
            }
            gattServer?.updateFridge(data)
            refreshStatusNotification()
            processData(data, config)
            true
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val errMsg = e.message ?: e.javaClass.simpleName
            val failures = _state.value.fridgeHealth.consecutiveFailures + 1
            AppLogger.warn("Fridge (${config.fridge.alias}) unreachable: $errMsg")
            try { fridge.disconnect(refreshCache = failures % 3 == 0) } catch (_: Exception) {}
            _state.update {
                val prev = it.fridgeHealth
                it.copy(
                    fridgeHealth = prev.copy(
                        phase = ConnectionPhase.ERROR,
                        lastAttemptMillis = attemptAt,
                        consecutiveFailures = failures,
                        lastError = errMsg
                    )
                )
            }
            refreshStatusNotification()
            false
        }
    }

    private suspend fun weatherPollLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val current = _state.value
                val lat = current.currentLat
                val lon = current.currentLon
                if (lat != null && lon != null) {
                    AppLogger.info("Polling Open-Meteo weather for lat=$lat, lon=$lon")
                    val weatherState = WeatherClient.fetchWeather(lat, lon)
                    _state.update { it.copy(weather = weatherState) }
                    gattServer?.updateWeather(weatherState)
                    AppLogger.info("Weather characteristic (ff13) updated: ${weatherState.currentTemp}°C")
                } else {
                    AppLogger.warn("Weather polling skipped: GPS location not yet resolved")
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                AppLogger.error("Error in weather polling loop", e)
                _state.update {
                    val prev = it.weather
                    if (prev != null) {
                        it.copy(weather = prev.copy(lastError = e.message))
                    } else {
                        it
                    }
                }
            }
            interruptibleDelay(60_000L)
        }
    }

    private suspend fun processData(data: Map<String, Any?>, config: AppConfig) {
        AppLogger.info(" => ${data["__name"]}: $data")
        if (config.influxDb2.enabled) {
            try {
                InfluxDb2Client.writeData(config.influxDb2, data)
                _state.update {
                    it.copy(
                        influxStatus = it.influxStatus.copy(
                            lastWriteTimeMillis = System.currentTimeMillis(),
                            totalWrites = it.influxStatus.totalWrites + 1,
                            lastError = null
                        )
                    )
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                AppLogger.error("InfluxDB2 write failed", e)
                _state.update {
                    it.copy(
                        influxStatus = it.influxStatus.copy(
                            lastError = e.message ?: "Write error"
                        )
                    )
                }
            }
        }
    }
}
