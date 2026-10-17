# 轻舵 · LiteHelm Android

轻量服务器管家，项目名 `litehelm`。原生 Java Views，零第三方运行时依赖。最低 Android 8.0（API 26），编译/目标 API 35。应用 ID：`com.litehelm.mobile`，入口：`.MainActivity`，桌面名称：轻舵 LiteHelm。

## 构建

```sh
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug lintDebug --offline
```

本机 SDK 路径可写入不入库的 `local.properties`；其他机器请设置自己的 `sdk.dir` 或 `ANDROID_HOME`。Gradle wrapper 8.11.1，AGP 8.7.3。离线构建要求本机已有 Gradle、插件及 SDK 缓存。APK：`app/build/outputs/apk/debug/app-debug.apk`。Debug 使用现有调试签名；正式签名由发布流程配置，不在仓库保存密钥。

## 连接

仅需 HTTPS origin 与 Bearer 访问令牌。地址不能包含路径、查询、fragment 或 userinfo；移除地址末尾的 `/`，所有构建均拒绝 HTTP，使用系统 TLS 证书验证。反向代理应直接转发 `/api/v1/` 请求与 Authorization 请求头，并返回 API JSON；应用拒绝重定向及 HTML 页面，没有证书验证或网络认证旁路。

首次启动为连接表单；保存前用草稿请求 `/api/v1/system`，成功后才替换加密配置，失败保留草稿和原有效配置。仅当前前台页面、当前请求代次的主线程回调可以保存凭据与切换有效连接；旋转、切页或后台导致的过期回调不会保存。

地址与令牌使用 AES-256/GCM + Android Keystore 加密，别名为 `litehelm.credentials.v1`；系统备份/设备迁移排除全部应用数据。凭据页禁止截图，输入不参与自动填充或实例状态持久化；配置变更仅在内存保留草稿，进程被系统终止后未保存草稿不会持久化。本应用使用独立应用 ID，不迁移此前开发版本的凭据。

## 行为与边界

- 概览 Activity 前台且可见时，每次请求结束 5 秒后拉取；单个串行 IO executor，全局写入互斥，离开概览清除网络速率基线，后台撤销下一次轮询并取消当前只读连接。
- 进程、服务按导航/手动刷新加载。过期 capability 在进入这些页面时先按需验证 system，然后获取列表。CPU 排序由 API 执行；进程 CPU 保留生命周期平均口径。
- TERM/KILL 原样携带 `start_time`；PID 1/缺标识/过期数据禁用。服务 stop/restart 单独确认；电源需 power capability 与手输 hostname 精确匹配。
- 所有 POST 不重试。未知写入结果显示“结果未确认”；不会声明进程已退出或自行修改服务状态。只读刷新用于确认现状。
- 10 秒连接/20 秒读取超时；响应总上限 2 MiB，日志文本额外限制 64 KiB。错误隐藏访问令牌，无终端/数据库/后台任务/网络日志。
- 按 `../DESIGN.md` 的固定深色变量、四入口导航、资源面板、细进度条、底部确认层实现。窄屏/大字体内容改纵向排列，列表复用 Views，日志可选中文本。

## 验证范围

构建与静态检查：`./gradlew assembleDebug lintDebug --offline`；签名可用 SDK 的 `apksigner verify` 检查。设备安装、真实 HTTPS 后端联调、旋转与后台交互、200% 字体及 TalkBack 仍需设备验收。构建和 Lint 无法代替这些交互检查；不应为验收执行真实服务停止、进程终止或主机电源操作。
