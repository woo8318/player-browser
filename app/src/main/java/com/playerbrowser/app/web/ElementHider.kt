package com.playerbrowser.app.web

import android.content.Context
import android.webkit.WebView
import com.playerbrowser.app.data.ElementHideStore
import com.playerbrowser.app.network.ChallengeDetector
import org.json.JSONArray
import org.json.JSONTokener

/**
 * "요소 숨기기" — applies the site's saved [ElementHideStore] rules as one
 * style sheet of `sel{display:none!important}` rules (see [styleScript]).
 *
 * CSS rather than removing nodes: elements the page re-creates later (infinite
 * scroll, SPA re-render, rotating ad slots) are hidden too, and nothing in the
 * page's own script state is touched. Each selector goes in on its own through
 * `insertRule` inside try/catch — one that no longer parses drops alone, and a
 * single rule can never smuggle in a second one.
 *
 * Runs from `onPageFinished` inside the `!onChallenge` block (plus the checks
 * here) — never on a challenge page (v1.3.87).
 */
object ElementHider {

    /** Main thread. [force] re-applies even an empty list (clears a released rule). */
    fun apply(view: WebView, url: String?, force: Boolean = false) {
        val host = url?.let(VisitedLinkKeys::hostOf) ?: return
        if (ChallengeDetector.isChallengeActive(host)) return
        if (ChallengeDetector.isChallengeTitle(view.title)) return
        val selectors = ElementHideStore.get(view.context).selectors(host)
        if (selectors.isEmpty() && !force) return
        view.evaluateJavascript(styleScript(selectors), null)
    }

    /**
     * Why the picker can't start on this page, or null if it can. The picker is
     * an explicit user action, but it is still page JS — same gate as [apply].
     */
    fun pickBlockedReason(url: String?, title: String?): String? {
        val host = url?.let(VisitedLinkKeys::hostOf)
            ?: return "웹 페이지(http/https)에서만 쓸 수 있어요"
        if (ChallengeDetector.isChallengeActive(host) || ChallengeDetector.isChallengeTitle(title)) {
            return "보안 확인 화면에서는 쓸 수 없어요"
        }
        return null
    }

    /**
     * Prefers a constructable sheet in `document.adoptedStyleSheets`: a strict
     * CSP `style-src` blocks a script-made `<style>` (its `sheet` stays null),
     * but not CSSOM. The `<style>` element is the fallback for WebViews without
     * constructable sheets. Selectors that match `<html>`/`<body>` right now are
     * skipped — never blank the whole page, whatever got stored.
     */
    private fun styleScript(selectors: List<String>): String {
        val arr = JSONArray()
        for (s in selectors) arr.put(s)
        return "(function(sels){try{" +
            "var d=document,w=window,ok=[],i,n=0,R='{display:none!important}';" +
            "for(i=0;i<sels.length;i++){try{" +
            "if(d.documentElement.matches(sels[i])||(d.body&&d.body.matches(sels[i])))continue;" +
            "}catch(e){continue}ok.push(sels[i])}" +
            "var old=d.getElementById('$STYLE_ID');" +
            "if(old&&old.parentNode)old.parentNode.removeChild(old);" +
            "var ad=('adoptedStyleSheets' in d)&&typeof CSSStyleSheet==='function';" +
            "var prev=w.$SHEET_KEY||null;w.$SHEET_KEY=null;" +
            "if(prev&&ad){try{d.adoptedStyleSheets=Array.prototype.filter.call(" +
            "d.adoptedStyleSheets,function(x){return x!==prev})}catch(e){}}" +
            "if(!ok.length)return 0;" +
            "if(ad){try{var cs=new CSSStyleSheet();" +
            "for(i=0;i<ok.length;i++){try{cs.insertRule(ok[i]+R,cs.cssRules.length);n++}catch(e){}}" +
            "d.adoptedStyleSheets=Array.prototype.slice.call(d.adoptedStyleSheets).concat([cs]);" +
            "w.$SHEET_KEY=cs;return n}catch(e){n=0}}" +
            "var el=d.createElement('style');el.id='$STYLE_ID';" +
            "(d.head||d.documentElement).appendChild(el);" +
            "var sh=el.sheet;if(!sh)return -1;" +
            "for(i=0;i<ok.length;i++){try{sh.insertRule(ok[i]+R,sh.cssRules.length);n++}catch(e){}}" +
            "return n}catch(e){return -1}})(" + arr.toString() + ");"
    }

    private const val STYLE_ID = "__pb_hide_css"
    private const val SHEET_KEY = "__pbHideSheet"
}

/**
 * Commands for `element_picker.js` (`window.__pbPicker`). Every call returns
 * its JS result through the `evaluateJavascript` callback (a JSON literal) — a
 * missing module comes back as `"nomodule"`, a thrown error as `"error"`.
 */
object ElementPickerCommands {

    private const val MAX_PICK = 20

    /** Defines the module if needed, then starts it: `"ok"` / `"already"` / `"nodoc"`. */
    fun start(context: Context): String =
        WebAssetLoader.elementPickerScript(context) + "\n" + call("start")

    /** Picker + pre-select the element under (cssX, cssY) — link long-press "요소 숨기기" (v1.3.101). */
    fun startAt(context: Context, cssX: Int, cssY: Int): String =
        WebAssetLoader.elementPickerScript(context) + "\n" + call("startAt", "$cssX,$cssY")

    /** Selection count after the step, 0 = nothing selected, -1 = picker not running. */
    val WIDEN: String = call("widen")
    val NARROW: String = call("narrow")

    /** `[{s, l}]` of the selection (which is then cleared), or null if not running. */
    val CONFIRM: String = call("confirm")

    /** Removes the overlay and every listener: `"ok"` / `"idle"`. */
    val EXIT: String = call("exit")

    private fun call(name: String, args: String = ""): String =
        ";(function(){try{return window.__pbPicker?window.__pbPicker.$name($args):'nomodule'}" +
            "catch(e){return 'error'}})();"

    /** A string result (`"ok"` → `ok`), or null. */
    fun parseStatus(raw: String?): String? =
        runCatching { JSONTokener(raw ?: return null).nextValue() as? String }.getOrNull()

    /** A numeric result, or -1 for anything else (`"nomodule"`, `"error"`, null). */
    fun parseCount(raw: String?): Int =
        raw?.trim()?.toDoubleOrNull()?.toInt() ?: -1

    /** CONFIRM's result as (selector, label) — page input, so re-validated here. */
    fun parsePicked(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        val value = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        val arr = when (value) {
            is JSONArray -> value
            is String -> runCatching { JSONArray(value) }.getOrNull()
            else -> null
        } ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until minOf(arr.length(), MAX_PICK)) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.optString("s", "").trim()
            if (!ElementHideStore.validSelector(s)) continue
            out.add(s to o.optString("l", ""))
        }
        return out
    }
}
