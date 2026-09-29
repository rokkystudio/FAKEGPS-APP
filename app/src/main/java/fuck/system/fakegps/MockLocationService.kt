package fuck.system.fakegps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps feeding mock GPS coordinates while Fake GPS is running in developer mode.
 */
class MockLocationService : Service() {
    private lateinit var engine: MockLocationEngine
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    private val tick = object : Runnable {
        override fun run() {
            engine.push(MockLocationStore.latitude, MockLocationStore.longitude)
            worker?.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        MockLocationStore.load(this)
        engine = MockLocationEngine(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMock()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        if (!engine.start()) {
            MockLocationStore.saveRunning(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        MockLocationStore.saveRunning(this, true)
        engine.push(MockLocationStore.latitude, MockLocationStore.longitude)
        startTicker()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTicker()
        engine.stop()
        MockLocationStore.saveRunning(this, false)
        super.onDestroy()
    }

    private fun startTicker() {
        if (worker != null) {
            return
        }
        val thread = HandlerThread("fake-gps", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
        workerThread = thread
        worker = Handler(thread.looper).also { it.post(tick) }
    }

    private fun stopTicker() {
        worker?.removeCallbacks(tick)
        worker = null
        workerThread?.quitSafely()
        workerThread = null
    }

    private fun stopMock() {
        stopTicker()
        engine.stop()
        MockLocationStore.saveRunning(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, MockLocationService::class.java).setAction(ACTION_STOP)
        val stop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val text = getString(
            R.string.coordinates_format,
            MockLocationStore.latitude,
            MockLocationStore.longitude
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launch)
            .addAction(0, getString(R.string.stop_mock_location), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        const val ACTION_STOP = "fuck.system.fakegps.STOP_MOCK"
        private const val CHANNEL_ID = "fake_gps_mock"
        private const val NOTIFICATION_ID = 42
        private const val UPDATE_INTERVAL_MS = 500L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MockLocationService::class.java)
            )
        }

        fun stop(context: Context) {
            val intent = Intent(context, MockLocationService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
