package app.pwdr.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Eine Aufzeichnung (Start bis Stopp). Ein Skitag ist die Summe der Aufzeichnungen eines Datums. */
@Entity
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long? = null,
    val finished: Boolean = false,
    /** Wurde der Beschleunigungssensor aufgezeichnet? Null bei Aufzeichnungen vor Version 5. */
    val gForce: Boolean? = null,
    /** Trageposition (Schlüssel von CarryPosition), null wenn G-Force aus war. */
    val carry: String? = null,
    /** Akkustand in Prozent beim Start und beim Stopp, null wenn nicht bekannt. */
    val batteryStartPct: Int? = null,
    val batteryEndPct: Int? = null,
)

/** Roher GPS-Punkt. Wird laufend gespeichert, damit nach einem Absturz nichts verloren geht. */
@Entity(indices = [Index("sessionId", "timeMs")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val speedMs: Double,
    val accuracyM: Float,
    /** Rohe GPS-Höhe; altM ist ab Version 2 barometrisch korrigiert (falls Sensor vorhanden). */
    val gpsAltM: Double? = null,
    /** Höchster und mittlerer G-Wert seit dem vorigen Punkt (ab Version 4), null ohne Sensor. */
    val gMax: Double? = null,
    val gMean: Double? = null,
)

fun TrackPointEntity.toSample() =
    app.pwdr.tracking.Sample(timeMs, lat, lon, altM, speedMs, accuracyM, gpsAltM, gMax, gMean)

/** Ein erkannter Abschnitt: Abfahrt, Lift oder Pause (type = Name von Activity). */
@Entity(indices = [Index("sessionId")])
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val type: String,
    val startMs: Long,
    val endMs: Long,
    val distanceM: Double,
    val vertDownM: Double,
    val vertUpM: Double,
    val maxSpeedMs: Double,
    val avgSpeedMs: Double,
    /** Pistenname bzw. Liftname aus OSM, null wenn nicht zugeordnet. */
    val name: String? = null,
    /** Schwierigkeit der Piste laut OSM (easy, intermediate, advanced ...). */
    val difficulty: String? = null,
    /** Höchster und mittlerer G-Wert des Abschnitts (ab Version 4), null ohne Messwerte. Richtwert, hängt von der Trageposition ab. */
    val maxG: Double? = null,
    val avgG: Double? = null,
)

/** Skigebiet, dessen OSM-Daten lokal gespeichert sind. */
@Entity
data class ResortEntity(
    @PrimaryKey val id: String,
    val name: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val loadedAtMs: Long,
)

/** OSM-Piste oder -Lift. [coords] ist die Geometrie als "lat,lon;lat,lon;...". */
@Entity(primaryKeys = ["resortId", "osmId"])
data class OsmWayEntity(
    val resortId: String,
    val osmId: Long,
    val kind: String,
    val name: String?,
    val difficulty: String?,
    val subtype: String?,
    val coords: String,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM SessionEntity WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Query("SELECT * FROM SessionEntity WHERE finished = 0")
    suspend fun unfinished(): List<SessionEntity>

    @Query("DELETE FROM SessionEntity WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)
}

@Dao
interface PointDao {
    @Insert
    suspend fun insertAll(points: List<TrackPointEntity>)

    @Query("SELECT * FROM TrackPointEntity WHERE sessionId = :sessionId ORDER BY timeMs")
    suspend fun forSession(sessionId: Long): List<TrackPointEntity>

    @Query("DELETE FROM TrackPointEntity WHERE sessionId IN (:ids)")
    suspend fun deleteForSessions(ids: List<Long>)
}

@Dao
interface OsmDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertResort(resort: ResortEntity)

    /** Ändert sich, sobald ein Skigebiet (neu) geladen wurde. */
    @Query("SELECT loadedAtMs FROM ResortEntity ORDER BY id")
    fun observeLoadTimes(): Flow<List<Long>>

    @Query("SELECT * FROM ResortEntity WHERE id = :id")
    suspend fun resort(id: String): ResortEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertWays(ways: List<OsmWayEntity>)

    @Query("DELETE FROM OsmWayEntity WHERE resortId = :resortId")
    suspend fun deleteWays(resortId: String)

    @Query(
        "SELECT * FROM OsmWayEntity WHERE maxLat >= :minLat AND minLat <= :maxLat " +
            "AND maxLon >= :minLon AND minLon <= :maxLon",
    )
    suspend fun waysIn(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<OsmWayEntity>

    /** Ersetzt die Daten eines Skigebiets atomar. */
    @androidx.room.Transaction
    suspend fun replaceResort(resort: ResortEntity, ways: List<OsmWayEntity>) {
        deleteWays(resort.id)
        insertWays(ways)
        insertResort(resort)
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE RunEntity ADD COLUMN name TEXT")
        db.execSQL("ALTER TABLE RunEntity ADD COLUMN difficulty TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS ResortEntity (id TEXT NOT NULL, name TEXT NOT NULL, south REAL NOT NULL, " +
                "west REAL NOT NULL, north REAL NOT NULL, east REAL NOT NULL, loadedAtMs INTEGER NOT NULL, PRIMARY KEY(id))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS OsmWayEntity (resortId TEXT NOT NULL, osmId INTEGER NOT NULL, kind TEXT NOT NULL, " +
                "name TEXT, difficulty TEXT, subtype TEXT, coords TEXT NOT NULL, minLat REAL NOT NULL, minLon REAL NOT NULL, " +
                "maxLat REAL NOT NULL, maxLon REAL NOT NULL, PRIMARY KEY(resortId, osmId))",
        )
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE TrackPointEntity ADD COLUMN gMax REAL")
        db.execSQL("ALTER TABLE TrackPointEntity ADD COLUMN gMean REAL")
        db.execSQL("ALTER TABLE RunEntity ADD COLUMN maxG REAL")
        db.execSQL("ALTER TABLE RunEntity ADD COLUMN avgG REAL")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE SessionEntity ADD COLUMN gForce INTEGER")
        db.execSQL("ALTER TABLE SessionEntity ADD COLUMN carry TEXT")
        db.execSQL("ALTER TABLE SessionEntity ADD COLUMN batteryStartPct INTEGER")
        db.execSQL("ALTER TABLE SessionEntity ADD COLUMN batteryEndPct INTEGER")
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE TrackPointEntity ADD COLUMN gpsAltM REAL")
    }
}

@Dao
interface RunDao {
    @Insert
    suspend fun insertAll(runs: List<RunEntity>)

    @Query("DELETE FROM RunEntity WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM RunEntity WHERE sessionId IN (:ids)")
    suspend fun deleteForSessions(ids: List<Long>)

    @Query("SELECT * FROM RunEntity ORDER BY startMs DESC")
    fun observeAll(): Flow<List<RunEntity>>

    @Query("SELECT * FROM RunEntity WHERE type = 'DOWNHILL' AND startMs >= :fromMs")
    suspend fun downhillsSince(fromMs: Long): List<RunEntity>
}

@Database(
    entities = [
        SessionEntity::class, TrackPointEntity::class, RunEntity::class,
        ResortEntity::class, OsmWayEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class PwdrDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun points(): PointDao
    abstract fun runs(): RunDao
    abstract fun osm(): OsmDao

    companion object {
        @Volatile private var instance: PwdrDatabase? = null

        fun get(context: Context): PwdrDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PwdrDatabase::class.java,
                "pwdr.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
        }
    }
}
