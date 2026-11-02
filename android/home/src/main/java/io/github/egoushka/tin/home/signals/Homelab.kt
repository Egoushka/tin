package io.github.egoushka.tin.home.signals

import org.json.JSONArray

/** One Gatus endpoint with its newest check results, oldest first (true = passed). */
data class Probe(val name: String, val group: String, val recent: List<Boolean>) {
    /** Broken after [FAILS_TO_BREAK] failed checks in a row; one failure keeps the card calm. */
    val broken: Boolean get() = recent.size >= Homelab.FAILS_TO_BREAK && recent.takeLast(Homelab.FAILS_TO_BREAK).none { it }
}

data class Homelab(val total: Int, val broken: List<Probe>) {
    val lit: Boolean get() = broken.isNotEmpty()

    companion object {
        const val FAILS_TO_BREAK = 2
        const val MAX_ROWS = 2
        val Empty = Homelab(0, emptyList())

        /** The body of `GET /api/v1/endpoints/statuses`: a JSON array of `{name, group, results: [{success}]}`. */
        fun parse(json: String): Homelab {
            val a = JSONArray(json)
            val probes = List(a.length()) { i ->
                val o = a.getJSONObject(i)
                val r = o.optJSONArray("results")
                Probe(o.optString("name"), o.optString("group"), List(r?.length() ?: 0) { j -> r!!.getJSONObject(j).optBoolean("success") })
            }
            return of(probes)
        }

        fun of(probes: List<Probe>) = Homelab(probes.size, probes.filter { it.broken }.sortedBy { it.name })
    }
}
