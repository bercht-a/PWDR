package app.pwdr.voice

import app.pwdr.tracking.Activity
import app.pwdr.tracking.LiveState
import kotlin.math.abs

/**
 * Entscheidet aus dem laufenden Zustand, welche Ansagen fällig sind: Speed-Intervall, neuer Tages-Höchstwert und
 * Abfahrt-Ende (PLAN.md 5b). Läuft im GPS-Takt, braucht keinen eigenen Timer. Unabhängig von Android.
 *
 * [dayMaxMs] ist der Höchstwert früherer Aufzeichnungen desselben Tages (0 = keiner). Ohne Vergleichswert
 * gibt es keinen "neuen Rekord".
 */
class AnnouncementPlanner(dayMaxMs: Double = 0.0) {

    private var previousDayMax = dayMaxMs
    private var latestSpeedMs = 0.0
    private var latestMaxMs = 0.0

    private var prevRunCount = 0
    private var prevActivity = Activity.PAUSE

    // Laufende Abfahrt
    private var closedDistBefore = 0.0
    private var closedVertBefore = 0.0
    private var runPeakSpeedMs = 0.0
    private var recordAnnouncedThisRun = false

    private var lastSpeedAnnounceMs: Long? = null
    private var lastAnnouncedKmh: Int? = null

    fun onState(state: LiveState, timeMs: Long, settings: VoiceSettings): List<Announcement> {
        latestSpeedMs = state.speedMs
        latestMaxMs = state.maxSpeedMs
        val out = ArrayList<Announcement>()

        if (state.activity == Activity.DOWNHILL) {
            if (prevActivity != Activity.DOWNHILL) {
                // Neue Abfahrt beginnt.
                runPeakSpeedMs = 0.0
                recordAnnouncedThisRun = false
                lastSpeedAnnounceMs = timeMs
                lastAnnouncedKmh = null
            }
            closedDistBefore = state.totalDistM - state.currentRunDistM
            closedVertBefore = state.totalVertDownM - state.currentRunVertDownM
            if (state.accuracyM <= 20f && state.speedMs <= MAX_PLAUSIBLE_MS) {
                runPeakSpeedMs = maxOf(runPeakSpeedMs, state.speedMs)
            }

            val interval = settings.speedIntervalSec
            val lastAt = lastSpeedAnnounceMs
            if (interval > 0 && lastAt != null && timeMs - lastAt >= interval * 1000L) {
                val now = SpeechText.kmh(state.speedMs)
                val last = lastAnnouncedKmh
                if (last == null || abs(now - last) >= settings.speedMinChangeKmh) {
                    lastSpeedAnnounceMs = timeMs
                    lastAnnouncedKmh = now
                    out.add(
                        Announcement(Kind.SPEED, Priority.LOW, SPEED_MAX_AGE_MS) {
                            SpeechText.speed(SpeechText.kmh(latestSpeedMs))
                        },
                    )
                }
            }

            if (settings.recordEnabled && !recordAnnouncedThisRun &&
                previousDayMax >= MIN_RECORD_BASELINE_MS && state.maxSpeedMs > previousDayMax + RECORD_MARGIN_MS
            ) {
                recordAnnouncedThisRun = true
                out.add(
                    Announcement(Kind.RECORD, Priority.MEDIUM, RECORD_MAX_AGE_MS) {
                        SpeechText.record(SpeechText.kmh(latestMaxMs))
                    },
                )
            }
        }

        if (state.runCount > prevRunCount) {
            // Eine Abfahrt wurde abgeschlossen (eine unter 10 Höhenmetern zählt nicht und kommt hier nicht an).
            val dist = (state.totalDistM - closedDistBefore).coerceAtLeast(0.0)
            val vert = (state.totalVertDownM - closedVertBefore).coerceAtLeast(0.0)
            val peak = SpeechText.kmh(runPeakSpeedMs)
            previousDayMax = maxOf(previousDayMax, state.maxSpeedMs)
            if (settings.runEndEnabled) {
                out.add(
                    Announcement(Kind.RUN_END, Priority.LOW, RUN_END_MAX_AGE_MS) {
                        SpeechText.runEnd(dist, vert, peak)
                    },
                )
            }
        }

        prevRunCount = state.runCount
        prevActivity = state.activity
        return out
    }

    private companion object {
        const val SPEED_MAX_AGE_MS = 3_000L
        const val RECORD_MAX_AGE_MS = 8_000L
        const val RUN_END_MAX_AGE_MS = 20_000L
        const val MAX_PLAUSIBLE_MS = 60.0

        /** Erst ab ca. 18 km/h früherem Höchstwert ist ein "Rekord" sinnvoll. */
        const val MIN_RECORD_BASELINE_MS = 5.0

        /** Mindestens ca. 2 km/h über dem bisherigen Höchstwert. */
        const val RECORD_MARGIN_MS = 0.5
    }
}
