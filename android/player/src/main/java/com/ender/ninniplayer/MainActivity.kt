package com.ender.ninniplayer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat

/** Setup and status screen. Dark on purpose: this phone sits next to a sleeping child. */
class MainActivity : Activity() {

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }

    private lateinit var pinText: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var startStop: Button
    private lateinit var nowPlaying: TextView
    private lateinit var levels: TextView
    private lateinit var extras: TextView
    private lateinit var checkBluetooth: TextView
    private lateinit var btnBluetooth: Button
    private lateinit var checkPermission: TextView
    private lateinit var btnPermission: Button
    private lateinit var checkAdvertising: TextView
    private lateinit var checkNotification: TextView
    private lateinit var btnNotification: Button
    private lateinit var restrictedHint: TextView
    private lateinit var checkBattery: TextView
    private lateinit var btnBattery: Button
    private lateinit var deviceInfo: TextView

    private var startAfterPermission = false
    private val statusListener: (Status.Snapshot) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pinText = findViewById(R.id.pinText)
        serviceStatus = findViewById(R.id.serviceStatus)
        startStop = findViewById(R.id.startStop)
        nowPlaying = findViewById(R.id.nowPlaying)
        levels = findViewById(R.id.levels)
        extras = findViewById(R.id.extras)
        checkBluetooth = findViewById(R.id.checkBluetooth)
        btnBluetooth = findViewById(R.id.btnBluetooth)
        checkPermission = findViewById(R.id.checkPermission)
        btnPermission = findViewById(R.id.btnPermission)
        checkAdvertising = findViewById(R.id.checkAdvertising)
        checkNotification = findViewById(R.id.checkNotification)
        btnNotification = findViewById(R.id.btnNotification)
        restrictedHint = findViewById(R.id.restrictedHint)
        checkBattery = findViewById(R.id.checkBattery)
        btnBattery = findViewById(R.id.btnBattery)
        deviceInfo = findViewById(R.id.deviceInfo)

        startStop.setOnClickListener {
            if (Status.current.running) {
                PlayerService.stop(this)
            } else if (PlayerService.hasBluetoothPermission(this)) {
                PlayerService.start(this)
            } else {
                startAfterPermission = true
                requestBluetoothPermission()
            }
        }
        findViewById<Button>(R.id.newPin).setOnClickListener {
            Prefs.newPin(this)
            PlayerService.instance?.onPinChanged()
            render()
        }
        btnBluetooth.setOnClickListener { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        btnPermission.setOnClickListener { requestBluetoothPermission() }
        btnNotification.setOnClickListener { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        btnBattery.setOnClickListener { requestBatteryExemption() }

        deviceInfo.text = getString(
            R.string.device_info, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
        )
    }

    override fun onResume() {
        super.onResume()
        Status.observe(statusListener)
        PlayerService.instance?.refresh()
        // The service was on but got killed: bring it back when the screen is opened.
        if (!Status.current.running && Prefs.enabled(this) && PlayerService.hasBluetoothPermission(this)) {
            PlayerService.start(this)
        }
    }

    override fun onPause() {
        Status.remove(statusListener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (startAfterPermission && PlayerService.hasBluetoothPermission(this)) PlayerService.start(this)
        startAfterPermission = false
        PlayerService.instance?.refresh()
        render()
    }

    private fun render() {
        val status = Status.current
        pinText.text = Prefs.pin(this).toCharArray().joinToString(" ")

        serviceStatus.text = when {
            !status.running -> getString(R.string.status_stopped)
            status.problem != null -> problemText(status)
            status.advertising -> getString(R.string.status_running, status.authorized)
            else -> getString(R.string.status_starting)
        }
        startStop.setText(if (status.running) R.string.stop else R.string.start)

        val state = status.state
        nowPlaying.text = when {
            state == null -> getString(R.string.status_stopped)
            state.title != null -> listOfNotNull(state.title, state.artist).joinToString(" — ")
            !state.mediaAccess -> getString(R.string.needs_access)
            else -> getString(R.string.nothing_playing)
        }
        levels.visibility = if (state == null) View.GONE else View.VISIBLE
        if (state != null) {
            val playing = getString(if (state.playing) R.string.playing else R.string.paused)
            val charging = if (state.charging) getString(R.string.charging) else ""
            levels.text = getString(R.string.levels, playing, state.volume, state.maxVolume, state.battery, charging)
        }
        val extraLines = listOfNotNull(
            state?.volumeCap?.let { getString(R.string.extra_cap, it, state.maxVolume) },
            state?.sleep?.let {
                val minutes = (it.remainingSeconds + 59) / 60
                val fading = it.fadeSeconds > 0 && it.remainingSeconds <= it.fadeSeconds
                getString(if (fading) R.string.extra_sleep_fading else R.string.extra_sleep, minutes)
            },
        )
        extras.text = extraLines.joinToString("\n")
        extras.visibility = if (extraLines.isEmpty()) View.GONE else View.VISIBLE

        renderChecks()
    }

    @SuppressLint("MissingPermission") // isEnabled / isMultipleAdvertisementSupported need no runtime permission.
    private fun renderChecks() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bluetoothOn = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        check(checkBluetooth, btnBluetooth, R.string.check_bluetooth, bluetoothOn)

        val permission = PlayerService.hasBluetoothPermission(this)
        check(checkPermission, btnPermission, R.string.check_permission, permission)
        checkPermission.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) View.VISIBLE else View.GONE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) btnPermission.visibility = View.GONE

        if (bluetoothOn) {
            val supported = runCatching { adapter?.isMultipleAdvertisementSupported == true }.getOrDefault(false) &&
                Status.current.problem != Status.Problem.ADVERTISING_UNSUPPORTED
            check(checkAdvertising, null, R.string.check_advertising, supported)
        } else {
            checkAdvertising.text = getString(R.string.check_advertising_unknown)
            checkAdvertising.setTextColor(getColor(R.color.dim))
        }

        val access = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        check(checkNotification, btnNotification, R.string.check_notification, access)
        restrictedHint.visibility = if (!access && Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE

        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        check(checkBattery, btnBattery, R.string.check_battery, exempt)
    }

    private fun check(label: TextView, button: Button?, text: Int, ok: Boolean) {
        label.text = getString(if (ok) R.string.check_ok else R.string.check_missing, getString(text))
        label.setTextColor(getColor(if (ok) R.color.ok else R.color.bad))
        button?.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun problemText(status: Status.Snapshot): String = when (status.problem) {
        Status.Problem.NO_BLUETOOTH -> getString(R.string.problem_no_bluetooth)
        Status.Problem.BLUETOOTH_OFF -> getString(R.string.problem_bluetooth_off)
        Status.Problem.NO_PERMISSION -> getString(R.string.problem_no_permission)
        Status.Problem.ADVERTISING_UNSUPPORTED -> getString(R.string.problem_adv_unsupported)
        Status.Problem.ADVERTISING_FAILED -> getString(R.string.problem_adv_failed, status.errorCode)
        Status.Problem.SERVER_FAILED -> getString(R.string.problem_server_failed)
        null -> ""
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val permissions = mutableListOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions(permissions.toTypedArray(), REQUEST_PERMISSIONS)
    }

    @SuppressLint("BatteryLife") // Sideloaded single-purpose device; the exemption is the point.
    private fun requestBatteryExemption() {
        open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }
    }
}
