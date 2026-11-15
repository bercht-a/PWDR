package app.pwdr.voice

enum class Priority { LOW, MEDIUM, HIGH }

enum class Kind { SPEED, RECORD, RUN_END, ON_DEMAND, DAY_SUMMARY, FRIEND, SAFETY, TEST }

/**
 * Eine Ansage. [text] wird erst beim Sprechen ausgewertet, damit bei Speed der aktuelle Wert zählt und nicht der
 * vom Zeitpunkt des Einreihens. Nach [maxAgeMs] ohne Sprechen ist die Ansage veraltet und wird verworfen.
 */
class Announcement(
    val kind: Kind,
    val priority: Priority,
    val maxAgeMs: Long,
    val text: () -> String,
) {
    var offeredAtMs: Long = 0
        internal set
}

/**
 * Zentrale Warteschlange der Sprachausgabe (PLAN.md 5b). Höhere Priorität zuerst, bei gleicher der Ältere.
 * Veraltete Ansagen fallen weg, zwischen zwei Ansagen liegt ein Mindestabstand (außer bei hoher Priorität).
 * Von Speed und Abruf wartet höchstens eine Ansage gleichzeitig, es zählt die neueste.
 * Unabhängig von Android, die Uhr wird übergeben.
 */
class SpeechQueue(private val minGapMs: Long = 2_000) {
    private val pending = ArrayList<Announcement>()
    private var lastEndMs: Long = Long.MIN_VALUE / 2

    val size: Int get() = pending.size

    fun offer(a: Announcement, nowMs: Long) {
        a.offeredAtMs = nowMs
        if (a.kind == Kind.SPEED || a.kind == Kind.ON_DEMAND) pending.removeAll { it.kind == a.kind }
        pending.add(a)
    }

    /** Nächste Ansage, die jetzt gesprochen werden darf, oder null. Entfernt sie aus der Warteschlange. */
    fun next(nowMs: Long): Announcement? {
        pending.removeAll { nowMs - it.offeredAtMs > it.maxAgeMs }
        val best = pending
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<Announcement>> { it.value.priority }.thenBy { it.index })
            .firstOrNull()?.value ?: return null
        if (best.priority != Priority.HIGH && nowMs - lastEndMs < minGapMs) return null
        pending.remove(best)
        return best
    }

    /** True, wenn eine Ansage mit hoher Priorität wartet und die laufende weniger wichtig ist. */
    fun shouldInterrupt(current: Priority, nowMs: Long): Boolean =
        pending.any { nowMs - it.offeredAtMs <= it.maxAgeMs && it.priority == Priority.HIGH && it.priority > current }

    fun onSpoken(nowMs: Long) {
        lastEndMs = nowMs
    }

    /** Alles verwerfen, z. B. bei Anruf oder wenn der Kopfhörer weg ist. */
    fun clear() = pending.clear()
}
