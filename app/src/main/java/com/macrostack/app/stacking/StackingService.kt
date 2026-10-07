package com.macrostack.app.stacking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.macrostack.app.MacroStackApp
import com.macrostack.app.MainActivity
import com.macrostack.app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps stacking alive outside the app: a foreground service, with a progress notification and a
 * wake lock, so leaving the app or turning the screen off doesn't kill or freeze a merge. Started by
 * [FusionManager] when work begins; stops itself once [FusionManager.active] turns false (stopping it
 * from outside could beat its startForeground call, which Android punishes with a crash).
 */
class StackingService : Service() {

    private val scope = MainScope()
    private var watcher: Job? = null
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var shownPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MacroStack:stacking")
            .apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_MS)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Called for every start request, so the foreground promise is always kept.
        lastStartId = startId
        shownPercent = -1
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(NOTIFICATION_ID, notification(null), type)
        if (watcher == null) {
            val fusion = (application as MacroStackApp).fusion
            watcher = scope.launch {
                launch { fusion.state.collect { update(it) } }
                fusion.active.collect { active ->
                    if (!active) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun update(state: FusionManager.State) {
        val running = state as? FusionManager.State.Running ?: return
        val percent = (running.fraction * 100).toInt()
        if (percent == shownPercent) return
        shownPercent = percent
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(running))
    }

    private fun notification(state: FusionManager.State.Running?) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.stacking_title_running))
            .setContentText(state?.source?.name)
            .setProgress(100, shownPercent.coerceAtLeast(0), state == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StackingService"
        private const val CHANNEL = "stacking"
        private const val NOTIFICATION_ID = 1

        /** A safety net: no stack takes this long, and the lock must never outlive a stuck one. */
        private const val MAX_WAKE_MS = 30L * 60 * 1000

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, StackingService::class.java))
            } catch (e: Exception) {
                // E.g. not allowed from the background. Stacking still runs; it is just less protected.
                Log.w(TAG, "Couldn't start the stacking service", e)
            }
        }

    }
}
