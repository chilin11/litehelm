# 轻舵 · LiteHelm

**给小服务器用的手机管理工具。** 原生 Android App + 单文件 Go 服务端，查看状态、结束进程、管理服务，随手打开即可操作。

- 无需 Docker、数据库、服务器网页运行时。
- 首页只在前台每 5 秒刷新，进程、服务和日志按需读取；退到后台停止请求。
- 深色原生手机界面，支持搜索、排序、日志查看和操作确认。
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
sudo sh litehelm-install.sh --version v0.1.0
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
| 设置 | 验证并加密保存连接，服务器电源操作 |

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

## 资源与权限

早期 ARM64 模拟器 Linux 内核参考：空闲 RSS 约 **5.6 MiB**，连续请求后约 **10.5 MiB**；状态请求约 **151 ms**（包含 150 ms CPU 采样等待），进程列表约 **11 ms**。这些不是目标服务器性能保证，进程数量、内核和硬件都会影响结果。没有常驻历史采集和持久化指标库。

为了管理其他用户的进程和系统服务，安装器创建受 systemd 沙箱与 capabilities 限制的 root 服务。令牌代表主机管理权限，请仅保存在自己的设备中。Android 使用 Keystore + AES-GCM 加密本地配置，禁用备份。

当前只保存一个服务器连接。多主机、收藏、端口排查等候选功能见 [路线图](ROADMAP.md)，尚未实现的功能不会出现在 UI 中。

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

令牌和自定义 drop-in 保留；需要彻底清除时再自行删除 `/etc/litehelm/`、`/etc/systemd/system/litehelm.service.d/`，并移除对应的反向代理规则。

## License

MIT。运行时无第三方动态依赖，构建使用的 Go 依赖遵循其各自许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
