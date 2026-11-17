package com.ender.ninnimanager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.ender.ninni.protocol.Blob
import com.ender.ninni.protocol.Command
import com.ender.ninni.protocol.PlayerState
import com.ender.ninni.protocol.Protocol
import com.ender.ninni.protocol.Queue
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * BLE central for the player: scan by service UUID, connect, subscribe, authorize with the
 * PIN, send commands, reconnect with backoff. Android allows one GATT operation at a time,
 * so every operation goes through [queue]. All state lives on the main thread.
 *
 * The cover art and the play queue are fetched from DATA page by page whenever the state
 * announces a new id (protocol v3); covers are kept in memory, so a repeating playlist does
 * not fetch them again.
 *
 * The player's Bluetooth address rotates (LE privacy), so it is found by scanning every time
 * instead of by a saved address.
 */
@SuppressLint("MissingPermission") // MainActivity calls start() only with the Bluetooth permissions.
class PlayerConnection(context: Context) {

    enum class Phase { IDLE, BLUETOOTH_OFF, SCANNING, NOT_FOUND, CONNECTING, NEED_PIN, WRONG_PIN, CONNECTED }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val deviceName: String? = null,
        /** Last known state; kept (greyed out) while reconnecting. */
        val state: PlayerState? = null,
        /** elapsedRealtime when [state] arrived; position and sleep timer count on from here. */
        val stateTime: Long = 0,
        /** Cover of the current track once fetched; null while loading or when there is none. */
        val art: Bitmap? = null,
        /** Last fetched play queue; null when the player shares none. */
        val queue: Queue? = null,
    )

    private sealed interface Op {
        data object Mtu : Op
        data object Discover : Op
        data object Subscribe : Op
        data object Read : Op
        class Write(val text: String, val onResult: (Int) -> Unit) : Op
        /** Reads DATA; null on failure. */
        class ReadData(val onResult: (ByteArray?) -> Unit) : Op
    }

    /** A cover or queue being fetched page by page. */
    private class Transfer(val blob: Blob) {
        val bytes = ByteArrayOutputStream()
        var count = 0
        var id = 0
    }

    private companion object {
        const val MTU = 517
        const val SCAN_TIMEOUT = 10_000L
        const val CONNECT_TIMEOUT = 12_000L
        const val OP_TIMEOUT = 6_000L
        const val STOP_DELAY = 30_000L
        const val MAX_QUEUED_COMMANDS = 5
        const val POLL_INTERVAL = 15_000L
        const val ART_CACHE = 40
    }

    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var wanted = false
    private var scanning = false
    private var failures = 0
    private var gatt: BluetoothGatt? = null
    private var stateChar: BluetoothGattCharacteristic? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var authorized = false
    private val queue = ArrayDeque<Op>()
    private var current: Op? = null

    private var transfer: Transfer? = null
    /** Id last asked for per blob on this connection, so a failing one is not fetched in a loop. */
    private val tried = mutableMapOf<Blob, String>()
    private val artCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > ART_CACHE
    }
    private var loadedQueue: Queue? = null
    private var loadedQueueId: String? = null

    var snapshot = Snapshot()
        private set
    private val listeners = mutableSetOf<(Snapshot) -> Unit>()

    private val retryRunnable = Runnable { connectNow() }
    private val stopRunnable = Runnable { stop() }
    private val scanTimeout = Runnable {
        stopScan()
        update(phase = Phase.NOT_FOUND)
        failures++
        retryLater()
    }
    private val connectTimeout = Runnable { fail() }
    private val opTimeout = Runnable { fail() }

    /**
     * Safety net: read the state now and then, in case notifications stop arriving without a
     * callback (e.g. a GATT service change on Android 10/11, which has no onServiceChanged).
     */
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!authorized) return
            if (current == null && queue.isEmpty()) enqueue(Op.Read)
            handler.postDelayed(this, POLL_INTERVAL)
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(this.context, bluetoothReceiver(), filter, ContextCompat.RECEIVER_EXPORTED)
    }

    // --- Public API (main thread) ---------------------------------------------------

    fun observe(listener: (Snapshot) -> Unit) {
        listeners += listener
        listener(snapshot)
    }

    fun remove(listener: (Snapshot) -> Unit) {
        listeners -= listener
    }

    /** Screen visible: connect and stay connected. */
    fun start() {
        handler.removeCallbacks(stopRunnable)
        if (wanted) return
        wanted = true
        failures = 0
        connectNow()
    }

    /** Screen hidden: keep the connection a little while for quick returns. */
    fun stopSoon() {
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, STOP_DELAY)
    }

    /** Try right away instead of waiting for the next retry (status line tap). */
    fun connectNow() {
        if (!wanted) return
        handler.removeCallbacks(retryRunnable)
        if (gatt != null || scanning) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            update(phase = Phase.BLUETOOTH_OFF)
            return
        }
        startScan()
    }

    fun submitPin(pin: String) {
        Prefs.setPin(context, pin)
        if (gatt != null && commandChar != null) authenticate() else connectNow()
    }

    /** [onResult] gets the player's answer (e.g. [Protocol.ERR_FAILED]) or -1 when not sent. */
    fun send(command: Command, onResult: ((Int) -> Unit)? = null) {
        // Holding a volume key repeats fast; don't build up a long backlog.
        if (!authorized || queue.count { it is Op.Write } >= MAX_QUEUED_COMMANDS) {
            onResult?.invoke(-1)
            return
        }
        enqueue(Op.Write(command.wire) { status ->
            if (status == Protocol.ERR_NOT_AUTHORIZED) {
                authorized = false
                authenticate()
            }
            onResult?.invoke(status)
        })
    }

    // --- Scan / connect -------------------------------------------------------------

    private fun stop() {
        wanted = false
        handler.removeCallbacks(retryRunnable)
        stopScan()
        teardown()
        update(phase = Phase.IDLE)
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return update(phase = Phase.BLUETOOTH_OFF)
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            update(phase = Phase.NOT_FOUND)
            failures++
            retryLater()
            return
        }
        scanning = true
        if (snapshot.phase != Phase.NOT_FOUND) update(phase = Phase.SCANNING)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT)
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!scanning) return@post
                stopScan()
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                connect(result.device, name)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                handler.removeCallbacks(scanTimeout)
                update(phase = Phase.NOT_FOUND)
                failures++
                retryLater()
            }
        }
    }

    private fun connect(device: BluetoothDevice, name: String?) {
        update(phase = Phase.CONNECTING, deviceName = name ?: snapshot.deviceName)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT)
    }

    /** 2 s, 4 s, 8 s, then every 15 s. Also keeps under Android's 5-scans-per-30-s limit. */
    private fun retryLater() {
        handler.removeCallbacks(retryRunnable)
        if (!wanted) return
        val delay = minOf(1_000L shl failures.coerceIn(1, 4), 15_000L)
        handler.postDelayed(retryRunnable, delay)
    }

    private fun fail() {
        teardown()
        if (!wanted) {
            update(phase = Phase.IDLE)
            return
        }
        failures++
        if (snapshot.phase != Phase.NOT_FOUND && snapshot.phase != Phase.BLUETOOTH_OFF) {
            update(phase = Phase.CONNECTING)
        }
        retryLater()
    }

    private fun teardown() {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(opTimeout)
        handler.removeCallbacks(pollRunnable)
        val dropped = listOfNotNull(current) + queue
        queue.clear()
        current = null
        authorized = false
        transfer = null
        tried.clear()
        stateChar = null
        commandChar = null
        dataChar = null
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        dropped.forEach { op ->
            when (op) {
                is Op.Write -> op.onResult(-1)
                is Op.ReadData -> op.onResult(null)
                else -> Unit
            }
        }
    }

    private fun authenticate() {
        val pin = Prefs.pin(context)
        if (pin == null) {
            update(phase = Phase.NEED_PIN)
            return
        }
        enqueue(Op.Write(Command.Auth(pin).wire) { status ->
            when (status) {
                Protocol.OK -> {
                    authorized = true
                    failures = 0
                    update(phase = Phase.CONNECTED)
                    handler.removeCallbacks(pollRunnable)
                    handler.postDelayed(pollRunnable, POLL_INTERVAL)
                }
                Protocol.ERR_WRONG_PIN -> update(phase = Phase.WRONG_PIN)
                -1 -> Unit // connection dropped; fail() already handles it
                else -> fail()
            }
        })
    }

    // --- GATT operation queue -------------------------------------------------------

    private fun enqueue(op: Op) {
        queue.addLast(op)
        pump()
    }

    private fun pump() {
        if (current != null) return
        val g = gatt ?: return
        val op = queue.removeFirstOrNull() ?: return
        current = op
        val started = try {
            when (op) {
                Op.Mtu -> g.requestMtu(MTU)
                Op.Discover -> g.discoverServices()
                Op.Subscribe -> subscribe(g)
                Op.Read -> stateChar?.let { g.readCharacteristic(it) } ?: false
                is Op.ReadData -> dataChar?.let { g.readCharacteristic(it) } ?: false
                is Op.Write -> write(g, op.text)
            }
        } catch (e: Exception) {
            false
        }
        if (started) {
            handler.postDelayed(opTimeout, OP_TIMEOUT)
            return
        }
        current = null
        when (op) {
            Op.Mtu -> pump() // optional: continue with the default MTU
            is Op.Write -> {
                op.onResult(-1)
                pump()
            }
            is Op.ReadData -> {
                op.onResult(null)
                pump()
            }
            else -> fail()
        }
    }

    private fun complete() {
        handler.removeCallbacks(opTimeout)
        current = null
        pump()
    }

    private fun subscribe(g: BluetoothGatt): Boolean {
        val c = stateChar ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val d = c.getDescriptor(Protocol.CCCD) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            writeDescriptorLegacy(g, d, value)
        }
    }

    private fun write(g: BluetoothGatt, text: String): Boolean {
        val c = commandChar ?: return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            writeCharacteristicLegacy(g, c, bytes, type)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorLegacy(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean {
        d.value = value
        return g.writeDescriptor(d)
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicLegacy(
        g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: ByteArray, type: Int,
    ): Boolean {
        c.value = bytes
        c.writeType = type
        return g.writeCharacteristic(c)
    }

    /** STATE and DATA reads both end here. */
    private fun onRead(uuid: UUID, value: ByteArray, status: Int) {
        val op = current
        complete()
        when {
            op is Op.ReadData -> op.onResult(value.takeIf { status == BluetoothGatt.GATT_SUCCESS })
            uuid == Protocol.STATE && status == BluetoothGatt.GATT_SUCCESS -> onStateBytes(value, fromRead = true)
        }
    }

    // --- Cover art and queue ----------------------------------------------------------

    /** Fetches what the state announces and is not here yet: the cover first, then the queue. */
    private fun fetchMissing() {
        if (!authorized || transfer != null || dataChar == null) return
        val state = snapshot.state ?: return
        if (state.version < 3) return
        val artId = state.artId
        if (artId != null && artId !in artCache && tried[Blob.ART] != artId) return startTransfer(Blob.ART, artId)
        val queueId = state.queueId
        if (queueId != null && queueId != loadedQueueId && tried[Blob.QUEUE] != queueId) startTransfer(Blob.QUEUE, queueId)
    }

    private fun startTransfer(blob: Blob, id: String) {
        tried[blob] = id
        transfer = Transfer(blob)
        // Shorter connection interval while pages flow; back to balanced when done.
        runCatching { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        requestPage(0)
    }

    private fun requestPage(index: Int) {
        val t = transfer ?: return
        enqueue(Op.Write(Command.GetPage(t.blob, index).wire) { status ->
            if (transfer !== t) return@Write
            if (status != Protocol.OK) return@Write finishTransfer(null)
            enqueue(Op.ReadData { bytes -> onPage(t, index, bytes) })
        })
    }

    private fun onPage(t: Transfer, index: Int, bytes: ByteArray?) {
        if (transfer !== t) return
        val page = bytes?.let(Protocol::decodePage)
        val fits = page != null && page.blob == t.blob && page.index == index &&
            (index == 0 || (page.count == t.count && page.id == t.id))
        if (!fits) return finishTransfer(null)
        if (index == 0) {
            t.count = page!!.count
            t.id = page.id
        }
        t.bytes.write(page!!.payload)
        if (index + 1 < t.count) requestPage(index + 1) else finishTransfer(t)
    }

    private fun finishTransfer(done: Transfer?) {
        transfer = null
        runCatching { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
        val bytes = done?.bytes?.toByteArray()
        // The content may have changed since the state was sent; keep it under its own id.
        if (done != null && bytes != null && Protocol.blobId(bytes) == done.id) {
            val id = Protocol.idText(done.id)
            when (done.blob) {
                Blob.ART -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { artCache[id] = it }
                Blob.QUEUE -> Protocol.decodeQueue(bytes)?.let {
                    loadedQueue = it
                    loadedQueueId = id
                }
            }
        }
        update() // shows the new cover/queue and fetches whatever is still missing
    }

    private fun onStateBytes(bytes: ByteArray, fromRead: Boolean) {
        val message = Protocol.decodeState(bytes) ?: return
        message.state?.let { update(state = it) }
        // Shortened to fit the packet: fetch the full value with a (long) read.
        if (message.truncated && !fromRead && current != Op.Read && Op.Read !in queue) enqueue(Op.Read)
    }

    /** Runs [block] on the main thread, ignoring callbacks of an already closed connection. */
    private fun post(g: BluetoothGatt, block: () -> Unit) {
        handler.post { if (g === gatt) block() }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = post(g) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                handler.removeCallbacks(connectTimeout)
                enqueue(Op.Mtu)
                enqueue(Op.Discover)
            } else {
                fail()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = post(g) { complete() }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = post(g) {
            val service = g.getService(Protocol.SERVICE)
            stateChar = service?.getCharacteristic(Protocol.STATE)
            commandChar = service?.getCharacteristic(Protocol.COMMAND)
            dataChar = service?.getCharacteristic(Protocol.DATA) // v3 players only
            if (status != BluetoothGatt.GATT_SUCCESS || stateChar == null || commandChar == null) {
                fail()
                return@post
            }
            complete()
            enqueue(Op.Subscribe)
            enqueue(Op.Read)
            if (!authorized) authenticate()
        }

        /**
         * Another app on the player phone added or removed a GATT service (Spotify does this
         * after logging in). Android then drops our notification subscription while writes keep
         * working, so the screen would freeze: discover again and resubscribe. Android 12+.
         */
        override fun onServiceChanged(g: BluetoothGatt) = post(g) {
            if (current != Op.Discover && Op.Discover !in queue) enqueue(Op.Discover)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = post(g) {
            if (status == BluetoothGatt.GATT_SUCCESS) complete() else fail()
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) = post(g) { onRead(characteristic.uuid, value, status) }

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            val value = characteristic.value ?: ByteArray(0) // copy now, the object is reused
            val uuid = characteristic.uuid
            post(g) { onRead(uuid, value, status) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) = post(g) { onStateBytes(value, fromRead = false) }

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            val value = characteristic.value ?: ByteArray(0)
            post(g) { onStateBytes(value, fromRead = false) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            post(g) {
                val op = current as? Op.Write
                complete()
                op?.onResult?.invoke(status)
            }
    }

    private fun bluetoothReceiver() = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    handler.removeCallbacks(retryRunnable)
                    stopScan()
                    teardown()
                    if (wanted) update(phase = Phase.BLUETOOTH_OFF)
                }
                BluetoothAdapter.STATE_ON -> if (wanted) {
                    failures = 0
                    connectNow()
                }
            }
        }
    }

    private fun update(
        phase: Phase = snapshot.phase,
        deviceName: String? = snapshot.deviceName,
        state: PlayerState? = snapshot.state,
    ) {
        val stateTime = if (state != snapshot.state) SystemClock.elapsedRealtime() else snapshot.stateTime
        val art = state?.artId?.let { artCache[it] }
        // Keep showing the last queue while a changed one is on its way.
        val queue = if (state?.queueId != null) loadedQueue else null
        val next = Snapshot(phase, deviceName, state, stateTime, art, queue)
        if (next != snapshot) {
            snapshot = next
            listeners.toList().forEach { it(next) }
        }
        fetchMissing()
    }
}
