package app.pwdr.data

import androidx.room.withTransaction
import app.pwdr.osm.WayKind
import app.pwdr.tracking.Activity
import app.pwdr.tracking.GForceStats
import app.pwdr.tracking.GpxExporter
import app.pwdr.tracking.RunMatcher
import app.pwdr.tracking.Sample
import app.pwdr.tracking.SegmentStatsCalculator
import app.pwdr.tracking.SessionAnalyzer

class TrackingRepository(private val db: PwdrDatabase) {

    suspend fun startSession(
        startMs: Long,
        gForce: Boolean? = null,
        carry: String? = null,
        batteryStartPct: Int? = null,
    ): Long = db.sessions().insert(
        SessionEntity(startMs = startMs, gForce = gForce, carry = carry, batteryStartPct = batteryStartPct),
    )

    suspend fun session(id: Long): SessionEntity? = db.sessions().get(id)

    suspend fun appendPoints(sessionId: Long, samples: List<Sample>) {
        if (samples.isEmpty()) return
        db.points().insertAll(
            samples.map {
                TrackPointEntity(
                    sessionId = sessionId,
                    timeMs = it.timeMs,
                    lat = it.lat,
                    lon = it.lon,
                    altM = it.altM,
                    speedMs = it.speedMs,
                    accuracyM = it.accuracyM,
                    gpsAltM = it.gpsAltM,
                    gMax = it.gMax,
                    gMean = it.gMean,
                )
            },
        )
    }

    /** Wertet die Rohpunkte aus, speichert die Abschnitte und markiert die Aufzeichnung als beendet. */
    suspend fun finishSession(sessionId: Long, endMs: Long, batteryEndPct: Int? = null) {
        val points = db.points().forSession(sessionId)
        val samples = points.map {
            it.toSample()
        }
        val ways = if (samples.isEmpty()) emptyList() else try {
            OsmRepository(db).waysIn(
                samples.minOf { it.lat }, samples.maxOf { it.lat },
                samples.minOf { it.lon }, samples.maxOf { it.lon },
            )
        } catch (e: Exception) {
            emptyList()
        }
        val runs = SessionAnalyzer.analyze(samples).map { seg ->
            val st = SegmentStatsCalculator.compute(seg.samples)
            val kind = when (seg.activity) {
                Activity.DOWNHILL -> WayKind.PISTE
                Activity.LIFT -> WayKind.LIFT
                Activity.PAUSE -> null
            }
            val match = kind?.let { RunMatcher.match(seg.samples, ways, it) }
            val g = GForceStats.of(seg.samples)
            RunEntity(
                sessionId = sessionId,
                type = seg.activity.name,
                startMs = seg.startMs,
                endMs = seg.endMs,
                distanceM = st.distanceM,
                vertDownM = st.vertDownM,
                vertUpM = st.vertUpM,
                maxSpeedMs = st.maxSpeedMs,
                avgSpeedMs = st.avgSpeedMs,
                name = match?.name,
                difficulty = match?.difficulty,
                maxG = g?.max,
                avgG = g?.mean,
            )
        }
        db.runs().deleteForSession(sessionId)
        db.runs().insertAll(runs)
        val session = db.sessions().get(sessionId) ?: return
        db.sessions().update(session.copy(endMs = endMs, finished = true, batteryEndPct = batteryEndPct))
    }

    /** GPX-Dokument mit allen Punkten der angegebenen Aufzeichnungen (ein Track-Segment je Aufzeichnung). */
    suspend fun exportGpx(sessionIds: List<Long>, name: String): String {
        val tracks = sessionIds.sorted().map { id ->
            db.points().forSession(id).map {
                it.toSample()
            }
        }.filter { it.isNotEmpty() }
        return GpxExporter.toGpx(name, tracks)
    }

    /**
     * Löscht Aufzeichnungen endgültig: Abschnitte, Rohpunkte und die Aufzeichnung selbst, in einem Schritt.
     * Ein Tag besteht aus den Aufzeichnungen seiner Abschnitte.
     */
    suspend fun deleteSessions(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.withTransaction {
            db.runs().deleteForSessions(ids)
            db.points().deleteForSessions(ids)
            db.sessions().deleteAll(ids)
        }
    }

    /**
     * Entfernt eine einzelne Fahrt aus Tag, Statistik und Karte (zum Beispiel einen Fehlalarm). Die Messpunkte der
     * Aufzeichnung bleiben bestehen und sind weiter im GPX-Export enthalten.
     */
    suspend fun deleteRun(runId: Long) {
        db.runs().deleteById(runId)
    }

    /** Beendet Aufzeichnungen, die nicht sauber gestoppt wurden (App oder Handy abgestürzt). */
    suspend fun recoverUnfinished() {
        for (s in db.sessions().unfinished()) {
            val last = db.points().forSession(s.id).lastOrNull()?.timeMs
            if (last == null) {
                // Keine Punkte: leere Aufzeichnung, als beendet markieren.
                db.sessions().update(s.copy(endMs = s.startMs, finished = true))
            } else {
                finishSession(s.id, last)
            }
        }
    }
}
