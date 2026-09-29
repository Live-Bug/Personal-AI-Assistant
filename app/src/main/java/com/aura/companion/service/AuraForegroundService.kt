package com.aura.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aura.companion.AuraApplication
import com.aura.companion.MainActivity
import com.aura.companion.capture.CaptureState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Always-on listening runs exactly as long as this service does: the bottom-bar mic starts and
 * stops it. Android only lets a microphone foreground service start while the app is visible,
 * so it is never restarted by the system after being killed.
 */
class AuraForegroundService : Service() {

    companion object {
        private const val TAG = "AuraForegroundService"
        const val CHANNEL_ID = "aura_listening_channel"
        const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.aura.companion.STOP_LISTENING"
        private const val PREF_LISTENING = "listening_enabled"

        /** Starts listening. Only call while the app is visible. */
        fun start(context: Context) {
            setWanted(context, true)
            ContextCompat.startForegroundService(context, Intent(context, AuraForegroundService::class.java))
        }

        fun stop(context: Context) {
            setWanted(context, false)
            context.stopService(Intent(context, AuraForegroundService::class.java))
        }

        /** Whether the user left listening on, so the app can turn it back on at next launch. */
        fun wasListening(context: Context): Boolean =
            context.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE).getBoolean(PREF_LISTENING, false)

        private fun setWanted(context: Context, on: Boolean) {
            context.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_LISTENING, on).apply()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as AuraApplication
    private var wakeLock: PowerManager.WakeLock? = null
    private var inForeground = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        app.tracker // start grouping utterances into conversations

        scope.launch {
            combine(app.capture.state, app.processor.busy) { state, busy -> state to busy }
                .collect { (state, busy) ->
                    if (inForeground) notificationManager().notify(NOTIFICATION_ID, buildNotification(state, busy))
                    // The CPU must stay awake to read the mic and run the VAD with the screen off
                    updateWakeLock(state == CaptureState.LISTENING || busy)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { // notification button
            stop(this)
            return START_NOT_STICKY
        }
        if (!inForeground) {
            try {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID,
                    buildNotification(app.capture.state.value, app.processor.busy.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                inForeground = true
            } catch (e: Exception) {
                // e.g. started from the background, where Android forbids microphone services
                Log.e(TAG, "Could not start foreground: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        app.capture.start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        app.capture.stop()
        updateWakeLock(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun updateWakeLock(hold: Boolean) {
        val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aura::Capture")
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        if (hold && !lock.isHeld) lock.acquire()
        if (!hold && lock.isHeld) lock.release()
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Listening status",
            NotificationManager.IMPORTANCE_LOW // silent, persistent
        ).apply {
            description = "Shows when Aura is listening"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(state: CaptureState, processing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AuraForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (state) {
            CaptureState.LISTENING -> "Listening"
            CaptureState.STARTING -> "Starting to listen…"
            CaptureState.ERROR -> "Can't listen: ${app.capture.error.value ?: "microphone error"}"
            CaptureState.STOPPED -> "Stopping…"
        }
        val text = if (processing) "Summarizing a conversation on this phone…"
                   else "Transcripts and AI stay on this phone"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .build()
    }
}
