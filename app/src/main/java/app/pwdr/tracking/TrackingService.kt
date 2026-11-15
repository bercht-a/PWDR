package app.pwdr.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.pwdr.MainActivity
import app.pwdr.R
import app.pwdr.data.PwdrDatabase
import app.pwdr.data.TrackingRepository
import app.pwdr.social.ShareSender
import app.pwdr.social.Social
import app.pwdr.voice.DayBase
import app.pwdr.voice.VoiceController
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Zeichnet im Vordergrund-Dienst die Position auf, auch bei gesperrtem Bildschirm.
 * Der Dienst wird nur aus der sichtbaren App gestartet, deshalb reicht die Standort-Berechtigung
 * "Beim Verwenden der App" (kein Hintergrund-Standort nötig).
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repository: TrackingRepository
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var wakeLock: PowerManager.WakeLock? = null
    private var recorder = LiveRecorder()
    private var baro = BarometerAltitude()
    private var voice: VoiceController? = null
    private var shareSender: ShareSender? = null
    private var sessionId: Long = -1
    private var lastAlt = 0.0
    private var lastFix: Sample? = null
    private val buffer = mutableListOf<Sample>()
    private var recording = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) onLocation(loc)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = TrackingRepository(PwdrDatabase.get(this))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            ACTION_BUTTON -> voice?.onButtonPress()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (recording) return
        recording = true
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pwdr:tracking")
            .apply { acquire(MAX_RECORDING_MS) }

        recorder = LiveRecorder()
        baro = BarometerAltitude()
        startBarometer()
        voice = VoiceController(this)
        shareSender = ShareSender(scope)
        TrackingStore.setRecording(true)
        scope.launch {
            sessionId = repository.startSession(System.currentTimeMillis())
            // Freunde sehen die Position, solange die Aufzeichnung läuft (läuft parallel, Fehler stören nicht).
            launch { Social.startAutoShare() }
            val dayBase = loadDayBase()
            withContext(Dispatchers.Main) { voice?.begin(dayBase) }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                .setMinUpdateIntervalMillis(500L)
                .setWaitForAccurateLocation(false)
                .build()
            withContext(Dispatchers.Main) {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            }
        }
    }

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.isNotEmpty()) baro.onPressure(event.values[0])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun startBarometer() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return
        sm.registerListener(pressureListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun stopBarometer() {
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(pressureListener)
    }

    private fun onLocation(loc: Location) {
        if (!recording) return
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else 50f
        val gpsAlt = if (loc.hasAltitude()) loc.altitude else null
        val fused = baro.altitude(loc.time, gpsAlt, accuracy)
        if (fused != null) lastAlt = fused
        val prev = lastFix
        val speed = when {
            loc.hasSpeed() -> loc.speed.toDouble()
            prev != null -> {
                val dt = (loc.time - prev.timeMs) / 1000.0
                if (dt > 0) haversineM(prev.lat, prev.lon, loc.latitude, loc.longitude) / dt else 0.0
            }
            else -> 0.0
        }
        val sample = Sample(
            timeMs = loc.time,
            lat = loc.latitude,
            lon = loc.longitude,
            altM = lastAlt,
            speedMs = speed,
            accuracyM = accuracy,
            gpsAltM = gpsAlt,
        )
        lastFix = sample
        val live = recorder.onSample(sample)
        TrackingStore.update(live)
        voice?.onLive(live, loc.time)
        shareSender?.onLocation(loc, live.activity)
        voice?.onPosition(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null, live.activity)

        buffer.add(sample)
        if (buffer.size >= FLUSH_EVERY) flush()
    }

    /** Werte früherer Aufzeichnungen des heutigen Tages (Ortszeit), für Rekord und Tageszusammenfassung. */
    private suspend fun loadDayBase(): DayBase = try {
        val startOfDay = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val runs = PwdrDatabase.get(this).runs().downhillsSince(startOfDay).filter { it.vertDownM >= 10.0 }
        DayBase(runs.size, runs.sumOf { it.vertDownM }, runs.maxOfOrNull { it.maxSpeedMs } ?: 0.0)
    } catch (e: Exception) {
        DayBase()
    }

    private fun flush() {
        if (buffer.isEmpty() || sessionId < 0) return
        val batch = buffer.toList()
        buffer.clear()
        val id = sessionId
        scope.launch { repository.appendPoints(id, batch) }
    }

    private fun stopRecording() {
        if (!recording) {
            stopSelf()
            return
        }
        recording = false
        client.removeLocationUpdates(callback)
        stopBarometer()
        voice?.release()
        voice = null
        val batch = buffer.toList()
        buffer.clear()
        val id = sessionId
        val endMs = System.currentTimeMillis()
        scope.launch {
            // Mit dem Ende der Aufzeichnung endet die Freigabe (PLAN.md 5c), die Live-Zeile wird gelöscht.
            Social.stopShareQuietly()
            if (id >= 0) {
                repository.appendPoints(id, batch)
                repository.finishSession(id, endMs)
            }
            withContext(Dispatchers.Main) {
                TrackingStore.setRecording(false)
                wakeLock?.let { if (it.isHeld) it.release() }
                ServiceCompat.stopForeground(this@TrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        stopBarometer()
        voice?.release()
        voice = null
        wakeLock?.let { if (it.isHeld) it.release() }
        // Läuft die Aufzeichnung noch (Dienst wurde vom System beendet), bleibt die Session
        // unfertig und wird beim nächsten App-Start wiederhergestellt.
        TrackingStore.setRecording(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tracking_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(getString(R.string.tracking_notification_text))
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_START = "app.pwdr.action.START"
        const val ACTION_STOP = "app.pwdr.action.STOP"
        const val ACTION_BUTTON = "app.pwdr.action.BUTTON"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val FLUSH_EVERY = 10
        private const val MAX_RECORDING_MS = 12 * 60 * 60 * 1000L

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, TrackingService::class.java).setAction(ACTION_START),
            )
        }

        /** Kopfhörer-Taste: nur sinnvoll, wenn der Dienst läuft. Startet ihn nicht neu. */
        fun sendButtonPress(context: Context) {
            if (!TrackingStore.recording.value) return
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_BUTTON))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
