package com.playerbrowser.app.data

import android.content.Context
import com.playerbrowser.app.web.VisitedLinkKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * "요소 숨기기" — per-site CSS selectors the user picked to hide, persisted as one
 * JSON map in SharedPreferences (same pattern as [WatchProgressStore]; kept out
 * of Room so it can't trigger a destructive migration).
 *
 * Keyed by the domain-number-agnostic site key (newtoki123 → newtoki#, see
 * [VisitedLinkKeys.siteKey]) so rules survive a mirror hop; hosts without a
 * numbered label fall back to the bare host (`www.` stripped). Rules stay until
 * the user releases them from "숨긴 요소 관리".
 *
 * Selectors come from page JS (`element_picker.js`), so they are page input:
 * [validSelector] refuses anything that could break out of a `sel{…}` rule
 * before it is stored, and [com.playerbrowser.app.web.ElementHider] inserts each
 * one on its own via `insertRule` inside try/catch.
 */
data class HideRule(
    val selector: String,
    val label: String,
    val addedAt: Long
)

class ElementHideStore private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Parsed once; every mutation writes through. Guarded by `this`. */
    private var cache: JSONObject? = null

    /** Rules for [host]'s site, oldest first (the order they were hidden in). */
    @Synchronized
    fun rules(host: String?): List<HideRule> {
        val arr = read().optJSONArray(siteOf(host) ?: return emptyList()) ?: return emptyList()
        val out = ArrayList<HideRule>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.optString("s", "")
            if (!validSelector(s)) continue
            out.add(HideRule(s, o.optString("l", ""), o.optLong("t", 0L)))
        }
        return out
    }

    @Synchronized
    fun selectors(host: String?): List<String> = rules(host).map { it.selector }

    @Synchronized
    fun count(host: String?): Int = rules(host).size

    /**
     * Adds (selector, label) pairs for [host]'s site. Returns the selectors that
     * were actually new — the "되돌리기" batch; duplicates and invalid ones drop.
     */
    @Synchronized
    fun add(host: String?, picked: List<Pair<String, String>>): List<String> {
        val site = siteOf(host) ?: return emptyList()
        val existing = rules(host)
        val known = existing.mapTo(HashSet()) { it.selector }
        val now = System.currentTimeMillis()
        val added = ArrayList<String>()
        val next = ArrayList(existing)
        for ((selector, label) in picked) {
            val s = selector.trim()
            if (!validSelector(s) || !known.add(s)) continue
            next.add(HideRule(s, cleanLabel(label), now))
            added.add(s)
        }
        if (added.isEmpty()) return added
        // Oldest go first when a site outgrows its cap.
        val kept = if (next.size > MAX_RULES_PER_SITE) next.takeLast(MAX_RULES_PER_SITE) else next
        writeSite(site, kept)
        return added.filter { s -> kept.any { it.selector == s } }
    }

    @Synchronized
    fun remove(host: String?, selectors: Collection<String>) {
        if (selectors.isEmpty()) return
        val site = siteOf(host) ?: return
        val drop = selectors.toHashSet()
        val current = rules(host)
        val kept = current.filterNot { it.selector in drop }
        if (kept.size != current.size) writeSite(site, kept)
    }

    @Synchronized
    fun clear(host: String?) {
        val site = siteOf(host) ?: return
        writeSite(site, emptyList())
    }

    private fun writeSite(site: String, rules: List<HideRule>) {
        val root = read()
        if (rules.isEmpty()) {
            root.remove(site)
        } else {
            val arr = JSONArray()
            for (r in rules) {
                arr.put(JSONObject().put("s", r.selector).put("l", r.label).put("t", r.addedAt))
            }
            root.put(site, arr)
            pruneSites(root, keep = site)
        }
        prefs.edit().putString(KEY_RULES, root.toString()).apply()
    }

    /**
     * Drops the sites whose newest rule is oldest until under [MAX_SITES] and
     * [MAX_TOTAL_RULES] — the whole map is one prefs string that Android reads
     * into memory and rewrites on every save, so it has to stay small.
     */
    private fun pruneSites(root: JSONObject, keep: String) {
        val sites = ArrayList<Triple<String, Long, Int>>(root.length())
        var total = 0
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = root.optJSONArray(k)
            var t = 0L
            val n = arr?.length() ?: 0
            for (i in 0 until n) {
                t = maxOf(t, arr?.optJSONObject(i)?.optLong("t", 0L) ?: 0L)
            }
            total += n
            sites.add(Triple(k, t, n))
        }
        if (sites.size <= MAX_SITES && total <= MAX_TOTAL_RULES) return
        sites.sortBy { it.second }
        var count = sites.size
        for ((k, _, n) in sites) {
            if (count <= MAX_SITES && total <= MAX_TOTAL_RULES) break
            if (k == keep) continue
            root.remove(k)
            count--
            total -= n
        }
    }

    private fun read(): JSONObject {
        cache?.let { return it }
        val raw = prefs.getString(KEY_RULES, null)
        val parsed = if (raw == null) JSONObject()
        else runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        cache = parsed
        return parsed
    }

    companion object {
        private const val PREFS_NAME = "element_hide"
        private const val KEY_RULES = "rules"
        private const val MAX_RULES_PER_SITE = 100
        private const val MAX_SITES = 200
        private const val MAX_TOTAL_RULES = 2000
        private const val MAX_SELECTOR = 500
        private const val MAX_LABEL = 80

        /** Site key for [host]: numbered mirrors share one key, others use the host. */
        fun siteOf(host: String?): String? {
            val h = host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return VisitedLinkKeys.siteKey(h) ?: h.removePrefix("www.").takeIf { it.isNotEmpty() }
        }

        /**
         * A selector that can only ever be a selector: no braces / `;` / `@` / `<`
         * (so it cannot close the rule or open another), no control characters.
         *
         * Also refuses what the picker never produces but a hostile page could
         * feed through a fake `__pbPicker`: selector lists (`,`), the universal
         * selector (`*`), and the page roots — one such rule would blank every
         * site sharing the site key until the user released it.
         */
        fun validSelector(s: String): Boolean {
            if (s.isEmpty() || s.length > MAX_SELECTOR) return false
            for (c in s) {
                if (c == '{' || c == '}' || c == ';' || c == '@' || c == '<') return false
                if (c == ',' || c == '*') return false
                if (c.code < 0x20 || c.code == 0x7f || c.code == 0x2028 || c.code == 0x2029) return false
            }
            return s.trim().lowercase() !in ROOT_SELECTORS
        }

        private val ROOT_SELECTORS = setOf("html", "body", ":root", "html > body")

        private fun cleanLabel(label: String): String {
            val flat = label.filter { it.code >= 0x20 && it.code != 0x7f }.trim()
            return if (flat.length > MAX_LABEL) flat.take(MAX_LABEL) + "…" else flat
        }

        @Volatile private var instance: ElementHideStore? = null
        fun get(context: Context): ElementHideStore =
            instance ?: synchronized(this) {
                instance ?: ElementHideStore(context.applicationContext)
                    .also { instance = it }
            }
    }
}
