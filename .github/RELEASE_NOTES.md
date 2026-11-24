轻舵 · LiteHelm v0.4.2：服务端持续监控资源占用，并改进 NAS 与非 systemd 部署。

## 服务端资源监控

- 服务端约每 5 秒采样 CPU、内存和根分区 `/`，占用达到 85% 时生成当前告警；没有客户端连接时也继续采样。
- Web 和 Android 展示服务端结果，明确区分预热、采样失败和数据过期；Android 只在前台概览约每 10 秒刷新，不在后台轮询。此版本不提供推送、历史告警或 NAS 存储池监控。
- 使用新资源提醒请同时升级服务端。正式 APK 使用现有发布签名，不能覆盖不同签名的 Debug 版。

## 安装和升级

- Debian 等 systemd Linux：下载本 Release 的 `install.sh`，执行 `sudo sh install.sh --version v0.4.2`；同一命令可首装或升级，保留现有令牌和服务配置。
- 群晖 DSM 或非 systemd Linux：普通账号可使用 `--manual-dir` 部署到私有目录；需自行启动或重启服务，不配置自启。飞牛 fnOS 若有可用的 systemd 可走标准安装。未做 DSM 或 fnOS 实机认证，详见仓库 README。
- 服务只监听本机 `127.0.0.1:9091`，手机访问仍需可信 HTTPS 反向代理。
