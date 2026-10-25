package de.gregoire.teamrowingremote.sync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class SyncStatus {
    NO_PERMISSION,
    BLUETOOTH_OFF,
    SEARCHING,
    CONNECTED,
}

/** Bluetooth access needs the "Nearby devices" permission since Android 12. */
fun hasBluetoothPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

/**
 * Connects to the display app on a paired device and syncs BPM entries with it. Only paired
 * computers, tablets and phones are tried, starting with the last connected one, so headphones or
 * car systems aren't contacted.
 */
class BluetoothSyncClient(private val context: Context, private val store: BpmSyncStore) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(SyncStatus.SEARCHING)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Keeps connected until cancelled, e.g. while the app is visible; retries after a lost connection. */
    suspend fun run() = withContext(Dispatchers.IO) {
        while (true) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            when {
                !hasBluetoothPermission(context) -> _status.value = SyncStatus.NO_PERMISSION
                adapter == null || !adapter.isEnabled -> _status.value = SyncStatus.BLUETOOTH_OFF
                else -> {
                    _status.value = SyncStatus.SEARCHING
                    for (device in candidates(adapter)) {
                        if (syncWith(device)) break
                    }
                }
            }
            delay(RETRY_DELAY_MS)
        }
    }

    @SuppressLint("MissingPermission") // Checked by hasBluetoothPermission() before
    private fun candidates(adapter: BluetoothAdapter): List<BluetoothDevice> {
        val lastAddress = prefs.getString(KEY_LAST_ADDRESS, null)
        return adapter.bondedDevices
            .filter { it.bluetoothClass?.majorDeviceClass in CANDIDATE_CLASSES }
            .sortedByDescending { it.address == lastAddress }
    }

    /** Syncs with [device] until the connection ends; returns false if the display app isn't reachable there. */
    @SuppressLint("MissingPermission") // Checked by hasBluetoothPermission() before
    private suspend fun syncWith(device: BluetoothDevice): Boolean = coroutineScope {
        val socket = device.createRfcommSocketToServiceRecord(BpmSyncProtocol.SERVICE_UUID)
        val closer = launch { closeOnCancellation(socket) }
        try {
            socket.connect()
        } catch (e: IOException) {
            closer.cancel()
            return@coroutineScope false
        }
        Log.i(TAG, "Connected to ${device.name}")
        prefs.edit { putString(KEY_LAST_ADDRESS, device.address) }
        _status.value = SyncStatus.CONNECTED
        try {
            BpmSyncSession(socket, store).run()
            Log.i(TAG, "Disconnected from ${device.name}")
        } catch (e: IOException) {
            Log.i(TAG, "Connection to ${device.name} lost", e)
        } finally {
            _status.value = SyncStatus.SEARCHING
            closer.cancel()
        }
        true
    }

    private companion object {
        const val TAG = "BluetoothSyncClient"
        const val PREFS_NAME = "sync"
        const val KEY_LAST_ADDRESS = "last_address"
        const val RETRY_DELAY_MS = 10_000L
        val CANDIDATE_CLASSES = setOf(
            BluetoothClass.Device.Major.COMPUTER,
            BluetoothClass.Device.Major.PHONE,
            BluetoothClass.Device.Major.MISC,
            BluetoothClass.Device.Major.UNCATEGORIZED,
        )
    }
}
