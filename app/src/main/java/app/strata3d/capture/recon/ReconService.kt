package app.strata3d.capture.recon

import android.app.Notification
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
import androidx.core.content.ContextCompat
import app.strata3d.R
import app.strata3d.capture.format.CaptureStore
import java.io.File

/**
 * Runs one reconstruction at a time as a foreground service in its own process (":recon"), so a tool that runs out of
 * memory or crashes never takes the app down, and the work continues with the screen off.
 *
 * Start with [start] (one capture), stop with [cancel]. Progress is read from `status.json` ([ReconRunner.readStatus]),
 * which also survives the app being closed. A partial wake lock keeps the CPU running for the job's duration only.
 */
class ReconService : Service() {

    @Volatile private var runner: ReconRunner? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> { runner?.cancel(); return START_NOT_STICKY }
            ACTION_START, ACTION_RESUME -> {
                val id = intent.getStringExtra(EXTRA_CAPTURE_ID) ?: return START_NOT_STICKY
                if (worker != null) {
                    Log.w(TAG, "already reconstructing; ignoring $id")
                    return START_NOT_STICKY
                }
                startInForeground(notification(getString(R.string.recon_notification_starting)))
                startJob(id, review = intent.getBooleanExtra(EXTRA_REVIEW, false), resume = intent.action == ACTION_RESUME)
            }
        }
        return START_NOT_STICKY
    }

    private fun startJob(captureId: String, review: Boolean, resume: Boolean) {
        val captureDir = File(File(filesDir, CaptureStore.DIR), captureId)
        val r = ReconRunner(captureDir, workRoot(this), File(applicationInfo.nativeLibraryDir))
        runner = r
        val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "strata3d:recon")
        worker = Thread({
            wake.acquire(MAX_JOB_MS)
            try {
                val final = r.run(review = review, resume = resume) { s -> updateNotification(s) }
                if (final.state == ReconStatus.State.REVIEW) postReviewNotification(captureId)
            } finally {
                if (wake.isHeld) wake.release()
                runner = null; worker = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "strata-recon").apply { start() }
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, type) else startForeground(NOTIFICATION_ID, n)
    }

    private fun updateNotification(s: ReconStatus) {
        val text = when (s.state) {
            ReconStatus.State.RUNNING -> stageText(this, s)
            else -> return
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    /** Mockup G: the build is paused for the user's check; tapping opens the capture. Nothing is running. */
    private fun postReviewNotification(captureId: String) {
        val open = PendingIntent.getActivity(
            this, 2,
            Intent().setClassName(this, "app.strata3d.capture.ui.CaptureActivity")
                .putExtra(EXTRA_OPEN_CAPTURE, captureId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recon)
            .setContentTitle(getString(R.string.recon_review_title))
            .setContentText(getString(R.string.recon_review_notification))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(REVIEW_NOTIFICATION_ID, n)
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.recon_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ReconService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recon)
            .setContentTitle(getString(R.string.recon_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
            .build()
    }

    companion object {
        private const val TAG = "ReconService"
        const val ACTION_START = "app.strata3d.recon.START"
        const val ACTION_CANCEL = "app.strata3d.recon.CANCEL"
        const val ACTION_RESUME = "app.strata3d.recon.RESUME"
        const val EXTRA_CAPTURE_ID = "captureId"
        /** Boolean: pause after the object box for the user's check. */
        const val EXTRA_REVIEW = "review"
        /** For the capture activity: open this capture's summary. */
        const val EXTRA_OPEN_CAPTURE = "app.strata3d.capture.OPEN_CAPTURE"
        private const val REVIEW_NOTIFICATION_ID = 3002
        private const val CHANNEL = "reconstruction"
        private const val NOTIFICATION_ID = 3001
        /** Upper bound for the wake lock (a large capture on a slow phone). */
        private const val MAX_JOB_MS = 3 * 60 * 60 * 1000L

        fun workRoot(context: Context) = File(context.filesDir, "recon")

        fun start(context: Context, captureId: String, review: Boolean = false) {
            ContextCompat.startForegroundService(
                context, Intent(context, ReconService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_CAPTURE_ID, captureId).putExtra(EXTRA_REVIEW, review),
            )
        }

        /** Continues a build paused for review, with the box the user saved in the scene. */
        fun resume(context: Context, captureId: String) {
            context.getSystemService(NotificationManager::class.java).cancel(REVIEW_NOTIFICATION_ID)
            ContextCompat.startForegroundService(
                context, Intent(context, ReconService::class.java).setAction(ACTION_RESUME).putExtra(EXTRA_CAPTURE_ID, captureId),
            )
        }

        fun dismissReviewNotification(context: Context) =
            context.getSystemService(NotificationManager::class.java).cancel(REVIEW_NOTIFICATION_ID)

        fun cancel(context: Context) {
            context.startService(Intent(context, ReconService::class.java).setAction(ACTION_CANCEL))
        }

        /** "Depth maps (3 of 9)" etc. */
        fun stageText(context: Context, s: ReconStatus): String {
            val name = when (s.stage) {
                ReconPlan.Stage.IMPORT -> R.string.recon_stage_import
                ReconPlan.Stage.CAMERAS -> R.string.recon_stage_cameras
                ReconPlan.Stage.BOX -> R.string.recon_stage_box
                ReconPlan.Stage.DEPTH -> R.string.recon_stage_depth
                ReconPlan.Stage.POINTS -> R.string.recon_stage_points
                ReconPlan.Stage.SURFACE -> R.string.recon_stage_surface
                ReconPlan.Stage.GROUND -> R.string.recon_stage_ground
                ReconPlan.Stage.CLEAN -> R.string.recon_stage_clean
                ReconPlan.Stage.TEXTURE -> R.string.recon_stage_texture
                null -> R.string.recon_notification_starting
            }
            val base = context.getString(name)
            return if (s.steps > 0) context.getString(R.string.recon_stage_steps, base, s.step, s.steps) else base
        }
    }
}
