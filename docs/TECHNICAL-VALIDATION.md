# RRBOX 1.0 技术验证记录

本文保留项目在 1.0 封板前登记的设备测量，并区分本次源码审查与历史实测。原始设备日志不随本仓库完整归档；本次没有重新开展实机 A/B。

## 双数据面

### System TUN
- Android VpnService TUN 直接交给 sing-box libbox。
- 默认稳定引擎，MTU 1500。
- `START_NOT_STICKY`，重复等价 START 不拆健康数据面。

### HEV Native
- Android VpnService 建立 TUN，HEV JNI 在 RRBOX 进程内启动 lwIP 数据面。
- TUN → HEV/lwIP → loopback SOCKS5 → sing-box outbound。
- MTU 8500，mapped DNS，SOCKS5 pipeline，best-effort client TCP Fast Open。
- sing-box 远端 socket 经 VpnService protect，避免 VPN 自环。

## A/B 实机结果

固定同一设备、网络、节点和 2 MiB HTTPS 负载。每个引擎每轮 3 次请求，并以 sing-box session traffic 交叉验证；HEV 额外验证 native RX。

三次有效运行的 run-level 中位数：

| 指标 | System | HEV |
|---|---:|---:|
| TLS | 591 ms | 610 ms |
| HTTPS TTFB | 859 ms | 1010 ms |
| 下载 | 1.57 MB/s | 1.56 MB/s |
| RRBOX CPU | 1081 ms | 790 ms |
| 服务内重建 | 121 ms | 222 ms |

结论：这组设备/公网条件下 HEV CPU 约低 27%，吞吐同档，TLS 接近，TTFB 约慢 18%。因此 System 保持默认，HEV 作为正式高性能可选引擎；不宣称 HEV 在所有网络中必然更快。

## 网络连续性

真实设备日志已经验证：蜂窝 `rmnet_data1` ↔ Wi-Fi `wlan0` 连续切换可实时识别；正常切换后 `dataPlaneHealthy=true`，不会误重启 VPN。恢复演练可主动暂停本地数据面，再通过最近一次已验证 Runtime Cache 恢复；连续两次演练均恢复成功，`recoveryCount` 正常递增。用户主动断开后切换网络，`desiredRunning=false`，自动恢复明确跳过。

网络守护是 Android `NetworkCallback` 驱动，不通过高频网络心跳轮询。

## Quick Settings

历史快捷缓存实现记录过 4–21 ms 的配置准备时间，不代表完整 VPN 启动，也不代表本次修复构建的性能。本次优先修正旧配置复用：快捷开关读取当前节点 / 设置并重建待比较配置，只有与已验证缓存一致才复用；不再枚举应用列表。该检查路径尚未重新测量准备耗时。

## 协议 / Raw

协议能力由 `ProtocolCapabilityRegistry` 声明；AnyTLS、Naive H2/H3 等已有解析路径不重复实现。Raw 模式支持单 outbound、`outbounds[]` 与完整 sing-box config 解析，并在写入本地节点前执行 libbox 配置校验。

## Release 验证

正式 CI 固定核心 commit、Go、NDK、Gradle 与 Android API，重新构建 arm64 `libbox.so` 和 `libhev-socks5-tunnel.so`，执行全部 Release 单元测试与 Android Lint，并验证 package/version/ABI、APK v2 签名、native libs、中国 SRS 规则资产、Adaptive/monochrome/notification icon 资源、App 内更新与 Obtainium 正式通道。

## 本次源码检查

范围、修复与未覆盖边界见 [CODE-AUDIT.md](CODE-AUDIT.md)。CI 测试成功不代表所有设备上的网络连续性已验证。
