package com.deansak.powernap

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.deansak.powernap.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    // ── View binding ──────────────────────────────────────────────────────────

    private lateinit var binding: ActivityMainBinding

    // ── App state ─────────────────────────────────────────────────────────────

    private var totalDurationMs = 0L
    private var isRunning       = false
    private var isAlarmRinging  = false

    private var selectedSoundUri: Uri? = null
    private var sounds       = listOf<Pair<String, Uri>>()
    private var soundButtons = listOf<MaterialButton>()

    private val rampOptions = listOf(0L, 15_000L, 30_000L, 60_000L)
    private var selectedRampMs = TimerService.DEFAULT_RAMP_MS
    private var rampButtons    = listOf<MaterialButton>()

    private val lbm   by lazy { LocalBroadcastManager.getInstance(this) }
    private val prefs by lazy { getSharedPreferences("pnap_state", MODE_PRIVATE) }

    // ── Broadcast receivers ───────────────────────────────────────────────────

    /**
     * TICK — fires every second while the timer is running.
     * Also acts as a defensive state check: if the button still reads "START"
     * but the service is clearly running, snap to running state immediately.
     */
    private val tickReceiver = receiver { intent ->
        val ms = intent?.getLongExtra(TimerService.EXTRA_TIME_MS, 0L) ?: return@receiver
        if (!isRunning && !isAlarmRinging) {
            // First tick: service is live, ensure UI reflects running state
            isRunning = true
            enterRunningState()
        }
        updateDisplay(ms)
    }

    /** ALARM — fired by the service the moment the countdown hits zero. */
    private val alarmReceiver = receiver {
        isRunning      = false
        isAlarmRinging = true
        updateDisplay(0)
        enterAlarmState()
    }

    /** STOPPED — fired when the service fully stops (user tapped Stop). */
    private val stoppedReceiver = receiver {
        isRunning      = false
        isAlarmRinging = false
        totalDurationMs = 0L
        updateDisplay(0)
        enterIdleState()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor     = getColor(R.color.bg_void)
        window.navigationBarColor = getColor(R.color.bg_void)

        sounds           = loadSounds()
        selectedSoundUri = sounds.firstOrNull()?.second

        setupAddButtons()
        setupSoundButtons()
        setupRampButtons()
        setupMainButton()
        setupSnoozeButton()
        updateBtStatus()
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        lbm.registerReceiver(tickReceiver,    IntentFilter(TimerService.BROADCAST_TICK))
        lbm.registerReceiver(alarmReceiver,   IntentFilter(TimerService.BROADCAST_ALARM))
        lbm.registerReceiver(stoppedReceiver, IntentFilter(TimerService.BROADCAST_STOPPED))
        updateBtStatus()
        restoreVisualState()
    }

    override fun onPause() {
        super.onPause()
        lbm.unregisterReceiver(tickReceiver)
        lbm.unregisterReceiver(alarmReceiver)
        lbm.unregisterReceiver(stoppedReceiver)
    }

    // ── State restoration ─────────────────────────────────────────────────────

    /**
     * Called on every onResume so the UI matches whatever the service is doing,
     * even if the activity was killed and recreated while the timer ran in the
     * background.
     *
     * SharedPreferences is the single source of truth written by BOTH the
     * service (on alarm fire + on stop) and the activity (on start/snooze).
     */
    private fun restoreVisualState() {
        when (prefs.getString("state", "idle")) {
            "running" -> {
                val endTime   = prefs.getLong("end_time_ms", 0L)
                val remaining = endTime - System.currentTimeMillis()
                if (remaining > 0) {
                    isRunning = true
                    isAlarmRinging = false
                    updateDisplay(remaining)
                    enterRunningState()
                } else if (!isAlarmRinging) {
                    // Timer may have just expired; wait for service broadcast.
                    // If it never comes (e.g. process died), fall back to idle.
                    enterIdleState()
                }
            }
            "alarm" -> {
                isAlarmRinging = true
                isRunning      = false
                updateDisplay(0)
                enterAlarmState()
            }
            else -> {
                if (!isRunning && !isAlarmRinging) enterIdleState()
            }
        }
    }

    // ── Sound loading ─────────────────────────────────────────────────────────

    private fun loadSounds(): List<Pair<String, Uri>> {
        val result = mutableListOf<Pair<String, Uri>>()

        fun fetchFrom(type: Int) {
            if (result.size >= 4) return
            try {
                val mgr    = RingtoneManager(this).apply { setType(type) }
                val cursor = mgr.cursor
                while (cursor.moveToNext() && result.size < 4) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                    val uri   = mgr.getRingtoneUri(cursor.position)
                    if (result.none { it.second == uri }) result.add(title to uri)
                }
            } catch (_: Exception) {}
        }

        fetchFrom(RingtoneManager.TYPE_ALARM)
        fetchFrom(RingtoneManager.TYPE_NOTIFICATION)

        if (result.isEmpty())
            result.add("Default" to RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))

        return result.take(4)
    }

    // ── UI wiring ─────────────────────────────────────────────────────────────

    private fun setupAddButtons() {
        listOf(
            binding.btn1  to 1,
            binding.btn5  to 5,
            binding.btn10 to 10,
            binding.btn25 to 25,
            binding.btn50 to 50,
            binding.btn90 to 90
        ).forEach { (btn, mins) ->
            btn.setOnClickListener {
                if (isRunning || isAlarmRinging) return@setOnClickListener
                totalDurationMs += mins * 60_000L
                updateDisplay(totalDurationMs)
                binding.tvSubtitle.text = "READY TO START"
                binding.tvSubtitle.setTextColor(getColor(R.color.text_mist))
            }
        }
    }

    private fun setupSoundButtons() {
        val btns = listOf(
            binding.btnSound0, binding.btnSound1,
            binding.btnSound2, binding.btnSound3
        )
        soundButtons = btns

        btns.forEachIndexed { i, btn ->
            if (i < sounds.size) {
                btn.text       = sounds[i].first.split(" ").first().take(9).uppercase()
                btn.visibility = View.VISIBLE
                btn.setOnClickListener {
                    selectedSoundUri = sounds[i].second
                    highlightChips(soundButtons, i)
                }
            } else {
                btn.visibility = View.GONE
            }
        }
        if (sounds.isNotEmpty()) highlightChips(soundButtons, 0)
    }

    private fun setupRampButtons() {
        val btns = listOf(
            binding.btnRamp0, binding.btnRamp1,
            binding.btnRamp2, binding.btnRamp3
        )
        rampButtons = btns

        btns.forEachIndexed { i, btn ->
            btn.setOnClickListener {
                selectedRampMs = rampOptions[i]
                highlightChips(rampButtons, i)
            }
        }
        // Default: 30 s = index 2
        val defaultIdx = rampOptions.indexOf(selectedRampMs).coerceAtLeast(0)
        highlightChips(rampButtons, defaultIdx)
    }

    /**
     * Toggle the selected/unselected look for a group of chip-style buttons.
     * Selected: cyan accent bg + stroke + text.
     * Unselected: slate bg + dim border + mist text.
     */
    private fun highlightChips(buttons: List<MaterialButton>, selected: Int) {
        buttons.forEachIndexed { i, btn ->
            if (i == selected) {
                btn.setBackgroundColor(getColor(R.color.accent_dim_bg))
                btn.strokeColor  = csl(R.color.accent_signal)
                btn.setTextColor(getColor(R.color.accent_signal))
            } else {
                btn.setBackgroundColor(getColor(R.color.bg_card))
                btn.strokeColor  = csl(R.color.border_default)
                btn.setTextColor(getColor(R.color.text_mist))
            }
        }
    }

    private fun setupMainButton() {
        binding.btnMain.setOnClickListener {
            when {
                isAlarmRinging      -> stopAlarm()
                isRunning           -> stopTimer()
                totalDurationMs > 0 -> startTimer()
            }
        }
    }

    private fun setupSnoozeButton() {
        binding.btnSnooze.setOnClickListener { snooze() }
    }

    // ── Timer actions ─────────────────────────────────────────────────────────

    private fun startTimer() {
        val intent = Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_START
            putExtra(TimerService.EXTRA_DURATION_MS, totalDurationMs)
            putExtra(TimerService.EXTRA_SOUND_URI,   selectedSoundUri?.toString())
            putExtra(TimerService.EXTRA_RAMP_MS,     selectedRampMs)
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            // Rare on API 26+ but guard anyway; timer still runs in app foreground
            android.util.Log.e("MainActivity", "startForegroundService failed: ${e.message}", e)
        }

        prefs.edit()
            .putString("state", "running")
            .putLong("end_time_ms", System.currentTimeMillis() + totalDurationMs)
            .putLong("total_duration_ms", totalDurationMs)
            .apply()

        isRunning = true
        enterRunningState()
    }

    private fun stopTimer() = serviceAction(TimerService.ACTION_STOP)
    private fun stopAlarm() = serviceAction(TimerService.ACTION_STOP)

    private fun snooze() {
        serviceAction(TimerService.ACTION_SNOOZE)
        prefs.edit()
            .putString("state", "running")
            .putLong("end_time_ms", System.currentTimeMillis() + TimerService.SNOOZE_DURATION_MS)
            .putLong("total_duration_ms", TimerService.SNOOZE_DURATION_MS)
            .apply()
        isAlarmRinging = false
        isRunning      = true
        enterRunningState()
    }

    private fun serviceAction(action: String) {
        startService(Intent(this, TimerService::class.java).apply { this.action = action })
    }

    // ── Visual state transitions ──────────────────────────────────────────────
    //
    // All button mutations are wrapped in post {} to guarantee they execute
    // after any in-flight layout pass, avoiding a rare race where the view
    // is not yet attached when the state change fires.

    private fun enterIdleState() {
        binding.tvSubtitle.text = "TAP TO ADD MINUTES"
        binding.tvSubtitle.setTextColor(getColor(R.color.text_dim))
        binding.tvTimer.setTextColor(getColor(R.color.text_frost))
        binding.timerProgress.progress = 0
        binding.btnSnooze.visibility = View.GONE
        binding.btnMain.post {
            binding.btnMain.text = "START"
            binding.btnMain.backgroundTintList = csl(R.color.accent_signal)
            binding.btnMain.setTextColor(getColor(R.color.bg_void))
        }
    }

    private fun enterRunningState() {
        binding.tvSubtitle.text = "RUNNING"
        binding.tvSubtitle.setTextColor(getColor(R.color.text_dim))
        binding.tvTimer.setTextColor(getColor(R.color.text_frost))
        binding.btnSnooze.visibility = View.GONE
        binding.btnMain.post {
            binding.btnMain.text = "STOP"
            binding.btnMain.backgroundTintList = csl(R.color.text_mist)
            binding.btnMain.setTextColor(getColor(R.color.bg_void))
        }
    }

    private fun enterAlarmState() {
        binding.tvSubtitle.text = "WAKE UP"
        binding.tvSubtitle.setTextColor(getColor(R.color.accent_signal))
        binding.tvTimer.setTextColor(getColor(R.color.accent_signal))
        binding.timerProgress.progress = 0
        binding.btnSnooze.post {
            binding.btnSnooze.visibility = View.VISIBLE
        }
        binding.btnMain.post {
            binding.btnMain.text = "STOP ALARM"
            binding.btnMain.backgroundTintList = csl(R.color.alarm_active)
            binding.btnMain.setTextColor(getColor(R.color.text_frost))
        }
    }

    // ── Timer display ─────────────────────────────────────────────────────────

    private fun updateDisplay(ms: Long) {
        val s = ms / 1_000
        binding.tvTimer.text = "%02d:%02d".format(s / 60, s % 60)

        // Progress bar: fraction of total duration remaining, on a 0–10 000 scale
        if (!isAlarmRinging) {
            val totalMs = prefs.getLong("total_duration_ms", 0L)
            if (totalMs > 0) {
                binding.timerProgress.progress =
                    (ms * 10_000L / totalMs).toInt().coerceIn(0, 10_000)
            }
        }
    }

    // ── Bluetooth status ──────────────────────────────────────────────────────

    private fun updateBtStatus() {
        val am    = getSystemService(AUDIO_SERVICE) as AudioManager
        val hasBt = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        if (hasBt) {
            binding.tvBtStatus.text = "BLUETOOTH ACTIVE"
            binding.tvBtStatus.setTextColor(getColor(R.color.accent_signal))
            binding.btDot.setBackgroundResource(R.drawable.circle_dot_active)
        } else {
            binding.tvBtStatus.text = "PHONE SPEAKER"
            binding.tvBtStatus.setTextColor(getColor(R.color.text_dim))
            binding.btDot.setBackgroundResource(R.drawable.circle_dot)
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestNeededPermissions() {
        buildList {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.POST_NOTIFICATIONS)

            if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.BLUETOOTH_CONNECT)
        }.takeIf { it.isNotEmpty() }
            ?.let { requestPermissions(it.toTypedArray(), 42) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) updateBtStatus()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** ColorStateList from a color resource — shorthand used throughout. */
    private fun csl(colorRes: Int) = ColorStateList.valueOf(getColor(colorRes))

    private fun receiver(block: (Intent?) -> Unit) = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) = block(intent)
    }
}
