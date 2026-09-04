<div align="center">

<img src="app/src/main/res/drawable-nodpi/ic_rrbox_launcher.webp" width="128" alt="RRBOX" />

# RRBOX 1.0

**Android 原生代理客户端 · System / HEV 双数据面 · 移动网络连续性守护 · 可复现构建**

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-00E5FF)](#)
[![Release](https://img.shields.io/github/v/release/Xiaowu7z/RR-BOX?label=Stable)](https://github.com/Xiaowu7z/RR-BOX/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/Xiaowu7z/RR-BOX/build-v2.yml?branch=main&label=Build)](https://github.com/Xiaowu7z/RR-BOX/actions/workflows/build-v2.yml)
[![Core](https://img.shields.io/badge/Core-sing--box%20libbox-161B22)](#)

[下载正式版](https://github.com/Xiaowu7z/RR-BOX/releases/latest) · [技术验证](docs/TECHNICAL-VALIDATION.md) · [第三方许可](THIRD_PARTY_NOTICES.md)

</div>

---

## 项目定位

RRBOX 是一个面向 Android 的原生 VPN / 代理客户端。1.0 的重点不是追求“协议数量最多”，而是把 **Android VpnService 数据面、移动网络切换、异常恢复、分应用路由、可观测性与可复现 Release** 做成一套稳定工程。

**当前正式版：1.0.0 · arm64-v8a · Android 8.0+。**

项目原则：**数据面正确性 > 移动网络稳定性 > 可测量性能 > 功能数量。**

---

## 双数据面

### System · 默认稳定引擎

```text
Android VpnService TUN
        ↓
sing-box libbox / system stack
        ↓
routing / DNS / outbound
        ↓
Internet
```

System 是 RRBOX 的默认稳定路径，启动快、兼容性高，并始终作为回退基线。

### HEV · 高性能可选引擎

```text
Android VpnService TUN
        ↓
HEV native / lwIP
        ↓
Loopback SOCKS5 (pipeline)
        ↓
sing-box outbound
        ↓
Internet
```

HEV 使用 8500 MTU、mapped DNS、SOCKS5 pipeline 与 best-effort client TCP Fast Open。它不是“所有场景必然更快”的替代品，而是正式保留的低 CPU / native 数据面选项。

---

## 实机 A/B 结果

同一台 Android 设备、同一节点、每个引擎 3 轮固定 2 MiB HTTPS，3 次有效 A/B 运行的 run-level 中位数汇总：

| 指标 | System | HEV |
|---|---:|---:|
| TLS 中位 | 591 ms | 610 ms |
| HTTPS 首字节 | 859 ms | 1010 ms |
| 2 MiB 下载 | 1.57 MB/s | 1.56 MB/s |
| RRBOX 进程 CPU | 1081 ms | 790 ms |
| 服务内重建 | 121 ms | 222 ms |

- 两条路径均完成代理路径计数验证；HEV 额外完成 native RX 验证。
- HEV 在这组实测中 CPU 约低 **27%**，吞吐基本同档；TTFB 约慢 **18%**。
- 这些数据用于解释产品取舍，不代表不同运营商、VPS、协议或设备上的普遍结论。

因此 1.0 保持：**System 默认，HEV 可选。**

---

## 移动网络稳定性

RRBOX 1.0 内置事件驱动的网络连续性守护，不靠高频心跳发包：

- 实时识别 Wi-Fi / 蜂窝 / 以太网与接口变化。
- Wi-Fi 与蜂窝同时有效时按健康度和物理网络优先级选择真实出口。
- Wi-Fi ↔ 蜂窝正常切换只观察，不重启健康 VPN。
- 切网后本地数据面确实停止时，才使用最近一次已验证 Runtime Cache 自动恢复。
- 用户手动断开后 `desiredRunning=false`，守护不会擅自重新连接。
- Network Lab 提供恢复演练，可控地暂停数据面并验证恢复链。

---

## 快捷控制

Android Quick Settings 的 RRBOX 快捷按钮使用已验证 Runtime Cache：

- 点击立即更新 Tile 状态，实际启动/停止在后台完成。
- 已缓存运行配置时跳过数据库扫描、App 枚举、规则重建和重复 ConfigBuilder。
- 实机记录的缓存准备时间约 **4–21 ms**；这只是配置准备时间，不等同于完整 VPN 数据面启动时间。
- 失败会回滚 Tile 状态，不用“假连接”掩盖启动错误。

---

## 协议与导入

当前覆盖 VLESS Reality/TLS、VMess WS/gRPC/TLS、Hysteria1/2、TUIC v5、AnyTLS、Naive H2/H3、Trojan、Shadowsocks、SOCKS、HTTP(S)、SSH、ShadowTLS、Snell；WireGuard/Tor 推荐原生 sing-box JSON，自定义或新字段可走 Raw outbound。

导入入口包括扫码、二维码图片、剪贴板、文本、订阅、Clash/Mihomo `proxies:`、sing-box 单 outbound / `outbounds[]` / 完整 config。Raw 高级模式会保留 UI 尚未建模的 outbound 字段，并在写入本地节点前执行 sing-box 1.14 配置校验。

---

## 主要能力

- Android 8.0+ / arm64-v8a
- System / HEV 双转发引擎
- 中国大陆域名 / IP SRS 智能分流
- 全部代理 / 仅选中应用代理 / 选中应用绕过
- HTTP / HTTPS 订阅、IPv4 / IPv6 订阅地址
- 节点编辑、Ping、本地覆盖与本地节点管理
- VPN 连接时仅保护实际运行节点，其他本地节点仍可删除
- Network Lab：物理网络、MTU、DNS、IPv4/IPv6、TUN、自检、日志、A/B、恢复演练
- 自动脱敏日志
- 通知栏实时速率与连接时长
- Android Quick Settings 快捷开关
- App 内检查更新 / Obtainium
- 可选 PIN 锁

---

## 安装与更新

**[下载 RRBOX 最新正式版](https://github.com/Xiaowu7z/RR-BOX/releases/latest)**

正式 Release 当前只提供 `arm64-v8a` APK。App 内更新和 Obtainium 都只跟踪 GitHub 的正式 Release APK。

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.rr.client%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FXiaowu7z%2FRR-BOX%22%2C%22author%22%3A%22Xiaowu7z%22%2C%22name%22%3A%22RRBOX%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22RRBOX-%5B0-9%5D%2B%5C%5C%5C%5C.%5B0-9%5D%2B%5C%5C%5C%5C.%5B0-9%5D%2B-arm64-v8a%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%7D%22%2C%22overrideSource%22%3A%22GitHub%22%2C%22allowIdChange%22%3Afalse%7D"><img alt="Add to Obtainium" src="https://img.shields.io/badge/Add_to_Obtainium-6750A3?style=for-the-badge"></a>

---

## 可复现构建

1.0.0 Release 固定 sing-box v1.14.0、HEV 固定 commit、Go 1.26.7、Android NDK r28、Gradle 8.10.2、Android API 35 / Build Tools 35.0.0。CI 从固定源码重新构建 `libbox.aar` 和 HEV JNI，运行单元测试，生成签名 Release APK，并验证版本、ABI、native libs、SRS 规则、签名、图标资源与更新通道。

---

## 1.0 之后

1.0 开始冻结核心数据面。后续优先处理真实设备 / 真实网络暴露的问题，不为了版本号继续堆叠自动选节点或未经验证的“智能”功能。

RRBOX 不替用户猜测哪台 VPS 最适合某个业务；线路、IP 地区、目标服务和运营商差异应由用户掌握，客户端负责把选择稳定地执行。
