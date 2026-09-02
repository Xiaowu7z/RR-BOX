# RR Client (RR-Android)

专为 **RRVPS** 定制开发的 Android 代理客户端。

## 核心特性
- **UI 框架**: Jetpack Compose + Material 3 暗黑科技感原生中文 UI
- **内核核心**: Sing-box `v1.14.0` (arm64-v8a 原生编译)
- **协议支持**: VLESS Reality Vision, Hysteria2 (含端口跳跃), TUIC v5, VMess WS+Argo, AnyTLS, NaiveProxy (H2/H3)
- **真实流量统计**: 基于单调时钟 `SystemClock.elapsedRealtime()`，直接统计 Outbound Proxy 流量，严格剔除 Direct 直连与 VPN 外部 Bypass 流量
- **常驻通知栏**: 原生 Android VpnService 前台常驻通知，实时刷新上/下行速率与连接时长
- **分应用分流**: 支持系统应用与第三方应用按包名直连、指定节点或全局绕过
- **智能分流**: 内置 `.srs` 规则集，大陆域名/IP与局域网自动直连

## GitHub Actions 一键出包指南 (方案 A)

1. 将当前 `RR-Android` 目录上传至你的 GitHub 私有仓库：
   ```bash
   git init
   git add .
   git commit -m "feat: initial commit for RR Client 0.1.0-alpha"
   git remote add origin https://github.com/<你的用户名>/RR-Android.git
   git branch -M main
   git push -u origin main
   ```

2. 触发构建：
   - 每次 `git push` 到 `main` 分支会自动触发 GitHub Actions 构建。
   - 或者在 GitHub 仓库页面点击 **Actions** -> **RR Client Automated Build** -> **Run workflow**。

3. 下载 APK：
   - 构建完成后（约 2-3 分钟），在 Actions 运行记录下方的 **Artifacts** 中直接下载 `RR-Client-0.1.0-alpha-arm64-v8a.zip`。
   - 解压后即可获取签名完毕的 `RR-Client-0.1.0-alpha-arm64-v8a.apk`，通过 ADB 安装至 OnePlus 手机即可直接运行。

## 安全规范
- 任何 `.jks` / `.keystore` / `local-test-config` 均已被 `.gitignore` 隔离。
- 签名仅通过 GitHub Secrets 动态注入，绝不泄露到代码库中。
