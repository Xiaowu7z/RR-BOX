# RRBOX

RRBOX 是一个基于 Android `VpnService` 与 sing-box libbox 的原生代理客户端，当前重点兼容 RRVPS 订阅，同时保持标准 sing-box JSON 节点参数的可移植性。

## 当前能力

- **Android 原生客户端**：Jetpack Compose + Material 3，标准 `VpnService`，无需 Root。
- **sing-box 内核**：CI 固定构建 `v1.14.0`；当前发布架构为 `arm64-v8a`。
- **已实机连通**：VMess WS/Argo、VLESS Reality、Hysteria2、TUIC。
- **节点工具**：节点 Ping、节点参数本地覆盖编辑；订阅刷新不会直接覆盖用户的本地编辑。
- **分应用 VPN**：全部代理、仅选中应用进入 VPN、选中应用绕过 VPN。最终应用过滤直接由 Android `VpnService.Builder` 执行。
- **中国大陆智能分流**：使用 SagerNet 维护的 `geosite-geolocation-cn.srs`、`geosite-geolocation-!cn.srs` 与 `geoip-cn.srs`。APK 自带构建时快照，应用内支持原子更新。
- **通知栏**：当前节点、实时上/下行速率、连接时长、重启连接、断开连接。
- **后台运行保护**：可由用户选择申请忽略 Android 电池优化；不依赖 Root 或厂商私有 API。
- **可选 PIN 锁**：4–8 位数字 PIN，仅锁定 RRBOX 界面，不中断已经运行的 VPN；PIN 使用 PBKDF2-HMAC-SHA256 + 随机盐保存校验值，不保存明文。
- **软件更新检查**：仓库公开并使用 GitHub Releases 发布后，可在设置中检查最新版并跳转到 APK/Release 下载地址。

## 数据面与性能方向

RRBOX 的 VPN 数据面由 sing-box libbox 直接处理，TUN 当前使用 `system` stack。Java/Kotlin 层不参与逐包转发，只处理配置、Android VPN 生命周期、通知和状态展示。

为了保证移动网络兼容性，当前默认 MTU 为 1500。更大的 MTU、其它 TUN stack 或 Android `auto_redirect` 等性能选项不会在缺少同设备 A/B 数据时默认启用；后续会基于同节点、同协议、同网络条件做吞吐/CPU/延迟基准再决定是否开放性能档位。

## 中国规则来源与更新

构建时从 SagerNet 的规则仓库获取并校验二进制 `.srs`：

- `SagerNet/sing-geosite`：`geosite-geolocation-cn.srs`
- `SagerNet/sing-geosite`：`geosite-geolocation-!cn.srs`
- `SagerNet/sing-geoip`：`geoip-cn.srs`

RRBOX 只在三份新规则全部下载并通过 `SRS` 文件头校验后才原子替换旧规则；更新失败会继续保留上一份可用规则。

## 构建

GitHub Actions 会：

1. 下载并校验中国 `.srs` 规则快照；
2. 按固定 tag/commit 构建 sing-box libbox；
3. 执行单元测试；
4. 构建并签名 Release APK；
5. 校验包名、版本、ABI、签名和内置规则资产；
6. 上传 APK 与构建报告。

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

RRBOX 仍处于早期开发阶段。协议连接核心已经进入实机验证阶段；分流、规则更新、设备兼容性和性能基准仍会持续完善。AnyTLS、NaiveProxy 等协议虽然会尽量保留订阅原始 outbound 参数，但在正式标注为“已验证”之前仍需要独立实机回归。
