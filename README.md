# Lin-Shizuku

基于 Shizuku 13.5.4 与 Sui 深度定制的授权管理工具，在原版基础上新增大量实用功能，让手机更好用。

## 核心功能

- **三种启动方式**：无线调试（无需电脑）、连接电脑 ADB、Root 直启
- **开机自启**：支持开机广播、预热、Root 脚本三种模式，重启后自动恢复授权
- **无线调试断 WiFi 不断服务**：切换 TCP 模式后，断开 WiFi 服务依然运行
- **高刷新率**：自动识别设备最高刷新率并锁定
- **应用授权管理**：一键管理应用的使用情况访问、悬浮窗等权限
- **电池优化白名单**：防止后台被系统杀死
- **进程守护**：双进程互守，崩溃自动恢复
- **内置浏览器与检查更新**：内置下载器，一键检测并安装新版本

## 与原版 Shizuku 的区别

原版只提供授权服务本身。Lin-Shizuku 在保留原版全部功能的基础上，加了无线调试自启、刷新率锁定、电池白名单、进程守护、内置浏览器、自动更新等系统级优化，开箱即用，不需要额外装其他工具。

## 下载与安装

| 版本 | 发布日期 | 更新说明 | 下载 |
|------|---------|---------|------|
| v1.0.8 | 2026-10-08 | 服务端深度优化、服务 API 升级至 1.0.2、开机启动改进、无线调试配对优化 | [下载](https://github.com/Linwan-yn/Lin-Shizuku/releases/tag/v1.0.8) |
| v1.0.7 | 2026-10-05 | 终端页长输出遮挡修复 | [下载](https://github.com/Linwan-yn/Lin-Shizuku/releases/tag/v1.0.7) |
| v1.0.6 | 2026-10-03 | WebUI 兼容性热修复 | [下载](https://github.com/Linwan-yn/Lin-Shizuku/releases/tag/v1.0.6) |
| v1.0.5 | 2026-10-01 | 完善模块体系、优化功能逻辑 | [下载](https://github.com/Linwan-yn/Lin-Shizuku/releases/tag/v1.0.5) |
| v1.0.4 | 2026-09-30 | UI 与内存优化、设置页整理 | [下载](https://github.com/Linwan-yn/Lin-Shizuku/releases/tag/v1.0.4) |
| v1.0.3 | 2026-09-29 | 优化 API、新增功能 | [下载](https://github.com/Linwan-yn/Lin-Shizuku/releases/tag/v1.0.3) |
| v1.0.2 | 2026-09-27 | UI 优化 | [下载](https://github.com/Linwan-yn/Lin-Shizuku/releases/tag/v1.0.2) |
| v1.0.1 | 2026-09-21 | UI、功能、性能优化 | [下载](https://github.com/Linwan-yn/Lin-Shizuku/releases/tag/v1.0.1) |
| v1.0.0 | 2026-09-19 | 首版发布 | [下载](https://github.com/Linwan-yn/Lin-Shizuku/releases/tag/v1.0.0) |

每个版本的 APK 文件在对应的 Release 页面内，点击版本链接即可进入下载。

### 安装说明

1. 下载 APK 后**直接覆盖安装**即可升级（各版本签名一致）
2. 打开应用，按引导开启**无线调试**并配对：开发者选项 → 无线调试 → 使用配对码配对设备，在通知栏或应用内输入 6 位配对码
3. 配对成功自动启动服务，首页显示「正在运行中」

## 项目状态

- 本仓库以 **APK 发布与更新说明**为主，**源代码暂不公开**
- 部分实验性功能仍在完善中；个别定制 ROM（如 MIUI/HyperOS）可能需要额外权限配置
- **推荐安装最新版本**（v1.0.8）以获得最佳体验
- 详细版本记录见 [CHANGELOG.md](CHANGELOG.md)

## 开源协议

[LICENSE](LICENSE)：MIT License（Copyright (c) 2026 Linwan-yn）
