package com.ender.ninnimanager

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.ender.ninni.protocol.Command
import com.ender.ninni.protocol.PlayerState
import com.ender.ninni.protocol.Protocol
import com.ender.ninni.protocol.QueueItem
import com.ender.ninni.protocol.RepeatMode
import com.ender.ninnimanager.PlayerConnection.Phase
import java.util.Locale

/** The remote control. Dark, big buttons; the volume keys control the player's volume. */
class MainActivity : Activity() {

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        val SLEEP_MINUTES = listOf(10, 20, 30, 45, 60)
        /** The sleep timer fades out during its last 10 minutes (or all of a shorter timer). */
        const val FADE_SECONDS = 600
    }

    private val connection get() = (application as NinniApp).connection
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var queueButton: Button
    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var progressGroup: View
    private lateinit var positionBar: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var durationText: TextView
    private lateinit var prev: ImageButton
    private lateinit var toggle: ImageButton
    private lateinit var next: ImageButton
    private lateinit var extraControls: View
    private lateinit var shuffle: ImageButton
    private lateinit var rewind: Button
    private lateinit var forward: Button
    private lateinit var repeat: ImageButton
    private lateinit var volDown: ImageButton
    private lateinit var volUp: ImageButton
    private lateinit var volumeText: TextView
    private lateinit var volumeSteps: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var capRow: View
    private lateinit var capText: TextView
    private lateinit var capDown: ImageButton
    private lateinit var capUp: ImageButton
    private lateinit var sleepCard: View
    private lateinit var sleepOff: View
    private lateinit var sleepOn: View
    private lateinit var sleepFade: CheckBox
    private lateinit var sleepRemaining: TextView
    private lateinit var sleepDetail: TextView
    private lateinit var battery: TextView
    private lateinit var warning: TextView
    private lateinit var controls: List<View>

    private var pinDialog: AlertDialog? = null
    private var queueDialog: AlertDialog? = null
    private var queueAdapter: QueueAdapter? = null
    private var draggingVolume = false
    private var draggingPosition = false
    private val listener: (PlayerConnection.Snapshot) -> Unit = { render(it) }

    /** Counts the track position and the sleep timer on between state updates. */
    private val clockTick = object : Runnable {
        override fun run() {
            renderClock()
            handler.postDelayed(this, 1_000)
        }
    }

    private val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        queueButton = findViewById(R.id.queueButton)
        cover = findViewById(R.id.cover)
        cover.clipToOutline = true // rounded corners from the background (the XML attribute is API 31+)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        progressGroup = findViewById(R.id.progressGroup)
        positionBar = findViewById(R.id.positionBar)
        elapsed = findViewById(R.id.elapsed)
        durationText = findViewById(R.id.duration)
        prev = findViewById(R.id.prev)
        toggle = findViewById(R.id.toggle)
        next = findViewById(R.id.next)
        extraControls = findViewById(R.id.extraControls)
        shuffle = findViewById(R.id.shuffle)
        rewind = findViewById(R.id.rewind)
        forward = findViewById(R.id.forward)
        repeat = findViewById(R.id.repeat)
        volDown = findViewById(R.id.volDown)
        volUp = findViewById(R.id.volUp)
        volumeText = findViewById(R.id.volumeText)
        volumeSteps = findViewById(R.id.volumeSteps)
        volumeBar = findViewById(R.id.volumeBar)
        capRow = findViewById(R.id.capRow)
        capText = findViewById(R.id.capText)
        capDown = findViewById(R.id.capDown)
        capUp = findViewById(R.id.capUp)
        sleepCard = findViewById(R.id.sleepCard)
        sleepOff = findViewById(R.id.sleepOff)
        sleepOn = findViewById(R.id.sleepOn)
        sleepFade = findViewById(R.id.sleepFade)
        sleepRemaining = findViewById(R.id.sleepRemaining)
        sleepDetail = findViewById(R.id.sleepDetail)
        battery = findViewById(R.id.battery)
        warning = findViewById(R.id.warning)
        val sleepChoices = createSleepChoices(findViewById(R.id.sleepChoices))
        val sleepCancel = findViewById<View>(R.id.sleepCancel)
        controls = listOf(
            prev, toggle, next, positionBar, shuffle, rewind, forward, repeat, volDown, volUp, volumeBar,
            sleepFade, sleepCancel,
        ) + sleepChoices

        queueButton.setOnClickListener { showQueue() }
        prev.setOnClickListener { connection.send(Command.Previous) }
        toggle.setOnClickListener { connection.send(Command.Toggle) }
        next.setOnClickListener { connection.send(Command.Next) }
        rewind.text = "−${Protocol.SKIP_SECONDS}"
        rewind.contentDescription = getString(R.string.rewind, Protocol.SKIP_SECONDS)
        rewind.setOnClickListener { connection.send(Command.Rewind) }
        forward.text = "+${Protocol.SKIP_SECONDS}"
        forward.contentDescription = getString(R.string.forward, Protocol.SKIP_SECONDS)
        forward.setOnClickListener { connection.send(Command.Forward) }
        shuffle.setOnClickListener {
            val on = connection.snapshot.state?.shuffle ?: return@setOnClickListener
            connection.send(Command.SetShuffle(!on))
        }
        repeat.setOnClickListener {
            val mode = connection.snapshot.state?.repeat ?: return@setOnClickListener
            connection.send(Command.SetRepeat(mode.next))
        }
        positionBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {
                draggingPosition = true
            }

            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) elapsed.text = clock(progress * 1_000L)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                draggingPosition = false
                connection.send(Command.Seek(bar.progress * 1_000L))
            }
        })

        volDown.setOnClickListener { connection.send(Command.VolumeDown) }
        volUp.setOnClickListener { connection.send(Command.VolumeUp) }
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {
                draggingVolume = true
            }

            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) showVolume(progress, bar.max)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                draggingVolume = false
                connection.send(Command.SetVolume(bar.progress))
            }
        })
        capDown.setOnClickListener {
            val state = connection.snapshot.state ?: return@setOnClickListener
            // From "no limit" the first step down is one below the maximum.
            val level = (state.volumeCap ?: state.maxVolume) - 1
            if (level >= 1) connection.send(Command.SetVolumeCap(level))
        }
        capUp.setOnClickListener {
            val state = connection.snapshot.state ?: return@setOnClickListener
            val cap = state.volumeCap ?: return@setOnClickListener
            connection.send(Command.SetVolumeCap(if (cap + 1 >= state.maxVolume) null else cap + 1))
        }

        sleepFade.isChecked = Prefs.fade(this)
        sleepFade.setOnCheckedChangeListener { _, checked -> Prefs.setFade(this, checked) }
        sleepCancel.setOnClickListener { connection.send(Command.CancelSleep) }

        status.setOnClickListener {
            if (hasPermissions()) connection.connectNow() else requestPermissions(permissions, REQUEST_PERMISSIONS)
        }
        findViewById<View>(R.id.pinButton).setOnClickListener { showPinDialog(wrong = false) }
    }

    override fun onStart() {
        super.onStart()
        connection.observe(listener)
        handler.post(clockTick)
        if (hasPermissions()) {
            connection.start()
        } else {
            render(connection.snapshot)
            requestPermissions(permissions, REQUEST_PERMISSIONS)
        }
    }

    override fun onStop() {
        handler.removeCallbacks(clockTick)
        connection.remove(listener)
        connection.stopSoon()
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && hasPermissions()) connection.start()
        render(connection.snapshot)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val command = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> Command.VolumeUp
            KeyEvent.KEYCODE_VOLUME_DOWN -> Command.VolumeDown
            else -> null
        }
        if (command != null && connection.snapshot.phase == Phase.CONNECTED) {
            connection.send(command)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val volumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (volumeKey && connection.snapshot.phase == Phase.CONNECTED) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun hasPermissions() = permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun createSleepChoices(row: LinearLayout): List<View> {
        val density = resources.displayMetrics.density
        return SLEEP_MINUTES.map { minutes ->
            Button(this, null, 0, R.style.Chip).apply {
                text = getString(R.string.minutes_short, minutes)
                layoutParams = LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f).apply {
                    marginStart = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                }
                setOnClickListener {
                    val seconds = minutes * 60
                    val fade = if (sleepFade.isChecked) minOf(FADE_SECONDS, seconds) else 0
                    connection.send(Command.StartSleep(seconds, fade))
                }
                row.addView(this)
            }
        }
    }

    private fun render(snapshot: PlayerConnection.Snapshot) {
        val connected = snapshot.phase == Phase.CONNECTED
        status.text = when {
            !hasPermissions() -> getString(R.string.phase_no_permission)
            else -> when (snapshot.phase) {
                Phase.IDLE, Phase.SCANNING -> getString(R.string.phase_scanning)
                Phase.BLUETOOTH_OFF -> getString(R.string.phase_bluetooth_off)
                Phase.NOT_FOUND -> getString(R.string.phase_not_found)
                Phase.CONNECTING -> getString(R.string.phase_connecting)
                Phase.NEED_PIN -> getString(R.string.phase_need_pin)
                Phase.WRONG_PIN -> getString(R.string.phase_wrong_pin)
                Phase.CONNECTED -> getString(R.string.phase_connected, snapshot.deviceName ?: getString(R.string.player_name))
            }
        }
        status.setTextColor(getColor(if (connected) R.color.ok else R.color.dim))
        controls.forEach { setEnabled(it, connected) }
        // Keep the screen on while connected, so the remote is ready between taps.
        if (connected) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val state = snapshot.state
        title.text = state?.title ?: getString(R.string.no_track)
        artist.text = state?.artist.orEmpty()
        artist.visibility = if (state?.artist == null) View.GONE else View.VISIBLE
        toggle.setImageResource(if (state?.playing == true) R.drawable.ic_pause else R.drawable.ic_play)
        toggle.contentDescription = getString(if (state?.playing == true) R.string.pause else R.string.play)
        renderCover(snapshot)
        renderProgress(state)

        if (state != null) {
            volumeBar.max = state.maxVolume
            if (!draggingVolume) {
                volumeBar.progress = state.volume
                showVolume(state.volume, state.maxVolume)
            }
            battery.visibility = if (state.battery >= 0) View.VISIBLE else View.GONE
            battery.text = getString(if (state.charging) R.string.battery_charging else R.string.battery, state.battery)
            val low = state.battery in 0..19 && !state.charging
            battery.setTextColor(getColor(if (low) R.color.bad else R.color.dim))
            warning.visibility = if (state.mediaAccess) View.GONE else View.VISIBLE
        } else {
            volumeText.text = getString(R.string.volume_unknown)
            volumeSteps.text = ""
            battery.visibility = View.GONE
            warning.visibility = View.GONE
        }
        renderVolumeCap(state, connected)
        renderSleep(state)
        renderClock()

        when (snapshot.phase) {
            Phase.NEED_PIN -> showPinDialog(wrong = false)
            Phase.WRONG_PIN -> showPinDialog(wrong = true)
            else -> Unit
        }
    }

    /**
     * The cover box stays (empty) while a cover is loading, so the layout does not jump; the
     * playlist button shows once a queue has arrived.
     */
    private fun renderCover(snapshot: PlayerConnection.Snapshot) {
        val state = snapshot.state
        cover.visibility = if (state?.artId != null) View.VISIBLE else View.GONE
        cover.setImageBitmap(snapshot.art)
        queueButton.visibility = if (snapshot.queue != null) View.VISIBLE else View.GONE
        if (snapshot.queue == null) {
            queueDialog?.dismiss()
        } else {
            queueAdapter?.show(snapshot.queue, snapshot.queueActive)
            queueDialog?.setTitle(snapshot.queue.title ?: getString(R.string.queue))
        }
    }

    private fun showQueue() {
        if (queueDialog?.isShowing == true || isFinishing) return
        val snapshot = connection.snapshot
        val queue = snapshot.queue ?: return
        val adapter = QueueAdapter(this).apply { show(queue, snapshot.queueActive) }
        val list = ListView(this).apply {
            this.adapter = adapter
            divider = null
            setOnItemClickListener { _, _, position, _ -> playItem(adapter.getItem(position)) }
        }
        queueAdapter = adapter
        queueDialog = AlertDialog.Builder(this, R.style.Dialog)
            .setTitle(queue.title ?: getString(R.string.queue))
            .setView(list)
            .setNegativeButton(R.string.close, null)
            .setOnDismissListener {
                queueDialog = null
                queueAdapter = null
            }
            .show()
        // Open with the track playing now in view, one row of context above it.
        adapter.activePosition().takeIf { it > 0 }?.let { list.setSelection(it - 1) }
    }

    private fun playItem(item: QueueItem) {
        val snapshot = connection.snapshot
        val queueId = snapshot.queueId ?: return
        if (snapshot.phase != Phase.CONNECTED) return
        connection.send(Command.PlayItem(queueId, item.id)) { status ->
            if (status == Protocol.ERR_FAILED) Toast.makeText(this, R.string.queue_failed, Toast.LENGTH_SHORT).show()
        }
        queueDialog?.dismiss()
    }

    /** Progress bar, shuffle, ±15 s and repeat; each only when the music app supports it. */
    private fun renderProgress(state: PlayerState?) {
        val duration = state?.duration
        val seekable = state?.position != null
        progressGroup.visibility = if (duration != null && seekable) View.VISIBLE else View.GONE
        if (duration != null) {
            positionBar.max = (duration / 1_000).toInt()
            durationText.text = clock(duration)
        }

        val shuffleOn = state?.shuffle
        shuffle.visibility = if (shuffleOn != null) View.VISIBLE else View.GONE
        shuffle.imageTintList = tint(shuffleOn == true)
        rewind.visibility = if (seekable) View.VISIBLE else View.GONE
        forward.visibility = rewind.visibility
        val mode = state?.repeat
        repeat.visibility = if (mode != null) View.VISIBLE else View.GONE
        repeat.setImageResource(if (mode == RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeat.imageTintList = tint(mode != null && mode != RepeatMode.OFF)
        val any = shuffleOn != null || seekable || mode != null
        extraControls.visibility = if (any) View.VISIBLE else View.GONE
    }

    private fun renderVolumeCap(state: PlayerState?, connected: Boolean) {
        capRow.visibility = if (state != null && state.version >= 2) View.VISIBLE else View.GONE
        if (state == null) return
        val cap = state.volumeCap
        capText.text = if (cap == null) {
            getString(R.string.cap_off)
        } else {
            getString(R.string.cap_value, percent(cap, state.maxVolume), cap)
        }
        capText.setTextColor(getColor(if (cap == null) R.color.dim else R.color.moon))
        setEnabled(capDown, connected && (cap == null || cap > 1))
        setEnabled(capUp, connected && cap != null)
    }

    private fun renderSleep(state: PlayerState?) {
        sleepCard.visibility = if (state != null && state.version >= 2) View.VISIBLE else View.GONE
        val on = state?.sleep != null
        sleepOn.visibility = if (on) View.VISIBLE else View.GONE
        sleepOff.visibility = if (on) View.GONE else View.VISIBLE
    }

    /** Parts that change every second: track position and sleep timer countdown. */
    private fun renderClock() {
        val snapshot = connection.snapshot
        val state = snapshot.state ?: return
        val live = snapshot.phase == Phase.CONNECTED
        val since = if (live) SystemClock.elapsedRealtime() - snapshot.stateTime else 0L

        val position = state.position
        if (position != null && !draggingPosition) {
            val now = if (state.playing) position + since else position
            val shown = state.duration?.let { now.coerceAtMost(it) } ?: now
            positionBar.progress = (shown / 1_000).toInt()
            elapsed.text = clock(shown)
        }

        val sleep = state.sleep ?: return
        val remaining = (sleep.remainingSeconds * 1_000L - since).coerceAtLeast(0)
        val fade = sleep.fadeSeconds * 1_000L
        sleepRemaining.text = clock(remaining)
        sleepDetail.text = when {
            fade == 0L -> getString(R.string.sleep_stop)
            remaining > fade -> getString(R.string.sleep_fade_in, clock(remaining - fade))
            else -> getString(R.string.sleep_fading)
        }
    }

    /**
     * Shown as a percentage so every phone reads the same, with the real step underneath:
     * the player can only move in whole steps (e.g. 15 steps ≈ 7% each).
     */
    private fun showVolume(step: Int, maxStep: Int) {
        volumeText.text = getString(R.string.volume_value, percent(step, maxStep))
        volumeSteps.text = getString(R.string.volume_steps, step, maxStep)
    }

    private fun percent(step: Int, maxStep: Int) = if (maxStep > 0) Math.round(step * 100f / maxStep) else 0

    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.35f
    }

    private fun tint(active: Boolean) = ColorStateList.valueOf(getColor(if (active) R.color.moon else R.color.dim))

    /** 3:07, or 1:02:07 from an hour on. */
    private fun clock(ms: Long): String {
        val total = ms / 1_000
        val h = total / 3_600
        val m = total % 3_600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    private fun showPinDialog(wrong: Boolean) {
        if (pinDialog?.isShowing == true || isFinishing) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
            textSize = 28f
            letterSpacing = 0.3f
            setText(Prefs.pin(this@MainActivity).orEmpty())
            setSelection(text.length)
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        pinDialog = AlertDialog.Builder(this, R.style.Dialog)
            .setTitle(R.string.pin_title)
            .setMessage(if (wrong) R.string.pin_wrong else R.string.pin_message)
            .setView(container)
            .setPositiveButton(R.string.connect) { _, _ ->
                val pin = input.text.toString()
                if (pin.length == 4) connection.submitPin(pin)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
        pinDialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
}
