package com.deansak.powernap

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class TimerService : Service() {

    // ── Public API ────────────────────────────────────────────────────────────

    companion object {
        const val ACTION_START  = "com.deansak.powernap.START"
        const val ACTION_STOP   = "com.deansak.powernap.STOP"
        const val ACTION_SNOOZE = "com.deansak.powernap.SNOOZE"

        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_SOUND_URI   = "sound_uri"
        const val EXTRA_RAMP_MS     = "ramp_ms"   // 0 = instant, else fade duration

        const val BROADCAST_TICK    = "com.deansak.powernap.TICK"
        const val BROADCAST_ALARM   = "com.deansak.powernap.ALARM"
        const val BROADCAST_STOPPED = "com.deansak.powernap.STOPPED"
        const val EXTRA_TIME_MS     = "time_ms"

        const val SNOOZE_DURATION_MS    = 9 * 60 * 1000L
        const val DEFAULT_RAMP_MS       = 30_000L   // 30-second default fade-in

        private const val CHANNEL_ID  = "power_nap_channel"
        private const val NOTIF_ID    = 1
        private const val TAG         = "TimerService"
        private const val FADE_STEP_MS = 200L       // volume update every 200 ms
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var countDownTimer: CountDownTimer? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var soundUri: Uri? = null
    private var rampDurationMs = DEFAULT_RAMP_MS
    private var isAlarmRinging = false
    private var wakeLock: PowerManager.WakeLock? = null

    // Handler lives on main thread so setVolume() is safe
    private val fadeHandler = Handler(Looper.getMainLooper())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                soundUri      = intent.getStringExtra(EXTRA_SOUND_URI)?.let { Uri.parse(it) }
                rampDurationMs = intent.getLongExtra(EXTRA_RAMP_MS, DEFAULT_RAMP_MS)
                startTimer(intent.getLongExtra(EXTRA_DURATION_MS, 0L))
            }
            ACTION_STOP   -> fullStop()
            ACTION_SNOOZE -> snooze()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        stopAudio()
        countDownTimer?.cancel()
    }

    // ── Timer logic ───────────────────────────────────────────────────────────

    private fun startTimer(durationMs: Long) {
        stopAudio()
        countDownTimer?.cancel()
        isAlarmRinging = false

        val notification = buildCountdownNotification(durationMs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        countDownTimer = object : CountDownTimer(durationMs, 1_000) {
            override fun onTick(remaining: Long) {
                updateCountdownNotification(remaining)
                broadcast(BROADCAST_TICK) { putExtra(EXTRA_TIME_MS, remaining) }
            }
            override fun onFinish() {
                isAlarmRinging = true
                // Persist so MainActivity can restore alarm state if it was backgrounded
                getSharedPreferences("pnap_state", MODE_PRIVATE).edit()
                    .putString("state", "alarm").apply()
                acquireWakeLock()
                playAlarm()
                showAlarmNotification()
                broadcast(BROADCAST_ALARM)
            }
        }.start()
    }

    private fun snooze() {
        stopAudio()
        isAlarmRinging = false
        releaseWakeLock()
        startTimer(SNOOZE_DURATION_MS)
    }

    private fun fullStop() {
        countDownTimer?.cancel()
        countDownTimer = null
        stopAudio()
        isAlarmRinging = false
        releaseWakeLock()
        // Clear persisted state so MainActivity restores to idle on next open
        getSharedPreferences("pnap_state", MODE_PRIVATE).edit()
            .putString("state", "idle").apply()
        stopForegroundCompat()
        stopSelf()
        broadcast(BROADCAST_STOPPED)
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    /**
     * Play the alarm sound, respecting two behaviors:
     *
     * 1. OTHER APPS' AUDIO — We request AudioFocus with AUDIOFOCUS_GAIN.
     *    This tells the OS to notify any active media app (sleep sounds, music,
     *    podcasts) that it should pause. Well-behaved apps honour this; they
     *    resume automatically when we release focus on stop/snooze.
     *
     * 2. VOLUME RAMP — We start the MediaPlayer at volume 0 and step it up to
     *    1.0 over rampDurationMs. If ramp = 0, we jump straight to full volume.
     *    MediaPlayer.setVolume() acts as a multiplier on top of the system
     *    media volume — so the user's system volume is always the ceiling.
     *
     * WHY USAGE_MEDIA (not USAGE_ALARM):
     *    USAGE_ALARM is hardwired to the phone speaker on Android; it bypasses
     *    BT A2DP by design. USAGE_MEDIA routes to whichever device is active
     *    (BT headphones, wired headset, or speaker) — which is what we want.
     */
    private fun playAlarm() {
        val uri = soundUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: run { Log.e(TAG, "No sound URI"); return }

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        // Request full audio focus — other media apps receive AUDIOFOCUS_LOSS
        // and should pause, giving the alarm clear air to play through.
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttrs)
            .setWillPauseWhenDucked(false)
            .build()
        am.requestAudioFocus(focusReq)
        audioFocusRequest = focusReq

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(audioAttrs)
                setDataSource(this@TimerService, uri)
                isLooping = true
                prepare()
                setVolume(0f, 0f)   // always start silent; ramp decides final level
                start()
            }
            startVolumeRamp()
            Log.i(TAG, "Alarm started — ramp: ${rampDurationMs}ms, URI: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer failed: ${e.message}", e)
            // Fallback: Ringtone API (no BT guarantee but audible on speaker)
            try {
                RingtoneManager.getRingtone(this,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.play()
            } catch (e2: Exception) {
                Log.e(TAG, "Ringtone fallback also failed", e2)
            }
        }
    }

    /**
     * Ramp MediaPlayer volume from 0.0 → 1.0 over [rampDurationMs].
     * Steps every FADE_STEP_MS. If ramp = 0, snap to full volume immediately.
     */
    private fun startVolumeRamp() {
        fadeHandler.removeCallbacksAndMessages(null)

        if (rampDurationMs <= 0L) {
            mediaPlayer?.setVolume(1f, 1f)
            return
        }

        val totalSteps = (rampDurationMs / FADE_STEP_MS).coerceAtLeast(1)
        val step = 1f / totalSteps
        var vol = 0f

        fadeHandler.post(object : Runnable {
            override fun run() {
                val mp = mediaPlayer ?: return
                vol = (vol + step).coerceAtMost(1f)
                mp.setVolume(vol, vol)
                if (vol < 1f) fadeHandler.postDelayed(this, FADE_STEP_MS)
                else Log.d(TAG, "Volume ramp complete")
            }
        })
    }

    private fun stopAudio() {
        fadeHandler.removeCallbacksAndMessages(null)

        // Release audio focus — sleep sound / music apps receive AUDIOFOCUS_GAIN
        // and can resume playing if they want to.
        audioFocusRequest?.let {
            (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.abandonAudioFocusRequest(it)
        }
        audioFocusRequest = null

        try {
            mediaPlayer?.run { if (isPlaying) stop(); release() }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaPlayer", e)
        }
        mediaPlayer = null
    }

    // ── Wake lock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "PowerNap:AlarmWake"
        ).also { it.acquire(10 * 60 * 1_000L) }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Power Nap Timer", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Countdown progress and alarm alerts"
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildCountdownNotification(ms: Long): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Power Nap")
            .setContentText("Time remaining: ${formatTime(ms)}")
            .setContentIntent(pendingActivity())
            .addAction(android.R.drawable.ic_delete, "STOP", pendingService(ACTION_STOP, 0))
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateCountdownNotification(ms: Long) {
        notificationManager.notify(NOTIF_ID, buildCountdownNotification(ms))
    }

    private fun showAlarmNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Rise and shine ☀️")
            .setContentText("Your nap is complete.")
            .setContentIntent(pendingActivity())
            .addAction(android.R.drawable.ic_delete,      "STOP",         pendingService(ACTION_STOP,   1))
            .addAction(android.R.drawable.ic_menu_rotate, "SNOOZE 9 MIN", pendingService(ACTION_SNOOZE, 2))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(NOTIF_ID, notification)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val notificationManager: NotificationManager
        get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun pendingActivity(): PendingIntent = PendingIntent.getActivity(
        this, 10,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun pendingService(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, TimerService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun broadcast(action: String, extras: (Intent.() -> Unit)? = null) {
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(action).also { extras?.invoke(it) })
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1_000
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
