package com.example.renogybt.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.renogybt.ble.ScannedBleDevice
import com.example.renogybt.model.AppConfig
import com.example.renogybt.model.ConnectionPhase
import com.example.renogybt.model.DeviceHealth
import com.example.renogybt.model.SolarMonitorState
import com.example.renogybt.network.WeatherClient
import com.example.renogybt.service.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val context = LocalContext.current
    val monitorState by viewModel.monitorState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val scannedDevices by viewModel.scannedDevices.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var configState by remember { mutableStateOf(viewModel.loadConfig(context)) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("RenogyBT Solar Monitor", fontWeight = FontWeight.Bold)
                        Text(
                            text = if (monitorState.serviceRunning) {
                                "Running • GATT Server: ${if (monitorState.bleServer.advertising) "Advertising (${monitorState.bleServer.serverName})" else "Idle"}"
                            } else {
                                "Service Stopped"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                actions = {
                    if (monitorState.serviceRunning) {
                        OutlinedButton(
                            onClick = { viewModel.retryNow(context) },
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text("Retry")
                        }
                        Button(
                            onClick = { viewModel.stopService(context) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Stop")
                        }
                    } else {
                        Button(
                            onClick = {
                                if (!permissionsGranted) {
                                    onRequestPermissions()
                                } else {
                                    viewModel.startService(context)
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Start")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("📊") },
                    label = { Text("Dashboard") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("⚙️") },
                    label = { Text("Settings") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("📜") },
                    label = { Text("Logs (${logs.size})") }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!permissionsGranted) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Bluetooth permissions (Scan/Connect/Advertise) are required.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onRequestPermissions) {
                            Text("Grant")
                        }
                    }
                }
            }

            when (selectedTab) {
                0 -> DashboardTab(
                    monitorState = monitorState,
                    onRetryNow = { viewModel.retryNow(context) },
                    onOpenBluetoothSettings = { viewModel.openBluetoothSettings(context) }
                )
                1 -> SettingsTab(
                    config = configState,
                    onConfigChange = { configState = it },
                    onSave = { viewModel.saveConfig(context, configState) },
                    isScanning = isScanning,
                    scannedDevices = scannedDevices,
                    onTriggerScan = { viewModel.scanBleDevices(context) }
                )
                2 -> LogsTab(
                    logs = logs,
                    onClear = { viewModel.clearLogs() }
                )
            }
        }
    }
}

@Composable
private fun DashboardTab(
    monitorState: SolarMonitorState,
    onRetryNow: () -> Unit,
    onOpenBluetoothSettings: () -> Unit
) {
    val scrollState = rememberScrollState()

    val failingDevices = buildList {
        if (monitorState.chargerHealth.phase == ConnectionPhase.ERROR) {
            add("DC Charger (${monitorState.chargerHealth.consecutiveFailures}x): ${monitorState.chargerHealth.lastError ?: "Unreachable"}")
        }
        if (monitorState.batteryHealth.phase == ConnectionPhase.ERROR) {
            add("Battery (${monitorState.batteryHealth.consecutiveFailures}x): ${monitorState.batteryHealth.lastError ?: "Unreachable"}")
        }
        if (monitorState.fridgeHealth.phase == ConnectionPhase.ERROR) {
            add("Dometic Fridge (${monitorState.fridgeHealth.consecutiveFailures}x): ${monitorState.fridgeHealth.lastError ?: "Unreachable"}")
        }
    }

    val repeatedFailures = maxOf(
        monitorState.chargerHealth.consecutiveFailures,
        monitorState.batteryHealth.consecutiveFailures
    ) >= 3

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Prominent unreachable-device summary banner (app stays running & retries automatically)
        if (failingDevices.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "⚠️ BLE Device Connection Issue (Auto-retrying)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    failingDevices.forEach { msg ->
                        Text(
                            text = "• $msg",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    if (repeatedFailures) {
                        Text(
                            text = "Tip: Android automatically clears the GATT cache on each failure. If the Renogy BT module or phone radio is wedged, toggle Bluetooth in system settings or power-cycle the BT-2 module.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Button(onClick = onRetryNow) {
                            Text("Retry Now")
                        }
                        OutlinedButton(onClick = onOpenBluetoothSettings) {
                            Text("Bluetooth Settings")
                        }
                    }
                }
            }
        }

        // 1. BLE GATT Server Card (for ESP32 Display)
        StatusCard(
            title = "ESP32 Display BLE GATT Server (${monitorState.bleServer.serverName})",
            updatedAt = monitorState.bleServer.lastReadTimeMillis
        ) {
            KeyValueRow("Advertising", if (monitorState.bleServer.advertising) "Active (0000ff10-...)" else "Stopped")
            KeyValueRow("Connected ESP32 Displays", monitorState.bleServer.connectedDevicesCount.toString())
            KeyValueRow("Last Characteristic Read", monitorState.bleServer.lastReadCharacteristic ?: "None yet")
            monitorState.bleServer.error?.let { err ->
                Text(
                    text = "Error: $err",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // 2. Battery Card (Eco-Worthy)
        val bat = monitorState.batteryData
        val batHealth = monitorState.batteryHealth
        StatusCard(
            title = "Battery (${bat["__name"] ?: "EW_BAT"})",
            updatedAt = monitorState.batteryUpdatedAt,
            health = batHealth,
            onRetry = onRetryNow
        ) {
            if (bat.isEmpty()) {
                Text(
                    text = when (batHealth.phase) {
                        ConnectionPhase.DISABLED -> "Battery polling is disabled in Settings."
                        ConnectionPhase.ERROR -> "No readings yet — waiting for battery to become reachable."
                        else -> "Waiting for battery BLE poll..."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                val isStale = batHealth.phase == ConnectionPhase.ERROR
                Column(modifier = Modifier.alpha(if (isStale) 0.65f else 1f)) {
                    if (isStale) {
                        Text(
                            text = "Showing last known values (stale)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    KeyValueRow("State of Charge", "${fmt(bat["percentage"], 1)} %")
                    KeyValueRow("1h SoC Change", "${fmt(monitorState.batteryPctChange1h, 1)} %/h")
                    KeyValueRow("Voltage", "${fmt(bat["voltage"], 2)} V")
                    KeyValueRow("Current", "${fmt(bat["current"], 2)} A")
                    KeyValueRow("Power", "${fmt(bat["power"], 1)} W")
                    KeyValueRow("Capacity", "${fmt(bat["capacity_remaining"], 1)} / ${fmt(bat["capacity"], 1)} Ah")
                    KeyValueRow("Temperature", "${fmt(bat["temperature"], 1)} °C")
                }
            }
        }

        // 3. Charger Card (Renogy DC-DC / MPPT)
        val chg = monitorState.chargerData
        val chgHealth = monitorState.chargerHealth
        StatusCard(
            title = "DC Charger (${chg["__name"] ?: "RNG_DCC"})",
            updatedAt = monitorState.chargerUpdatedAt,
            health = chgHealth,
            onRetry = onRetryNow
        ) {
            if (chg.isEmpty()) {
                Text(
                    text = when (chgHealth.phase) {
                        ConnectionPhase.DISABLED -> "Charger polling is disabled in Settings."
                        ConnectionPhase.ERROR -> "No readings yet — waiting for Renogy DCC charger to respond."
                        else -> "Waiting for charger BLE poll..."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                val isStale = chgHealth.phase == ConnectionPhase.ERROR
                Column(modifier = Modifier.alpha(if (isStale) 0.65f else 1f)) {
                    if (isStale) {
                        Text(
                            text = "Showing last known values (stale)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    val pvV = (chg["pv_voltage"] as? Number)?.toDouble() ?: 0.0
                    val pvI = (chg["pv_current"] as? Number)?.toDouble() ?: 0.0
                    val altV = (chg["alternator_voltage"] as? Number)?.toDouble() ?: 0.0
                    val altI = (chg["alternator_current"] as? Number)?.toDouble() ?: 0.0
                    KeyValueRow("Solar PV", "${fmt(pvV * pvI, 1)} W (${fmt(pvV, 1)} V, ${fmt(pvI, 2)} A)")
                    KeyValueRow("Alternator", "${fmt(altV * altI, 1)} W (${fmt(altV, 1)} V, ${fmt(altI, 2)} A)")
                    KeyValueRow("Charger Bat Voltage", "${fmt(chg["battery_voltage"], 1)} V")
                    KeyValueRow("Combined Charge Current", "${fmt(chg["combined_charge_current"], 2)} A")
                    KeyValueRow("Controller / Bat Temp", "${fmt(chg["controller_temperature"], 0)} °C / ${fmt(chg["battery_temperature"], 0)} °C")
                    KeyValueRow("Today Generation", "${chg["power_generation_today"] ?: 0} Wh (${chg["charging_amp_hours_today"] ?: 0} Ah)")
                }
            }
        }

        // 4. Fridge Card (Dometic CFX)
        val frg = monitorState.fridgeData
        val frgHealth = monitorState.fridgeHealth
        StatusCard(
            title = "Dometic Fridge (${frg["__name"] ?: "DOMETIC"})",
            updatedAt = monitorState.fridgeUpdatedAt,
            health = frgHealth,
            onRetry = onRetryNow
        ) {
            if (frg.isEmpty()) {
                Text(
                    text = when (frgHealth.phase) {
                        ConnectionPhase.DISABLED -> "Fridge monitoring is disabled in Settings."
                        ConnectionPhase.ERROR -> "No readings yet — waiting for Dometic cooler BLE connection."
                        else -> "Waiting for Dometic BLE publish..."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                val isStale = frgHealth.phase == ConnectionPhase.ERROR
                Column(modifier = Modifier.alpha(if (isStale) 0.65f else 1f)) {
                    if (isStale) {
                        Text(
                            text = "Showing last known values (stale)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    KeyValueRow("Protocol / Zones", "${frg["protocol"] ?: "-"} (${frg["compartment_count"] ?: 1} zone)")
                    KeyValueRow("Zone 0 Temp (Set)", "${fmt(frg["temperature_0"], 1)} °C (Set: ${fmt(frg["set_temperature_0"], 1)} °C)")
                    if (((frg["compartment_count"] as? Number)?.toInt() ?: 1) >= 2) {
                        KeyValueRow("Zone 1 Temp (Set)", "${fmt(frg["temperature_1"], 1)} °C (Set: ${fmt(frg["set_temperature_1"], 1)} °C)")
                    }
                    KeyValueRow("Power / Supply", "${fmt(frg["power"], 1)} W @ ${fmt(frg["voltage"], 2)} V (${frg["power_source"] ?: "-"})")
                    val windowSec = (frg["energy_window_seconds"] as? Number)?.toInt() ?: 0
                    val fullHour = windowSec >= 3420
                    KeyValueRow(
                        "Energy (1h)",
                        "${fmt(frg["energy_wh_1h"], 2)} Wh ${if (fullHour) "(1h full)" else "(provisional ${windowSec / 60}m)"}"
                    )
                    KeyValueRow("Compressor / Cooler", "Comp: ${onOff(frg["compressor_power"])} • Cooler: ${onOff(frg["cooler_power"])}")
                    KeyValueRow("Doors Open", "Zone 0: ${yesNo(frg["door_open_0"])} • Zone 1: ${yesNo(frg["door_open_1"])}")
                }
            }
        }

        // 5. GPS & Environmental Card (BLE_ESP only + Last Known Good persistence)
        val gps = monitorState.gpsEnvData
        val gpsHealth = monitorState.gpsHealth
        StatusCard(
            title = "GPS & Environment (${monitorState.gpsSource})",
            updatedAt = monitorState.gpsUpdatedAt,
            health = gpsHealth,
            onRetry = onRetryNow
        ) {
            KeyValueRow(
                "Coordinates",
                if (monitorState.currentLat != null && monitorState.currentLon != null) {
                    "${fmt(monitorState.currentLat, 5)}, ${fmt(monitorState.currentLon, 5)}"
                } else {
                    "Unresolved (waiting for first BLE_ESP fix while driving)"
                }
            )
            if (gpsHealth.phase == ConnectionPhase.STANDBY) {
                Text(
                    text = if (monitorState.currentLat != null && monitorState.currentLon != null) {
                        "ESP32 GPS is off (normal when parked) — using last known good location"
                    } else {
                        "ESP32 GPS is off (only powered while driving)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                )
            }
            if (gps.isNotEmpty()) {
                val isParkedOrStale = gpsHealth.phase != ConnectionPhase.CONNECTED
                Column(modifier = Modifier.alpha(if (isParkedOrStale) 0.7f else 1f)) {
                    if (isParkedOrStale) {
                        Text(
                            text = "Last recorded sensor reading while driving",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    KeyValueRow("Altitude / Speed", "${gps["alt"] ?: "-"} m • ${fmt(gps["speed"], 1)} km/h (Sats: ${gps["satellites"] ?: "-"})")
                    if (gps.containsKey("temperature")) {
                        KeyValueRow("BME680 Temp / Humidity", "${fmt(gps["temperature"], 2)} °C • ${fmt(gps["humidity"], 2)} %")
                        KeyValueRow("Pressure / Gas", "${fmt(gps["pressure"], 1)} hPa • ${gps["gas_resistance"] ?: "-"} Ω")
                    }
                }
            }
        }

        // 6. Weather Card (Open-Meteo)
        val weather = monitorState.weather
        StatusCard(
            title = "Weather Forecast (Open-Meteo -> ff13)",
            updatedAt = weather?.updatedAtMillis
        ) {
            if (weather == null) {
                Text("Waiting for GPS coordinates to poll Open-Meteo...", style = MaterialTheme.typography.bodySmall)
            } else {
                KeyValueRow(
                    "Current",
                    "${fmt(weather.currentTemp, 1)} °C • ${WeatherClient.wmoDescription(weather.currentWeatherCode)}"
                )
                weather.forecast.forEachIndexed { idx, day ->
                    val dayLabel = when (idx) {
                        0 -> "Today"
                        1 -> "Tomorrow"
                        else -> "Day +2"
                    }
                    KeyValueRow(
                        dayLabel,
                        "${fmt(day.tempMin, 1)}°C..${fmt(day.tempMax, 1)}°C • ${WeatherClient.wmoDescription(day.weatherCode)} (${fmt(day.precipitationSum, 1)}mm)"
                    )
                }
                weather.lastError?.let { err ->
                    Text(
                        text = "Last weather refresh error: $err",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // 7. InfluxDB2 Status Card
        StatusCard(
            title = "InfluxDB 2.0 Logger",
            updatedAt = monitorState.influxStatus.lastWriteTimeMillis
        ) {
            KeyValueRow("Points Written", monitorState.influxStatus.totalWrites.toString())
            monitorState.influxStatus.lastError?.let { err ->
                Text(
                    text = "Last Error: $err",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SettingsTab(
    config: AppConfig,
    onConfigChange: (AppConfig) -> Unit,
    onSave: () -> Unit,
    isScanning: Boolean,
    scannedDevices: List<ScannedBleDevice>,
    onTriggerScan: () -> Unit
) {
    val scrollState = rememberScrollState()
    var scanTargetCallback by remember { mutableStateOf<((ScannedBleDevice) -> Unit)?>(null) }

    if (scanTargetCallback != null) {
        BleScannerDialog(
            isScanning = isScanning,
            devices = scannedDevices,
            onRescan = onTriggerScan,
            onSelect = { dev ->
                scanTargetCallback?.invoke(dev)
                scanTargetCallback = null
            },
            onDismiss = { scanTargetCallback = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Settings & Apply")
        }

        // BLE GATT Server section
        SectionCard("BLE GATT Server (ESP32 Display)") {
            SwitchRow(
                label = "Enable BLE GATT Server",
                checked = config.bleServer.enabled,
                onCheckedChange = {
                    onConfigChange(config.copy(bleServer = config.bleServer.copy(enabled = it)))
                }
            )
            OutlinedTextField(
                value = config.bleServer.name,
                onValueChange = {
                    onConfigChange(config.copy(bleServer = config.bleServer.copy(name = it)))
                },
                label = { Text("Advertised BLE Name (e.g. SolarBLE)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // InfluxDB2 section
        SectionCard("InfluxDB 2.0") {
            SwitchRow(
                label = "Enable InfluxDB 2.0 Logging",
                checked = config.influxDb2.enabled,
                onCheckedChange = {
                    onConfigChange(config.copy(influxDb2 = config.influxDb2.copy(enabled = it)))
                }
            )
            OutlinedTextField(
                value = config.influxDb2.url,
                onValueChange = {
                    onConfigChange(config.copy(influxDb2 = config.influxDb2.copy(url = it)))
                },
                label = { Text("Host URL (e.g. http://192.168.1.10:8086)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.influxDb2.org,
                    onValueChange = {
                        onConfigChange(config.copy(influxDb2 = config.influxDb2.copy(org = it)))
                    },
                    label = { Text("Organization") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = config.influxDb2.bucket,
                    onValueChange = {
                        onConfigChange(config.copy(influxDb2 = config.influxDb2.copy(bucket = it)))
                    },
                    label = { Text("Bucket") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = config.influxDb2.token,
                onValueChange = {
                    onConfigChange(config.copy(influxDb2 = config.influxDb2.copy(token = it)))
                },
                label = { Text("API Token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = config.influxDb2.fieldsFilter,
                onValueChange = {
                    onConfigChange(config.copy(influxDb2 = config.influxDb2.copy(fieldsFilter = it)))
                },
                label = { Text("Fields Filter (comma-separated, blank = all)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Charger section
        SectionCard("Renogy DC Charger ([charger])") {
            SwitchRow(
                label = "Enable Charger Polling",
                checked = config.charger.enabled,
                onCheckedChange = {
                    onConfigChange(config.copy(charger = config.charger.copy(enabled = it)))
                }
            )
            MacAddressField(
                mac = config.charger.macAddr,
                onMacChange = {
                    onConfigChange(config.copy(charger = config.charger.copy(macAddr = it)))
                },
                onScanClick = {
                    onTriggerScan()
                    scanTargetCallback = { dev ->
                        onConfigChange(
                            config.copy(
                                charger = config.charger.copy(
                                    macAddr = dev.address,
                                    alias = if (dev.name != "Unknown") dev.name else config.charger.alias
                                )
                            )
                        )
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.charger.alias,
                    onValueChange = {
                        onConfigChange(config.copy(charger = config.charger.copy(alias = it)))
                    },
                    label = { Text("Alias") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = config.charger.name,
                    onValueChange = {
                        onConfigChange(config.copy(charger = config.charger.copy(name = it)))
                    },
                    label = { Text("Measurement") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Battery section
        SectionCard("Eco-Worthy Battery ([battery])") {
            SwitchRow(
                label = "Enable Battery Polling",
                checked = config.battery.enabled,
                onCheckedChange = {
                    onConfigChange(config.copy(battery = config.battery.copy(enabled = it)))
                }
            )
            MacAddressField(
                mac = config.battery.macAddr,
                onMacChange = {
                    onConfigChange(config.copy(battery = config.battery.copy(macAddr = it)))
                },
                onScanClick = {
                    onTriggerScan()
                    scanTargetCallback = { dev ->
                        onConfigChange(
                            config.copy(
                                battery = config.battery.copy(
                                    macAddr = dev.address,
                                    alias = if (dev.name != "Unknown") dev.name else config.battery.alias
                                )
                            )
                        )
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.battery.alias,
                    onValueChange = {
                        onConfigChange(config.copy(battery = config.battery.copy(alias = it)))
                    },
                    label = { Text("Alias") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = config.battery.name,
                    onValueChange = {
                        onConfigChange(config.copy(battery = config.battery.copy(name = it)))
                    },
                    label = { Text("Measurement") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            SwitchRow(
                label = "Read Individual Cell Voltages",
                checked = config.battery.readCellVoltages,
                onCheckedChange = {
                    onConfigChange(config.copy(battery = config.battery.copy(readCellVoltages = it)))
                }
            )
        }

        // Fridge section
        SectionCard("Dometic CFX Fridge ([fridge])") {
            SwitchRow(
                label = "Enable Dometic Fridge",
                checked = config.fridge.enabled,
                onCheckedChange = {
                    onConfigChange(config.copy(fridge = config.fridge.copy(enabled = it)))
                }
            )
            MacAddressField(
                mac = config.fridge.macAddr,
                onMacChange = {
                    onConfigChange(config.copy(fridge = config.fridge.copy(macAddr = it)))
                },
                onScanClick = {
                    onTriggerScan()
                    scanTargetCallback = { dev ->
                        onConfigChange(
                            config.copy(
                                fridge = config.fridge.copy(
                                    macAddr = dev.address,
                                    alias = if (dev.name != "Unknown") dev.name else config.fridge.alias
                                )
                            )
                        )
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.fridge.protocol,
                    onValueChange = {
                        onConfigChange(config.copy(fridge = config.fridge.copy(protocol = it)))
                    },
                    label = { Text("Protocol (auto/ddm1/ddm2)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = config.fridge.productType,
                    onValueChange = {
                        onConfigChange(config.copy(fridge = config.fridge.copy(productType = it)))
                    },
                    label = { Text("Product Type (dz/sz/szi)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            SwitchRow(
                label = "Pair/Bond on Connect",
                checked = config.fridge.pair,
                onCheckedChange = {
                    onConfigChange(config.copy(fridge = config.fridge.copy(pair = it)))
                }
            )
        }

        // GPS section
        SectionCard("GPS & Environment ([gps] — BLE Only)") {
            SwitchRow(
                label = "Enable BLE_ESP Sensor Polling",
                checked = config.gps.enabled,
                onCheckedChange = {
                    onConfigChange(config.copy(gps = config.gps.copy(enabled = it)))
                }
            )
            MacAddressField(
                mac = config.gps.macAddr,
                onMacChange = {
                    onConfigChange(config.copy(gps = config.gps.copy(macAddr = it)))
                },
                onScanClick = {
                    onTriggerScan()
                    scanTargetCallback = { dev ->
                        onConfigChange(
                            config.copy(
                                gps = config.gps.copy(
                                    macAddr = dev.address,
                                    alias = if (dev.name != "Unknown") dev.name else config.gps.alias
                                )
                            )
                        )
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.gps.alias,
                    onValueChange = {
                        onConfigChange(config.copy(gps = config.gps.copy(alias = it)))
                    },
                    label = { Text("Alias") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = config.gps.name,
                    onValueChange = {
                        onConfigChange(config.copy(gps = config.gps.copy(name = it)))
                    },
                    label = { Text("Measurement") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Settings & Apply")
        }
    }
}

@Composable
private fun LogsTab(
    logs: List<LogEntry>,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Live Activity Logs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(onClick = onClear) {
                Text("Clear")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(8.dp)
                )
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(logs.reversed()) { entry ->
                val color = when (entry.level) {
                    "ERROR" -> MaterialTheme.colorScheme.error
                    "WARN" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    text = "[${entry.timestamp}] ${entry.level}: ${entry.message}",
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BleScannerDialog(
    isScanning: Boolean,
    devices: List<ScannedBleDevice>,
    onRescan: () -> Unit,
    onSelect: (ScannedBleDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select BLE Device") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isScanning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                        Text("Scanning nearby BLE devices...", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (devices.isEmpty() && !isScanning) {
                    Text("No BLE devices found. Make sure Bluetooth is enabled and devices are in range.")
                } else {
                    LazyColumn(modifier = Modifier.height(260.dp)) {
                        items(devices) { dev ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(dev) }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(dev.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${dev.address}  •  RSSI: ${dev.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRescan, enabled = !isScanning) {
                Text("Rescan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ConnectionBadge(health: DeviceHealth) {
    val (label, bgColor, textColor) = when (health.phase) {
        ConnectionPhase.CONNECTED -> Triple("Connected", Color(0xFF1B5E20), Color.White)
        ConnectionPhase.CONNECTING -> Triple("Polling...", Color(0xFFE65100), Color.White)
        ConnectionPhase.ERROR -> Triple(
            "Unreachable (${health.consecutiveFailures}x)",
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onError
        )
        ConnectionPhase.DISABLED -> Triple("Disabled", Color.Gray, Color.White)
        ConnectionPhase.STANDBY -> Triple("Standby (Parked)", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        ConnectionPhase.IDLE -> Triple("Idle", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    }
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun StatusCard(
    title: String,
    updatedAt: Long?,
    health: DeviceHealth? = null,
    onRetry: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (health != null) {
                        ConnectionBadge(health)
                    }
                    if (updatedAt != null) {
                        val formatted = remember(updatedAt) {
                            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(updatedAt))
                        }
                        Text(
                            text = formatted,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (health != null && health.lastError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = health.lastError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    if (onRetry != null && health.phase == ConnectionPhase.ERROR) {
                        TextButton(onClick = onRetry) {
                            Text("Retry", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MacAddressField(
    mac: String,
    onMacChange: (String) -> Unit,
    onScanClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = mac,
            onValueChange = onMacChange,
            label = { Text("BLE MAC Address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = onScanClick) {
            Text("Scan")
        }
    }
}

private fun fmt(value: Any?, decimals: Int): String {
    val num = (value as? Number)?.toDouble() ?: return "-"
    return String.format(Locale.US, "%.${decimals}f", num)
}

private fun onOff(value: Any?): String {
    val n = (value as? Number)?.toInt() ?: return "-"
    return if (n != 0) "ON" else "OFF"
}

private fun yesNo(value: Any?): String {
    val n = (value as? Number)?.toInt() ?: return "-"
    return if (n != 0) "OPEN" else "Closed"
}
