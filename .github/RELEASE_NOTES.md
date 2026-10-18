轻舵 · LiteHelm v0.3.0：新增“运行脚本”，可在手机上让服务器执行部署等一次性 Shell 脚本。

- 主机工具 → 运行脚本：粘贴/编辑脚本，选择时间上限（5 分钟–24 小时），确认后运行；任务页实时显示输出、退出码，可取消、复制输出、编辑后重跑、删除记录。
- **默认关闭**。开启后令牌等同于服务用户（默认 root）的 shell，请只在需要时开启：`sudo sh litehelm-install.sh --enable-exec`，关闭用 `--disable-exec`；之后普通更新保持当前开关。
- root + systemd 下每个脚本是独立的临时 unit，不受 LiteHelm 沙箱限制，LiteHelm 重启或升级不会中断任务；非 root 运行时脚本作为子进程，继承服务沙箱。
- 脚本不可交互（标准输入为空），后台进程随脚本结束；常驻程序请交给 systemd 或容器。服务器保留最近 30 条记录，最多 4 个同时运行。
- 需要同时更新服务端与 App；旧服务端会提示不支持。

更新服务端（保留令牌、unit 与 drop-in）：

```sh
curl -fsSL https://raw.githubusercontent.com/chilin11/litehelm/main/install.sh -o litehelm-install.sh
sudo sh litehelm-install.sh --enable-exec   # 不需要脚本功能就去掉 --enable-exec
```

`LiteHelm-release.apk` 使用固定签名，可直接覆盖 v0.2.1 并保留已保存的主机。之前从 GitHub 下载的 v0.1.0 / v0.2.0 使用临时签名，需先卸载一次。详见 README 与 VERIFICATION.md。
