package com.ender.ninniplayer

import com.ender.ninni.protocol.Command
import com.ender.ninni.protocol.PlayerState
import com.ender.ninni.protocol.Protocol
import com.ender.ninni.protocol.Sleep
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that keeps the BLE server alive while the screen is off.
 * Collects the state (track, volume, battery), publishes it and runs commands.
 * Everything runs on the main thread.
 */
class PlayerService : Service() {

    companion object {
        private const val CHANNEL = "service"
        private const val NOTIFICATION_ID = 1
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val STREAM = AudioManager.STREAM_MUSIC

        var instance: PlayerService? = null
            private set

        fun start(context: Context) {
            Prefs.setEnabled(context, true)
            ContextCompat.startForegroundService(context, Intent(context, PlayerService::class.java))
        }

        fun stop(context: Context) {
            Prefs.setEnabled(context, false)
            context.stopService(Intent(context, PlayerService::class.java))
        }

        fun hasBluetoothPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            ).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var media: MediaWatcher
    private lateinit var ble: BleServer
    private lateinit var sleepTimer: SleepTimer
    private var started = false
    private var battery = -1
    private var charging = false
    /** Highest music volume step allowed; null = no limit. */
    private var volumeCap: Int? = null
    private var lastState: PlayerState? = null

    private val pushRunnable = Runnable { pushState() }

    /** Safety net for changes without a callback (e.g. playback without notification access). */
    private val tick = object : Runnable {
        override fun run() {
            pushState()
            handler.postDelayed(this, 5_000)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    readBattery(intent)
                    scheduleUpdate()
                }
                VOLUME_CHANGED -> {
                    enforceVolumeCap() // also catches the player phone's own volume keys
                    scheduleUpdate()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> startBle()
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        ble.stop()
                        Status.update { it.copy(problem = Status.Problem.BLUETOOTH_OFF, advertising = false) }
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!startInForeground()) {
            stopSelf()
            return
        }
        instance = this
        audio = getSystemService(AudioManager::class.java)
        media = MediaWatcher(this, handler, ::scheduleUpdate)
        ble = BleServer(this, handler, ::execute)
        sleepTimer = SleepTimer(this, handler, audio, media::pause, ::volumeLimit, ::scheduleUpdate)
        volumeCap = Prefs.volumeCap(this)
        enforceVolumeCap()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(VOLUME_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // All three are protected system broadcasts. EXPORTED because on Android < 13 the
        // NOT_EXPORTED compat path adds a sender permission the Bluetooth process lacks.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            ?.let(::readBattery)

        started = true
        Status.update { it.copy(running = true) }
        media.start()
        pushState()
        startBle()
        handler.postDelayed(tick, 5_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * Diagnostics over adb (dumpsys needs the DUMP permission, so only adb/system can call it):
     *   adb shell dumpsys activity service com.ender.ninniplayer/.PlayerService
     *   adb shell dumpsys activity service com.ender.ninniplayer/.PlayerService cmd shuffle:1
     * The second form runs a protocol command as if an authorized remote had sent it.
     */
    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        if (args != null && args.size >= 2 && args[0] == "cmd") {
            val command = Protocol.parseCommand(args[1].toByteArray())
            if (command == null) {
                writer.println("unknown command: ${args[1]}")
            } else {
                // Services are dumped on the main thread already; post only if that ever changes.
                val result = if (Looper.myLooper() == Looper.getMainLooper()) {
                    execute(command)
                } else {
                    val box = AtomicInteger(-1)
                    val done = CountDownLatch(1)
                    handler.post {
                        box.set(execute(command))
                        done.countDown()
                    }
                    done.await(3, TimeUnit.SECONDS)
                    box.get()
                }
                writer.println("$command -> 0x${Integer.toHexString(result)}")
            }
        }
        if (started) pushState()
        writer.println("state=${lastState?.let { String(Protocol.encodeState(it)) }}")
        writer.println("status=${Status.current.copy(state = null)}")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (started) {
            runCatching { unregisterReceiver(receiver) }
            sleepTimer.release()
            ble.stop()
            media.stop()
        }
        if (instance === this) instance = null
        Status.update { Status.Snapshot() }
        super.onDestroy()
    }

    /** Re-checks permissions and notification access, e.g. when the screen comes back. */
    fun refresh() {
        if (!started) return
        media.start()
        startBle()
        scheduleUpdate()
    }

    fun onPinChanged() {
        if (started) ble.revokeAll()
    }

    private fun startBle() {
        if (ble.isRunning) return
        if (!hasBluetoothPermission(this)) {
            Status.update { it.copy(problem = Status.Problem.NO_PERMISSION, advertising = false) }
            return
        }
        ble.start()
        lastState?.let(ble::publish)
    }

    private fun execute(command: Command): Int {
        val done = try {
            run(command)
        } catch (e: Exception) {
            false
        }
        scheduleUpdate()
        return if (done) android.bluetooth.BluetoothGatt.GATT_SUCCESS else Protocol.ERR_FAILED
    }

    /** False when the player cannot do it (e.g. the music app does not support seeking). */
    private fun run(command: Command): Boolean {
        when (command) {
            Command.Play -> media.play()
            Command.Pause -> media.pause()
            Command.Toggle -> media.toggle()
            Command.Next -> media.next()
            Command.Previous -> media.previous()
            Command.Forward -> return media.skip(Protocol.SKIP_SECONDS * 1_000L)
            Command.Rewind -> return media.skip(-Protocol.SKIP_SECONDS * 1_000L)
            is Command.Seek -> return media.seekTo(command.positionMs)
            is Command.SetShuffle -> return media.setShuffle(command.on)
            is Command.SetRepeat -> return media.setRepeat(command.mode)
            // Flags 0: no on-screen volume panel and no beep.
            Command.VolumeUp -> {
                if (audio.getStreamVolume(STREAM) < volumeLimit()) {
                    audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_RAISE, 0)
                }
                sleepTimer.onVolumeSetByUser()
            }
            Command.VolumeDown -> {
                audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_LOWER, 0)
                sleepTimer.onVolumeSetByUser()
            }
            is Command.SetVolume -> {
                val level = command.level.coerceIn(audio.getStreamMinVolume(STREAM), volumeLimit())
                audio.setStreamVolume(STREAM, level, 0)
                sleepTimer.onVolumeSetByUser()
            }
            is Command.SetVolumeCap -> setVolumeCap(command.level)
            is Command.StartSleep -> sleepTimer.start(command.seconds * 1_000L, command.fadeSeconds * 1_000L)
            Command.CancelSleep -> sleepTimer.cancel()
            is Command.Auth, Command.Ping -> Unit
        }
        return true
    }

    /** The cap, or the phone's maximum when there is none. */
    private fun volumeLimit(): Int = volumeCap ?: audio.getStreamMaxVolume(STREAM)

    private fun setVolumeCap(level: Int?) {
        // A cap at or above the maximum is no cap; 0 would silence the music for good.
        volumeCap = level?.takeIf { it < audio.getStreamMaxVolume(STREAM) }?.coerceAtLeast(1)
        Prefs.setVolumeCap(this, volumeCap)
        enforceVolumeCap()
    }

    /** Only ever lowers the volume, never makes the music louder. */
    private fun enforceVolumeCap() {
        val cap = volumeCap ?: return
        if (audio.getStreamVolume(STREAM) > cap) audio.setStreamVolume(STREAM, cap, 0)
    }

    private fun scheduleUpdate() {
        handler.removeCallbacks(pushRunnable)
        handler.postDelayed(pushRunnable, 120)
    }

    private fun pushState() {
        if (!started) return
        val info = media.info()
        val state = PlayerState(
            playing = info.playing ?: audio.isMusicActive,
            title = info.title,
            artist = info.artist,
            volume = audio.getStreamVolume(STREAM),
            maxVolume = audio.getStreamMaxVolume(STREAM),
            battery = battery,
            charging = charging,
            mediaAccess = media.hasAccess,
            duration = info.duration,
            position = info.position,
            shuffle = info.shuffle,
            repeat = info.repeat,
            volumeCap = volumeCap,
            sleep = sleepTimer.remainingMs()?.let { remaining ->
                Sleep(((remaining + 999) / 1_000).toInt(), (sleepTimer.fadeLengthMs / 1_000).toInt())
            },
        )
        if (state == lastState) return
        lastState = state
        ble.publish(state)
        Status.update { it.copy(state = state) }
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) battery = level * 100 / scale
        charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun startInForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        nm.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
