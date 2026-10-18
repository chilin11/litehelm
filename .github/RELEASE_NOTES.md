轻舵 · LiteHelm v0.2.1：修复底部导航错位，新增多语言界面。

- 修复底部导航文字与图标不对齐；大字体/窄屏下标签自动缩小，标题与按钮不再把英文单词断开。
- 界面支持简体中文、繁體中文与 English，默认跟随系统；设置页可切换语言，Android 13+ 也可在系统“应用语言”中设置。
- 后台错误提示和磁盘扫描限制原因同步翻译；服务端返回的错误原文显示。
- 服务端无改动，已安装 v0.2.0 服务端无需更新。

安装及更新服务端：

```sh
curl -fsSL https://raw.githubusercontent.com/chilin11/litehelm/main/install.sh -o litehelm-install.sh
sudo sh litehelm-install.sh
```

`LiteHelm-release.apk` 使用固定签名，与维护者本机编译版本一致，今后可直接覆盖更新、保留已保存的主机。之前从 GitHub 下载的 v0.1.0 / v0.2.0 使用临时签名，无法直接覆盖：需先卸载一次（会清除手机上保存的主机，需要重新填写）。详见 README 与 VERIFICATION.md。
