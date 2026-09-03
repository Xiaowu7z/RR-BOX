# RRBOX (RR-Android)

RRBOX 是一个基于 Android `VpnService` 与 sing-box libbox 的原生代理客户端，当前重点适配 RRVPS 订阅格式。

## 当前能力
- **原生 Android 客户端**：Jetpack Compose + Material 3
- **sing-box 内核**：固定构建 `v1.14.0`，当前 CI 仅输出 `arm64-v8a`
- **节点协议**：已实机跑通 VMess WS、VLESS、Hysteria2、TUIC；订阅解析同时保留 AnyTLS、NaiveProxy 等 RRVPS sing-box outbound 原始参数
- **VPN 前台服务**：Android 标准 `VpnService`，无需 Root
- **通知栏状态**：连接节点、实时上/下行速率、连接时长与断开操作
- **节点工具**：节点 Ping、节点参数本地覆盖编辑
- **分应用 VPN**：全部代理、仅选中应用进入 VPN、选中应用绕过 VPN
- **基础智能分流**：局域网私有地址与 `.cn` 域名直连；当前版本尚未内置完整大陆 IP/域名 `.srs` 规则集
- **后台运行保护**：可引导用户申请忽略 Android 电池优化，降低息屏或后台时 VPN 被系统停止的概率

## 构建

仓库的 GitHub Actions 会构建固定版本的 sing-box libbox，然后执行单元测试、Release APK 构建、签名校验、包名/版本/ABI 检查并上传 Artifact。

正式 Release 构建需要在 Repository Secrets 中配置：

```text
RR_KEYSTORE_BASE64
RR_KEYSTORE_PASSWORD
RR_KEY_ALIAS
RR_KEY_PASSWORD
```

签名文件和密码不要提交到仓库。

## 当前平台范围

- 最低 Android：8.0 / API 26
- 当前 CI 架构：arm64-v8a
- 不依赖 Xposed / LSPosed / SystemUI Hook
- 不要求 Root

## 项目状态

项目仍处于早期开发阶段。连接核心已经进入实机验证阶段，但分流、规则集、协议边界参数、流量统计口径和更多设备兼容性仍会继续完善。
