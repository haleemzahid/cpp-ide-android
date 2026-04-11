package dev.cppide.ide.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.cppide.core.ai.ModelDownloadState
import dev.cppide.core.ai.ModelRepository
import dev.cppide.ide.CppIdeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Tiny foreground service whose only job is to keep the process alive
 * while a model download is in flight and show a notification so Android
 * doesn't kill the app mid-transfer. It does NOT perform the download
 * itself — that still lives in [ModelRepository]. The service just
 * subscribes to `allStates` and mirrors the active download into a
 * progress notification.
 *
 * Start via [start]; the service calls [stopSelf] once no downloads
 * are active. Safe to call [start] repeatedly — it's a no-op if the
 * service is already running.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: ModelRepository
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = (application as CppIdeApp).core.modelRepository
        ensureChannel()
        startForegroundCompat(buildNotification(title = "Preparing download", progress = null))
        observerJob = repo.allStates
            .onEach { snapshot -> onStateChanged(snapshot) }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If there's nothing to do when we start, stop immediately.
        if (!repo.anyActive.value) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun onStateChanged(snapshot: Map<String, ModelDownloadState>) {
        val active = snapshot.entries.firstOrNull { it.value is ModelDownloadState.Downloading }
        if (active == null) {
            // All downloads finished or were cancelled — release the
            // foreground slot and let the process be reclaimed.
            stopSelf()
            return
        }
        val info = repo.availableModels.firstOrNull { it.id == active.key }
        val label = info?.displayName ?: active.key
        val downloading = active.value as ModelDownloadState.Downloading
        val percent = (downloading.progress * 100).toInt()
        notify(
            buildNotification(
                title = "Downloading $label",
                progress = percent,
            )
        )
    }

    // --- notification plumbing ------------------------------------------------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Model downloads",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "Progress of on-device AI model downloads"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    private fun buildNotification(title: String, progress: Int?) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply {
                if (progress != null) {
                    setProgress(100, progress, /* indeterminate = */ false)
                    setContentText("$progress%")
                } else {
                    setProgress(0, 0, /* indeterminate = */ true)
                }
            }
            .build()

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(notification: android.app.Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 0x1D10A4

        /**
         * Start the service. Called from the UI just before / after
         * kicking off a download so the process survives backgrounding.
         * Safe to call when a download is already running.
         */
        fun start(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
