# X (Twitter) Ads Bypass: Frida → LSPosed Module — Session Summary

**Date**: 2026-08-05
**Working Directory**: `E:\Work\Research\mq_test`
**Context**: Research X Android's ads mechanism in the decompiled `X\src` (v12.9.1) and build a working ad-removal bypass for the device's installed X (v12.12.0). The frida route crashed repeatedly on the device; the final working solution is an LSPosed module (TwiFucker fork) verified stripping all ads.

---

## Problem

Remove ads from the X (Twitter) Android app on a Pixel 6a (Android 16, custom kernel, KernelSU) running **X 12.12.0** — a different version than the decompiled reference source (`X\src`, 12.9.1). Initial approach was a Frida script hooking the request/response path; it crashed the app repeatedly. The user suggested an LSPosed hook (TwiFucker reference, cloned at `X\TwiFucker`).

## Investigation

1. **Ads mechanism mapping** (`X\src`, 12.9.1): `ssp_ads_*` remote config keys (~35) via `com.twitter.util.config.y.g(String,boolean)`; feature-switch facade `com.twitter.ads.featureswitches.a` (display locations 14/17/22/28/63); `dsp_client_context` request param produced by `com.twitter.ads.dsp.{t,q}.b(int)`; GAM native ads via `ads.dsp.s2c.f` (`ServerSlotClientFetchNativeAdCacheManagerImpl`); server-injected promoted tweets carry `promotedMetadata` (12.9.1) — not gated by any client param.
2. **Frida script iterations** (`x_ads_frida_bypass.js`): LoganSquare/Jackson factory hook (TwiFucker-style), OkHttp `Response$Builder`/`Request$Builder` hooks, `RealInterceptorChain.proceed` with depth counter, `BridgeInterceptor.intercept` — each crashed on the device.
3. **Live device diagnosis**: pulled the installed APK, found **no LoganSquare timeline parsing** (hooked all factory overloads — zero timeline parses); timeline flows GraphQL (`api.x.com/graphql/.../HomeTimeline`) via okhttp3 → **kotlinx.serialization**. Device frida-server was **17.16.4** vs host **17.9.1** (host upgraded to match).
4. **Crash root causes on this device**: ART GC SIGSEGV (`HeapTaskDaemon` → `CodeInfo::DecodeGcMasksOnly`) when threads sit inside long-running frida JS hook frames; plus a possible frida-detection surface. Conclusion: frida is unsuitable on this device → **pivot to LSPosed** (v1.11.0 Vector, already installed).
5. **Module build (TwiFucker fork)**: added `OkHttpFilterHook` — direct Xposed hook on `okhttp3.internal.http.BridgeInterceptor.intercept` (real ART hook, all-reflection body handling). Iterated through 7 bugs (see Solution).

## Solution

### Files Created

| File | Purpose |
|------|---------|
| `E:\Work\Research\mq_test\X\TwiFucker\app\src\main\java\icu\nullptr\twifucker\hook\OkHttpFilterHook.kt` | The ad filter: request-side strip + response-side gzip round-trip |
| `E:\Work\Research\mq_test\docs\X_Ads_Frida_Bypass_Research.md` | Full research write-up (7 sections) |
| `E:\Work\Research\mq_test\x_ads_frida_bypass.js` | Frida attempts (superseded by the module) |
| `E:\Work\Research\mq_test\run_frida_spawn.py` | frida-python spawn runner (diagnostics) |
| `E:\Work\Research\mq_test\diag_x_parse.js`, `probe_*.js` | Frida diagnostic probes |
| `E:\Work\Research\mq_test\enable_lsposed_module.sh` | LSPosed enable + scope + restart helper |

### Files Modified

| File | Change |
|------|--------|
| `X\TwiFucker\app\src\main\java\icu\nullptr\twifucker\hook\HookEntry.kt` | Only `OkHttpFilterHook` active; legacy TwiFucker hooks disabled (they corrupt 12.12.0 responses) |
| `X\TwiFucker\app\src\main\cpp\src\genuine.h` | `GENUINE_SIZE`/`GENUINE_HASH` disabled — the anti-tamper check `exit(0)`'d every process 5s after start |
| `X\TwiFucker\app\build.gradle.kts` | buildTools 34.0.0; lazy `adbExecutable` (Windows path fix) |
| `X\TwiFucker\local.properties`, `gradle-wrapper.properties` | `sdk.dir=D:/Android/Sdk` (forward slashes!); Gradle 8.1.1 → 8.9 |
| `X\TwiFucker\app\proguard-rules.pro` | keep rules for the hook classes |

### Key technical details

- **Final hook design**: `BridgeInterceptor.intercept` after-hook (response) + `RealInterceptorChain.proceed` before-hook (request). All okhttp interaction is reflective — the module runs in an isolated classloader and must not reference okhttp types.
- **X sets its own `Accept-Encoding: gzip`** → okhttp Bridge does NOT decompress → the filter does a **gzip round-trip** (`bytes()` → GZIPInputStream → filter → GZIPOutputStream), keeping `Content-Encoding: gzip`, removing `Content-Length`.
- **12.12.0 ad markers** (verified from a live response dump): entries carry `promoted_metadata` + `client_event_info.details.adindex_details` (NOT the old `promotedMetadata`).
- **Request side**: the GraphQL body is an **Apollo** body (`com.apollographql.apollo.network.http.m`) with **obfuscated okio** — read via a dynamic proxy sink over `writeTo`'s parameter type. **Finding: 12.12.0's HomeTimeline request has NO ad params** (no `dspClientContext`, no ad feature flags) — the response side is the effective primary.
- **7 bugs fixed**: ① `genuine.h` anti-tamper exit(0); ② module classloader isolation (dynamic proxy first, then direct hook); ③ returning the original response after consuming its body → EOF/"network failed" — always rebuild; ④ reflection wrappers must be unwrapped and rethrown as `IOException` (cert-pinning failures are handled by X); ⑤ okhttp Kotlin subclasses (`ResponseBody$Companion$asResponseBody$1`) break exact-class `getMethod` — find by shape; ⑥ `body.string()` on gzip bytes corrupts them — gzip round-trip; ⑦ `removed` counter never incremented for dropped plain entries — transform silently returned null.
- **Install gotchas**: this ROM's package verifier blocks adb/root installs (broken by `playintegrityfix`); user-authorized `settings put global verifier_verify_adb_installs 0` unblocked it. **Reinstalling the module resets it to DISABLED in LSPosed** — re-enable with `lspd cli modules enable icu.nullptr.twifucker` after every update.
- Device adb is flaky (wifi-adb drops; `adb reconnect` / `connect pixel6a:5555` fixes it). The device dozes during long waits — wake (`keyevent 224`), `wm dismiss-keyguard`, `cmd statusbar collapse` before UI interaction.

## Usage

```bash
# module already installed + enabled on the device; verify state:
adb -s pixel6a:5555 shell "su -c '/data/adb/lspd/cli modules ls'" | grep twifucker
# re-enable after a reinstall:
adb -s pixel6a:5555 shell "su -c '/data/adb/lspd/cli modules enable icu.nullptr.twifucker'"
# rebuild (JDK 17 at D:\Java\jdk-17; sdk.dir in local.properties):
cd X\TwiFucker && ./gradlew.bat :app:assembleRelease --no-daemon
# verify stripping in logcat:
adb -s pixel6a:5555 logcat -d | grep -E "OkHttpFilterHook: (removed|stripped)"
# expected output: "removed 7 promoted item(s)" + "stripped ads (gzip)" per timeline fetch
```

The APK: `X\TwiFucker\app\release\TwiFucker-V2.1-release.apk`. If the module is ever updated, reinstall + re-enable (reinstall resets to disabled).
