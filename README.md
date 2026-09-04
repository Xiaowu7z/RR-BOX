# RR-BOX / RRBOX

> **RRBOX** 是面向 Android 的原生 VPN / 代理客户端，核心关注 **Android TUN 数据面、移动网络稳定性、分应用路由、可切换 native 转发引擎与可复现构建**。

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-00E5FF)
![Release](https://img.shields.io/badge/Stable-0.9.5-2EA44F)
![Core](https://img.shields.io/badge/Core-sing--box%20libbox-161B22)
![Build](https://img.shields.io/badge/Build-Reproducible-6f42c1)

## RRBOX 0.9.5 正式版

0.9.5 是通知图标修正版本，重点把通知卡片恢复为 **只使用左侧 Small Icon** 的标准布局。

### 0.9.5 修复

- **移除通知卡片右侧 Large Icon**：不再在通知卡片右侧显示任何图片。
- **替换左侧 Small Icon**：使用全新的 `ic_rrbox_status` 资源，按 RRBOX 当前正式 App 图标的圆环 + R + 人像轮廓制作单色版本。
- 使用新的资源名，避免部分 Android / OxygenOS 对旧通知图标资源的缓存干扰。
- 快速设置磁贴同步使用新的 RRBOX 单色状态图标。
- App 内“检查更新”继续读取 `Xiaowu7z/RR-BOX` 最新正式 Release。
- Obtainium 继续跟踪正式 Release，0.9.4 用户可正常识别并升级到 0.9.5。

> Android 的通知 Small Icon 由系统强制按单色蒙版渲染，因此它不能显示桌面图标的原始彩色效果；0.9.5 使用与桌面图标同一视觉结构的单色轮廓来保持识别一致性。

## 安装

- **GitHub Releases**：下载 `RRBOX-0.9.5-arm64-v8a.apk`
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
- **App / Obtainium 双更新通道**：共同跟踪公开正式 Release。
- **可复现 Release 构建**：固定 sing-box、HEV、Go、NDK 与 Gradle 版本，并校验 ABI、签名、包名、规则资产和更新资产。
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
- Android 下拉快速开关
- App 内检查更新 + Obtainium 更新
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

## 0.9.5 构建链

Release CI 固定：

- sing-box `v1.14.0` / `0b8995879f29a9b98ee027bc17b75e101445b238`
- HEV `64cc609f945253b0e9ebc56317d544268f3c68c1`
- Go `1.26.7`
- Android NDK `r28`
- Gradle `8.10.2`
- Android API 35 / Build Tools 35.0.0

CI 会编译 arm64 `libbox.aar` 与 HEV JNI，执行 Release 单元测试，构建签名 APK，并验证 package、version、ABI、native libs、SRS assets、签名、通知图标布局与更新通道。

## 项目定位

RRBOX 的优先级始终是：

**数据面正确性 > 移动网络稳定性 > 可测量性能 > 功能数量。**

0.9.5 继续沿用正式稳定发布线；实验性的 HEV 数据面仍会明确标注，不会把未经同机 A/B 验证的性能推断当作结论。
