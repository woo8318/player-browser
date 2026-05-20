package com.playerbrowser.app.update

object Version {
    fun normalize(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V")

    fun parts(raw: String): List<Int> =
        normalize(raw)
            .split('.', '-', '_')
            .mapNotNull { it.toIntOrNull() }

    /** Returns true when [remote] is strictly newer than [current]. */
    fun isNewer(remote: String, current: String): Boolean {
        val r = parts(remote)
        val c = parts(current)
        val n = maxOf(r.size, c.size)
        for (i in 0 until n) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
