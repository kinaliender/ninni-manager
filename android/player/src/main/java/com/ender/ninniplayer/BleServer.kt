package com.ender.ninniplayer

import com.ender.ninni.protocol.Command
import com.ender.ninni.protocol.PlayerState
import com.ender.ninni.protocol.Protocol
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import android.os.SystemClock

/**
 * BLE peripheral: advertises [Protocol.SERVICE] and runs a GATT server with the STATE and
 * COMMAND characteristics. Every callback is moved onto [handler] (main thread), so the
 * fields below are only touched from one thread.
 */
@SuppressLint("MissingPermission") // PlayerService starts this only after Bluetooth permissions are granted.
class BleServer(
    private val context: Context,
    private val handler: Handler,
    private val execute: (Command) -> Int,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var stateChar: BluetoothGattCharacteristic? = null
    private var state: PlayerState? = null
    private var includeName = true
    private var pinLockedUntil = 0L

    private val connected = mutableSetOf<BluetoothDevice>()
    private val subscribed = mutableSetOf<BluetoothDevice>()
    private val authorized = mutableSetOf<BluetoothDevice>()
    private val mtu = mutableMapOf<BluetoothDevice, Int>()
    /** Devices with a notification in flight (Android allows one at a time per device). */
    private val sendingSince = mutableMapOf<BluetoothDevice, Long>()
    /** Devices that should get the latest state once the in-flight notification is done. */
    private val pending = mutableSetOf<BluetoothDevice>()
    /** Value being read with offsets (long read), so all parts come from the same state. */
    private val readSnapshot = mutableMapOf<BluetoothDevice, ByteArray>()

    private val restartAdvertising = Runnable { startAdvertising() }
    private val flushPending = Runnable { pending.toList().forEach { notify(it) } }

    val isRunning get() = server != null

    fun start() {
        if (server != null) return
        Status.update { it.copy(problem = null, errorCode = 0) }
        val mgr = manager ?: return report(Status.Problem.NO_BLUETOOTH)
        val adapter = mgr.adapter ?: return report(Status.Problem.NO_BLUETOOTH)
        if (!adapter.isEnabled) return report(Status.Problem.BLUETOOTH_OFF)
        val adv = adapter.bluetoothLeAdvertiser ?: return report(Status.Problem.ADVERTISING_UNSUPPORTED)
        val gatt = try {
            mgr.openGattServer(context, callback)
        } catch (e: SecurityException) {
            return report(Status.Problem.NO_PERMISSION)
        } ?: return report(Status.Problem.SERVER_FAILED)

        val stateC = BluetoothGattCharacteristic(
            Protocol.STATE,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    Protocol.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }
        val commandC = BluetoothGattCharacteristic(
            Protocol.COMMAND,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val service = BluetoothGattService(Protocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(stateC)
            addCharacteristic(commandC)
        }
        server = gatt
        advertiser = adv
        stateChar = stateC
        // Advertising starts in onServiceAdded, once the service is visible to clients.
        if (!gatt.addService(service)) {
            stop()
            report(Status.Problem.SERVER_FAILED)
        }
    }

    fun stop() {
        handler.removeCallbacks(restartAdvertising)
        handler.removeCallbacks(flushPending)
        stopAdvertising()
        server?.let { s ->
            connected.forEach { runCatching { s.cancelConnection(it) } }
            runCatching { s.clearServices() }
            runCatching { s.close() }
        }
        server = null
        advertiser = null
        stateChar = null
        connected.clear()
        subscribed.clear()
        authorized.clear()
        mtu.clear()
        sendingSince.clear()
        pending.clear()
        readSnapshot.clear()
        publishCounts()
    }

    /** Stores the latest state and notifies every subscribed manager. */
    fun publish(newState: PlayerState) {
        state = newState
        subscribed.toList().forEach { notify(it) }
    }

    /** Called when the pairing code changes: every manager has to send the new code. */
    fun revokeAll() {
        authorized.clear()
        publishCounts()
    }

    // --- Advertising ---------------------------------------------------------------

    private fun startAdvertising() {
        val adv = advertiser ?: return
        if (server == null) return
        stopAdvertising()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(Protocol.SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(includeName)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                handler.post {
                    if (advertiseCallback === this) {
                        Status.update { it.copy(advertising = true, problem = null, errorCode = 0) }
                    }
                }
            }

            override fun onStartFailure(errorCode: Int) {
                handler.post {
                    if (advertiseCallback !== this) return@post
                    advertiseCallback = null
                    when (errorCode) {
                        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
                            if (includeName) {
                                // Long Bluetooth name: advertise without it.
                                includeName = false
                                startAdvertising()
                            } else {
                                report(Status.Problem.ADVERTISING_FAILED, errorCode)
                            }
                        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                            report(Status.Problem.ADVERTISING_UNSUPPORTED)
                        else -> {
                            report(Status.Problem.ADVERTISING_FAILED, errorCode)
                            handler.postDelayed(restartAdvertising, 5_000)
                        }
                    }
                }
            }
        }
        advertiseCallback = cb
        try {
            adv.startAdvertising(settings, data, scanResponse, cb)
        } catch (e: Exception) {
            advertiseCallback = null
            report(Status.Problem.ADVERTISING_FAILED)
            handler.postDelayed(restartAdvertising, 5_000)
        }
    }

    private fun stopAdvertising() {
        val cb = advertiseCallback ?: return
        advertiseCallback = null
        runCatching { advertiser?.stopAdvertising(cb) }
        Status.update { it.copy(advertising = false) }
    }

    /**
     * A connection may end connectable advertising on some controllers. Restart it after
     * every connect/disconnect so a second manager (or a reconnect) can always find us.
     */
    private fun scheduleAdvertisingRestart() {
        handler.removeCallbacks(restartAdvertising)
        handler.postDelayed(restartAdvertising, 500)
    }

    // --- GATT ----------------------------------------------------------------------

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) connected += device else forget(device)
                publishCounts()
                scheduleAdvertisingRestart()
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    startAdvertising()
                } else {
                    stop()
                    report(Status.Problem.SERVER_FAILED)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post {
                this@BleServer.mtu[device] = mtu
                // Resend so the title fits now that packets are bigger.
                if (device in subscribed) notify(device)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            handler.post {
                val s = server ?: return@post
                if (characteristic.uuid != Protocol.STATE) {
                    s.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                    return@post
                }
                val full = if (offset == 0) {
                    fullState().also { readSnapshot[device] = it }
                } else {
                    readSnapshot[device] ?: fullState()
                }
                if (offset > full.size) {
                    s.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                } else {
                    // The stack sends as much as fits (MTU - 1); the client asks for the rest.
                    s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, full.copyOfRange(offset, full.size))
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            handler.post {
                val status = when {
                    characteristic.uuid != Protocol.COMMAND -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                    preparedWrite || offset != 0 -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    else -> handleCommand(device, value ?: ByteArray(0))
                }
                if (responseNeeded) server?.sendResponse(device, requestId, status, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            handler.post {
                val ok = descriptor.uuid == Protocol.CCCD && !preparedWrite && offset == 0
                if (ok) {
                    val enable = value != null && value.isNotEmpty() && (value[0].toInt() and 0x03) != 0
                    if (enable) subscribed += device else subscribed -= device
                }
                if (responseNeeded) {
                    val status = if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                    server?.sendResponse(device, requestId, status, 0, null)
                }
                if (ok && device in subscribed) notify(device)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            handler.post {
                val value = if (descriptor.uuid == Protocol.CCCD && device in subscribed) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            handler.post {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post {
                sendingSince -= device
                if (pending.remove(device)) notify(device)
            }
        }
    }

    private fun handleCommand(device: BluetoothDevice, value: ByteArray): Int {
        val command = Protocol.parseCommand(value) ?: return Protocol.ERR_BAD_COMMAND
        return when (command) {
            is Command.Auth -> authorize(device, command.pin)
            Command.Ping -> BluetoothGatt.GATT_SUCCESS
            else -> if (device in authorized) execute(command) else Protocol.ERR_NOT_AUTHORIZED
        }
    }

    private fun authorize(device: BluetoothDevice, pin: String): Int {
        val now = SystemClock.elapsedRealtime()
        // After a wrong code, refuse every attempt for 3 s so the code cannot be guessed quickly.
        if (now < pinLockedUntil) return Protocol.ERR_WRONG_PIN
        if (pin != Prefs.pin(context)) {
            pinLockedUntil = now + 3_000
            return Protocol.ERR_WRONG_PIN
        }
        authorized += device
        publishCounts()
        return BluetoothGatt.GATT_SUCCESS
    }

    private fun notify(device: BluetoothDevice) {
        val s = server ?: return
        val c = stateChar ?: return
        val current = state ?: return
        if (device !in subscribed) {
            pending -= device
            return
        }
        val since = sendingSince[device]
        if (since != null && SystemClock.elapsedRealtime() - since < 2_000) {
            pending += device
            return
        }
        pending -= device
        val payload = Protocol.encodeState(current, (mtu[device] ?: 23) - 3)
        if (payload.isEmpty()) return
        val sent = try {
            if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(device, c, false, payload) == BluetoothStatusCodes.SUCCESS
            } else {
                notifyLegacy(s, device, c, payload)
            }
        } catch (e: Exception) {
            false
        }
        if (sent) {
            sendingSince[device] = SystemClock.elapsedRealtime()
        } else {
            pending += device
            handler.removeCallbacks(flushPending)
            handler.postDelayed(flushPending, 300)
        }
    }

    @Suppress("DEPRECATION")
    private fun notifyLegacy(
        s: BluetoothGattServer, device: BluetoothDevice, c: BluetoothGattCharacteristic, payload: ByteArray,
    ): Boolean {
        c.value = payload
        return s.notifyCharacteristicChanged(device, c, false)
    }

    private fun fullState(): ByteArray = state?.let { Protocol.encodeState(it) } ?: ByteArray(0)

    private fun forget(device: BluetoothDevice) {
        connected -= device
        subscribed -= device
        authorized -= device
        mtu -= device
        sendingSince -= device
        pending -= device
        readSnapshot -= device
    }

    private fun publishCounts() {
        Status.update { it.copy(connected = connected.size, authorized = authorized.size) }
    }

    private fun report(problem: Status.Problem, code: Int = 0) {
        Status.update { it.copy(problem = problem, errorCode = code, advertising = false) }
    }
}
