# RR-BOX / RRBOX

> **RRBOX** 是面向 Android 的原生 VPN / 代理客户端，核心关注 **Android TUN 数据面、移动网络稳定性、分应用路由、可切换 native 转发引擎与可复现构建**。

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-00E5FF)
![Release](https://img.shields.io/badge/Stable-0.9.3-2EA44F)
![Core](https://img.shields.io/badge/Core-sing--box%20libbox-161B22)
![Build](https://img.shields.io/badge/Build-Reproducible-6f42c1)

## RRBOX 0.9.3 正式版

0.9.3 是 RRBOX 的首个正式稳定发布线版本。它保留已经验证的 System TUN 稳定数据面，同时继续提供 HEV native/lwIP 实验数据面，并把日常使用链路补齐。

### 0.9.3 新增 / 完成

- **Android 快速设置磁贴**：下拉控制中心可直接连接 / 断开 RRBOX。
- 快速磁贴直接读取当前选中节点、智能分流、分应用模式和轻量模式，不需要先打开主界面。
- 首次尚未授权 VPN 时，从磁贴进入系统授权流程，授权后自动连接。
- **通知栏状态图标更新为 RRBOX 专用单色 R 标识**，与快速设置磁贴统一。
- 桌面启动图标保持 RRBOX 最终版。
- App 内更新检查正式切换到 `Xiaowu7z/RR-BOX`。
- Obtainium 默认跟踪 **正式版本**，不再把公测 / prerelease 当成稳定更新。

## 安装

- **GitHub Releases**：下载 `RRBOX-0.9.3-arm64-v8a.apk`
- **Obtainium 一键添加**：

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.rr.client%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FXiaowu7z%2FRR-BOX%22%2C%22author%22%3A%22Xiaowu7z%22%2C%22name%22%3A%22RRBOX%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22RRBOX-%5B0-9%5D%2B%5C%5C%5C%5C.%5B0-9%5D%2B%5C%5C%5C%5C.%5B0-9%5D%2B-arm64-v8a%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%7D%22%2C%22overrideSource%22%3A%22GitHub%22%2C%22allowIdChange%22%3Afalse%7D"><img alt="Add to Obtainium" src="https://img.shields.io/badge/Add_to_Obtainium-6750A3?style=for-the-badge"></a>

当前正式发布架构为 **arm64-v8a**，最低 Android 8.0（API 26）。

### 添加状态栏快速开关

安装并至少打开 RRBOX 一次后：

1. 下拉 Android 控制中心。
2. 进入“编辑 / 添加快捷开关”。
3. 找到 **RRBOX**，拖入常用区域。
4. 之后直接点击磁贴即可连接 / 断开。

---

## 核心架构

RRBOX 保留两套可以切换、但共享同一套节点与路由配置的 Android VPN 数据面。

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

这是默认稳定基线。Kotlin / Compose 层负责配置、生命周期和 UI，不参与逐包转发。

### HEV Native / lwIP

```text
Android VpnService TUN
        ↓
HEV native (JNI, arm64, -O3)
        ↓
lwIP
        ↓
Loopback SOCKS5
        ↓
sing-box outbound
        ↓
Internet
```

HEV 数据面用于研究 Android 上 **native TUN → userspace TCP/IP → sing-box outbound** 的可行性、稳定性和性能上限。正式版仍保留 System TUN 作为稳定回退路径。

---

## 工程约束

- `START_NOT_STICKY`：避免系统用过期 Intent 静默恢复旧 VPN。
- **重复 START 去重**：相同配置重复启动不会拆掉健康数据面。
- **分应用路由**：全部代理、仅选中应用进入 VPN、选中应用绕过 VPN。
- **智能中国大陆分流**：SagerNet 二进制 SRS 规则，本地原子更新。
- **System / HEV 双引擎**：共享节点、订阅和路由配置。
- **Quick Settings Tile**：系统级快速开关复用当前用户配置。
- **可复现 Release 构建**：固定 sing-box、HEV、Go、NDK 与 Gradle 版本，并校验 ABI、签名、包名和规则资产。
- **无 Root 依赖**：使用 Android 标准 VpnService。

---

## 已验证能力

- Android 8.0+ / arm64-v8a
- VLESS Reality
- VMess WS
- Hysteria2
- TUIC
- 标准 sing-box outbound 参数透传
- 本地节点：相机扫码、从图片读取二维码、剪贴板、文本、手动添加
- 订阅：HTTP/HTTPS、IPv4/IPv6、Base64/URI、sing-box JSON、Clash/Mihomo `proxies:` 转换
- 节点 Ping、节点参数本地覆盖
- 中国大陆智能分流 + SRS 规则原子更新
- 分应用代理 / 绕过
- System TUN / HEV native 两套转发引擎
- 通知栏实时速率、连接时长、重启、断开
- **Android 下拉快速开关**
- 可选 PIN 锁：PBKDF2-HMAC-SHA256 + 随机盐

AnyTLS、NaiveProxy 等路径仍按独立实机回归结果决定是否标记为“稳定验证”。

---

## 已记录的数据面差异

| 项目 | System TUN 稳定引擎 | HEV Native 引擎 |
|---|---|---|
| TUN stack | sing-box `system` | HEV + lwIP |
| 数据路径 | TUN → libbox | TUN → HEV/lwIP → SOCKS5 → libbox |
| TUN MTU | **1500** | **8500** |
| native runtime | `libbox.so` | `libbox.so` + `libhev-socks5-tunnel.so` |
| HEV native `.so` | — | **342,296 bytes** |
| 实机数据路径 | 稳定基线 | TUN → lwIP → SOCKS5 → sing-box 已跑通 |
| VPN 生命周期 | START_NOT_STICKY | START_NOT_STICKY |
| 重复启动保护 | 有 | 有 |

历史稳定构建 APK：**39,288,731 bytes**  
加入 HEV 数据面后的实机构建 APK：**39,873,533 bytes**

增加约 **584,802 bytes（约 0.56 MiB / 1.49%）**。

> RRBOX 不把理论优势写成跑分。HEV 是否在具体手机、网络、协议上更快，需要同设备、同节点、同协议的吞吐 / CPU / RTT / 电量 A/B 数据支持。

---

## 0.9.3 构建链

Release CI 固定：

- sing-box `v1.14.0` / `0b8995879f29a9b98ee027bc17b75e101445b238`
- HEV `64cc609f945253b0e9ebc56317d544268f3c68c1`
- Go `1.26.7`
- Android NDK `r28`
- Gradle `8.10.2`
- Android API 35 / Build Tools 35.0.0

CI 会编译 arm64 `libbox.aar` 与 HEV JNI，执行 Release 单元测试，构建签名 APK，并验证 package、version、ABI、native libs、SRS assets 和签名。

## 项目定位

RRBOX 的优先级始终是：

**数据面正确性 > 移动网络稳定性 > 可测量性能 > 功能数量。**

0.9.3 从公测线进入正式发布线，但实验性的 HEV 数据面仍会明确标注，不会把未经同机 A/B 验证的性能推断当作结论。
