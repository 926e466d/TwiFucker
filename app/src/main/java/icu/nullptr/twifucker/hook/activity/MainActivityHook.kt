package icu.nullptr.twifucker.hook.activity

import android.app.Activity
import android.content.Intent
import android.view.MotionEvent
import com.github.kyuubiran.ezxhelper.AndroidLogger
import com.github.kyuubiran.ezxhelper.EzXHelper
import com.github.kyuubiran.ezxhelper.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import icu.nullptr.twifucker.BuildConfig
import icu.nullptr.twifucker.hook.BaseHook
import icu.nullptr.twifucker.hook.HookEntry.Companion.currentActivity
import icu.nullptr.twifucker.hook.ShareMediaHook
import icu.nullptr.twifucker.modulePrefs
import icu.nullptr.twifucker.ui.SettingsDialog

object MainActivityHook : BaseHook() {
    override val name: String
        get() = "MainActivityHook"

    private var firstRunDone = false

    override fun init() {
        val cl = EzXHelper.classLoader ?: return

        // X 12.x renamed the activity package (com.twitter.app.main ->
        // com.x.android.main) and may not declare onResume itself, so hook
        // the framework Activity.onResume and filter by the concrete class.
        val mainActivityNames = setOf(
            "com.x.android.main.MainActivity",
            "com.twitter.app.main.MainActivity",
        )
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.thisObject?.javaClass?.name !in mainActivityNames) return
                        if (!firstRunDone) {
                            firstRunDone = true
                            if (BuildConfig.DEBUG || modulePrefs.getBoolean("first_run", true)) {
                                SettingsDialog(param.thisObject as Activity)
                                modulePrefs.putBoolean("first_run", false)
                            }
                            if (modulePrefs.getBoolean("show_toast", true)) {
                                AndroidLogger.toast("TwiFucker version ${BuildConfig.VERSION_NAME}")
                            }
                        }
                        // a pending download from a share intent — the chooser
                        // (separate activity) has closed, show the dialog now
                        val pending = ShareMediaHook.takePendingDownload()
                        if (pending != null) {
                            icu.nullptr.twifucker.ui.DownloadDialog(
                                param.thisObject as Activity, pending.first, pending.second) {}
                        }
                    } catch (t: Throwable) {
                        Log.e(t)
                    }
                }
            }
        )

        // Settings entry on 12.12.0+: the UI is Jetpack Compose, so the legacy
        // logo long-press / About version-click entries don't exist. Add a
        // long-press on the home top-left avatar (drawer toggle) to reopen the
        // TwiFucker settings dialog.
        hookComposeEntry()

        // X 12.13.0's MainActivity swallows activity results, so the settings
        // dialog's SAF folder picker / log export results never reach the
        // PrefsFragment via the fragment manager. Intercept results at the
        // framework level (and the concrete MainActivity override, if X
        // redefines the method) and forward them to the settings dialog.
        hookActivityResult(cl)
    }

    private fun hookActivityResult(cl: ClassLoader) {
        val forward = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val act = param.thisObject as? Activity ?: return
                    if (act.javaClass.name !in setOf(
                            "com.x.android.main.MainActivity",
                            "com.twitter.app.main.MainActivity",
                        )
                    ) return
                    // dispatchActivityResult(String, int, int, Intent, String) and
                    // internalDispatchActivityResult(..., ComponentCaller, String):
                    // args = who, requestCode, resultCode, data, [caller, pkg]
                    // onActivityResult(int, int, Intent[, ComponentCaller]): args =
                    // requestCode, resultCode, data[, caller]
                    val offset = if (param.method.name.contains("ispatch")) 1 else 0
                    val requestCode = param.args[offset] as Int
                    val resultCode = param.args[offset + 1] as Int
                    val data = param.args[offset + 2] as? Intent
                    SettingsDialog.forwardActivityResult(requestCode, resultCode, data)
                } catch (t: Throwable) {
                    Log.e(t)
                }
            }
        }
        // framework entry — Android 16 delivers results through
        // dispatchActivityResult(String, int, int, Intent, String) (the old
        // 3-arg form and ActivityThread.deliverActivityResult are gone)
        kotlin.runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "dispatchActivityResult", String::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Intent::class.java,
                String::class.java, forward)
            Log.d("MainActivityHook: hooked Activity.dispatchActivityResult")
        }.onFailure { Log.e(it) }
        // framework legacy onActivityResult — fires when nothing overrides it
        kotlin.runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onActivityResult", Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Intent::class.java, forward)
            Log.d("MainActivityHook: hooked Activity.onActivityResult")
        }.onFailure { Log.e(it) }
        // Android 16's ComponentCaller path
        kotlin.runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onActivityResult", Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Intent::class.java,
                "android.app.ComponentCaller", forward)
            Log.d("MainActivityHook: hooked Activity.onActivityResult(caller)")
        }.onFailure { Log.e(it) }
        kotlin.runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "internalDispatchActivityResult", String::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Intent::class.java,
                "android.app.ComponentCaller", String::class.java, forward)
            Log.d("MainActivityHook: hooked Activity.internalDispatchActivityResult")
        }.onFailure { Log.e(it) }
        // concrete overrides — X may override the delivery methods in an
        // intermediate base class (skipping super), and findAndHookMethod
        // only matches the exact class, so walk the hierarchy and hook the
        // nearest class that declares any delivery method.
        listOf("com.x.android.main.MainActivity", "com.twitter.app.main.MainActivity").forEach { name ->
            kotlin.runCatching {
                var c: Class<*>? = XposedHelpers.findClass(name, cl)
                var hooked = false
                while (c != null && c != Activity::class.java) {
                    val found = kotlin.runCatching {
                        XposedHelpers.findAndHookMethod(
                            c, "dispatchActivityResult", String::class.java,
                            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                            Intent::class.java, String::class.java, forward)
                    }.isSuccess || kotlin.runCatching {
                        XposedHelpers.findAndHookMethod(
                            c, "onActivityResult", Int::class.javaPrimitiveType!!,
                            Int::class.javaPrimitiveType!!, Intent::class.java, forward)
                    }.isSuccess || kotlin.runCatching {
                        XposedHelpers.findAndHookMethod(
                            c, "onActivityResult", Int::class.javaPrimitiveType!!,
                            Int::class.javaPrimitiveType!!, Intent::class.java,
                            "android.app.ComponentCaller", forward)
                    }.isSuccess
                    if (found) {
                        Log.d("MainActivityHook: hooked result delivery on ${c.name}")
                        hooked = true
                        break
                    }
                    c = c.superclass
                }
                if (!hooked) Log.d("MainActivityHook: no result-delivery override in $name hierarchy")
            }
        }
        Log.d("MainActivityHook: activity-result forwarding installed")
    }

    private fun hookComposeEntry() {
        var downTime = 0L
        // Activity.dispatchTouchEvent is the reliable per-activity touch entry
        // (framework hooking proven to work on this device — see onResume).
        // The top-left region is the avatar / X logo on the home screen.
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "dispatchTouchEvent", MotionEvent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.thisObject?.javaClass?.name !in setOf(
                                "com.x.android.main.MainActivity",
                                "com.twitter.app.main.MainActivity",
                            )
                        ) return
                        val ev = param.args[0] as MotionEvent
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                // home top-left: the avatar / X logo area
                                if (ev.rawX < 260f && ev.rawY < 400f) {
                                    downTime = System.currentTimeMillis()
                                } else {
                                    downTime = 0
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                val elapsed = if (downTime != 0L) System.currentTimeMillis() - downTime else 0L
                                if (downTime != 0L && elapsed > 500 && ev.rawX < 320f && ev.rawY < 450f) {
                                    downTime = 0
                                    currentActivity.get()?.let { act ->
                                        SettingsDialog(act)
                                        Log.d("TwiFucker settings opened via avatar long-press")
                                    }
                                }
                                downTime = 0
                            }
                            MotionEvent.ACTION_CANCEL -> downTime = 0
                        }
                    } catch (t: Throwable) {
                        Log.e(t)
                    }
                }
            }
        )
        Log.d("MainActivityHook: avatar long-press entry hooked")
    }
}
