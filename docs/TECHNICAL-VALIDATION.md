# RRBOX 0.9.1 技术验证记录

本文只记录可以由源码、CI 或真实设备日志支撑的事实。

## 双数据面

### System TUN
- Android VpnService TUN 直接交给 sing-box libbox。
- `stack = system`。
- 稳定路径 MTU = 1500。
- `START_NOT_STICKY`，不恢复陈旧 runtime。
- 分应用策略由 TUN include/exclude package 约束。

### HEV Native
- Android VpnService 建立 TUN。
- HEV `libhev-socks5-tunnel.so` 通过 JNI 在 RRBOX 进程内启动。
- HEV 内部使用 lwIP，并把流量转到 loopback SOCKS5，再进入 sing-box outbound。
- HEV virtual TUN MTU = 8500。
- mapped DNS = 198.18.0.2，synthetic mapping range = 100.64.0.0/10。
- RRBOX/bridge core UID 保持在 Android VPN 外，避免 local SOCKS loop。

## 实机证据
发布前设备日志已经确认：
- `libbox.so` 已映射。
- `libhev-socks5-tunnel.so` 已映射。
- Android VPN interface = `tun0`。
- HEV session = `RRBOX · HEV`。
- 日志出现：`HEV native 极速数据面已启动：TUN → lwIP → SOCKS5 → sing-box`。
- 日志出现：`VPN tunnel started ... engine=HEV`。

## 体积对比
- 0.1.8-stability APK: 39,288,731 bytes。
- HEV 后实机构建 APK: 39,873,533 bytes。
- 增量: 584,802 bytes ≈ 0.56 MiB ≈ 1.49%。
- HEV arm64 native library: 342,296 bytes（APK 内未压缩条目尺寸）。

## 目前没有的数据
尚未完成严格同机 A/B 的：
- sustained TCP throughput
- UDP throughput
- RTT / jitter
- CPU time
- battery / energy per GB

因此 0.9.1 不声明 HEV 必然快于 System TUN。后续数据必须在固定手机、固定网络、固定节点、固定协议、固定测试时段下采集。
