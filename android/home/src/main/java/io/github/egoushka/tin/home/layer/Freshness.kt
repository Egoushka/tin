package io.github.egoushka.tin.home.layer

enum class AgeUnit { Now, Min, Hour, Day }

data class Age(val unit: AgeUnit, val n: Int)

/** Source age and staleness, the "evidence on the surface" rule: every card says how old its data is. */
object Freshness {
    /** Three missed refreshes make a source stale (Light.md). */
    const val MISSED_TO_STALE = 3

    fun age(nowMs: Long, atMs: Long): Age {
        val min = ((nowMs - atMs).coerceAtLeast(0) / 60_000).toInt()
        return when {
            min < 1 -> Age(AgeUnit.Now, 0)
            min < 60 -> Age(AgeUnit.Min, min)
            min < 24 * 60 -> Age(AgeUnit.Hour, min / 60)
            else -> Age(AgeUnit.Day, min / (24 * 60))
        }
    }

    /** [intervalMs] is the source's refresh period; a push source (null) is never stale by the clock. */
    fun isStale(nowMs: Long, lastOkMs: Long, intervalMs: Long?): Boolean =
        intervalMs != null && nowMs - lastOkMs > MISSED_TO_STALE * intervalMs
}
