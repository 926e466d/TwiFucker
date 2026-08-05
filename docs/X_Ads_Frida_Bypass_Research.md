# X (Twitter) Android — Ads Mechanism & Frida Bypass Research

**Target:** `X/src` — X Android v12.9.1 (release), JEB-decompiled
**Date:** 2026-08-03
**Deliverables:** `x_ads_frida_bypass.js` (this repo root), this document

---

## 1. Ads architecture at a glance

X's client ad stack is a **hybrid of server-injected ads and client-fetched programmatic ads**:

| Layer | What | Where in source |
|---|---|---|
| Remote config flags | `ssp_ads_*` (~35 keys) gate every client ad integration | `com.twitter.util.config.y` (getter funnel `g(String,boolean)`) |
| Feature-switch facade | per-surface gate: 14=tweet details, 17/34=home, 22=spotlight, 28=profile, 63=immersive | `com.twitter.ads.featureswitches.a` |
| Request param | `dsp_client_context` attached to timeline GraphQL requests (Google SCAR / auction token) | producer `com.twitter.ads.dsp.{t,q}.b(int)`; consumers `com.twitter.api.legacy.request.urt.timelines.{n,j}`, `profiles.requests.c`, `explore.repository.api.b` |
| Google Ad Manager native ads | client fetches native ads per *server slot* using `ssp_ads_google_dsp_*_ad_unit_id` unit IDs | `com.twitter.ads.dsp.s2c.f` (`ServerSlotClientFetchNativeAdCacheManagerImpl`), `ads.dsp.s2s.e` |
| Google RTB media ads | `b1`-typed timeline items rendered by RTB binder | `com.twitter.timeline.itembinder.b` (`AbstractGoogleRtbMediaAdItemBinder`) |
| IMA video preroll | in-player preroll ads | `media/av/vast/ads/ima/*`, gated by `ssp_ads_preroll_enabled` |
| Server-injected promoted tweets | normal timeline JSON entries carrying `promotedMetadata` — **not gated by any client param** | `model/json/timeline/urt/JsonTimelineTweet.e`, `model/core/entity/ad/*` |
| Ad scribe / analytics | impression & request events per display location | `com.twitter.analytics.util.k` (`b/d/e/f/g`) |

Key config keys (from source enumeration):

```
Enable flags:   ssp_ads_home_enabled, ssp_ads_immersive, ssp_ads_profile,
                ssp_ads_spotlight, ssp_ads_tweet_details, ssp_ads_preroll_enabled,
                ssp_ads_home_client_only_integration, ssp_ads_spotlight_client_only_integration,
                ssp_ads_profile_client_only_integration_enabled, ssp_ads_immersive_client_only_integration,
                ssp_ads_tweet_details_client_only_integration, ssp_ads_dsp_client_context_enabled,
                ssp_ads_google_dsp_client_context_enabled, ssp_ads_google_native_ad_report_enabled,
                ssp_ads_google_native_ad_dsa_report_enabled, ssp_ads_google_native_ad_repository_scribe_enabled,
                ssp_ads_ppid_c2s_enabled, ssp_ads_expanded_app_install_card_enabled, ssp_ads_log_errors
Ad unit IDs:    ssp_ads_google_dsp_tweet_details_ad_unit_id, ssp_ads_google_dsp_spotlight_ad_unit_id,
                ssp_ads_google_dsp_profile_ad_unit_id, ssp_ads_google_dsp_immersive_ad_unit_id,
                ssp_ads_native_ad_unit_id
Tuning:         ssp_ads_google_native_ad_timeout_ms*, ssp_ads_native_ads_lru_cache_count_per_timeline,
                ssp_ads_spotlight_ad_fetch_timeout_milliseconds, ssp_ads_spotlight_allowed_aspect_ratio
```

## 2. Flow details

### 2.1 Home timeline request (`com.twitter.api.legacy.request.urt.timelines.n`)
- Endpoint: `/2/timeline/home.json` (home_timeline GraphQL operation; `home_latest` for 34/76).
- If a `com.twitter.ads.dsp.c` (client context) is injected, `l0()` attaches it as the `dsp_client_context` request param — the only ad-related thing in the request.
- `featureswitches.a.c(loc)` gates the ad scribe (`analytics.util.k.b(loc)`).
- No param exists that tells the server "don't inject promoted content" — the server decides. Client-side flag removal cannot stop server-injected promoted tweets; that requires the response-side filter (L4).

### 2.2 DSP client context (`com.twitter.ads.dsp.t` / `q`)
`b(int loc)` returns `com.twitter.model.timeline.n`:
- If `featureswitches.a.b(loc)` (client-only integration on) → hardcoded auction token `aB3xP9kR2jH4sV1wQ8mL7tZ5cF0yN6dJ8rK2uX4eA7fW1qG9pT3` + user agent.
- Else → Google SCAR context, but only persisted if **both** `ssp_ads_dsp_client_context_enabled` and `ssp_ads_google_dsp_client_context_enabled` are true; otherwise returns `null` → no param on the wire.

### 2.3 GAM native ads (server-slot client fetch, `com.twitter.ads.dsp.s2c.f`)
- `b(int loc)`: maps loc → `ssp_ads_google_dsp_*_ad_unit_id` string; if the config value is **null**, no ad request is ever created (`if (s1 != null)` guard).
- `f(loc, adUnitId)`: builds a `com.google.android.gms.ads.admanager` request with the Twitter user's SCAR/publisher-provided ID header.
- `c(m1 item, loc)`: cache lookup — the itembinder asks for a native ad per timeline item.
- `e(q1 item, timeout)`: async fetch with per-loc timeout; timeout is scribed as `ad/response/timeout`.

### 2.4 Timeline rendering
- `com.twitter.timeline.itembinder.b` (`AbstractGoogleRtbMediaAdItemBinder`) binds `b1` items using the native ad cache manager; config reads inside gate DSA/report UI.
- Server-injected promoted tweets are ordinary `c2` entries (`com.twitter.model.timeline.c2`, wraps `ApiTweet` = `com.twitter.model.core.b`) — the entity carries `com.twitter.model.core.entity.ad.h` promoted metadata.

### 2.5 Display-location enum (`com.twitter.model.timeline.i2`)
`14` tweet details, `17/34/76` home, `22/26` spotlight, `28` profile, `63` immersive, `66/69` … helper predicates `a–f` classify surfaces.

## 3. Frida bypass (`x_ads_frida_bypass.js`) — TwiFucker-fitted

Strategy borrowed from [TwiFucker](https://github.com/Dr-TSNG/TwiFucker)
(Dr-TSNG): **never hook obfuscated classes by name — hook one stable,
un-obfuscated JSON boundary and rewrite the stream before parsing.**

Every API response InputStream in X becomes a Jackson parser via
`com.fasterxml.jackson.core.<obfuscated>.createParser(InputStream)`:

- the factory instance is exposed as `LoganSquare.JSON_FACTORY`
  (`com.bluelinelabs.logansquare.LoganSquare` — third-party lib, kept by R8)
- the URT timeline reader (`com.twitter.api.common.reader.a.g`) calls
  `p.a.createParser(bodyStream)` for home / conversation / profile / explore
  **and GraphQL timeline responses**, then parses with LoganSquare mappers
  (`JsonTimelineResponse$$JsonObjectMapper` → `com.twitter.api.legacy.request.urt.a0`)
- the factory class is obfuscated (`e` in v12.9.1) but located dynamically:
  `LoganSquare.class.getDeclaredField("JSON_FACTORY").getType()`; the method is
  found by shape (single `java.io.InputStream` param). A class-level hook covers
  every factory instance.

| Layer | Hook | Effect |
|---|---|---|
| L1 JSON rewrite (primary) | factory `(InputStream)` method → read stream, transform, replace | removes `entries`/`addEntries` containing `promotedMetadata`/`promoted_metadata` (ads, promoted trends, promoted users, promoted replies; module/thread entries keep organic items); scrubs `ssp_ad*`/`ssp_image*` keys from any JSON — the remote ad flags die at parse time, no config class needed |
| L2 feature gate (exact name) | `com.twitter.ads.featureswitches.a.{a,b,c,d}` → `false` | belt & suspenders; inert when the class is absent |
| L3 GMS/IMA no-op | `AdManagerAdView.loadAd`, `AdLoader.loadAd`, `AdView.loadAd`, `InterstitialAd/RewardedAd/RewardedInterstitialAd/AppOpenAd.load` (multi-overload), `AdsLoader.requestAds` → no-op (stable public SDK) | kills client-fetched GAM/RTB/video ads on any build |

Usage:
```
frida -U -f com.twitter.android -l x_ads_frida_bypass.js     # spawn (preferred)
frida -U -n X -l x_ads_frida_bypass.js                        # attach + pull-to-refresh
```

## 4. Why this design is version-proof

| Obfuscated thing | How it is bypassed |
|---|---|
| Jackson factory class/method names (`e.e`) | discovered via `LoganSquare.JSON_FACTORY` field (kept third-party name) + method shape `(InputStream)` |
| config class / feature-switch class names | not needed — `ssp_ad*` keys are scrubbed from the JSON document itself, before any class reads them |
| timeline model classes | not needed — filtering happens on raw JSON structure (`entries`, `promotedMetadata`) |
| per-build changes in the parse path | the fallback scans `com.fasterxml.jackson.core.*` loaded classes (deferred +30s, post-startup, scoped) for the `(InputStream)` shape |

### Crash-safety notes (history on Android 16 / custom kernel)
- `Java.registerClass` during startup and `Java.enumerateLoadedClassesSync` while
  the app is booting both caused SIGSEGV inside frida-agent's JNI bridge. The
  script no longer uses `registerClass` at all; class enumeration is limited to
  one deferred, scoped fallback at +30s.
- Hidden framework classes (`android.app.SharedPreferencesImpl`) and OkHttp
  builder-field hacks were removed after crashing; the JSON boundary needs
  neither.
- Every hook installs retry until the class loads and fails soft; the stream
  hook falls back to the original InputStream on any error (original bytes are
  preserved when the document carries no ad/ssp markers, so non-ad traffic is
  untouched).

### Notes / caveats
- The transform is a JS `JSON.parse/stringify` round-trip only for documents
  containing `promotedMetadata` / `promoted_metadata` / `ssp_ad` / `ssp_image` —
  all other responses pass through byte-identical.
- Ad *scribing* (`com.twitter.analytics.util.k`) is not blocked by default —
  it reduces telemetry but not display.
- Blue/Premium subscribers are already excluded from client-only integration
  by `featureswitches.a` (`feature/twitter_blue_verified`, `feature/premium_plus`);
  the JSON rewrite still strips their server-injected promoted tweets.

## 5. Live device findings (X 12.12.0, Pixel 6a, Android 16) — and the pivot to LSPosed

Verified on-device on 2026-08-03:

1. **The installed build is 12.12.0** (not 12.9.1 of `X/src`). Timeline traffic is
   GraphQL → `api.x.com/graphql/.../HomeTimeline` via okhttp3 (Cronet absent).
2. **LoganSquare/Jackson is dead for timelines on 12.12.0**: hooking every
   single-arg overload of the Jackson factory (`e(InputStream)`, `f(byte[])`,
   `g(String)`) produced ZERO timeline parses — the TwiFucker JSON boundary no
   longer sees the timeline. (kotlinx.serialization parses GraphQL responses.)
3. **okhttp3 is the durable chokepoint** — un-obfuscated, public API.
4. **Frida on this device is unreliable**: repeated `SIGSEGV` inside ART's GC
   (`HeapTaskDaemon` → `CodeInfo::DecodeGcMasksOnly`) when threads are parked
   inside long-running frida JS hook frames (hook `RealInterceptorChain.proceed`
   / `BridgeInterceptor.intercept` = crash on traffic; short hooks such as
   `Request$Builder.build` survived). Root causes: custom kernel/ART on Android
   16 + host/client frida version skew (17.9.1 host vs 17.16.4 device server —
   now matched). Possible frida detection by the app cannot be excluded.
5. **Pivot: LSPosed module** (device has Zygisk-LSPosed v1.11.0). The module
   (fork of TwiFucker at `X/TwiFucker`) adds `OkHttpFilterHook`: hooks
   `okhttp3.OkHttpClient$Builder.build()` via a `ClassLoader.loadClass` hook and
   injects a real Kotlin `Interceptor` into every client — strips
   `promotedMetadata` entries/items and scrubs `ssp_ad*`/`ssp_image*` keys from
   JSON responses. Real ART hooks: no JS bridge, no GC-stack-walk issue, no
   frida agent for detection code to find.

Enable after install:
```
adb shell su -c '/data/adb/lspd/cli modules enable icu.nullptr.twifucker'
adb shell su -c '/data/adb/lspd/cli scope add icu.nullptr.twifucker com.twitter.android/0'
```

## 6. Final solution: LSPosed module — VERIFIED WORKING (2026-08-05)

The TwiFucker fork at `X/TwiFucker` with the new `OkHttpFilterHook` is installed
on the device, enabled in LSPosed (v1.11.0 / Vector), and **verified stripping
7 promoted entries from every HomeTimeline response**:

```
08-05 11:28:35.330 D TwiFucker: OkHttpFilterHook: removed 7 promoted item(s), scrubbed 0 ssp key(s)
08-05 11:28:35.337 D TwiFucker: OkHttpFilterHook: stripped ads (gzip)
```

UI verification: 6+ scrolls of the For You timeline, zero "Promoted" badges,
app stable (no crashes, no "network failed").

### Final hook design (`OkHttpFilterHook`)
- Hook `okhttp3.internal.http.BridgeInterceptor.intercept(Chain)` directly via
  Xposed (classloader hook for lazy loading). One real ART hook, no proxy, no
  okhttp types referenced (all reflective — the module runs in an isolated
  classloader).
- X sets its own `Accept-Encoding: gzip`, so Bridge does NOT decompress: the
  filter does a **gzip round-trip** (`bytes()` → GZIPInputStream → filter →
  GZIPOutputStream → rebuild with `Content-Encoding: gzip` kept, `Content-Length`
  removed). Never returns the consumed original — failure falls back to a
  byte-identical re-wrap.
- **12.12.0 ad markers** (verified from a live response dump): entries carry
  `promoted_metadata` and `client_event_info.details.adindex_details`; the
  promoted entries are dropped from every `entries`/`addEntries` array; module/
  thread entries keep organic items (TwiFucker semantics).
- `ssp_ad*`/`ssp_image*` config keys scrubbed from any JSON.

### Bugs found and fixed along the way
1. `genuine.h` anti-tamper (`GENUINE_SIZE`/`GENUINE_HASH`) — exit(0) every
   process 5s after start → disabled for the fork build.
2. Module classloader can't see app classes → dynamic proxy first, then the
   simpler direct Bridge hook.
3. Returning the original response after consuming its body → EOF/parse
   failures ("network failed") → always rebuild the body.
4. Reflection wrappers must be unwrapped and rethrown as `IOException` so
   okhttp/X handle errors normally (cert pinning failures etc.).
5. okhttp Kotlin subclasses (`ResponseBody$Companion$asResponseBody$1`) break
   `getMethod` exact-class matching → find methods by shape.
6. `body.string()` on gzip bytes corrupts them → gzip round-trip.
7. The `removed` counter was never incremented for dropped plain entries →
   transform returned null → fixed.

## 7. Request-side interception (primary) — implemented, findings documented

The module now has TWO layers:

1. **REQUEST side (primary design goal)** — hooks `RealInterceptorChain.proceed(Request)`
   before the request flows down the chain. Reads the outgoing GraphQL body (which is
   an **Apollo Android** body — `com.apollographql.apollo.network.http.m` — via a dynamic
   proxy sink over the app's obfuscated okio `BufferedSink`), strips ad params
   (`dspClientContext` / `dsp_client_context` from variables, disables ad feature flags),
   rebuilds the request. Fail-soft; only touches `graphql` POST requests.

2. **RESPONSE side (verified effective primary on 12.12.0)** — the BridgeInterceptor
   gzip round-trip filter (§6). Removes `promoted_metadata`/`adindex_details` entries.

**Verified finding for X 12.12.0**: the HomeTimeline request contains NO ad-control
parameters — the captured request is
`{"variables":"{count, cursor, request_context, autoplay_enabled, seen_tweet_ids, ...}",
"features":"{9 flags, none ad-related}"}`. There is no `dspClientContext` and no ad
feature flag to toggle; the server injects promoted content unconditionally. So on this
build the **response side is the effective primary** and the request side is a
future-proof guard (if X ever adds ad params to requests, they are stripped before the
server sees them).

Log confirmation (final build):
```
OkHttpFilterHook: hooked RealInterceptorChain.proceed
OkHttpFilterHook: hooked BridgeInterceptor.intercept
OkHttpFilterHook: removed 7 promoted item(s), scrubbed 0 ssp key(s)
OkHttpFilterHook: stripped ads (gzip)
```

## 8. Module settings UI restored (2026-08-05)

The settings dialog (TwiFucker's `SettingsDialog` — the "Main" tab with toggles:
Disable Promoted Content, Disable Promoted Users, etc.) is back, shown on every
launch. Fixes:

- **Root cause**: the isolation change (only `OkHttpFilterHook`) disabled the UI
  hooks (`MainActivityHook`, `SettingsHook`, `ActivityHook`, `ViewHook`).
- **MainActivityHook**: X renamed the activity package (`com.twitter.app.main`
  → `com.x.android.main.MainActivity`) and 12.12.0's MainActivity does not
  declare `onResume` itself → now hooks `android.app.Activity.onResume` and
  filters by the concrete class name.
- **SettingsHook**: the top-level `loadClass("com.twitter.app.settings.AboutActivity")`
  crashed the whole object on 12.12.0 → made nullable; the dexKit fallback
  search is guarded to run only when the class exists.
- **Entry points on 12.12.0**: the home top bar and settings screen are Jetpack
  **Compose** — the logo long-press (`ImageView` with id `logo`) and the About
  version-click (`android.preference.Preference`) entries cannot work. The
  reliable entry is now the **every-launch popup** (`SettingsDialog` on every
  MainActivity onResume + a version toast).
