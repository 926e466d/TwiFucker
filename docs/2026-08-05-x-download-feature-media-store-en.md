# X Tweet Media Download Feature (Compose UI) — Session Summary

**Date**: 2026-08-05
**Working Directory**: E:\Work\Research\mq_test
**Context**: Continuation of the X (Twitter) ads-bypass work (see `2026-08-05-x-ads-bypass-frida-lsposed-module-en.md`). The ad filter on X 12.12.0 was working; this session restored the module settings UI and rebuilt the tweet-media download feature, which was dead because 12.12.0 replaced the legacy View share sheet with Jetpack Compose.

---

## Problem

TwiFucker's legacy `DownloadHook` injected a "Download" button into the old in-app action sheet. On X 12.12.0 the share sheet is Jetpack Compose, so the button never appears. The user asked to fix the download feature and, later, instructed: "you can get the log not ask me manual" — i.e. drive the on-device test flow via adb myself and read logs instead of asking for manual checks.

## Investigation

1. **Confirmed the legacy path is dead**: `DownloadHook` injects into the legacy View-based share sheet; 12.12.0 uses Compose — no injection point. Verified with UI dumps + logcat.
2. **Located the 12.12.0 media structure**: `tweet_results.result.media_entities[].media_results.result.media_info` — `ApiVideo.variants[].url` (pick best bitrate) / `ApiImage.original_img_url`.
3. **Chose the trigger**: watching for the share intent. The share sheet's "Share via…" launches the system chooser via `Activity.startActivityForResult`. Hooking `View.dispatchTouchEvent` proved unreliable on this device (silently no-fire); `Activity.startActivity(Intent)` alone also didn't fire — the actual calls are the **`startActivityForResult(Intent, int[, Bundle])` overloads**.
4. **Chooser discovery**: the system chooser is an `ACTION_CHOOSER` intent that wraps the real `ACTION_SEND` in `Intent.EXTRA_INTENT`. The old action check ran before logging, so `[send]` never appeared — reordered: log every intent first (cap 15), then unwrap.
5. **Dialog-behind-chooser**: the chooser is a separate activity; a dialog shown at launch time appears *behind* it. Fixed by deferring: store a pending download in `ShareMediaHook`, consume it in `MainActivityHook.onResume` (the hook had been unhooked after first run — removed `theHook?.unhook()`).
6. **Scoped storage failure**: DOWNLOAD ALL ran without errors but `/sdcard/Download/TwiFucker/` stayed empty — direct `File` writes to public storage fail on Android 10+ (device is Android 16). Fixed with a MediaStore fallback.
7. **Stray SAF picker**: during the last end-to-end run a Storage Access Framework folder picker appeared at `/storage/emulated/0/Download/` instead of the download dialog (origin unclear — possibly stale state or an accidental interaction); the app was dismissed. The MediaStore build's disk-save result is still to be re-verified.

## Solution

### Files Modified

| File | Change |
|------|--------|
| `X\TwiFucker\app\src\main\java\icu\nullptr\twifucker\hook\OkHttpFilterHook.kt` | Ad filter (gzip round-trip; strips `promoted_metadata`/`adindex_details` entries, scrubs `ssp_ad*`/`ssp_image*`) + **media capture**: `captureTweetMedia(contentObj)` walks `content.tweet_results.result` → `media_entities[].media_results.result.media_info` → `ApiVideo.variants[].url` (best bitrate) / `ApiImage.original_img_url`, stored in `tweetMediaCache` (cap 400, `[media]` diag log), exposed via `fun tweetMedia(tweetId: String): List<String>?` |
| `X\TwiFucker\app\src\main\java\icu\nullptr\twifucker\hook\ShareMediaHook.kt` | New download trigger: hooks all four `Activity.startActivity(Intent[, Bundle])` / `startActivityForResult(Intent, int[, Bundle])` overloads. Logs `[send] action=… method=… act=…` (cap 15) *before* any filter; unwraps `ACTION_CHOOSER` → `EXTRA_INTENT`; extracts `status/(\d+)` from `EXTRA_TEXT`; looks up `OkHttpFilterHook.tweetMedia(tweetId)`; stores `pendingTweetId`/`pendingUrls`; `takePendingDownload()` consumes them |
| `X\TwiFucker\app\src\main\java\icu\nullptr\twifucker\hook\activity\MainActivityHook.kt` | Keeps the `Activity.onResume` hook active (removed `theHook?.unhook()`); on each MainActivity resume, consumes `ShareMediaHook.takePendingDownload()` and shows `DownloadDialog` (deferred until the chooser closes). Added `import icu.nullptr.twifucker.hook.ShareMediaHook`. Existing: first-run SettingsDialog (once per process + `first_run` MMKV pref), version toast, avatar long-press settings entry (`Activity.dispatchTouchEvent`, region x<260/y<400 down, >500 ms, x<320/y<450 up) |
| `X\TwiFucker\app\src\main\java\icu\nullptr\twifucker\ui\DownloadDialog.kt` | `copyFile()` now falls back to MediaStore on scoped-storage failure: insert into `MediaStore.Downloads.EXTERNAL_CONTENT_URI` with `DISPLAY_NAME`/`MIME_TYPE`/`RELATIVE_PATH = Download/TwiFucker`, then `openOutputStream` copy. Dialog flow: `DownloadMediaAdapter` list with per-item copy/download, DOWNLOAD ALL, DISMISS; download = HttpURLConnection → `appContext.cacheDir` file → `copyFile`/`copyFileUri` → `MediaScannerConnection.scanFile` → cache file deleted; `ProgressDialog` during, `Log.e` + "download failed" toast on error |
| `X\TwiFucker\app\src\main\cpp\src\genuine.h` | `GENUINE_SIZE`/`GENUINE_HASH` commented out (anti-tamper `exit(0)` 5 s after start) |
| `X\TwiFucker\app\src\main\java\icu\nullptr\twifucker\hook\HookEntry.kt` | Hook list now includes `ShareMediaHook` (after `DownloadHook`) |

### Key technical details

- **Device-specific hooking rule**: `View.dispatchTouchEvent` hooks silently don't fire on this device; `Activity.dispatchTouchEvent` and `Activity.onResume` (framework-level) DO. All UI triggers use Activity-level hooks.
- **Chooser wrapping**: the share flow is `ACTION_CHOOSER` (with `EXTRA_INTENT` inside) launched via `startActivityForResult` — check both the outer action (log) and the unwrapped real intent (trigger).
- **Deferred dialog**: the chooser is a separate activity; show the dialog on MainActivity `onResume` after the user returns (BACK from chooser).
- **MediaStore fallback**: on Android 10+ scoped storage, direct `File` writes to `/sdcard/Download/…` throw — catch and insert via `MediaStore.Downloads.EXTERNAL_CONTENT_URI` with `RELATIVE_PATH = Downloads/TwiFucker`.
- **MainActivity names**: `com.x.android.main.MainActivity` (12.x) and `com.twitter.app.main.MainActivity` (legacy).
- **Media capture path**: `tweet_results.result.media_entities[].media_results.result.media_info` — `ApiVideo.variants[].url` (pick max bitrate) / `ApiImage.original_img_url`.
- **Module context**: runs in LSPosed isolated classloader (Vector v1.11.0 on KernelSU) — all okhttp interaction in `OkHttpFilterHook` is reflective (dynamic proxy sink over the `writeTo` param type; gzip round-trip since X sets its own `Accept-Encoding: gzip` so okhttp Bridge does not decompress).
- **Reinstall gotcha**: reinstalling the module resets its enabled state — re-enable with `lspd cli modules enable icu.nullptr.twifucker` (see Usage).

## Usage

```bat
:: build (X\TwiFucker)
cd /d E:\Work\Research\mq_test\X\TwiFucker
.\gradlew.bat :app:assembleRelease --no-daemon

:: install + enable
adb -s pixel6a:5555 install -r app\build\outputs\apk\release\app-release.apk
adb -s pixel6a:5555 shell "su -c '/data/adb/lspd/cli modules enable icu.nullptr.twifucker'"

:: verify hooks inited
adb -s pixel6a:5555 logcat -d | grep -E "Inited (MainActivity|ShareMedia|OkHttp)"

:: drive the download flow (all via adb — no manual checks needed)
adb -s pixel6a:5555 shell am force-stop com.twitter.android
adb -s pixel6a:5555 shell am start -n com.twitter.android/com.x.android.main.MainActivity
:: open a tweet with media -> tap share -> tap "Share via…" -> BACK
adb -s pixel6a:5555 shell uiautomator dump /sdcard/ui.xml && adb -s pixel6a:5555 shell cat /sdcard/ui.xml
adb -s pixel6a:5555 logcat -d | grep -E "ShareMediaHook|TwiFucker"
:: expect: [send] action=android.intent.action.CHOOSER ... + pending download for tweet <id> (N urls)
:: after BACK: "Download or Copy" dialog -> tap DOWNLOAD ALL -> ~6 s
adb -s pixel6a:5555 shell ls -la /sdcard/Download/TwiFucker/
adb -s pixel6a:5555 logcat -d | grep -E "download (failed|completed)|Log.e"
```

## Status

- ✅ Ad removal (7 promoted items stripped per fetch, zero Promoted badges)
- ✅ Settings UI (first-run popup + avatar long-press entry)
- ✅ Share trigger (`[send]` + pending download logs)
- ✅ Deferred dialog (appears after BACK from chooser)
- ✅ MediaStore save fallback implemented and installed
- ⏳ Final disk-save verification of the MediaStore build still outstanding (stray SAF picker interrupted the last run); pending cleanup of diagnostic logs (`[media]`, `[down]`, `[send]`).

---

## Follow-up (same session): dialog cosmetics + result-delivery fix

**Problem**: the dialog was "ugly / Media 1 not meaningful". The dialog in use was *already* the original TwiFucker `DownloadDialog` (only the MediaStore fallback was ours) — so upstream offered no better reference; it was upgraded in place.

### Changes

| File | Change |
|------|--------|
| `hook\OkHttpFilterHook.kt` | New top-level `TweetMedia` data class (kind/url/thumbUrl/width/height/bitRate/durationMillis). `captureTweetMedia` now stores metadata: ApiImage → `original_width`/`original_height`, thumb via `name=orig`→`name=thumb` (or `:orig`→`:thumb`); ApiVideo → best-bitrate variant + `poster_image`/`thumbnail_image` thumb + `duration_millis`. New accessor `tweetMediaInfo(tweetId)`; old `tweetMedia()` removed. Diagnostic `[media]` logs reduced to one capped "captured media" line |
| `ui\DownloadItem.kt` | Row reworked: 64 dp rounded `ImageView` thumbnail (async HttpURLConnection + `BitmapFactory.decodeStream` with `inSampleSize=2`, UA header for pbs.twimg.com, token-guarded `view.post` so recycled rows never show stale images) + descriptive label instead of "Media N": `1 · Image · 1200×675` / `1 · Video · 720p · 1:04` / `1 · Video · 1080p · 0:32` (resolution regexed from the variant URL, duration formatted m:ss, bitrate shown when no dims). Measure/layout updated for the thumb, RTL mirrored |
| `ui\DownloadDialog.kt` | Constructor + adapter take `List<TweetMedia>`; click listeners set on every `getView` call (fixes a latent recycling bug where a scrolled row copied/downloaded the wrong item); DOWNLOAD ALL uses `.url` per item |
| `hook\ShareMediaHook.kt` | Pending state holds `List<TweetMedia>`; `takePendingDownload()` returns it; dropped the diagnostic `[send]` intent logging |
| `hook\DownloadHook.kt` | Legacy path builds `TweetMedia` entries (IMAGE → kind=image, VIDEO/ANIMATED_GIF → kind=video) |
| `hook\activity\MainActivityHook.kt` | **Activity-result forwarding**: X 12.13.0/Android 16 never delivers `startActivityForResult` results to the settings dialog's `PrefsFragment` (Android 16 removed the 3-arg `dispatchActivityResult` and `ActivityThread.deliverActivityResult`; X's obfuscated base `com.twitter.app.common.base.h` overrides `onActivityResult` without `super`). Hooks the actual delivery chain — `Activity.dispatchActivityResult(String,int,int,Intent,String)`, `onActivityResult(int,int,Intent[,ComponentCaller])`, `internalDispatchActivityResult(6-arg)` — plus a superclass walk over both MainActivity names to catch intermediate overrides, then forwards to `SettingsDialog.forwardActivityResult`. Removed dead `theHook` field + `[down]` touch logging + unused `downRawX/Y` |
| `ui\SettingsDialog.kt` | `currentFragment` registry (set on dialog init, cleared on dismiss) + `forwardActivityResult()`; the cancel path now also `modulePrefs.sync()` so removal persists immediately |

### Key findings (Android 16 result delivery)

- `Activity.dispatchActivityResult(String,int,int,Intent,String)` is the 5-arg entry (not the 3-arg form found in older docs); `dispatchActivityResult` is *gone* as a 3-arg method, `ActivityThread.deliverActivityResult` is gone, `ActivityClientController` doesn't exist.
- `XposedHelpers.findAndHookMethod` only matches the exact class — an override in an intermediate base class must be found by walking `superclass`.
- `PreferenceFragment` results rely on the host `FragmentActivity` dispatching; X's base class breaks that chain.

### Verification (all via adb)

- Dialog shows `1 · Video · 720p · 1:04` + rounded thumbnail (screenshot); another tweet showed `1 · Video · 1080p · 0:32`.
- DOWNLOAD ALL (default path, pref cleared): `/sdcard/Download/TwiFucker/2084767276176179486_1.mp4` (20 MB) → `MediaScannerConnection: Scanned … → content://media/external_primary/video/media/1000001591`.
- Settings → Select Download Directory → picker cancel → pref removed (`value=null` after remove+sync, persists across process restart; MMKV only marks removal in the index, so `strings` on the file still shows stale bytes — check via the app, not the file).
- The stray SAF picker from the earlier session was explained: a stale `download_directory` tree URI (pointing at Download root) made `copyFileUri` write outside `TwiFucker/`; cleared via the fixed settings path.

### Notes

- `gradle.properties` keeps a machine-local `org.gradle.java.home=D:/Java/jdk-17` (AGP 8.1 is incompatible with the default JDK 22 — jlink transform fails) — intentionally NOT committed; everything else (buildTools 34, Gradle wrapper 8.9, proguard keep for `OkHttpFilterHook`, `genuine.h` anti-tamper disable) is committed.
- Diagnostic logs were trimmed to one capped line per feature (`removed N promoted item(s)`, `captured media …`, `pending download for tweet …`); the `[send]`/`[down]`/`[media]` noise and instrumentation logs are gone.
