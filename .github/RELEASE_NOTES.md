轻舵 · LiteHelm v0.2.0：六项按需功能，继续面向低配置 Linux 服务器。

- 多服务器：加密保存、验证后添加/修改、快速切换，缓存与请求按主机隔离。
- 服务收藏：首页快捷入口，收藏状态按主机保存，点开时读取最新服务状态。
- 日志：本地搜索、匹配行数、复制可见/全部内容、手动刷新。
- 监听端口：查看 TCP/UDP 监听地址、进程及关联服务；缺少 `ss` 时明确提示。
- systemd 定时任务：查看最近/下次执行，仅服务端明确允许的任务可手动触发；返回表示接受启动。
- 磁盘与 inode：本地文件系统用量、允许目录的手动大小扫描，显示截断与不完整原因。

服务端无 Docker、数据库、后台历史采集；仅前台首页每 5 秒刷新状态，其他页面按需请求，App 退到后台停止刷新。扫描限制 2 秒协作预算、10000 项、6 层深度与 15 秒冷却，不跟随符号链接、不跨文件系统；文件系统阻塞可能超出预算。结果是逻辑大小，不代表可回收空间。

服务端新增配置（systemd 环境变量）：

- `LITEHELM_RUN_TIMERS`：允许手动触发的完整 timer 名称，逗号分隔；默认不允许。
- `LITEHELM_SCAN_ROOTS`：允许扫描的绝对目录，逗号分隔；默认 `/var/log,/var/cache`，空值禁用。

安装及更新：

```sh
curl -fsSL https://raw.githubusercontent.com/chilin11/litehelm/main/install.sh -o litehelm-install.sh
sudo sh litehelm-install.sh
```

安全目录扫描要求 Linux 5.6+ 的 openat2 支持；不可用时拒绝扫描，子挂载在读取属性前跳过。

支持 Linux amd64 / arm64 / ARMv7。进程信号要求 Linux 5.3+；服务和定时任务要求 systemd。APK 要求 HTTPS，旧服务端的新增工具接口会提示更新。

`LiteHelm-debug.apk` 为开发测试签名；配置发布签名时提供 `LiteHelm-release.apk`。不同构建的开发签名可能不同，覆盖安装前请核对签名。详见 README 与 VERIFICATION.md；未安装到用户目标服务器，也未执行真实主机重启。
