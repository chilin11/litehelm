# 轻舵 · LiteHelm

**给小服务器用的手机管理工具。** 原生 Android App + 单文件 Go 服务端（内置网页版），查看状态、结束进程、管理服务，随手打开即可操作。

- 无需 Docker、数据库或单独的网页运行时；网页版静态文件编译进同一个程序。
- **同一个地址两种用法**：App 填 `https://server.example.com`，浏览器直接打开同一地址就是网页版，功能与 App 一致，电脑大屏多栏显示。
- 首页只在前台每 5 秒刷新，进程、服务和日志按需读取；退到后台停止请求。
- 深色原生手机界面，支持搜索、排序、日志查看和操作确认；简体中文 / 繁體中文 / English，默认跟随系统，可在设置中切换。
- 标准 HTTPS + Bearer Token，可接入你自己的反向代理。

```text
Android App ─┐
             ├─ HTTPS + Token ── 你的反向代理 ── http://127.0.0.1:9091
浏览器网页 ──┘   /api/v1/* → JSON API（令牌）   其他路径 → 内置网页
```

[下载 APK 和服务端](https://github.com/chilin11/litehelm/releases/latest) · [协议](API.md) · [设计](DESIGN.md) · [验证记录](VERIFICATION.md) · [功能路线图](ROADMAP.md)

## 快速部署

默认安装模式适用于运行 systemd 的 Linux，支持 amd64、arm64、ARMv7。服务器需要 `curl`、`sha256sum`，无需安装 Go。群晖 DSM、飞牛 fnOS 或其他定制 Linux 请先看下方 [NAS 与非 systemd 部署](#nas-与非-systemd-部署)，不要仅凭品牌判断是否能安装系统服务。

```sh
curl -fsSL https://raw.githubusercontent.com/chilin11/litehelm/main/install.sh -o litehelm-install.sh
sudo sh litehelm-install.sh
```

脚本从最新 Release 下载对应架构程序并校验 SHA-256，安装 systemd 服务，首次生成随机令牌；重复执行可更新到该 Release，保留已有令牌、unit 与 drop-in。启动失败会尝试恢复旧程序与原运行状态。不会更改防火墙、安装其他软件或配置反向代理。

**注意版本**：下载 main 上的安装脚本不等于安装 main 的服务端，脚本默认仍下载 `releases/latest`。仅推送 main 不会生成新版 Release；发布工作流由 `v*` 标签触发且需要成功发布。没有包含目标代码的 Release 时，使用下方[从源码构建和升级](#从源码构建和升级)，不能依靠 latest 获取新版。

查看服务和连接令牌：

```sh
sudo systemctl status litehelm --no-pager
sudo cat /etc/litehelm/token
```

服务端监听 `127.0.0.1:9091`。把现有 HTTPS 反向代理的上游设为 `http://127.0.0.1:9091`，在 App 填写 `https://server.example.com` 和令牌。代理需转发 `Authorization`，管理域名不应额外跳转到网页登录。App 使用系统证书校验，不支持忽略证书错误。

指定版本或离线安装：

```sh
sudo sh litehelm-install.sh --version v0.4.0
# 从同一 Release 下载本机架构程序和 SHA256SUMS，放在同一目录：
sudo sh litehelm-install.sh --local-dir ./downloads
```

程序位于 `/usr/local/bin/litehelm`，令牌位于 `/etc/litehelm/token`（0600），服务配置为 `/etc/systemd/system/litehelm.service`。Release 的校验清单用于验证下载完整性；脚本与程序来源均为本仓库的 HTTPS 发布地址。

## NAS 与非 systemd 部署

**支持边界：以下是基于代码与本地安装器夹具的兼容路径，不是 NAS 实机认证。DSM 6.x / 7.x、飞牛 fnOS 的具体发行版本、机型、CPU 与固件组合均未经实机验证。** 不安装 SPK/fpk、不接管厂商启动系统，也不自动修改 NAS 用户、系统分区、防火墙或反向代理。

| 环境 | 建议部署路径 | 不作保证的部分 |
| --- | --- | --- |
| 群晖 DSM，尤其没有可用 systemd 管理器时 | 普通本机账号的私有目录 + `--manual-dir`，先前台运行 | DSM 套件服务、任务计划、存储池、系统关机流程不由 LiteHelm 适配；不能把 `synoservice` 等同于 systemd |
| 飞牛 fnOS，实际运行 systemd 时 | 确认管理器可达与安装权限后可用默认安装；不想更改系统时仍可手动部署 | 不按发行版名称假定 systemd 版本、沙箱、命令选项与普通 Debian 完全相同；固件升级后的持久性需自行验证 |
| 其他非 systemd Linux，或 systemd 工具/权限不完整 | `--manual-dir` + 自己已有的前台监管器 | 不提供 OpenRC、procd 或厂商 init 的服务管理适配 |

先用只读命令确认实际环境（不存在的命令可以跳过；不要为此更换 NAS 的 init）：

```sh
uname -srm
cat /proc/1/comm
command -v systemctl
# 仅在 systemctl 存在时：确认能与管理器通信，而不只是安装了命令
systemctl show --property=Version
```

Release 架构按 `uname -m` 选择：`x86_64` → `amd64`、`aarch64` / `arm64` → `arm64`、`armv7l` / `armv8l` → `armv7`（需要 ARMv7 指令支持）。没有 i686、ARMv5、ARMv6 等旧 NAS 的预编译包；ARM64 CPU 搭配 32 位用户空间的组合也未验证。Go 静态程序不依赖 NAS 的 glibc 版本，但仍受 Linux 内核、CPU 指令和挂载执行权限约束，不能靠换 libc 绕过这些要求。

### 无需 root 的手动部署

使用**普通本机账号**，选择真正的本地、可执行、仅自己可访问的目录；不需要 NAS 的管理员组或 root。`--manual-dir` 与 `--version` / `--local-dir` 可组合，但不能搭配系统模式的 exec/web 配置选项：

```sh
curl -fsSL https://raw.githubusercontent.com/chilin11/litehelm/main/install.sh -o litehelm-install.sh
# DSM 的 HOME 可能经过 /var/services 等符号链接，先取得真实路径
nas_home=$(CDPATH='' cd -- "$HOME" && pwd -P)
manual_dir="$nas_home/.local/share/litehelm"
sh litehelm-install.sh --manual-dir "$manual_dir"
# 离线时改用：sh litehelm-install.sh --manual-dir "$manual_dir" --local-dir ./downloads
cd "$manual_dir"
GOMEMLIMIT=32MiB GOGC=75 ./litehelm -token-file ./token
```

此模式只在目标目录放置 `litehelm` 与首次生成的 `token`，校验 SHA-256 并在目标文件系统执行 `-h` 检查；不调用 systemctl、不创建账号、不注册自启、不重启已有进程。目录须由当前账号拥有且没有组/其他人权限（通常 0700），已有 token 须由当前账号拥有且为 0600；不符合时拒绝而非替你改权限。路径各段及目标文件不能是符号链接。需要 Linux 的 GNU/BusyBox `stat -c`、标准 Shell 工具及支持 Unix 权限/硬链接的本地文件系统。新目录按 0700 创建；NAS 的共享权限/ACL 仍应自行确认，**不要放在多人可写或可浏览的共享文件夹中**。

程序仍只监听 `127.0.0.1:9091`，本机读取 `token` 后，使用 NAS 上已有的 HTTPS 反向代理访问；代理应保留 `Host`、转发 `Authorization`。不能直接改成 `0.0.0.0` 对公网开放；另一容器或另一机器上的代理也不能直接访问该 loopback，应由用户设计安全的本机转发，勿为此开放特权容器或 Docker socket。令牌不要粘贴到工单或公开日志。

前台运行可用 Ctrl+C / SIGTERM 停止。需要自启与崩溃拉起时，由用户在 NAS **已有**的监管器中配置绝对路径启动命令、运行账号、日志轮转及停止策略，并确保数据卷已挂载；DSM 的“开机任务”不等于持续监管，具体固件可能回收任务子进程，未验证前不要假定能常驻。不建议修改 `/etc/rc*`、厂商套件文件或手工用 PID 文件自动 kill。手动模式不具备默认 systemd unit 的沙箱，切勿为了功能完整直接改成 root 常驻。

升级时重跑同一 `--manual-dir` 命令：保留 token，校验成功后原子替换二进制，不自动停止或重启正在运行的旧程序。先自行备份旧二进制，升级后通过原监管器停止/启动；启动失败时由用户还原备份，不承诺手动模式自动回滚。卸载时先停监管器/进程、移除自启配置，再仅删除自己的部署目录；不要执行下方 systemd 卸载命令。

### NAS 上各功能的实际条件

- **资源/进程查看**：依赖可读的 `/proc` 和本地文件系统信息。普通账号受 NAS ACL、`hidepid`、容器命名空间限制，可能只看到部分进程/归属。概览容量是根分区，不是 NAS 存储池容量；挂载点页不等于 RAID/SMART/配额管理。
- **结束进程**：安全发信号需要可用的 pidfd（上游 Linux 5.3+，厂商回移/过滤可能不同）及相应权限；缺少时拒绝，不退回有 PID 复用风险的 kill。当前能力字段主要反映 root 身份，不代表内核功能已探测成功；NAS 管理员组也不等于 root。不要为结束厂商守护进程提升权限，可能影响网络、存储与远程访问。
- **服务、服务日志、定时任务**：只适配 systemd units / journal / timers，分别需要可达管理器和 `systemctl` / `journalctl`。无 systemd 时列表报告不支持；不是 DSM 套件、飞牛应用、容器或 NAS 图形界面任务计划的管理接口。非 root 通常无权启停服务，日志也可能受限。
- **磁盘/目录扫描**：仅已知本地文件系统，远程/FUSE 挂载跳过；缺少 `/proc/self/fdinfo` 的挂载标识时也可能跳过。安全扫描需要可用的 openat2（上游 Linux 5.6+），路径中有符号链接或沙箱屏蔽调用时拒绝；不会为兼容旧内核关闭这些安全校验。NAS 的共享目录常是链接或受 ACL 限制，需使用明确授权的真实路径。
- **脚本**：仍默认关闭。普通账号手动运行时可显式加 `-allow-exec -jobs-dir /absolute/private/jobs`，任务以同一账号作为子进程组运行；目录须符合现有的 ASCII 绝对路径限制，服务重启不保证任务存活。不提供非 systemd 下从 root 安全切换执行账号的实现；不要用 `-exec-user root` 规避限制。systemd 临时任务还依赖 `systemd-run` 及其属性支持，旧发行版/厂商变体不保证可用。
- **重启/关机**：默认关闭；依赖 root、systemd、`systemd-run`，未适配厂商关机/存储卸载流程，NAS 上建议保持关闭并使用厂商控制台。

可复现的最小本地验证：`sh scripts/test-install.sh --manual-only`，只用临时目录和 Shell 假二进制验证手动安装、重装保留 token、校验失败不替换、拒绝符号链接及不调用系统安装命令；**不验证真实 Release 在 NAS 上可执行，也不进行真实系统安装**。默认不带该选项的安装测试仍仅限一次性 systemd CI 主机。

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

## 网页版

服务端在同一端口按路径区分：`/api/v1/*` 是 App 与网页共用的 JSON API（必须带 Bearer 令牌）；其他路径返回内置网页。反向代理无需改动，浏览器打开 App 填写的同一个 HTTPS 地址，输入令牌即可。

- 页面：概览（资源、网速、收藏服务、快捷入口）、进程、服务（筛选/详情/启停/日志搜索复制）、定时任务、端口、磁盘与受限扫描、脚本、设置（能力、语言、电源）。电脑侧栏 + 双栏详情；手机底部导航，和 App 同一套颜色。
- 简体中文 / 繁體中文 / English，跟随浏览器，可在设置切换。
- 令牌默认只保存到关闭标签页（sessionStorage），勾选“在此浏览器记住”才写入 localStorage；不使用 Cookie，因此没有 CSRF 面。收藏服务保存在浏览器本地。
- 浏览器请求只允许同源：`Sec-Fetch-Site: same-origin` 或 `Origin` 与请求 Host 一致，其余带 `Origin` 的请求 403。代理需保留原始 `Host`（Caddy/nginx `proxy_set_header Host $host` 等默认做法）。App 不发送 `Origin`，不受影响。
- 网页响应带严格 CSP（仅本站脚本/样式，无内联脚本、无第三方资源）、`frame-ancestors 'none'`、`no-referrer`。网页本身不含数据，无需令牌即可加载。
- 不需要网页时关闭（API 不受影响）：

```sh
sudo sh install.sh --disable-web   # 添加 30-litehelm-web.conf（LITEHELM_WEB=0）
sudo sh install.sh --enable-web    # 恢复默认开启
```

手动运行可用 `-web=false`。之后不带参数重装保持当前开关。

## 主机重启 / 关机

默认关闭。需要时 `sudo systemctl edit litehelm`，填写：

```ini
[Service]
ExecStart=
ExecStart=/usr/local/bin/litehelm -listen 127.0.0.1:9091 -token-file /etc/litehelm/token -allow-power
```

重载配置并重启服务后，App 会显示电源操作。需手输主机名确认，接受后延迟 5 秒执行。关机后需要供应商控制台或其他方式开机。写操作不会自动重试；网络中断时请先检查执行结果。

## 运行脚本（默认关闭）

开启后，持有令牌的设备可在服务器上执行**任意命令**，适合部署、更新等一次性任务。脚本默认以**专用低权限用户 `litehelm`** 运行，不是 root。只在确实需要时开启：

```sh
sudo sh install.sh --enable-exec          # 开启；不存在时创建 litehelm 用户
sudo sh install.sh --exec-user root       # 需要 root 时显式指定（也可填其他已有用户）
sudo sh install.sh --exec-user litehelm   # 改回默认
sudo sh install.sh --disable-exec         # 关闭（保留 litehelm 用户）
```

开启会添加 `20-litehelm-exec.conf`（`LITEHELM_ALLOW_EXEC=1`、`StateDirectory=litehelm`，非默认用户时加 `LITEHELM_EXEC_USER=`）；之后不带参数重装保持当前开关和用户不变。手动运行可用 `-allow-exec`、`-exec-user` 与 `-jobs-dir`。App 和网页的脚本页会显示实际运行身份。

- **默认 `litehelm` 用户**：安装器创建的系统用户，无登录 shell、无 sudo，家目录 `/home/litehelm`（0700，也是工作目录）。任务设置 `NoNewPrivileges`，无法通过 sudo 或 setuid 程序提权；不能写 `/etc`、`/usr`，也读不到 LiteHelm 的令牌和任务记录。部署目录请提前授权，例如 `sudo chown -R litehelm /srv/myapp`；需要重启服务、装软件包时用 `--exec-user root`。
- **systemd 运行方式**（LiteHelm 以 root 运行时）：每个脚本是独立的临时 unit `litehelm-job-<ID>.service`，以上述用户运行，不在 LiteHelm 自身的沙箱里；LiteHelm 重启或升级不会中断任务。服务页不能停止这些 unit，请在任务页取消。
- **非 root 运行 LiteHelm**：无法切换用户，脚本是 LiteHelm 的子进程组，权限与该用户相同，并继承 unit 沙箱（如 `ProtectSystem=strict`、`ProtectHome=read-only`）；需写入的路径请用 drop-in 的 `ReadWritePaths=` 放行。LiteHelm 重启会中断任务，记录显示“丢失”。
- 从 v0.4.0 及更早版本升级：原来脚本以 root 运行；不带参数重装后改为 `litehelm`。确需 root 的部署脚本请执行 `--exec-user root`。
- 首行 `#!` 指定解释器，否则用 `/bin/sh`；Windows 换行自动转换。标准输入为空，不能交互，请使用 `-y`、`DEBIAN_FRONTEND=noninteractive` 等参数。
- 脚本结束时，它启动的后台进程会一起结束；常驻程序请交给 systemd 服务或容器。
- 时间上限可选 5 分钟 / 30 分钟 / 2 小时 / 24 小时（API 允许 10 秒–24 小时），超时或取消先发 TERM，再发 KILL。最多 4 个同时运行，保留最近 30 条记录（脚本、输出、结果），位于 `/var/lib/litehelm/jobs`（0700）。
- 输出总量不设上限，请避免无限刷屏；App 打开任务页时每 2 秒增量读取，只保留最新约 128K 字符，关闭页面即停止。
- 服务日志只记录任务 ID、名称、字节数和结果，不记录脚本内容；脚本可能含密钥，记录保存在服务器上，直到被清理或删除。

## 资源与权限

早期 ARM64 模拟器 Linux 内核参考：空闲 RSS 约 **5.6 MiB**，连续请求后约 **10.5 MiB**；这些是旧版本数据，不代表当前后台监控版本的性能。当前服务端约每 5 秒采样资源并计算告警，不保存历史数据或持久化指标库；实际开销取决于服务器环境。

为了管理其他用户的进程和系统服务，安装器创建受 systemd 沙箱与 capabilities 限制的 root 服务。令牌代表主机管理权限，请仅保存在自己的设备中。Android 使用 Keystore + AES-GCM 加密本地配置，禁用备份。

多服务器配置和收藏保存在手机加密存储，旧版本的单连接自动迁移。只刷新当前主机，切换后清空旧主机状态和速率；不查询其他保存的主机。Android 概览仅在前台约每 10 秒刷新，不在后台采样或轮询；工具页手动刷新。资源告警由服务端计算，使用该功能需同步升级服务端。

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


## 从源码构建和升级

以下命令从**包含目标改动的同一源码树根目录**运行。服务端构建需要 Go 1.24+、Python 3 和构建依赖；构建机会输出 Linux amd64、arm64、armv7 三个程序，不需要在 NAS 上安装 Go：

```sh
# 可选：记录当前源码版本和未提交变更，确认确实包含要部署的代码
git rev-parse HEAD
git status --short
sh scripts/build-server.sh
python3 scripts/checksums.py
```

产物在 `dist/litehelm-linux-{amd64,arm64,armv7}` 和 `dist/SHA256SUMS`；脚本默认以 Git 描述（含 dirty 标记）写入版本，也可在构建时设置不含空格的 `VERSION`。构建机与目标机不同时，通过可信渠道传送对应架构程序、清单和**同一源码树的** `install.sh`；`SHA256SUMS` 只能检查完整性，不能在程序与清单都被替换时证明来源。确保目标机有 `sha256sum`，核实 CPU 架构与文件来源。源码树内的 `deploy/install.sh` 是固定使用本树 `dist` 的便捷包装；下面的命令均在目标机的源码树根目录执行，或在只传送产物时改为 `sh install.sh --local-dir /实际产物目录` 并保留对应模式参数。

### systemd 首装或升级

升级前安排维护窗口，检查现有 unit、drop-in、代理与运行状态；将 `/usr/local/bin/litehelm`、`/etc/litehelm/`、`/etc/systemd/system/litehelm.service` 及其 drop-in、`/var/lib/litehelm/`（如存在）安全备份到仅授权人员可读的位置。令牌和任务记录可能包含敏感信息，不要公开。首次安装没有旧文件时跳过备份。确认目标上 systemd 管理器可达，再执行：

```sh
systemctl show --property=Version
sudo sh deploy/install.sh
sudo systemctl status litehelm --no-pager
/usr/local/bin/litehelm -version
```

该包装使用 `--local-dir dist`，不是下载 latest；安装器校验并试运行程序，保留既有 token、unit 和用户 drop-in，重启并启用服务，启动失败时尝试恢复旧程序与原状态，但不代替备份。检查日志 `sudo journalctl -u litehelm -n 40 --no-pager`，确认原 HTTPS 地址及带令牌的访问正常；检查版本时同时核对构建记录，dirty 版本不唯一。恢复时按现场备份与原配置操作，避免覆盖令牌。

### NAS / 非 systemd 手动首装或升级

按上方 NAS 章节选择普通账号、真实的私有可执行目录，预先确认权限和监管方式；首次没有目录时安装器创建。升级前安全备份该目录中的旧 `litehelm` 和 `token`，以及运行参数、监管配置和任务数据（如有）；令牌须保持私密。以下仍从目标机上包含 `dist` 的源码树根目录执行：

```sh
nas_home=$(CDPATH='' cd -- "$HOME" && pwd -P)
manual_dir="$nas_home/.local/share/litehelm"
sh deploy/install.sh --manual-dir "$manual_dir"
# 首次前台启动；已有监管器时通过原监管器停止并启动，不要额外启动第二份
cd "$manual_dir"
GOMEMLIMIT=32MiB GOGC=75 ./litehelm -token-file ./token
```

手动模式保留 token、替换磁盘上的程序，但**不会重启正在运行的旧进程，也不会自动回滚**；升级后由原监管器停止旧进程并启动新程序，确认服务和 HTTPS 访问正常，失败时停止后从安全备份还原旧程序。仅以普通账号前台部署时按 Ctrl+C 停止旧进程，再启动新程序。安装器不替用户配置监管器或反向代理。所有模式均只监听本机 loopback；远程访问需要可信 HTTPS 反向代理转发 `Authorization`，App 校验证书且不支持跳过证书错误，令牌切勿放进公开日志。

### Android 构建与安装

Android 需要 JDK 17 和 Android SDK 35：

```sh
cd android
./gradlew assembleDebug
```

仓库中的 [Debug APK](documents/LiteHelm-debug.apk) 是测试构建，包名 `com.litehelm.mobile`，最低 Android 8.0（API 26）。传到设备后从可信文件管理器打开，并按系统提示允许该来源安装；不自动安装或卸载。其 Debug 签名与正式 Release 固定签名不同，**不能覆盖安装正式版**；卸载正式版会删除应用数据（包括加密的主机配置），无法保证保留。只有签名一致、版本号等安装条件满足的构建才能覆盖升级；请事先安全保存服务器地址和令牌，勿公开分享。Debug APK 可调试，不应作为正式生产发布包。

发布工作流需要配置稳定签名 Secrets，并核验固定证书；缺少签名配置会失败，不会自动提供正式签名 APK。

## 卸载

```sh
sudo systemctl disable --now litehelm
sudo rm /etc/systemd/system/litehelm.service /usr/local/bin/litehelm
sudo systemctl daemon-reload
```

令牌和自定义 drop-in 保留；需要彻底清除时再自行删除 `/etc/litehelm/`、`/etc/systemd/system/litehelm.service.d/`、`/var/lib/litehelm/`（脚本任务记录），开启过脚本的执行 `sudo userdel -r litehelm`，并移除对应的反向代理规则。

## License

MIT。运行时无第三方动态依赖，构建使用的 Go 依赖遵循其各自许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
