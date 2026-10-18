# 轻舵 · LiteHelm

**给小服务器用的手机管理工具。** 原生 Android App + 单文件 Go 服务端，查看状态、结束进程、管理服务，随手打开即可操作。

- 无需 Docker、数据库、服务器网页运行时。
- 首页只在前台每 5 秒刷新，进程、服务和日志按需读取；退到后台停止请求。
- 深色原生手机界面，支持搜索、排序、日志查看和操作确认；简体中文 / 繁體中文 / English，默认跟随系统，可在设置中切换。
- 标准 HTTPS + Bearer Token，可接入你自己的反向代理。

```text
Android App ── HTTPS + Token ── 你的反向代理 ── http://127.0.0.1:9091
```

[下载 APK 和服务端](https://github.com/chilin11/litehelm/releases/latest) · [协议](API.md) · [设计](DESIGN.md) · [验证记录](VERIFICATION.md) · [功能路线图](ROADMAP.md)

## 快速部署

适用于运行 systemd 的 Linux，支持 amd64、arm64、ARMv7。服务器需要 `curl`、`sha256sum`，无需安装 Go。

```sh
curl -fsSL https://raw.githubusercontent.com/chilin11/litehelm/main/install.sh -o litehelm-install.sh
sudo sh litehelm-install.sh
```

脚本从最新 Release 下载对应架构程序并校验 SHA-256，安装 systemd 服务，首次生成随机令牌；重复执行可更新，保留已有令牌、unit 与 drop-in。启动失败会尝试恢复旧程序与原运行状态。不会更改防火墙、安装其他软件或配置反向代理。

查看服务和连接令牌：

```sh
sudo systemctl status litehelm --no-pager
sudo cat /etc/litehelm/token
```

服务端监听 `127.0.0.1:9091`。把现有 HTTPS 反向代理的上游设为 `http://127.0.0.1:9091`，在 App 填写 `https://server.example.com` 和令牌。代理需转发 `Authorization`，管理域名不应额外跳转到网页登录。App 使用系统证书校验，不支持忽略证书错误。

指定版本或离线安装：

```sh
sudo sh litehelm-install.sh --version v0.3.0
# 从同一 Release 下载本机架构程序和 SHA256SUMS，放在同一目录：
sudo sh litehelm-install.sh --local-dir ./downloads
```

程序位于 `/usr/local/bin/litehelm`，令牌位于 `/etc/litehelm/token`（0600），服务配置为 `/etc/systemd/system/litehelm.service`。Release 的校验清单用于验证下载完整性；脚本与程序来源均为本仓库的 HTTPS 发布地址。

## 可以做什么

| 页面 | 当前功能 |
| --- | --- |
| 概览 | CPU、内存、Swap、根分区容量、网络速率、负载和运行时间 |
| 进程 | 搜索名称/PID/用户，按内存或 CPU 平均占用排序，TERM / KILL |
| 服务 | 搜索和状态筛选，启动/停止/重启，读取最近最多 80 行日志 |
| 设置 | 加密保存多服务器、切换/编辑/删除主机，服务器电源操作 |
| 收藏 | 每台主机独立保存最多 12 个常用服务，首页点击后先获取当前状态 |
| 日志 | 本地搜索与匹配行数、复制当前结果或全部，手动刷新 |
| 工具 | 按需查看监听端口与进程/服务、systemd 定时任务、本地挂载点容量与 inode |
| 脚本 | 服务端开启后粘贴/编辑 Shell 脚本运行，查看实时输出与退出码，可取消、重跑、删除记录 |

进程 CPU 是运行以来平均占用；网络速率需两个有效样本，首样本显示 `—`。进程结束使用 pidfd 和启动标识防止 PID 复用，要求 Linux 5.3+。服务管理与电源操作要求 systemd，OpenRC/procd 暂未支持。停止被守护的进程可能被自动拉起，需完全停止时请使用服务页。

默认保护 LiteHelm、SSH、systemd 和基础网络服务。可通过 `sudo systemctl edit litehelm` 添加你需要保护的程序/服务名前缀：

```ini
[Service]
Environment="LITEHELM_PROTECTED_PROCESSES=my-agent,my-proxy"
Environment="LITEHELM_PROTECTED_SERVICES=my-agent,my-proxy"
```

修改后执行 `sudo systemctl daemon-reload && sudo systemctl restart litehelm`。前缀匹配，不区分大小写。

## 主机重启 / 关机

默认关闭。需要时 `sudo systemctl edit litehelm`，填写：

```ini
[Service]
ExecStart=
ExecStart=/usr/local/bin/litehelm -listen 127.0.0.1:9091 -token-file /etc/litehelm/token -allow-power
```

重载配置并重启服务后，App 会显示电源操作。需手输主机名确认，接受后延迟 5 秒执行。关机后需要供应商控制台或其他方式开机。写操作不会自动重试；网络中断时请先检查执行结果。

## 运行脚本（默认关闭）

开启后，持有令牌的设备可在服务器上以 LiteHelm 服务用户身份执行**任意命令**（默认安装为 root），适合部署、更新等一次性任务。只在确实需要时开启：

```sh
sudo sh install.sh --enable-exec    # 开启，保留令牌与现有配置
sudo sh install.sh --disable-exec   # 关闭
```

开启会添加 `20-litehelm-exec.conf`（`LITEHELM_ALLOW_EXEC=1`、`StateDirectory=litehelm`）；之后不带参数重装保持当前开关不变。手动运行可用 `-allow-exec` 与 `-jobs-dir`。App 入口：主机工具 → 运行脚本。

- **root + systemd**：每个脚本是独立的临时 unit `litehelm-job-<ID>.service`，不受 LiteHelm 沙箱与 capabilities 限制，可写 `/etc`、`/usr`、`/opt`；LiteHelm 重启或升级不会中断任务。工作目录为 root 家目录。服务页不能停止这些 unit，请在任务页取消。
- **非 root 运行 LiteHelm**：脚本是 LiteHelm 的子进程组，权限与该用户相同，并继承 unit 沙箱（如 `ProtectSystem=strict`、`ProtectHome=read-only`）；需写入的路径请用 drop-in 的 `ReadWritePaths=` 放行。LiteHelm 重启会中断任务，记录显示“丢失”。
- 首行 `#!` 指定解释器，否则用 `/bin/sh`；Windows 换行自动转换。标准输入为空，不能交互，请使用 `-y`、`DEBIAN_FRONTEND=noninteractive` 等参数。
- 脚本结束时，它启动的后台进程会一起结束；常驻程序请交给 systemd 服务或容器。
- 时间上限可选 5 分钟 / 30 分钟 / 2 小时 / 24 小时（API 允许 10 秒–24 小时），超时或取消先发 TERM，再发 KILL。最多 4 个同时运行，保留最近 30 条记录（脚本、输出、结果），位于 `/var/lib/litehelm/jobs`（0700）。
- 输出总量不设上限，请避免无限刷屏；App 打开任务页时每 2 秒增量读取，只保留最新约 128K 字符，关闭页面即停止。
- 服务日志只记录任务 ID、名称、字节数和结果，不记录脚本内容；脚本可能含密钥，记录保存在服务器上，直到被清理或删除。

## 资源与权限

早期 ARM64 模拟器 Linux 内核参考：空闲 RSS 约 **5.6 MiB**，连续请求后约 **10.5 MiB**；状态请求约 **151 ms**（包含 150 ms CPU 采样等待），进程列表约 **11 ms**。这些不是目标服务器性能保证，进程数量、内核和硬件都会影响结果。没有常驻历史采集和持久化指标库。

为了管理其他用户的进程和系统服务，安装器创建受 systemd 沙箱与 capabilities 限制的 root 服务。令牌代表主机管理权限，请仅保存在自己的设备中。Android 使用 Keystore + AES-GCM 加密本地配置，禁用备份。

多服务器配置和收藏保存在手机加密存储，旧版本的单连接自动迁移。只刷新当前主机，切换后清空旧主机状态和速率；不查询其他保存的主机。工具页手动刷新，首页每 5 秒刷新不会加载服务、端口、任务或目录扫描。使用新工具需同步更新服务端。

## 按需诊断的限制

端口查看依赖系统 `ss`，缺少时显示不支持，不自动安装。最多展示 500 行；权限不足的 PID/服务归属显示未知。磁盘页只检查已知本地文件系统，跳过远程/FUSE 挂载，最多 64 个挂载点。

定时任务先支持已加载的 systemd timers，显示下一次/上次触发时间；仅单调时钟任务可能没有墙上时钟日期，显示不可用。手动触发默认关闭；要允许指定任务，可 `sudo systemctl edit litehelm`：

```ini
[Service]
Environment="LITEHELM_RUN_TIMERS=backup.timer,cleanup.timer"
Environment="LITEHELM_SCAN_ROOTS=/var/log,/var/cache"
```

重载配置并重启 LiteHelm 后生效。timer 名称精确匹配，受保护服务不能通过 timer 绕过限制；接受启动不等于任务已完成。不编辑 cron；任意脚本见“运行脚本”。

目录占用仅在明确确认后扫描，默认只允许 `/var/log`、`/var/cache`。限制 2 秒预算、1 万项、6 层，不跟随符号链接、不跨文件系统，最多返回前 20 项，扫描间隔至少 15 秒。空的 `LITEHELM_SCAN_ROOTS` 可禁用扫描。路径按段拒绝符号链接，配置目录本身或上级含符号链接时会拒绝。

扫描显示逻辑文件大小，不是实际分配量或可清理空间；硬链接按路径重复计数。遇到条数、深度、权限或时间限制显示部分结果。时间预算在文件系统调用之间检查，磁盘 I/O 卡顿可能超过预算；不会后台扫描或自动删除文件。
安全目录扫描要求 Linux 5.6+ 的 `openat2` 支持；不可用时明确拒绝扫描。子挂载（包括同设备 bind mount）在读取属性前跳过，其他功能不受此扫描要求影响。
安装器添加 `10-litehelm-openat2.conf`，设置 `RestrictSUIDSGID=false`：部分 systemd 版本在该项开启时屏蔽 openat2。原 unit 和用户 drop-in 保留；只读系统、NoNewPrivileges 与 capabilities 限制继续启用。若后续自定义配置再次屏蔽 openat2，扫描会拒绝执行。


## 从源码构建

服务端需要 Go 1.24+：

```sh
cd server
go test ./...
go vet ./...
cd ..
sh scripts/build-server.sh
python3 scripts/checksums.py
sudo sh deploy/install.sh
```

非 systemd 主机可手动启动状态/进程功能：

```sh
umask 077
./dist/litehelm-linux-arm64 -generate-token > token
GOMEMLIMIT=32MiB GOGC=75 ./dist/litehelm-linux-arm64 -token-file ./token
```

Android 需要 JDK 17 和 Android SDK 35：

```sh
cd android
./gradlew assembleDebug lintDebug
```

测试 APK 使用开发签名；持续分发需要稳定的签名密钥。发布工作流可读取仓库签名 Secrets，否则明确提供测试 APK。服务端测试只结束自己启动的临时进程，不执行主机重启。

## 卸载

```sh
sudo systemctl disable --now litehelm
sudo rm /etc/systemd/system/litehelm.service /usr/local/bin/litehelm
sudo systemctl daemon-reload
```

令牌和自定义 drop-in 保留；需要彻底清除时再自行删除 `/etc/litehelm/`、`/etc/systemd/system/litehelm.service.d/`、`/var/lib/litehelm/`（脚本任务记录），并移除对应的反向代理规则。

## License

MIT。运行时无第三方动态依赖，构建使用的 Go 依赖遵循其各自许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
