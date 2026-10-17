# 已做检查与限制

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
