<div align="center">

<img src="app/src/main/res/drawable-nodpi/ic_rrbox_launcher.webp" width="128" alt="RRBOX" />

# RR-BOX / RRBOX

**Android 原生 VPN / 代理客户端 · 双 TUN 数据面研究 · 移动网络稳定性 · 可复现构建**

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-00E5FF)](#)
[![Release](https://img.shields.io/github/v/release/Xiaowu7z/RR-BOX?label=Stable)](https://github.com/Xiaowu7z/RR-BOX/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/Xiaowu7z/RR-BOX/build-v2.yml?branch=main&label=Build)](https://github.com/Xiaowu7z/RR-BOX/actions/workflows/build-v2.yml)
[![Core](https://img.shields.io/badge/Core-sing--box%20libbox-161B22)](#)

[最新正式版](https://github.com/Xiaowu7z/RR-BOX/releases/latest) · [技术验证](docs/TECHNICAL-VALIDATION.md) · [第三方许可](THIRD_PARTY_NOTICES.md)

</div>

---

## RRBOX 是什么

RRBOX 是一个面向 Android 的原生 VPN / 代理客户端。项目重点不是简单堆叠协议，而是研究和验证 **Android VpnService 上的数据面、移动网络稳定性、生命周期控制、分应用路由与 native 转发路径**。

当前稳定版：**0.9.7 / arm64-v8a / Android 8.0+**。

核心设计目标：

- **数据面正确性优先**：稳定 System TUN 作为默认基线。
- **双数据面可切换**：System TUN 与 HEV Native / lwIP 共用节点与路由配置。
- **移动端稳定性**：针对 Android VPN 生命周期、重复启动、系统恢复和 OEM SystemUI 行为持续回归。
- **分应用路由**：支持全部代理、仅选中应用进入 VPN、选中应用绕过 VPN。
- **可复现 Release**：固定核心依赖、NDK、Go、Gradle、ABI，并在 CI 中验证签名、native libs、规则资产和更新通道。
- **不把推测写成跑分**：没有严格同机 A/B 数据的性能结论不会包装成“必然更快”。

---

## 核心技术：双 TUN 数据面

RRBOX 保留两套独立的数据转发路径，可以切换，但共享同一套节点、订阅和路由配置。

### Stable System TUN

```text
Android VpnService TUN
        ↓
sing-box libbox / system stack
        ↓
routing / DNS / outbound
        ↓
Internet
```

这是当前正式版的稳定基线。Kotlin / Compose 层负责配置、UI 与生命周期，不参与逐包转发。

### HEV Native / lwIP

```text
Android VpnService TUN
        ↓
HEV native (JNI / arm64)
        ↓
lwIP
        ↓
Loopback SOCKS5
        ↓
sing-box outbound
        ↓
Internet
```

HEV 路径用于研究 **Android TUN → native userspace TCP/IP → SOCKS5 → sing-box outbound** 的可行性、稳定性和性能上限。正式版仍保留 System TUN 作为稳定回退路径。

---

## 已完成的实机与构建验证

下面只列可以由源码、CI 或真实设备日志支撑的事实。

| 项目 | System TUN | HEV Native |
|---|---|---|
| Android VPN interface | `tun0` | `tun0` |
| TUN stack | sing-box `system` | HEV + lwIP |
| 数据路径 | TUN → libbox | TUN → HEV/lwIP → SOCKS5 → libbox |
| TUN MTU | **1500** | **8500** |
| native runtime | `libbox.so` | `libbox.so` + `libhev-socks5-tunnel.so` |
| VPN 生命周期 | `START_NOT_STICKY` | `START_NOT_STICKY` |
| 重复启动保护 | 有 | 有 |
| 实机路径 | 稳定基线 | 已确认 native HEV session 启动并进入 sing-box outbound |

### Native 数据面体积成本

已记录构建：

- System 基线 APK：**39,288,731 bytes**
- 加入 HEV 数据面后的实机构建：**39,873,533 bytes**
- 增量：**584,802 bytes ≈ 0.56 MiB ≈ 1.49%**
- HEV arm64 native library：**342,296 bytes**（APK 内未压缩条目尺寸）

> 当前还没有完成固定手机、固定网络、固定节点、固定协议和固定测试时段下的 sustained TCP / UDP throughput、RTT / jitter、CPU time、battery / energy per GB 严格 A/B。因此 RRBOX 不声明 HEV 必然快于 System TUN。

---

## Android 稳定性设计

- `START_NOT_STICKY`：避免系统用陈旧 Intent 静默恢复旧 VPN runtime。
- **重复 START 去重**：相同配置重复启动不会拆掉健康数据面。
- **VPN UID / bridge 保护**：避免本地 SOCKS 和 VPN 形成自环。
- **分应用策略**：通过 VpnService include / exclude package 约束数据入口。
- **中国大陆智能分流**：使用 sing-box 二进制 SRS 规则，本地加载。
- **Quick Settings Tile**：Android 控制中心直接连接 / 断开 RRBOX。
- **OEM 图标适配**：Adaptive Icon + Android 13+ monochrome layer，并处理 OnePlus / OxygenOS 类 SystemUI 图标缓存与取图路径。

---

## 已验证能力

- Android 8.0+ / arm64-v8a
- VLESS Reality
- VMess WS
- Hysteria2
- TUIC
- 标准 sing-box outbound 参数透传
- 相机扫码导入节点
- 从图片文件读取二维码
- 剪贴板 / 文本 / 手动添加节点
- HTTP / HTTPS 订阅
- IPv4 / IPv6 订阅地址
- Base64 / URI / sing-box JSON
- Clash / Mihomo `proxies:` 转换
- 节点 Ping 与节点参数本地覆盖
- 中国大陆智能分流
- 分应用代理 / 绕过
- System TUN / HEV Native 双引擎
- 通知栏实时速率、连接时长、重启、断开
- Android 下拉快速开关
- App 内检查更新
- Obtainium 自动更新
- 可选 PIN 锁：PBKDF2-HMAC-SHA256 + 随机盐

AnyTLS、NaiveProxy 等路径仍按独立实机回归结果决定是否标记为“稳定验证”。

---

## 安装与更新

### GitHub Releases

下载最新正式版：

**[RRBOX Releases](https://github.com/Xiaowu7z/RR-BOX/releases/latest)**

当前只发布 **arm64-v8a** 正式 APK。

### Obtainium 一键添加

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.rr.client%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FXiaowu7z%2FRR-BOX%22%2C%22author%22%3A%22Xiaowu7z%22%2C%22name%22%3A%22RRBOX%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22RRBOX-%5B0-9%5D%2B%5C%5C%5C%5C.%5B0-9%5D%2B%5C%5C%5C%5C.%5B0-9%5D%2B-arm64-v8a%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%7D%22%2C%22overrideSource%22%3A%22GitHub%22%2C%22allowIdChange%22%3Afalse%7D"><img alt="Add to Obtainium" src="https://img.shields.io/badge/Add_to_Obtainium-6750A3?style=for-the-badge"></a>

App 内“检查更新”和 Obtainium 都跟踪 `Xiaowu7z/RR-BOX` 的最新正式 Release，并只接受 RRBOX arm64-v8a 正式 APK。

---

## 0.9.7

0.9.7 继续保持网络核心不变，主要完成 Android / OnePlus / OxygenOS 通知与桌面图标的标准化：

- Application、MainActivity、RRVpnService 使用标准 Android Adaptive Icon。
- Android 13+ 增加 RRBOX monochrome layer。
- `mipmap/ic_launcher` / `ic_launcher_round` 与正式 RRBOX 图标统一。
- 通知 Small Icon、Channel、Notification ID 刷新，右侧 Large Icon 保持为空。
- App 内更新与 Obtainium 更新链继续验证通过。

> 某些 OxygenOS / SystemUI 会缓存通知或 VPN 服务图标。升级涉及图标资源的版本后，如果通知仍显示旧图标，重启系统可让 SystemUI 重新建立图标缓存。

---

## 可复现构建链

当前正式 Release 固定：

- sing-box `v1.14.0` / `0b8995879f29a9b98ee027bc17b75e101445b238`
- HEV `64cc609f945253b0e9ebc56317d544268f3c68c1`
- Go `1.26.7`
- Android NDK `r28`
- Gradle `8.10.2`
- Android API 35 / Build Tools 35.0.0

Release CI 会重新构建 arm64 `libbox.aar` 与 HEV JNI，执行单元测试，生成签名 APK，并验证：

- package / version
- arm64 ABI
- `libbox.so`
- `libhev-socks5-tunnel.so`
- SRS 规则资产
- APK 签名
- Adaptive / monochrome / notification icon 资源
- App 内更新通道
- Obtainium 更新通道

---

## 项目原则

RRBOX 的优先级始终是：

**数据面正确性 > 移动网络稳定性 > 可测量性能 > 功能数量。**

这个仓库更关注可以被源码、日志、CI 和实机验证的工程事实，而不是未经验证的理论优势。
