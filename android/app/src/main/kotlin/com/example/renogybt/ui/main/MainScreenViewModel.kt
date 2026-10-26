package com.example.renogybt.ui.main

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.renogybt.ble.BleConnectionManager
import com.example.renogybt.ble.ScannedBleDevice
import com.example.renogybt.model.AppConfig
import com.example.renogybt.model.ConfigRepository
import com.example.renogybt.model.SolarMonitorState
import com.example.renogybt.service.AppLogger
import com.example.renogybt.service.LogEntry
import com.example.renogybt.service.SolarMonitorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainScreenViewModel : ViewModel() {
    val monitorState: StateFlow<SolarMonitorState> = SolarMonitorService.state
    val logs: StateFlow<List<LogEntry>> = AppLogger.logs

    private val _scannedDevices = MutableStateFlow<List<ScannedBleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedBleDevice>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun loadConfig(context: Context): AppConfig {
        return ConfigRepository(context.applicationContext).loadConfig()
    }

    fun saveConfig(context: Context, config: AppConfig) {
        ConfigRepository(context.applicationContext).saveConfig(config)
        AppLogger.info("Configuration saved")
        if (monitorState.value.serviceRunning) {
            SolarMonitorService.startService(context.applicationContext)
        }
    }

    fun startService(context: Context) {
        SolarMonitorService.startService(context.applicationContext)
    }

    fun stopService(context: Context) {
        SolarMonitorService.stopService(context.applicationContext)
    }

    fun retryNow(context: Context) {
        SolarMonitorService.retryNow(context.applicationContext)
    }

    fun openBluetoothSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.warn("Could not open Bluetooth settings: ${e.message}")
        }
    }

    fun clearLogs() {
        AppLogger.clear()
    }

    fun scanBleDevices(context: Context) {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            _scannedDevices.value = emptyList()
            try {
                val results = BleConnectionManager.scanDevices(context.applicationContext, durationMs = 5000L)
                _scannedDevices.value = results
            } finally {
                _isScanning.value = false
            }
        }
    }
}
