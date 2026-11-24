# 轻舵 · LiteHelm Android v0.3.1

原生 Java Views，零第三方运行时依赖。最低 Android 8.0（API 26），编译/目标 API 35；应用 ID `com.litehelm.mobile`。版本 `versionCode 8 / versionName 0.4.2`。

## 构建

```sh
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug lintDebug assembleDebugAndroidTest --offline
```

Gradle wrapper 8.11.1 / AGP 8.7.3，离线构建需要已有本机缓存。主 APK：`app/build/outputs/apk/debug/app-debug.apk`；测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。Debug 使用现有调试签名，正式签名由发布流程配置。

## 语言

- 简体中文（`values-zh`）、繁體中文（`values-b+zh+Hant`，含 zh-TW/zh-HK）和 English（默认 `values`）。其他系统语言回退到 English。
- 默认跟随系统；设置页“语言”可切换。Android 13+ 通过 `LocaleManager` 写入系统应用语言（`locales_config.xml` 同步出现在系统设置中）；Android 8–12 保存在本地 `ui` 偏好并以配置覆盖重建界面。切换在请求或写入进行中被拒绝，草稿和已保存主机保留。
- 所有界面文本、提示和后台错误都在 `strings.xml`（`Text` 供无 Context 的请求代码使用）；服务端错误原文显示，已知的扫描限制原因在客户端翻译。App Bundle 关闭语言拆分，保证应用内切换可用。

## 多主机与连接

- 地址仅接受 HTTPS origin，不接受 path/query/fragment/userinfo；标准 TLS 验证，无认证或证书旁路，无重定向。
- 顶栏主机选择器点开即为锚定在顶栏下方的紧凑下拉（主机名 + 精简地址，当前主机 ✓）：点其他主机一步切换，点当前主机仅关闭且不取消正在进行的读取；底部为「+ 添加主机」和「管理主机」。未配置主机时直接进入添加表单。下拉带 FLAG_SECURE（Dialog 实现，PopupWindow 无法单独禁止截图）。
- 切换后留在进程/服务/脚本页并加载新主机，仅在设置页时回到概览。编辑与删除只在设置页「管理主机」紧凑卡片中（切换/编辑/删除单行小按钮，删除仍需确认），与日常切换分离。
- 添加/编辑在设置表单验证 `/system` 成功后才保存，表单顶部标明正在编辑的主机或新增；未修改的表单随活动主机同步，已输入的草稿在切换时保留。候选地址校验失败不会把当前主机标为连接失败。失败、后台、旋转、导航、切换或删除主机使旧校验失效（被删主机不会因迟到的校验成功而复活），旧配置保留。小型加密持久化和活动身份更新在当前有效主线程回调的同一轮完成。
- 整份主机文档（稳定 UUID、名称、origin、token、活动 ID、每主机收藏）以 Android Keystore AES-256/GCM 加密，仍使用 `litehelm.credentials.v1` 别名和原 `vault/encrypted` 槽位。读取 v1 单连接密文后自动迁移，原子提交失败不会覆盖旧密文。备份/设备迁移继续排除应用数据。
- 草稿仅在配置变更时内存保留，不写 Bundle/明文文件；进程终止后未保存草稿丢失。凭据页与主机管理弹层禁止截图，不参与 autofill。
- 切主机清空系统、进程、服务、速率和筛选状态，同步关闭弹层并取消读取后立即加载新主机；请求发送前与交付回调均检查活动连接身份、页面代次及弹层代次。写入尚未完成时禁止切换、编辑和删除主机。主机删除需要明确确认。

## 收藏、日志与工具

- 服务详情收藏/取消收藏，每主机最多 12 项，按稳定 profile ID 隔离。概览不显示缓存健康状态；点击收藏先获取新 `/system` 与 `/services` 后才开放动作，不随 10 秒轮询查询服务。
- 日志手动获取，保留最多 64 KiB 原文。本地大小写不敏感搜索按行筛选并统计匹配次数；清空查询恢复原文。仅明确点击才复制可见/全部原文到系统剪贴板，界面提示其他应用可能读取。
- 脚本任务在底部「脚本」页：编辑与最近任务同页，点任务原地查看详情，仅详情可见且任务运行中时每 2 秒增量读取输出。
- 概览和服务页工具菜单本身不发请求。选择监听端口、systemd 定时任务、磁盘后单次读取对应 JSON API，仅手动刷新，可本地搜索。unsupported 和空结果分开展示；新接口 404 明确提示更新 LiteHelm 服务端。
- 定时任务仅 `can_run=true` 显示触发按钮，确认完整主机、timer、unit 后 POST `{confirm: timer}`；接受启动不代表作业完成。
- 磁盘显示容量/可用量/inode（总数零显示不可用）。只能选择服务器返回的 `scan_roots`，扫描前明确确认只读 IO、时间/条目/深度及冷却限制。POST `{path: root}`，显示按逻辑字节降序前 20 项；`partial` 与原因突出显示、结果为下界，不代表物理占用/可释放空间。刷新磁盘不会重新扫描，429/失败不自动重试，无删除操作。
- 弹层打开时暂停概览轮询；关闭弹层只取消该弹层自己的读取（仍在进行的页面刷新保留，不会误报失败），后台、切页或切主机取消读取并拒绝迟到结果。弹层内读取（日志、工具、扫描、收藏）遇到正在进行的单个后台读取时排队到其结束后执行，而非提示“忙碌”；读取仍严格串行。
- 进程搜索本地即时筛选，输入停止约 0.6 秒后按新关键词（含清空）向服务端重读一次；切换排序会替换进行中的读取。再次点当前底部标签回到顶部并刷新，脚本详情中则返回任务列表。后台刷新不再闪烁“连接中”。取消扫描只能取消客户端连接，服务端已开始的受限扫描可能继续至其自身上限。

## 资源高占用提醒

概览资源面板上方的监控卡片只展示服务端 `/system` 的 `resource_monitor`（服务端后台约每 5 秒采样并按阈值判定）；手机**不做任何本地阈值计算**，告警项、百分比和阈值都直接取自服务端 `alerts` 与 `threshold_percent`（`disk` 即根分区 `/`）。

- `state=ok` 且新鲜：有 `alerts` 时显示“服务端告警”，空数组显示“服务端监控：无告警”，附阈值与采样时间。
- 以下情况一律显示“资源监控不可用”及原因，并注明不代表正常：最近一次请求失败/未完成、旧服务端缺少该字段（提示升级服务器）、`warming_up`、`error`、格式无法识别、过期。
- 过期：服务端响应 `timestamp` 与 `sampled_at` 相差 ≥15 秒，或手机距上次成功刷新 ≥15 秒（仅前台的本地重绘计时器，不发请求）。
- 无后台服务、定时任务或推送；离开前台即停止。前台概览仍每 10 秒请求一次 `/system`，会消耗网络流量，可随时手动刷新。解析与状态分类在 `ResourceAlert`。

## 既有行为

仅前台概览且无弹层时，在上一请求结束 10 秒后请求 `/system`；所有 IO 使用单个串行 executor，后台无采样。进程/服务按导航或手动刷新；TERM/KILL 携带原 `start_time`。全部变更确认展示主机，写入全局互斥、不重试；超时/中断显示结果未确认。服务成功后重新读列表，电源操作仍需 capability 与手输 hostname 双重验证。

传输连接/读取超时为 10/20 秒，总 JSON 上限 2 MiB；令牌在错误消息中隐藏。固定深墨色与薄荷绿原生界面、五入口导航、滚动弹层，无外部库/后台任务/终端 UI。

## 验证

依赖纯 JDK 的主机端测试（仓库根目录）：`sh scripts/test-android-policy.sh`，覆盖连接身份与代次失效、后台/页面/弹层轮询矩阵、日志原文保持和本地匹配；CI 与发布流程都会运行。

Android 平台 Instrumentation 无外部测试依赖，覆盖 Keystore 加密 v1 迁移、稳定 ID/活动主机/收藏隔离、密文篡改拒绝、能力信封、timer/scan 请求体、404、429 不重试、日志上限，以及 zh-CN/zh-SG/zh-TW/zh-HK/zh-Hans/zh-Hant/en/fr 的资源解析。使用独立 `v02-test-vault` preferences，不清理真实凭据。在明确选择的设备安装主 APK 和测试 APK 后执行：

```sh
adb -s DEVICE_SERIAL shell am instrument -w com.litehelm.mobile.test/com.litehelm.mobile.ProfileVaultTest
```

已在模拟器运行通过；双 HTTPS 模拟主机联调、语言切换、320dp/200% 字体与底部导航对齐的检查结果见仓库根目录 `VERIFICATION.md`。TalkBack 人工验收与真实外网 HTTPS 联调尚未完成。
