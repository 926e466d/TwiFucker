package icu.nullptr.twifucker.hook

import com.github.kyuubiran.ezxhelper.EzXHelper
import com.github.kyuubiran.ezxhelper.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * The primary ad filter for X 12.12.0+.
 *
 * Why: on 12.12.0 the timelines no longer parse through LoganSquare/Jackson
 * (verified live), so the TwiFucker JsonHook boundary is dead. All traffic
 * still flows through okhttp3 (un-obfuscated public API).
 *
 * We hook okhttp3.internal.http.BridgeInterceptor.intercept(Chain) directly
 * (Xposed can hook the app's class natively — no dynamic proxy needed). The
 * response it returns has an already-DECOMPRESSED, freshly created body that
 * nobody has read yet, so we can safely read it, strip promoted entries, and
 * replace the body.
 *
 * Classloader note: the module runs in an isolated classloader, so all okhttp
 * interaction is done reflectively — no okhttp types are referenced.
 */
object OkHttpFilterHook : BaseHook() {

    override val name: String
        get() = "OkHttpFilterHook"

    // tweetId -> media info, captured from timeline responses for the
    // download feature (12.12.0's share sheet is Compose — no injection
    // possible, so the download dialog triggers on the share intent)
    private val tweetMediaCache = java.util.concurrent.ConcurrentHashMap<String, List<TweetMedia>>()
    private var mediaLogCount = 0

    /** full metadata for the download dialog */
    fun tweetMediaInfo(tweetId: String): List<TweetMedia>? = tweetMediaCache[tweetId]

    /** a smaller preview variant of a pbs.twimg.com URL if known */
    private fun thumbVariant(url: String): String? {
        return when {
            url.contains("name=orig") -> url.replace("name=orig", "name=thumb")
            url.contains(":orig") -> url.replace(":orig", ":thumb")
            else -> url
        }
    }

    fun captureTweetMedia(contentObj: org.json.JSONObject?) {
        try {
            if (contentObj == null) return
            val result = contentObj.optJSONObject("tweet_results")?.optJSONObject("result") ?: return
            val tweetId = result.optString("rest_id")
            if (tweetId.isEmpty()) return
            val mediaEntities = result.optJSONArray("media_entities") ?: return
            val media = mutableListOf<TweetMedia>()
            for (i in 0 until mediaEntities.length()) {
                val me = mediaEntities.optJSONObject(i) ?: continue
                val mr = me.optJSONObject("media_results")?.optJSONObject("result") ?: continue
                val mi = mr.optJSONObject("media_info") ?: continue
                when (mi.optString("__typename")) {
                    "ApiImage" -> {
                        val u = mi.optString("original_img_url")
                        if (u.isNotEmpty()) {
                            media.add(
                                TweetMedia(
                                    kind = "image",
                                    url = u,
                                    thumbUrl = thumbVariant(u),
                                    width = mi.optInt("original_width", 0),
                                    height = mi.optInt("original_height", 0),
                                )
                            )
                        }
                    }
                    "ApiVideo" -> {
                        val variants = mi.optJSONArray("variants")
                        var best: Pair<Int, String>? = null
                        if (variants != null) {
                            for (v in 0 until variants.length()) {
                                val vv = variants.optJSONObject(v) ?: continue
                                val br = vv.optInt("bit_rate", 0)
                                val u = vv.optString("url")
                                if (u.isNotEmpty() && (best == null || br > best.first)) {
                                    best = br to u
                                }
                            }
                        }
                        best?.second?.let { u ->
                            // poster thumbnail lives under poster_image /
                            // thumbnail_image (ApiImage) in the video's media_info
                            val poster = mi.optJSONObject("poster_image")
                                ?: mi.optJSONObject("thumbnail_image")
                            media.add(
                                TweetMedia(
                                    kind = "video",
                                    url = u,
                                    thumbUrl = poster?.optString("original_img_url")
                                        ?.let(::thumbVariant),
                                    bitRate = best?.first ?: 0,
                                    durationMillis = mi.optLong("duration_millis", 0),
                                )
                            )
                        }
                    }
                }
            }
            if (media.isNotEmpty()) {
                tweetMediaCache[tweetId] = media
                if (mediaLogCount < 5) {
                    mediaLogCount++
                    Log.d("OkHttpFilterHook: captured media for tweet $tweetId (${media.size} items)")
                }
                if (tweetMediaCache.size > 400) {
                    val it = tweetMediaCache.keys.iterator()
                    if (it.hasNext()) { it.next(); it.remove() }
                }
            }
        } catch (t: Throwable) {
        }
    }

    override fun init() {
        val cl = EzXHelper.classLoader ?: return
        val handler = FilterHandler(cl)

        // ---- response side (fallback): filter at BridgeInterceptor ----
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val response = param.result ?: return
                    val filtered = handler.filterResponse(response)
                    if (filtered !== response) param.result = filtered
                } catch (t: Throwable) {
                    Log.e(t)
                }
            }
        }

        // ---- request side (primary): strip ad params at the chain level ----
        val reqHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val req = param.args[0] ?: return
                    val stripped = handler.stripRequest(req)
                    if (stripped !== req) param.args[0] = stripped
                } catch (t: Throwable) {
                    Log.e(t)
                }
            }
        }

        // okhttp classes load lazily with okhttp — hook when they appear
        val loaderHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                when (param.args[0]) {
                    "okhttp3.internal.http.BridgeInterceptor" ->
                        (param.result as? Class<*>)?.let { hookBridge(it, hook) }
                    "okhttp3.internal.http.RealInterceptorChain" ->
                        (param.result as? Class<*>)?.let { hookChain(it, reqHook) }
                }
            }
        }
        val bridgeCls = XposedHelpers.findClassIfExists(
            "okhttp3.internal.http.BridgeInterceptor", cl)
        if (bridgeCls != null) hookBridge(bridgeCls, hook)
        val chainCls = XposedHelpers.findClassIfExists(
            "okhttp3.internal.http.RealInterceptorChain", cl)
        if (chainCls != null) hookChain(chainCls, reqHook)
        XposedHelpers.findAndHookMethod(
            ClassLoader::class.java, "loadClass", String::class.java, loaderHook
        )
        XposedHelpers.findAndHookMethod(
            ClassLoader::class.java, "loadClass", String::class.java,
            Boolean::class.javaPrimitiveType!!, loaderHook
        )
        Log.d("OkHttpFilterHook installed")
    }

    private fun hookBridge(bridgeClass: Class<*>, hook: XC_MethodHook) {
        val cl = EzXHelper.classLoader ?: return
        val chainCls = XposedHelpers.findClass("okhttp3.Interceptor\$Chain", cl)
        XposedHelpers.findAndHookMethod(bridgeClass, "intercept", chainCls, hook)
        Log.d("OkHttpFilterHook: hooked BridgeInterceptor.intercept")
    }

    private fun hookChain(chainClass: Class<*>, hook: XC_MethodHook) {
        val cl = EzXHelper.classLoader ?: return
        val requestCls = XposedHelpers.findClass("okhttp3.Request", cl)
        XposedHelpers.findAndHookMethod(chainClass, "proceed", requestCls, hook)
        Log.d("OkHttpFilterHook: hooked RealInterceptorChain.proceed")
    }
}

/** Reflective response filter — no okhttp types referenced. */
private class FilterHandler(private val appCl: ClassLoader) {

    /**
     * REQUEST side (primary): strip ad params from outgoing GraphQL requests
     * so the server has nothing to inject. Fail-soft: returns the original
     * request on any error.
     */
    fun stripRequest(request: Any): Any {
        try {
            val url = request.javaClass.getMethod("url").invoke(request).toString()
            if (!url.contains("graphql")) return request
            val body = request.javaClass.getMethod("body").invoke(request) ?: return request
            val ct = body.javaClass.getMethod("contentType").invoke(body) ?: return request
            val ctType = ct.javaClass.getMethod("type").invoke(ct) as? String ?: return request
            val ctSub = ct.javaClass.getMethod("subtype").invoke(ct) as? String ?: return request
            if (ctType != "application" || !ctSub.contains("json")) return request
            val text = readBodyText(body) ?: return request

            if (!text.contains("dspClient") && !text.contains("dsp_client") &&
                !text.contains("ad_metadata") && !text.contains("ads_enabled")
            ) return request

            val out = stripRequestJson(text)
            if (out === text || out == text) return request

            // rebuild the request with the stripped body
            val rbCls = XposedHelpers.findClass("okhttp3.RequestBody", appCl)
            val createM = rbCls.methods.firstOrNull { m ->
                m.name == "create" && m.parameterCount == 2 &&
                    m.parameterTypes[1] == String::class.java
            } ?: return request
            val newBody = createM.invoke(null, ct, out)
            val builder = request.javaClass.getMethod("newBuilder").invoke(request)
            val bCls = builder.javaClass
            val methodM = bCls.methods.firstOrNull { it.name == "method" && it.parameterCount == 2 }
                ?: return request
            val reqMethod = request.javaClass.getMethod("method").invoke(request) as? String
                ?: return request
            methodM.invoke(builder, reqMethod, newBody)
            return bCls.getMethod("build").invoke(builder)
        } catch (t: Throwable) {
            Log.e("OkHttpFilterHook: stripRequest failed: " + (t.cause ?: t))
            return request
        }
    }

    // read a RequestBody via writeTo(sink) using a dynamic proxy sink —
    // works regardless of the app's okio package/name
    private fun readBodyText(body: Any): String? {
        return try {
            val writeTo = body.javaClass.methods.firstOrNull { m ->
                m.name == "writeTo" && m.parameterCount == 1
            }
            if (writeTo == null) return null
            val sinkCls = writeTo.parameterTypes[0]
            val sb = StringBuilder()
            val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
                // capture content from ANY argument (String / byte[] / ByteString)
                args?.forEach { a ->
                    when {
                        a is String -> sb.append(a)
                        a is ByteArray -> sb.append(String(a, Charsets.UTF_8))
                        a.javaClass.name.contains("ByteString") ->
                            try { sb.append(a.javaClass.getMethod("utf8").invoke(a)) } catch (e: Throwable) {}
                    }
                }
                // BufferedSink methods are fluent (return the sink) or void —
                // return proper values so the proxy never throws
                val rt = method.returnType
                when {
                    rt == java.lang.Void.TYPE -> null
                    rt.isPrimitive ->
                        if (rt == java.lang.Boolean.TYPE) java.lang.Boolean.FALSE
                        else if (rt == java.lang.Integer.TYPE) 0
                        else if (rt == java.lang.Long.TYPE) 0L
                        else if (rt == java.lang.Byte.TYPE) 0.toByte()
                        else null
                    rt.isInstance(proxy) -> proxy
                    else -> null
                }
            }
            val sink = java.lang.reflect.Proxy.newProxyInstance(appCl, arrayOf(sinkCls), handler)
            writeTo.invoke(body, sink)
            if (sb.isEmpty()) null else sb.toString()
        } catch (t: Throwable) {
            null
        }
    }

    private fun stripRequestJson(text: String): String {
        val obj = try {
            org.json.JSONObject(text)
        } catch (e: Exception) {
            return text
        }
        var changed = false
        // variables: drop the ad auction context
        obj.optJSONObject("variables")?.let { vars ->
            for (k in listOf("dspClientContext", "dsp_client_context")) {
                if (vars.has(k)) {
                    vars.remove(k)
                    changed = true
                }
            }
        }
        // features: disable ad-related flags so the server sends no ads
        obj.optJSONObject("features")?.let { feats ->
            val keys = feats.keys().asSequence().toList()
            for (k in keys) {
                if (k.contains("ad_") || k.contains("ads_") || k.contains("promoted")) {
                    feats.put(k, false)
                    changed = true
                }
            }
        }
        return if (changed) obj.toString() else text
    }

    fun filterResponse(response: Any): Any {
        try {
            val body = response.javaClass.getMethod("body").invoke(response) ?: return response
            val ct = body.javaClass.getMethod("contentType").invoke(body) ?: return response
            val ctType = ct.javaClass.getMethod("type").invoke(ct) as? String ?: return response
            val ctSub = ct.javaClass.getMethod("subtype").invoke(ct) as? String ?: return response
            if (ctType != "application" || !ctSub.contains("json")) return response

            val headers = response.javaClass.getMethod("headers").invoke(response)
            val enc = headers.javaClass.getMethod("get", String::class.java)
                .invoke(headers, "Content-Encoding") as? String

            // X sets its own Accept-Encoding, so okhttp's Bridge does NOT
            // decompress — the body is gzip. Round-trip it: decompress ->
            // filter -> re-compress, keeping the Content-Encoding header.
            if (enc != null && enc.contains("gzip")) {
                return filterGzipped(response, ct, headers)
            }
            if (!enc.isNullOrEmpty()) {
                Log.d("OkHttpFilterHook: skipping encoded response (Content-Encoding=$enc)")
                return response
            }

            val text: String
            try {
                text = body.javaClass.getMethod("string").invoke(body) as String
            } catch (t: Throwable) {
                Log.e("OkHttpFilterHook: body read failed: " + (t.cause ?: t))
                return response
            }
            if (text.isEmpty()) {
                Log.e("OkHttpFilterHook: EMPTY json body ($ctSub) — returning original")
                return response
            }
            val out = if (hasMarkers(text)) transformJson(text) ?: text else text
            if (out !== text && out != text) {
                Log.d("OkHttpFilterHook: stripped ads from a response")
            }
            return try {
                rebuildResponse(response, ct, out)
            } catch (t: Throwable) {
                // never hand back the consumed original — retry with the
                // untransformed text; last resort keeps the original
                Log.e("OkHttpFilterHook: rebuild failed ($t); retrying with original text")
                try {
                    rebuildResponse(response, ct, text)
                } catch (t2: Throwable) {
                    response
                }
            }
        } catch (t: Throwable) {
            Log.e("OkHttpFilterHook: filter failed: " + (t.cause ?: t))
            return response
        }
    }

    // gzip round-trip: decompress -> filter -> re-compress (same encoding).
    // NEVER returns the consumed original: any failure falls back to a
    // byte-identical re-wrap of the raw compressed bytes.
    private fun filterGzipped(response: Any, ct: Any, headers: Any): Any {
        val body = response.javaClass.getMethod("body").invoke(response) ?: return response
        val raw = body.javaClass.getMethod("bytes").invoke(body) as ByteArray
        try {
            val text = String(gunzip(raw), Charsets.UTF_8)
            val out = try {
                if (hasMarkers(text)) transformJson(text) ?: text else text
            } catch (t: Throwable) {
                Log.e("OkHttpFilterHook: transform threw: " + (t.cause ?: t))
                text
            }
            if (out !== text && out != text) {
                Log.d("OkHttpFilterHook: stripped ads (gzip)")
            }
            val compressed = gzip(out)
            return rebuildWithBody(response, ct, headers, compressed, ByteArray::class.java)
        } catch (t: Throwable) {
            Log.e("OkHttpFilterHook: gzip filter failed: " + (t.cause ?: t))
            // last resort: re-wrap the ORIGINAL raw bytes byte-identically
            return try {
                rebuildWithBody(response, ct, headers, raw, ByteArray::class.java)
            } catch (t2: Throwable) {
                response
            }
        }
    }

    private fun gunzip(data: ByteArray): ByteArray {
        val gis = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(data))
        val bos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(65536)
        var n: Int
        while (gis.read(buf).also { n = it } != -1) bos.write(buf, 0, n)
        gis.close()
        return bos.toByteArray()
    }

    private fun gzip(text: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val gos = java.util.zip.GZIPOutputStream(bos)
        gos.write(text.toByteArray(Charsets.UTF_8))
        gos.close()
        return bos.toByteArray()
    }

    // find builder methods by SHAPE (exact-class getMethod breaks on okhttp's
    // Kotlin-generated ResponseBody subclasses)
    private fun rebuildResponse(response: Any, ct: Any, text: String): Any {
        return rebuildWithBody(response, ct, null, text, String::class.java)
    }

    private fun rebuildWithBody(
        response: Any, ct: Any, headers: Any?, payload: Any, payloadType: Class<*>
    ): Any {
        val rbCls = XposedHelpers.findClass("okhttp3.ResponseBody", appCl)
        val createM = rbCls.methods.firstOrNull { m ->
            m.name == "create" && m.parameterCount == 2 &&
                m.parameterTypes[1] == payloadType
        } ?: throw NoSuchMethodException("ResponseBody.create(MediaType, $payloadType)")
        val newBody = createM.invoke(null, ct, payload)

        val h = headers ?: response.javaClass.getMethod("headers").invoke(response)
        val hb = h.javaClass.getMethod("newBuilder").invoke(h)
        hb.javaClass.getMethod("removeAll", String::class.java).invoke(hb, "Content-Length")
        val newHeaders = hb.javaClass.getMethod("build").invoke(hb)

        val builder = response.javaClass.getMethod("newBuilder").invoke(response)
        val bCls = builder.javaClass
        val headersM = bCls.methods.firstOrNull { it.name == "headers" && it.parameterCount == 1 }
            ?: throw NoSuchMethodException("Response.Builder.headers")
        val bodyM = bCls.methods.firstOrNull { it.name == "body" && it.parameterCount == 1 }
            ?: throw NoSuchMethodException("Response.Builder.body")
        headersM.invoke(builder, newHeaders)
        bodyM.invoke(builder, newBody)
        return bCls.getMethod("build").invoke(builder)
    }

    private fun hasMarkers(s: String): Boolean =
        s.contains("promotedMetadata") || s.contains("promoted_metadata") ||
            s.contains("adindex_details") || s.contains("ad_metadata_container") ||
            s.contains("advertiser_results") ||
            s.contains("ssp_ad") || s.contains("ssp_image")

    private fun isSspKey(k: String): Boolean =
        k.startsWith("ssp_ad") || k.startsWith("ssp_image")

    private fun transformJson(text: String): String? {
        val obj = try {
            org.json.JSONObject(text)
        } catch (e: Exception) {
            Log.e("OkHttpFilterHook: transform parse failed: $e")
            return null
        }
        var removed = 0
        var scrubbed = 0

        // drop promoted items inside module/thread content, keep organic ones
        fun filterModuleItems(entry: org.json.JSONObject) {
            val content = entry.optJSONObject("content") ?: return
            val items = content.optJSONArray("items")
                ?: content.optJSONObject("timelineModule")?.optJSONArray("items") ?: return
            val kept = org.json.JSONArray()
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i)
                if (it != null && hasMarkers(it.toString())) {
                    removed++
                    continue
                }
                kept.put(it)
            }
            if (kept.length() != items.length()) {
                if (content.has("items")) content.put("items", kept)
                else content.optJSONObject("timelineModule")?.put("items", kept)
            }
        }

        fun processEntries(arr: org.json.JSONArray): org.json.JSONArray {
            val kept = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val content = e.optJSONObject("content")
                val isModule = content != null && (
                    content.has("items") ||
                        (content.optJSONObject("timelineModule")?.has("items") == true)
                    )
                if (hasMarkers(e.toString())) {
                    OkHttpFilterHook.captureTweetMedia(
                        e.optJSONObject("content")?.optJSONObject("content"))
                    if (isModule) {
                        filterModuleItems(e)
                        val still = e.optJSONObject("content")
                        val itemsLeft = still?.optJSONArray("items")
                            ?: still?.optJSONObject("timelineModule")?.optJSONArray("items")
                        if (itemsLeft != null && itemsLeft.length() > 0) kept.put(e)
                        else removed++   // module fully emptied -> dropped
                    } else {
                        removed++        // plain ad entry -> dropped
                    }
                } else {
                    OkHttpFilterHook.captureTweetMedia(
                        e.optJSONObject("content")?.optJSONObject("content"))
                    filterModuleItems(e)
                    kept.put(e)
                }
            }
            return kept
        }

        fun walk(node: Any?) {
            when (node) {
                is org.json.JSONArray ->
                    for (i in 0 until node.length()) walk(node.opt(i))
                is org.json.JSONObject -> {
                    val keys = node.keys()
                    val sspKeys = mutableListOf<String>()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (isSspKey(k)) {
                            sspKeys.add(k)
                            scrubbed++
                            continue
                        }
                        val v = node.opt(k)
                        if (v is org.json.JSONArray && (k == "entries" || k == "addEntries")) {
                            node.put(k, processEntries(v))
                            val arr = node.getJSONArray(k)
                            for (i in 0 until arr.length()) walk(arr.opt(i))
                        } else {
                            walk(v)
                        }
                    }
                    sspKeys.forEach { node.remove(it) }
                }
            }
        }

        walk(obj)
        if (removed == 0 && scrubbed == 0) return null
        Log.d("OkHttpFilterHook: removed $removed promoted item(s), scrubbed $scrubbed ssp key(s)")
        return obj.toString()
    }
}

/**
 * One captured media entry from a tweet. kind is "image" or "video";
 * width/height only present for ApiImage (videos carry their resolution
 * inside the variant URL).
 */
class TweetMedia(
    val kind: String,
    val url: String,
    val thumbUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val bitRate: Int = 0,
    val durationMillis: Long = 0,
)
