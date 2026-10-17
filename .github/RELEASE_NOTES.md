轻舵 · LiteHelm：原生 Android 手机管理端 + 轻量 Go Linux 服务端。

- 状态查看、进程搜索/排序与 TERM/KILL、systemd 服务管理及日志、可选主机电源操作。
- 服务端无 Docker、数据库和常驻历史采集；只监听本机端口，由用户配置 HTTPS 接入。
- `install.sh` 自动识别架构、校验下载、保留令牌与服务配置，并支持启动失败回退。
- 支持 Linux amd64 / arm64 / ARMv7；进程信号要求 Linux 5.3+，服务和电源操作要求 systemd。
- `LiteHelm-debug.apk` 为开发测试签名；配置发布签名时提供 `LiteHelm-release.apk`。本地或不同 CI 构建的开发签名可能不同，覆盖安装前请核对签名。

安装及更新：

```sh
curl -fsSL https://raw.githubusercontent.com/chilin11/litehelm/main/install.sh -o litehelm-install.sh
sudo sh litehelm-install.sh
```

详见仓库 README、验证记录与功能路线图。没有执行用户目标服务器部署或真实主机重启。
