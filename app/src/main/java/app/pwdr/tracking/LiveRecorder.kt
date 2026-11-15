package app.pwdr.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Werte für die Anzeige während der Aufzeichnung. */
data class LiveState(
    val activity: Activity = Activity.PAUSE,
    val speedMs: Double = 0.0,
    val maxSpeedMs: Double = 0.0,
    val altM: Double = 0.0,
    val accuracyM: Float = 0f,
    val runCount: Int = 0,
    val liftCount: Int = 0,
    /** Summe über alle Abfahrten dieser Aufzeichnung. */
    val totalDistM: Double = 0.0,
    val totalVertDownM: Double = 0.0,
    /** Laufende Abfahrt, sonst 0. */
    val currentRunDistM: Double = 0.0,
    val currentRunVertDownM: Double = 0.0,
    val elapsedMs: Long = 0,
)

/** Hält den Zustand der Anzeige und ist unabhängig von Android testbar. */
class LiveRecorder(private val detector: ActivityDetector = ActivityDetector()) {

    private var firstMs: Long? = null
    private var maxSpeed = 0.0
    private var recentSpeeds = ArrayDeque<Double>()

    private var runCount = 0
    private var liftCount = 0
    private var closedDist = 0.0
    private var closedVertDown = 0.0

    fun onSample(s: Sample): LiveState {
        if (firstMs == null) firstMs = s.timeMs

        if (s.accuracyM <= MAX_ACCURACY_FOR_SPEED_M) {
            recentSpeeds.addLast(s.speedMs)
            if (recentSpeeds.size > 3) recentSpeeds.removeFirst()
            if (recentSpeeds.size == 3) {
                val median = recentSpeeds.sorted()[1]
                if (median <= MAX_PLAUSIBLE_SPEED_MS && median > maxSpeed) maxSpeed = median
            }
        }

        val update = detector.onSample(s)
        for (seg in update.closed) account(seg)

        var curDist = 0.0
        var curDown = 0.0
        if (update.activity == Activity.DOWNHILL) {
            val st = SegmentStatsCalculator.compute(detector.currentSegmentSamples)
            curDist = st.distanceM
            curDown = st.vertDownM
        }

        return LiveState(
            activity = update.activity,
            speedMs = s.speedMs,
            maxSpeedMs = maxSpeed,
            altM = s.altM,
            accuracyM = s.accuracyM,
            runCount = runCount,
            liftCount = liftCount,
            totalDistM = closedDist + curDist,
            totalVertDownM = closedVertDown + curDown,
            currentRunDistM = curDist,
            currentRunVertDownM = curDown,
            elapsedMs = s.timeMs - (firstMs ?: s.timeMs),
        )
    }

    private fun account(seg: Segment) {
        when (seg.activity) {
            Activity.DOWNHILL -> {
                val st = SegmentStatsCalculator.compute(seg.samples)
                if (st.vertDownM >= MIN_RUN_VERT_DOWN_M) {
                    runCount++
                    closedDist += st.distanceM
                    closedVertDown += st.vertDownM
                }
            }
            Activity.LIFT -> liftCount++
            Activity.PAUSE -> Unit
        }
    }

    private companion object {
        const val MAX_ACCURACY_FOR_SPEED_M = 20f
        const val MAX_PLAUSIBLE_SPEED_MS = 60.0
        const val MIN_RUN_VERT_DOWN_M = 10.0
    }
}

/** Gemeinsamer Zustand zwischen Service und Oberfläche. */
object TrackingStore {
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()

    fun setRecording(value: Boolean) {
        _recording.value = value
        if (value) _live.value = LiveState()
    }

    fun update(state: LiveState) {
        _live.value = state
    }
}
