package icu.nullptr.twifucker.hook

import android.app.Activity
import android.content.Intent
import com.github.kyuubiran.ezxhelper.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import icu.nullptr.twifucker.hook.HookEntry.Companion.currentActivity
import icu.nullptr.twifucker.ui.DownloadDialog

/**
 * Download feature for X 12.12.0+.
 *
 * The legacy TwiFucker DownloadHook injects a button into the old in-app
 * action sheet — dead on 12.12.0 where the share sheet is Jetpack Compose.
 * New design: OkHttpFilterHook.captureTweetMedia() records tweet media URLs
 * while parsing timeline responses; this hook watches the share intent
 * (ACTION_SEND) and shows the DownloadDialog with the captured media for the
 * shared tweet.
 */
object ShareMediaHook : BaseHook() {

    @Volatile
    private var pendingTweetId = 0L
    @Volatile
    private var pendingMedia: List<TweetMedia>? = null

    /** consumed by MainActivityHook.onResume after the chooser closes */
    fun takePendingDownload(): Pair<Long, List<TweetMedia>>? {
        val m = pendingMedia
        if (m == null) return null
        pendingMedia = null
        return pendingTweetId to m
    }

    override val name: String
        get() = "ShareMediaHook"

    override fun init() {
        // Activity hooks are proven reliable on this device (the View-level
        // dispatch hook did not fire) — use Activity.startActivity*.
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val intent = param.args[0] as Intent
                    // the system chooser wraps the real intent (ACTION_CHOOSER
                    // with EXTRA_INTENT) — unwrap it
                    val real = if (intent.action == Intent.ACTION_CHOOSER) {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    } else intent
                    if (real == null || real.action != Intent.ACTION_SEND) return
                    val url = real.getStringExtra(Intent.EXTRA_TEXT)
                    if (url == null) return
                    val m = Regex("status/(\\d+)").find(url) ?: return
                    val tweetId = m.groupValues[1]
                    val media = OkHttpFilterHook.tweetMediaInfo(tweetId)
                    if (media.isNullOrEmpty()) return
                    // the system chooser is a separate activity — the dialog
                    // would appear behind it. Store the pending download and
                    // let MainActivityHook show it when the user returns.
                    pendingTweetId = tweetId.toLongOrNull() ?: 0L
                    pendingMedia = media
                    Log.d("ShareMediaHook: pending download for tweet $tweetId (${media.size} items)")
                } catch (t: Throwable) {
                    Log.e(t)
                }
            }
        }
        // the share sheet's "Share via…" launches the system chooser with
        // startActivityForResult — hook every launch variant
        XposedHelpers.findAndHookMethod(Activity::class.java, "startActivity", Intent::class.java, hook)
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "startActivity", Intent::class.java, android.os.Bundle::class.java, hook)
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "startActivityForResult", Intent::class.java, Int::class.javaPrimitiveType!!, hook)
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "startActivityForResult", Intent::class.java, Int::class.javaPrimitiveType!!,
            android.os.Bundle::class.java, hook)
        Log.d("ShareMediaHook installed")
    }
}
