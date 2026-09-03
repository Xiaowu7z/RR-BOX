# RR-BOX / RRBOX

> Android 原生双数据面代理客户端。项目重点不是“再做一个壳”，而是持续研究 **Android VPN 数据面、原生 TUN 转发、分应用路由、移动网络稳定性与可复现构建**。

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-00E5FF)
![Release](https://img.shields.io/badge/Public%20Beta-0.9.1-8A2BE2)
![Core](https://img.shields.io/badge/Core-sing--box%20libbox-161B22)

## 安装

- **GitHub Release**：下载 `RRBOX-0.9.1-arm64-v8a.apk`
- **Obtainium 一键添加**：

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.rr.client%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FXiaowu7z%2FRR-Android%22%2C%22author%22%3A%22Xiaowu7z%22%2C%22name%22%3A%22RRBOX%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22RRBOX-0%5C%5C%5C%5C.9%5C%5C%5C%5C.1-arm64-v8a%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%7D%22%2C%22overrideSource%22%3A%22GitHub%22%2C%22allowIdChange%22%3Afalse%7D"><img alt="Add to Obtainium" src="https://img.shields.io/badge/Add_to_Obtainium-6750A3?style=for-the-badge"></a>

0.9.1 是公开测试版；Obtainium 配置已开启预发布跟踪，并只匹配 RRBOX 的 arm64-v8a APK。

---

## 我们在研究什么

RRBOX 的核心是 **两套可以切换、但共享同一套节点与路由配置的 Android VPN 数据面**。

### 1. Stable System TUN

```text
Android VpnService TUN
        ↓
sing-box libbox / system stack
        ↓
routing / DNS / outbound
        ↓
Internet
```

这是稳定基线。Android TUN 直接交给 libbox，Kotlin/Compose 层不参与逐包转发，只负责配置、生命周期、状态和 UI。

### 2. HEV Native / lwIP TUN

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

HEV 数据面在进程内运行，重点研究 Android 上 **native TUN → userspace TCP/IP → sing-box outbound** 的可行性、稳定性和性能上限。

### 3. 两套数据面共享的工程约束

- `START_NOT_STICKY`：禁止系统用过期 Intent 静默恢复旧 VPN。
- **重复 START 去重**：相同配置的重复启动不会拆掉已经健康运行的数据面。
- **环路规避**：System TUN 与 HEV 使用不同的 UID 策略；HEV 下 RRBOX/桥接核心保持在 VPN 外，避免本机 SOCKS 回环。
- **分应用路由**：全部代理、仅选中应用进入 VPN、选中应用绕过 VPN。
- **中国规则原子更新**：SagerNet `geosite-geolocation-cn.srs` + `geoip-cn.srs`，两份规则同时通过格式校验后才替换旧版本。
- **构建可复现**：sing-box、HEV、Go、NDK、Gradle 均固定版本/commit；Release CI 同时验证 ABI、包名、签名、规则资产与 native library。

---

## 0.9.1 发布前实机 / 构建对比数据

这里区分 **已经测到的数据** 和 **尚未完成的性能 A/B**。我们不把理论优势写成跑分。

| 项目 | System TUN 稳定引擎 | HEV Native 引擎 |
|---|---|---|
| Android TUN stack | sing-box `system` | HEV + lwIP |
| 数据路径 | TUN → libbox | TUN → HEV/lwIP → SOCKS5 → libbox |
| TUN MTU | **1500** | **8500** |
| native runtime | `libbox.so` | `libbox.so` + `libhev-socks5-tunnel.so` |
| HEV native `.so` 体积 | — | **342,296 bytes** |
| 实机 native library 映射 | `libbox.so` | 两个 `.so` 同时映射成功 |
| HEV 会话标识 | — | `RRBOX · HEV` |
| HEV DNS 映射 | — | `198.18.0.2` + `100.64.0.0/10` synthetic mapping |
| 分应用核心策略 | RRBOX 保持在稳定 allow-list 路径 | bridge/core UID 置于 VPN 外，规避本地 SOCKS loop |
| VPN 生命周期 | `START_NOT_STICKY` | `START_NOT_STICKY` |
| 重复启动保护 | 相同配置不重建 | 相同配置不重建 |
| 真实设备状态 | 已作为稳定基线验证 | `TUN → lwIP → SOCKS5 → sing-box` 已跑通 |
| CI | unit tests + signed Release + ABI/package/signature checks | 同一条 CI，同时验证 HEV native library |

### Native 引擎的体积成本

历史稳定构建 APK：**39,288,731 bytes**  
加入 HEV 数据面后的实机构建 APK：**39,873,533 bytes**

增加约 **584,802 bytes（约 0.56 MiB / 1.49%）**，其中 HEV arm64 native library 本体为 **342,296 bytes**。

> 目前尚未公开“HEV 一定比 System TUN 快 X%”之类结论。真正的吞吐 / CPU / RTT / 电量同机 A/B 会在固定手机、固定网络、固定节点、固定协议下继续补充。公开测试版的目标就是让数据说话。

---

## 已验证能力

- Android 8.0+，当前发布 `arm64-v8a`
- VLESS Reality
- VMess WS
- Hysteria2
- TUIC
- 标准 sing-box outbound 参数透传
- 本地节点：扫码、**从图片读取二维码**、剪贴板、文本、手动添加
- 订阅：HTTP/HTTPS、IPv4/IPv6、Base64/URI、sing-box JSON、Clash/Mihomo `proxies:` 节点转换
- 节点 Ping、节点参数本地覆盖
- 智能中国大陆分流 + 规则原子更新
- 通知栏实时速率、连接时长、重启、断开
- 可选 PIN 锁：PBKDF2-HMAC-SHA256 + 随机盐
- 无需 Root；不依赖 Xposed / LSPosed / SystemUI Hook

AnyTLS、NaiveProxy 等路径会继续做独立实机回归；在验证完成前不会把“能解析”写成“已稳定验证”。

---

## 构建链

0.9.1 CI 会：

1. 固定 sing-box `v1.14.0` / commit `0b8995879f29a9b98ee027bc17b75e101445b238`
2. 固定 HEV commit `64cc609f945253b0e9ebc56317d544268f3c68c1`
3. 使用 Go `1.26.7`、Android NDK `r28`、Gradle `8.10.2`
4. 编译 arm64 `libbox.aar`
5. 以 `-O3` 编译 HEV JNI native library
6. 执行 Release 单元测试与源码回归约束
7. 构建签名 APK
8. 验证 package / version / ABI / signature / native libs / SRS assets
9. 生成 SHA-256 与 Build Report
10. 发布 GitHub `v0.9.1` 公测 Release

## 项目定位

RRBOX 不是以功能数量为目标的“协议集合”。当前优先级是：

**数据面正确性 > 移动网络稳定性 > 可测量性能 > UI 功能数量。**

如果一个“性能优化”没有同机对比数据，默认不会写成性能结论。
