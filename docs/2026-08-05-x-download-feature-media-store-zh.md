# X 推文媒体下载功能（Compose UI）— 会话总结

**日期**: 2026-08-05
**工作目录**: E:\Work\Research\mq_test
**背景**: 接续 X(Twitter) 广告绕过工作（见 `2026-08-05-x-ads-bypass-frida-lsposed-module-zh.md`）。X 12.12.0 的广告过滤已生效；本次会话恢复了模块设置界面，并重建了推文媒体下载功能——因为 12.12.0 已用 Jetpack Compose 替换了旧的 View 分享面板。

---

## 问题

TwiFucker 的旧版 `DownloadHook` 是在旧版应用内分享面板中注入"下载"按钮。X 12.12.0 的分享面板是 Jetpack Compose，按钮永不出现。用户要求修复下载功能，随后指示："you can get the log not ask me manual"——即由我自己用 adb 驱动设备测试流程并读取日志，不再要求用户手动验证。

## 调查过程

1. **确认旧路径失效**: `DownloadHook` 注入的是旧 View 分享面板；12.12.0 为 Compose，无注入点。通过 UI dump + logcat 验证。
2. **定位 12.12.0 媒体结构**: `tweet_results.result.media_entities[].media_results.result.media_info` — `ApiVideo.variants[].url`（取最高码率）/ `ApiImage.original_img_url`。
3. **选择触发点**: 监听分享 Intent。分享面板的 "Share via…" 通过 `Activity.startActivityForResult` 启动系统选择器。本设备上 `View.dispatchTouchEvent` hook 静默不触发；`Activity.startActivity(Intent)` 单独也不触发——实际调用的是 **`startActivityForResult(Intent, int[, Bundle])` 两个重载**。
4. **选择器包装发现**: 系统选择器是 `ACTION_CHOOSER` Intent，把真正的 `ACTION_SEND` 包在 `Intent.EXTRA_INTENT` 里。旧的 action 检查在打日志之前执行，所以 `[send]` 从未出现——调整为先记录所有 Intent（上限 15 条）再解包。
5. **对话框被选择器遮挡**: 选择器是独立 Activity，启动时弹出的对话框会出现在它*后面*。改为延迟显示：`ShareMediaHook` 先存 pending，`MainActivityHook.onResume` 消费并弹出（之前 `theHook?.unhook()` 把 hook 卸掉了——已移除）。
6. **分区存储写盘失败**: DOWNLOAD ALL 无报错但 `/sdcard/Download/TwiFucker/` 为空——Android 10+（设备为 Android 16）禁止直接 File 写公共存储。用 MediaStore 回退修复。
7. **莫名 SAF 选择器**: 最后一次端到端测试中出现了存储访问框架文件夹选择器（位于 `/storage/emulated/0/Download/`）而非下载对话框（来源不明——可能是残留状态或误触）；应用随后被关闭。MediaStore 版本的实际落盘结果尚待重新验证。

## 解决方案

### 修改的文件

| 文件 | 变更 |
|------|------|
| `X\TwiFucker\app\src\main\java\icu\nullptr\twifucker\hook\OkHttpFilterHook.kt` | 广告过滤（gzip 往返；剔除含 `promoted_metadata`/`adindex_details` 的条目，清洗 `ssp_ad*`/`ssp_image*`）+ **媒体捕获**: `captureTweetMedia(contentObj)` 遍历 `content.tweet_results.result` → `media_entities[].media_results.result.media_info` → `ApiVideo.variants[].url`（最高码率）/ `ApiImage.original_img_url`，存入 `tweetMediaCache`（上限 400，`[media]` 诊断日志），对外暴露 `fun tweetMedia(tweetId: String): List<String>?` |
| `X\TwiFucker\app\src\main\java\icu\nullptr\twifucker\hook\ShareMediaHook.kt` | 新下载触发点：hook 全部四个 `Activity.startActivity(Intent[, Bundle])` / `startActivityForResult(Intent, int[, Bundle])` 重载。*先*记录 `[send] action=… method=… act=…`（上限 15）再做任何过滤；解包 `ACTION_CHOOSER` → `EXTRA_INTENT`；从 `EXTRA_TEXT` 提取 `status/(\d+)`；查 `OkHttpFilterHook.tweetMedia(tweetId)`；存 `pendingTweetId`/`pendingUrls`；`takePendingDownload()` 负责消费 |
| `X\TwiFucker\app\src\main\java\icu\nullptr\twifucker\hook\activity\MainActivityHook.kt` | 保持 `Activity.onResume` hook 常驻（移除 `theHook?.unhook()`）；每次 MainActivity 恢复时消费 `ShareMediaHook.takePendingDownload()` 并弹出 `DownloadDialog`（延迟到选择器关闭后）。新增 `import icu.nullptr.twifucker.hook.ShareMediaHook`。已有功能：首启 SettingsDialog（进程内一次 + `first_run` MMKV 偏好）、版本 toast、头像长按设置入口（`Activity.dispatchTouchEvent`，按下区域 x<260/y<400、长按 >500ms、抬起区域 x<320/y<450） |
| `X\TwiFucker\app\src\main\java\icu\nullptr\twifucker\ui\DownloadDialog.kt` | `copyFile()` 在分区存储失败时回退 MediaStore：插入 `MediaStore.Downloads.EXTERNAL_CONTENT_URI`，带 `DISPLAY_NAME`/`MIME_TYPE`/`RELATIVE_PATH = Download/TwiFucker`，再 `openOutputStream` 复制。对话框流程：`DownloadMediaAdapter` 列表逐条复制/下载、DOWNLOAD ALL、DISMISS；下载 = HttpURLConnection → `appContext.cacheDir` 文件 → `copyFile`/`copyFileUri` → `MediaScannerConnection.scanFile` → 删除缓存文件；期间 `ProgressDialog`，出错 `Log.e` + "download failed" toast |
| `X\TwiFucker\app\src\main\cpp\src\genuine.h` | 注释掉 `GENUINE_SIZE`/`GENUINE_HASH`（反篡改 `exit(0)` 会在启动 5 秒后退出） |
| `X\TwiFucker\app\src\main\java\icu\nullptr\twifucker\hook\HookEntry.kt` | hook 列表加入 `ShareMediaHook`（在 `DownloadHook` 之后） |

### 关键技术细节

- **本设备专属 hook 规则**: `View.dispatchTouchEvent` hook 在此设备静默不触发；`Activity.dispatchTouchEvent` 与 `Activity.onResume`（框架层）可靠触发。所有 UI 触发均用 Activity 级 hook。
- **选择器包装**: 分享流程是 `ACTION_CHOOSER`（内含 `EXTRA_INTENT`），经 `startActivityForResult` 启动——外层 action 用于日志，解包后的真实 Intent 用于触发。
- **延迟对话框**: 选择器是独立 Activity；在用户返回（从选择器按 BACK）后、MainActivity `onResume` 时再弹窗。
- **MediaStore 回退**: Android 10+ 分区存储下直接 File 写 `/sdcard/Download/…` 会抛异常——捕获后经 `MediaStore.Downloads.EXTERNAL_CONTENT_URI` 插入，`RELATIVE_PATH = Downloads/TwiFucker`。
- **MainActivity 类名**: `com.x.android.main.MainActivity`（12.x）与 `com.twitter.app.main.MainActivity`（旧版）。
- **媒体捕获路径**: `tweet_results.result.media_entities[].media_results.result.media_info` — `ApiVideo.variants[].url`（取最大码率）/ `ApiImage.original_img_url`。
- **模块运行环境**: LSPosed 隔离 classloader（KernelSU 上 Vector v1.11.0）——`OkHttpFilterHook` 中对 okhttp 的一切交互均为反射（基于 `writeTo` 参数类型的动态代理 sink；因 X 自带 `Accept-Encoding: gzip`、okhttp Bridge 不解压，故需 gzip 往返）。
- **重装坑**: 重装模块会重置启用状态——需 `lspd cli modules enable icu.nullptr.twifucker`（见用法）。

## 用法

```bat
:: 构建（X\TwiFucker 目录下）
cd /d E:\Work\Research\mq_test\X\TwiFucker
.\gradlew.bat :app:assembleRelease --no-daemon

:: 安装 + 启用
adb -s pixel6a:5555 install -r app\build\outputs\apk\release\app-release.apk
adb -s pixel6a:5555 shell "su -c '/data/adb/lspd/cli modules enable icu.nullptr.twifucker'"

:: 确认 hook 已初始化
adb -s pixel6a:5555 logcat -d | grep -E "Inited (MainActivity|ShareMedia|OkHttp)"

:: 全程 adb 驱动下载流程（无需手动验证）
adb -s pixel6a:5555 shell am force-stop com.twitter.android
adb -s pixel6a:5555 shell am start -n com.twitter.android/com.x.android.main.MainActivity
:: 打开带媒体的推文 -> 点分享 -> 点 "Share via…" -> 按 BACK
adb -s pixel6a:5555 shell uiautomator dump /sdcard/ui.xml && adb -s pixel6a:5555 shell cat /sdcard/ui.xml
adb -s pixel6a:5555 logcat -d | grep -E "ShareMediaHook|TwiFucker"
:: 预期: [send] action=android.intent.action.CHOOSER ... + pending download for tweet <id> (N urls)
:: BACK 后: "Download or Copy" 对话框 -> 点 DOWNLOAD ALL -> 等约 6 秒
adb -s pixel6a:5555 shell ls -la /sdcard/Download/TwiFucker/
adb -s pixel6a:5555 logcat -d | grep -E "download (failed|completed)|Log.e"
```

## 状态

- ✅ 广告移除（每次抓取剔除 7 条推广条目，无 Promoted 角标）
- ✅ 设置界面（首启弹窗 + 头像长按入口）
- ✅ 分享触发（`[send]` + pending download 日志）
- ✅ 延迟对话框（BACK 返回选择器后出现）
- ✅ MediaStore 保存回退已实现并安装
- ⏳ MediaStore 版本最终落盘验证尚未完成（上次被莫名 SAF 选择器打断）；待清理诊断日志（`[media]`、`[down]`、`[send]`）。

---

## 后续（同会话）：对话框美化 + 结果分发修复

**问题**: 对话框"丑、Media 1 无意义"。实际上所用对话框*就是*原版 TwiFucker 的 `DownloadDialog`（只有 MediaStore 回退是我们加的）——上游没有更好的参考，因此就地升级。

### 修改

| 文件 | 变更 |
|------|------|
| `hook\OkHttpFilterHook.kt` | 新增顶层 `TweetMedia` 数据类（kind/url/thumbUrl/width/height/bitRate/durationMillis）。`captureTweetMedia` 现存元数据：ApiImage → `original_width`/`original_height`，缩略图经 `name=orig`→`name=thumb`（或 `:orig`→`:thumb`）派生；ApiVideo → 最高码率变体 + `poster_image`/`thumbnail_image` 缩略图 + `duration_millis`。新增 `tweetMediaInfo(tweetId)`；删除旧 `tweetMedia()`。`[media]` 诊断日志收敛为一条有上限的 "captured media" |
| `ui\DownloadItem.kt` | 行重做：64dp 圆角 `ImageView` 缩略图（异步 HttpURLConnection + `BitmapFactory.decodeStream` 配 `inSampleSize=2`、pbs.twimg.com UA 头、`view.post` token 防护避免复用行显示旧图）+ 描述性标签取代 "Media N"：`1 · Image · 1200×675` / `1 · Video · 720p · 1:04` / `1 · Video · 1080p · 0:32`（分辨率从变体 URL 正则提取，时长 m:ss，无尺寸时显示码率）。测量/布局适配缩略图，RTL 镜像 |
| `ui\DownloadDialog.kt` | 构造函数与适配器改收 `List<TweetMedia>`；点击监听每次 `getView` 都重新设置（修复潜在回收 bug：滚动后的行会复制/下载错项）；DOWNLOAD ALL 逐项取 `.url` |
| `hook\ShareMediaHook.kt` | 待处理状态改存 `List<TweetMedia>`；`takePendingDownload()` 返回之；删除诊断用的 `[send]` intent 日志 |
| `hook\DownloadHook.kt` | 旧路径构建 `TweetMedia` 条目（IMAGE → kind=image，VIDEO/ANIMATED_GIF → kind=video） |
| `hook\activity\MainActivityHook.kt` | **Activity 结果转发**：X 12.13.0/Android 16 不向设置对话框的 `PrefsFragment` 投递 `startActivityForResult` 结果（Android 16 移除了 3 参 `dispatchActivityResult` 与 `ActivityThread.deliverActivityResult`；X 的混淆基类 `com.twitter.app.common.base.h` 覆写 `onActivityResult` 且不调 `super`）。Hook 真实投递链——`Activity.dispatchActivityResult(String,int,int,Intent,String)`、`onActivityResult(int,int,Intent[,ComponentCaller])`、`internalDispatchActivityResult(6 参)`——并对两个 MainActivity 类名做父类链遍历以捕获中间层覆写，再转发给 `SettingsDialog.forwardActivityResult`。删除死字段 `theHook`、`[down]` 触摸日志与未用变量 `downRawX/Y` |
| `ui\SettingsDialog.kt` | `currentFragment` 注册表（对话框 init 时登记、dismiss 时清空）+ `forwardActivityResult()`；取消路径现在也调用 `modulePrefs.sync()` 确保立即持久化 |

### 关键发现（Android 16 结果投递）

- `Activity.dispatchActivityResult(String,int,int,Intent,String)` 是 5 参入口（并非旧文档的 3 参形式）；3 参 `dispatchActivityResult` 已不存在，`ActivityThread.deliverActivityResult` 已移除，`ActivityClientController` 类不存在。
- `XposedHelpers.findAndHookMethod` 只匹配精确类——中间基类的覆写必须靠 `superclass` 遍历寻找。
- `PreferenceFragment` 的结果依赖宿主 `FragmentActivity` 分发；X 的基类打断了这条链。

### 验证（全程 adb）

- 对话框显示 `1 · Video · 720p · 1:04` + 圆角缩略图（截图）；另一条推文显示 `1 · Video · 1080p · 0:32`。
- DOWNLOAD ALL（默认路径，pref 已清）：`/sdcard/Download/TwiFucker/2084767276176179486_1.mp4`（20 MB）→ `MediaScannerConnection: Scanned … → content://media/external_primary/video/media/1000001591`。
- 设置 → 选择下载目录 → 选择器取消 → pref 被移除（remove+sync 后 `value=null`，进程重启后仍生效；MMKV 只在索引中标记删除，`strings` 看文件仍有旧字节——要以应用读值为准，不能看文件）。
- 上次莫名的 SAF 选择器之谜就此解开：残留的 `download_directory` 树 URI（指向 Download 根目录）导致 `copyFileUri` 写到 `TwiFucker/` 之外；已通过修复后的设置路径清除。

### 备注

- `gradle.properties` 保留本机专属 `org.gradle.java.home=D:/Java/jdk-17`（AGP 8.1 与默认 JDK 22 不兼容——jlink transform 失败）——有意不提交；其余（buildTools 34、Gradle wrapper 8.9、`OkHttpFilterHook` 的 proguard keep、`genuine.h` 反篡改禁用）均提交。
- 诊断日志收敛为每特性一条有上限的日志（`removed N promoted item(s)`、`captured media …`、`pending download for tweet …`）；`[send]`/`[down]`/`[media]` 噪音与插桩日志已删除。
