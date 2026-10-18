# 已做检查与限制

## v0.2.1 底部导航与多语言

- 底部导航错位原因：竖向 LinearLayout 默认让文字标签占满整格宽度，文字靠左，图标居中。修正后在 1080×2400 截图中逐格测量图标与文字水平中心，偏差 ≤1.5 px（v0.2.0 同一测量为约 107 px 偏左）；English、简体中文、繁體中文以及 320dp / 200% 字体下均对齐，大字体时标签自动缩小以完整显示。
- 界面文本全部移入资源：English（默认）、简体中文、繁體中文，代码中不再有中文硬编码文本；格式参数与复数由 Lint 检查，0 errors。
- Instrumentation 新增语言解析检查：zh-CN/zh-SG/zh-Hans → 简体，zh-TW/zh-HK/zh-Hant → 繁體，en-US/fr-FR → English；原有加密迁移、请求体、404/429 等检查继续通过。144 项客户端策略检查通过。
- Android 16 (API 36) 模拟器：设置页切换 English → 简体 → 繁體，保存的主机和连接保留；系统“应用语言”设为 zh-TW/zh-CN 时界面与语言按钮同步；“跟随系统”恢复设备语言。Android 8–12 使用的应用内覆盖路径以临时阈值版本在同一模拟器验证：切换、重启后保留、恢复跟随系统通过；未在真实旧版本设备运行。
- 三种语言逐页截图检查（概览、进程/服务及详情、日志、设置、语言、主机、工具、端口、定时任务、磁盘、扫描确认与结果），未发现超出屏幕的文本；320dp / 200% 字体下标题与刷新按钮改为上下排列、服务筛选改为 2×2，避免英文单词中途断开。截图用临时去除防截屏的构建，发布版本仍保留 FLAG_SECURE。
- 发布 APK 改用与本机构建相同的固定证书（SHA-256 b9e3c20a…351650）签名，发布流程校验证书指纹，缺少密钥时拒绝发布。本地按 CI 步骤签名的 release APK 已在模拟器上直接覆盖本机 debug 构建，已保存主机保留；GitHub 的 v0.1.0 / v0.2.0 曾使用临时签名，需卸载一次。
- 服务端代码未改动；v0.2.0 的服务端验证与资源占用结论不变。

## v0.2 六项低占用功能

- 服务端 11 组测试在 ARM64 模拟器 Linux 内核通过；Go vet、三架构编译通过，含子挂载解析前拒绝与失效 timer 不推断目标回归。GitHub Ubuntu CI 验证 race/vet、安装器及真实 systemd 路径，覆盖监听端口、定时任务读取/授权触发、磁盘/inode、扫描及冷却。
- 最终服务端空闲 RSS 5824 KiB，8 秒内 CPU tick 增量为 0；请求 system/processes/ports/timers/disks 后 RSS 10688 KiB。请求耗时分别约 164/24/11/2/5 ms（timers 在模拟器为无 systemd 降级）。均为模拟环境参考，未测用户目标机器。
- Android `assembleDebug assembleRelease lintDebug --offline` 通过；Lint 0 errors / 18 warnings；主 APK 签名校验通过。纯 JDK 144 项检查覆盖连接身份、迟到响应、后台/页面/弹层轮询和日志筛选，并接入 CI。
- 平台 Instrumentation 在 Android 模拟器通过：旧 AES-GCM 单连接迁移、稳定主机 ID、收藏隔离与持久化、密文篡改拒绝、工具不支持响应、扫描逻辑大小、正确 POST 请求体、404 更新提示、429 不重试和日志上限。
- 双 HTTPS 模拟主机联调通过：新增 BETA 后无 ALPHA 收藏/缓存；切回恢复收藏，强制重启 App 仍保留；ALPHA 延迟响应不会覆盖切换后的 BETA，任务写入期间禁止切换。已实测删除非当前/当前主机与空配置状态，测试凭据移除。
- 日志搜索显示 2 处/2 行，复制按钮和手动刷新通过；监听地址、PID、服务、磁盘/inode 与扫描部分结果可见。定时任务与扫描各一次确认只产生一次 POST；任务确认显示完整主机/timer/service，并说明接受启动不等于完成。
- 工具结果页空闲 6 秒、App 后台 6 秒均无新增 HTTP 请求；首页 6 秒窗口仅请求当前主机 `/system`，收藏点击才读取服务。测试窗口有限，未宣称绝对零资源消耗。
- 320dp 宽 / 200% 字体下新增工具菜单、端口内容和关闭按钮可操作；未进行 TalkBack 人工验收。
- HTTPS 联调使用仅临时构建注入的 localhost 测试证书；最终普通构建已检查不含 fixture 证书。发布 APK 保持系统证书校验。

- 代码复查发现并修复 timer 失效时猜测目标、子挂载先 stat 与叠加挂载身份问题；扫描要求 Linux 5.6+ openat2，不支持时拒绝。容量读取先核对挂载 ID，再对固定句柄 fstatfs。

- v0.2.0 Release 已发布；最终 CI、发布构建及公开脚本在线安装全部通过，含旧版 unit 兼容、受保护/已删除 timer 拒绝、磁盘与受限扫描。下载后的 5 项发布程序/脚本 SHA-256、APK v2 签名、包名和 0.2.0 版本校验通过；发布 APK 约 74 KB，开发测试签名。测试服务和端口转发已清理。

## 服务端

- Go 交叉编译 Linux amd64 / arm64 / ARMv7 成功，CGO_ENABLED=0，单文件约 6–7 MiB。
- `GOOS=linux GOARCH=amd64 go vet ./...` 通过。
- 测试交叉编译后在本机 Android ARM64 模拟器的 Linux 内核运行（非目标服务器）：6 组全部通过，涵盖认证拒绝、Origin 拒绝、并发限制、变更互斥、电源默认关闭、状态与进程读取、无 systemd 降级、输入校验、pidfd 实例保护及真实临时 sleep TERM、未加载服务合并、中文日志边界和输出上限。
- `BenchmarkSystem` 3 次：约 150.8 ms/次，约 46 KiB 分配/次，包含 150 ms CPU 采样等待。
- `BenchmarkProcesses` 3 次：约 11.3 ms/次，约 2.27 MiB 临时分配/次，遍历本测试环境的进程；规模与系统权限影响开销。
- 真实 HTTP 检查：未认证 401，20 次状态请求、进程列表、无 systemd 返回与未知路径 404 通过。
- 独立服务启动后空闲 RSS 5760 KiB；10 秒空闲期间 /proc stat CPU tick 差为 0。连续状态请求与一次进程列表后 RSS / 高水位 10752 KiB。
- 内存与速度是早期模拟器参考数据，未代表目标弱服务器；没有测量用户反向代理的增量。没有声称零性能影响。
- GitHub Ubuntu 临时 runner 已通过 Go race 测试/vet、三架构编译、ShellCheck、systemd unit 校验、首次安装/重复安装保留令牌与 unit、临时服务 start/stop/start 及日志、受保护服务拒绝、错误 SHA256 拒绝、启动失败恢复旧程序与原运行状态。未对用户服务器执行安装，也未实际重启/关机任何设备。
- Linux 5.3+ pidfd required for process signalling; no unsafe PID reuse fallback. 服务名包含 systemd 十六进制转义的 unit 暂不提供管理。

## Android

- `assembleDebug lintDebug --offline` 成功，Lint 0 errors / 15 warnings（中文固定文本、RTL、目标 SDK 等提示），最终 APK v2 签名校验通过，约 100 KB，最低 Android 8.0。
- 最终 APK 已安装并在 Android 模拟器启动，四个入口、状态读数、服务 active/exited 区分、按需日志、设置/排序标题检查通过。
- 模拟 API 验证 TERM 携带正确 start_time、仅一次提交；服务重启需确认并发送正确 action；主机重启要求手输精确 hostname 后才可用。所有模拟操作未执行真实主机变更。
- 进程页空闲 6 秒、App 后台 6 秒分别无新 HTTP 请求。首页 5 秒刷新和首样本网络速率“—”已观察。
- 错误令牌显示认证失败；强制重启 App 后原有效连接仍可用。持久配置不含明文令牌或地址。
- APK 连接真实 Go 服务验证 system、processes 与无 systemd 提示通过。尚未通过用户实际 HTTPS 反向代理做外网手机联调。
- 约 320dp 宽与 200% 系统字体下四页仍可导航，资源改纵排、列表保留可操作区域、设置可滚动；未执行 TalkBack 人工验收。
- Android 为 debug 签名，未配置正式发布私钥。未保存的表单草稿仅内存保留，系统终止进程后不恢复。
- 有范围代码复查发现的配置保存竞态、停止服务后漏项、长列表静默截断和 UTF-8 日志截断均已修复。延迟连接验证期间旋转屏幕，再重启 App，仍保持原有效连接的回归通过。
- LiteHelm 品牌、包名 com.litehelm.mobile、通用地址/令牌两字段连接页已通过本地安装启动与扫描检查；本地与 GitHub Android build/lint 通过，开发签名 APK 校验通过。新品牌 APK 要求 HTTPS；早期本地 HTTP 联调不代表已完成用户外网接入测试。

## 发布检查

- 源码与 v0.1.0 已发布到 https://github.com/chilin11/litehelm。Release 附件下载后的 SHA256、APK 签名与品牌扫描通过；Online install smoke 在 Ubuntu 临时 runner 完整验证公开脚本下载、Release 程序下载、校验、systemd 启动及认证 API，结果通过。
- 构建产物、SDK 路径和签名文件不进入源码仓库。首版 APK 为开发测试签名，长期分发需配置稳定的发布密钥。

## 部署尚需的信息

目标服务器的发行版、架构、内核版本、RAM 与实际空闲量、HTTPS 反向代理上游和域名。未连接或改动用户服务器。
