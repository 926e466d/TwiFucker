# X（Twitter）广告绕过：Frida → LSPosed 模块 — 会话总结

**日期**: 2026-08-05
**工作目录**: `E:\Work\Research\mq_test`
**背景**: 研究反编译源码 `X\src`（12.9.1）中 X Android 的广告机制，并为设备上已安装的 X（12.12.0）构建可用的去广告方案。Frida 路线在设备上反复崩溃；最终方案是一个 LSPosed 模块（TwiFucker 分支），已验证可剥离全部广告。

---

## 问题

移除 Pixel 6a（Android 16、定制内核、KernelSU）上 **X 12.12.0** 的广告——该版本与反编译参考源码（`X\src`，12.9.1）不同。最初方案是 Frida 脚本 hook 请求/响应路径，但在设备上反复崩溃。用户建议改用 LSPosed hook（参考 TwiFucker，克隆于 `X\TwiFucker`）。

## 调查过程

1. **广告机制梳理**（`X\src`，12.9.1）：`ssp_ads_*` 远程配置键（约 35 个），经 `com.twitter.util.config.y.g(String,boolean)` 读取；特性开关门面 `com.twitter.ads.featureswitches.a`（展示位置 14/17/22/28/63）；`dsp_client_context` 请求参数由 `com.twitter.ads.dsp.{t,q}.b(int)` 生成；GAM 原生广告经 `ads.dsp.s2c.f`（`ServerSlotClientFetchNativeAdCacheManagerImpl`）；服务端注入的推广推文带 `promotedMetadata`（12.9.1）——不受任何客户端参数控制。
2. **Frida 脚本多次迭代**（`x_ads_frida_bypass.js`）：LoganSquare/Jackson 工厂 hook（TwiFucker 风格）、OkHttp `Response$Builder`/`Request$Builder` hook、带深度计数器的 `RealInterceptorChain.proceed`、`BridgeInterceptor.intercept`——在设备上均崩溃。
3. **设备实况诊断**：拉取已安装 APK，发现 **12.12.0 的时间线不再走 LoganSquare 解析**（hook 全部工厂重载——零时间线解析）；时间线走 GraphQL（`api.x.com/graphql/.../HomeTimeline`）经 okhttp3 → **kotlinx.serialization**。设备 frida-server 为 **17.16.4** 而主机为 **17.9.1**（主机已升级对齐）。
4. **本设备崩溃根因**：线程长时间停留在 frida JS hook 帧内时 ART GC 段错误（`HeapTaskDaemon` → `CodeInfo::DecodeGcMasksOnly`）；另疑似存在 frida 检测面。结论：frida 不适合本设备 → **转向 LSPosed**（v1.11.0 Vector，已安装）。
5. **模块构建（TwiFucker 分支）**：新增 `OkHttpFilterHook`——直接 Xposed hook `okhttp3.internal.http.BridgeInterceptor.intercept`（真实 ART hook，全部反射处理 body）。迭代修复 7 个 bug（见解决方案）。

## 解决方案

### 新建文件

| 文件 | 用途 |
|------|------|
| `E:\Work\Research\mq_test\X\TwiFucker\app\src\main\java\icu\nullptr\twifucker\hook\OkHttpFilterHook.kt` | 广告过滤器：请求侧剥离 + 响应侧 gzip 往返过滤 |
| `E:\Work\Research\mq_test\docs\X_Ads_Frida_Bypass_Research.md` | 完整研究文档（7 节） |
| `E:\Work\Research\mq_test\x_ads_frida_bypass.js` | Frida 尝试（已被模块取代） |
| `E:\Work\Research\mq_test\run_frida_spawn.py` | frida-python spawn 运行器（诊断用） |
| `E:\Work\Research\mq_test\diag_x_parse.js`、`probe_*.js` | Frida 诊断探针 |
| `E:\Work\Research\mq_test\enable_lsposed_module.sh` | LSPosed 启用 + 作用域 + 重启辅助脚本 |

### 修改文件

| 文件 | 变更 |
|------|------|
| `X\TwiFucker\...\hook\HookEntry.kt` | 仅启用 `OkHttpFilterHook`；禁用旧 TwiFucker hook（它们在 12.12.0 上会破坏响应） |
| `X\TwiFucker\app\src\main\cpp\src\genuine.h` | 禁用 `GENUINE_SIZE`/`GENUINE_HASH`——防篡改检查会在进程启动 5 秒后 `exit(0)` |
| `X\TwiFucker\app\build.gradle.kts` | buildTools 34.0.0；`adbExecutable` 惰性化（Windows 路径修复） |
| `X\TwiFucker\local.properties`、`gradle-wrapper.properties` | `sdk.dir=D:/Android/Sdk`（必须用正斜杠！）；Gradle 8.1.1 → 8.9 |
| `X\TwiFucker\app\proguard-rules.pro` | hook 类的 keep 规则 |

### 关键技术细节

- **最终 hook 设计**：`BridgeInterceptor.intercept` after-hook（响应）+ `RealInterceptorChain.proceed` before-hook（请求）。所有 okhttp 交互均为反射——模块运行在隔离的类加载器中，不能直接引用 okhttp 类型。
- **X 自带 `Accept-Encoding: gzip`** → okhttp Bridge 不解压 → 过滤器做 **gzip 往返**（`bytes()` → GZIPInputStream → 过滤 → GZIPOutputStream），保留 `Content-Encoding: gzip`、移除 `Content-Length`。
- **12.12.0 广告标记**（经实况响应转储验证）：条目携带 `promoted_metadata` + `client_event_info.details.adindex_details`（而非旧的 `promotedMetadata`）。
- **请求侧**：GraphQL body 是 **Apollo** body（`com.apollographql.apollo.network.http.m`），okio 已混淆——通过 `writeTo` 参数类型的动态代理 sink 读取。**发现：12.12.0 的 HomeTimeline 请求没有任何广告参数**（无 `dspClientContext`、无广告特性开关）——响应侧才是实际有效的拦截点。
- **修复的 7 个 bug**：① `genuine.h` 防篡改 exit(0)；② 模块类加载器隔离（先动态代理、后直接 hook）；③ 读取 body 后返回原响应 → EOF/"network failed"——必须始终重建；④ 反射包装异常须解包并以 `IOException` 重抛（证书固定失败由 X 自行处理）；⑤ okhttp Kotlin 子类（`ResponseBody$Companion$asResponseBody$1`）使精确类 `getMethod` 失效——按形状查找；⑥ 对 gzip 字节做 `body.string()` 会损坏数据——需 gzip 往返；⑦ 丢弃普通广告条目时未递增 `removed` 计数——转换静默返回 null。
- **安装坑**：本 ROM 的包验证器阻止 adb/root 安装（被 `playintegrityfix` 破坏）；用户授权 `settings put global verifier_verify_adb_installs 0` 后解锁。**重装模块后 LSPosed 会将其重置为禁用**——每次更新后需用 `lspd cli modules enable icu.nullptr.twifucker` 重新启用。
- 设备 adb 不稳定（wifi-adb 易断；`adb reconnect` / `connect pixel6a:5555` 可恢复）。设备在长时间等待后会休眠——交互前需唤醒（`keyevent 224`）、`wm dismiss-keyguard`、`cmd statusbar collapse`。

## 使用方法

```bash
# 模块已安装并启用；查看状态：
adb -s pixel6a:5555 shell "su -c '/data/adb/lspd/cli modules ls'" | grep twifucker
# 重装后重新启用：
adb -s pixel6a:5555 shell "su -c '/data/adb/lspd/cli modules enable icu.nullptr.twifucker'"
# 重新构建（JDK 17 在 D:\Java\jdk-17；sdk.dir 在 local.properties）：
cd X\TwiFucker && ./gradlew.bat :app:assembleRelease --no-daemon
# 在 logcat 中验证剥离：
adb -s pixel6a:5555 logcat -d | grep -E "OkHttpFilterHook: (removed|stripped)"
# 预期输出：每次时间线拉取出现 "removed 7 promoted item(s)" + "stripped ads (gzip)"
```

APK 位置：`X\TwiFucker\app\release\TwiFucker-V2.1-release.apk`。模块更新后需重装 + 重新启用（重装会重置为禁用状态）。
