简体中文 | [English](#english-version-coming-soon)

# Lin-Shizuku

> 基于 Shizuku 13.5.4 与 Sui 深度定制的增强版授权管理工具

一个功能丰富的 Android 权限管理和系统优化应用，在保留原版 Shizuku 全部功能的基础上，新增无线调试自启、Wi-Fi 保活、刷新率锁定、电池优化、进程守护等多项实用功能。

[![Latest Release](https://img.shields.io/github/v/release/Linwan-yn/Lin-Shizuku?style=flat-square)](https://github.com/Linwan-yn/Lin-Shizuku/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-10%2B-green?style=flat-square)](https://www.android.com/)
[![Stars](https://img.shields.io/github/stars/Linwan-yn/Lin-Shizuku?style=flat-square)](https://github.com/Linwan-yn/Lin-Shizuku/stargazers)

## 📥 快速开始

### 最简单的方式：直接下载 APK

1. 前往 [Release 页面](https://github.com/Linwan-yn/Lin-Shizuku/releases)
2. 下载最新版本的 APK ���件（例：`Lin-Shizuku_1.0.8-lin-Release.apk`）
3. 在 Android 手机上安装 APK
4. 启动应用，按照提示完成配置

### 最低要求

- **Android 系统**：10 及以上
- **Shizuku 服务**：必须安装（通过无线调试、ADB 或 Root 方式启动）
- **内存**：建议 2GB 以上
- **存储**：约 50MB 空余空间

### 启动方式（三选一）

#### 1. 无线调试（推荐新手，无需电脑）
- 进入手机设置 > 开发者选项 > 无线调试
- 打开 Lin-Shizuku，进入设置 > Shizuku 配置
- 选择"无线调试启动"，按照指引完成配对
- 首次配对后，支持开机自启

#### 2. 连接电脑 ADB
- 需要在电脑上安装 ADB 工具
- 执行命令：`adb shell sh /sdcard/Lin-Shizuku/start.sh`
- 详见 [BUILD.md](BUILD.md#adb-启动方式)

#### 3. Root 直启
- 手机需要已 Root（推荐使用 KernelSU、Magisk 等）
- 应用会自动检测 Root 权限并启动服务
- 支持开机自启脚本

## ✨ 核心功能

### 📱 启动方式
- ✅ 无线调试启动（无需电脑）
- ✅ ADB 启动（连接电脑）
- ✅ Root 直启（已 Root 设备）

### 🚀 开机自启
- ✅ 开机广播自启
- ✅ 系统预热自启
- ✅ Root 脚本自启
- ✅ 重启后服务自动恢复

### 🌐 无线调试保活
- ✅ TCP 模式支持
- ✅ WiFi 断开时服务不中断
- ✅ 自动重连机制
- ✅ 网络切换适配

### 🎮 高刷新率支持
- ✅ 自动识别设备最高刷新率
- ✅ 刷新率智能锁定
- ✅ 减少帧率波动
- ✅ 支持 90Hz、120Hz、144Hz 等

### 🔐 应用授权管理
- ✅ 一键查看应用权限使用情况
- ✅ 应用使用情况访问权限管理
- ✅ 悬浮窗权限一键管理
- ✅ 权限批量操作

### 🔋 电池优化
- ✅ 电池优化白名单管理
- ✅ 防止应用被系统后台杀死
- ✅ 自定义白名单规则
- ✅ 省电和流畅平衡

### 🛡️ 进程守护
- ✅ 双进程互相守护
- ✅ 应用崩溃自动重启
- ✅ 内存监控与清理
- ✅ 稳定性增强

### 🌐 内置浏览器
- ✅ 无需跳转外部应用
- ✅ 直接浏览更新说明
- ✅ 快速下载应用更新
- ✅ 集成下载管理器

### 📡 自动更新检测
- ✅ 启动应用自动检测新版本
- ✅ 内置 APK 下载器
- ✅ 一键安装更新
- ✅ 支持差量更新

## 📥 下载

| 版本 | 发布日期 | 文件 | 下载 |
|------|---------|------|------|
| **1.0.8** | 2026-10-08 | Lin-Shizuku_1.0.8-lin-Release.apk | [下载](https://github.com/Linwan-yn/Lin-Shizuku/releases/download/v1.0.8/Lin-Shizuku_1.0.8-lin-Release.apk) |
| 1.0.7 | 2026-10-05 | Lin-Shizuku_1.0.7-lin-Release.apk | [下载](https://github.com/Linwan-yn/Lin-Shizuku/releases/download/v1.0.7/Lin-Shizuku_1.0.7-lin-Release.apk) |
| 1.0.6 | 2026-10-03 | Lin-Shizuku_1.0.6-lin-Release.apk | [下载](https://github.com/Linwan-yn/Lin-Shizuku/releases/download/v1.0.6/Lin-Shizuku_1.0.6-lin-Release.apk) |

👉 **[查看全部版本 →](https://github.com/Linwan-yn/Lin-Shizuku/releases)**

## 🔄 版本历史

### 最近更新

**v1.0.8** (2026-10-08)
- 服务端深度优化：权限检查与查询全面无锁化
- 客户端状态并发安全加固
- 版本信息加载提速，服务响应更快
- 服务 API 版本升级至 1.0.2
- 开机启动改进，重启后服务恢复更可靠
- 新增若干实验性功能
- 无线调试配对启动优化，连接与自动启动更稳定

**v1.0.7** (2026-10-05)
- 修复用户反馈的终端页遮挡问题
- 优化滚动体验，最后一行停到胶囊上方
- 完整显示内容，可完整滑完

更多版本信息请查看 [完整更新日志 →](CHANGELOG.md)

## 🛠️ 构建说明

如需本地构建项目，请参考 [BUILD.md](BUILD.md)

**简要说明：**
```bash
# 克隆仓库
git clone https://github.com/Linwan-yn/Lin-Shizuku.git
cd Lin-Shizuku

# 使用 Gradle 构建
./gradlew assembleRelease

# 生成的 APK 位置
# app/build/outputs/apk/release/Lin-Shizuku-release.apk
```

## 🤔 常见问题

### Q: 为什么无法启动服务？
**A:** 
- 确认已安装 Shizuku 或 Sui
- 确认已通过无线调试、ADB 或 Root 之一启动了 Shizuku 服务
- 检查是否给予了应用必要权限
- 尝试重启手机

### Q: 支持哪些 Android 版本？
**A:** 支持 Android 10 及以上。部分功能在特定版本可能有差异。

### Q: 项目是否开源？
**A:** 
- 当前 APK 版本已发布，源代码根据许可协议管理
- 项目遵循 MIT 许可证
- 欢迎提 Issue 和贡献建议

### Q: 如何报告 bug？
**A:** 
- 请在 [Issues](https://github.com/Linwan-yn/Lin-Shizuku/issues) 页面创建 Issue
- 描述复现步骤、手机型号、系统版本
- 附加相关日志信息

### Q: 支持自定义功能吗？
**A:** 
- 当前不支持自定义模块或插件
- 欢迎在 Issues 中提出功能建议
- 所有建议都会被认真考虑并可能在未来版本中实现

### Q: 项目更新频率？
**A:** 
- 当前处于活跃开发阶段
- 通常每周发布 1-2 个版本
- 所有更新会自动推送给应用内用户

## 📋 系统要求

| 项目 | 要求 |
|------|------|
| 最低 Android 版本 | 10 (API 29) |
| 目标 Android 版本 | 14 (API 34) |
| 最小内存 | 2GB RAM |
| 推荐内存 | 4GB+ RAM |
| 存储空间 | 50MB 可用空间 |

## 📝 许可证

本项目采用 **MIT 许可证**。详见 [LICENSE](LICENSE) 文件。

简体概括：
- ✅ 你可以自由使用、复制、修改和分发本软件
- ✅ 商业和私有用途都可以
- ⚠️ 需要在副本或衍生品中包含许可证和版权声明
- ⚠️ 软件按"现状"提供，无任何担保

## 📞 联系方式

- **GitHub Issues**：[Report Issues](https://github.com/Linwan-yn/Lin-Shizuku/issues)
- **Discussions**：[讨论区](#) （如果启用）

## 🙏 致谢

本项目基于以下开源项目：
- [Shizuku](https://github.com/RikkaApps/Shizuku) - 权限管理框架
- [Sui](https://github.com/RikkaApps/Sui) - 权限提升库

感谢所有贡献者和用户的支持与建议！

## 📊 项目状态

```
开发状态：🟢 活跃开发中
最后更新：2026-10-08
当前版本：v1.0.8
许可证：MIT
```

---

## English Version (Coming Soon)

Coming soon. If you'd like to help translate this README to English, please feel free to open a Pull Request!

---

**Made with ❤️ by @Linwan-yn**
