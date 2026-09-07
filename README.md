<div align="center">

<img src="app/src/main/res/drawable-nodpi/ic_rrbox_launcher.webp" width="128" alt="RRBOX" />

# RRBOX

**Android 原生代理客户端 · System / HEV 双引擎 · 大陆分流 · 移动网络连续性**

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#安装)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-00E5FF)](#安装)
[![Stable](https://img.shields.io/github/v/release/Xiaowu7z/RR-BOX?label=Stable)](https://github.com/Xiaowu7z/RR-BOX/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/Xiaowu7z/RR-BOX/build-v2.yml?branch=main&label=Build)](https://github.com/Xiaowu7z/RR-BOX/actions/workflows/build-v2.yml)

[下载正式 APK](https://github.com/Xiaowu7z/RR-BOX/releases/latest) · [使用说明](docs/USER-GUIDE.md) · [本次检查](docs/CODE-AUDIT.md) · [技术验证](docs/TECHNICAL-VALIDATION.md) · [更新记录](CHANGELOG.md)

</div>

## 项目定位

RRBOX 为用户自己的节点和订阅提供 Android VPN / 代理连接，不提供线路、账户或服务器。以 **数据面正确性、移动网络稳定性、可测量性能** 为优先级，不替用户自动挑选地区或擅自切换业务节点。

当前正式版本 **1.0.0（100）**，仅发布 **arm64-v8a**，最低 Android 8.0。不需要 Root。默认使用 System TUN，HEV native 数据面为可选项；图标、分流拓扑和两个核心的固定源码版本保持不变。

## 核心能力

| 能力 | 实现与边界 |
|---|---|
| System / HEV 双引擎 | 默认 System TUN；HEV 通过 native/lwIP → 本地 SOCKS5 → sing-box 转发，可切回 System |
| 移动网络连续性 | 监听 Android 物理网络事件；健康 VPN 不因普通切网反复重启；本地数据面停止时尝试恢复 |
| 大陆与应用分流 | 内置中国域名 / IP SRS；全部代理、仅选中应用代理、选中应用绕过 |
| 节点与订阅 | 剪贴板、扫码、二维码图片、文本、JSON / YAML；节点与订阅分类处理 |
| 本地管理 | 独立重命名、节点编辑、ICMP Ping、本地节点删除、订阅名称覆盖与恢复 |
| Quick Settings | 快捷开关；启动前核对当前节点和配置，避免误用旧缓存；不枚举已安装应用 |
| Network Lab | 物理网络、DNS、IPv4/IPv6、MTU、TUN、A/B、恢复演练、Raw 校验和日志 |
| 交付与隐私 | 固定核心源码构建、Release 单元测试与 Lint、签名 / SHA-256 报告、可选 PIN 锁、日志自动脱敏 |

## 双引擎与历史实测

```text
System: Android TUN → sing-box system stack → routing / DNS / outbound
HEV:    Android TUN → HEV native/lwIP → loopback SOCKS5 → sing-box outbound
```

保留 1.0 封板前的同机 A/B 记录：相同设备与节点，每个引擎 3 轮固定 2 MiB HTTPS，以 3 次有效运行的 run-level 中位数汇总。**这是历史样本，不是本次代码检查重新测得的性能。**

| 指标 | System | HEV |
|---|---:|---:|
| TLS | 591 ms | 610 ms |
| HTTPS 首字节 | 859 ms | 1010 ms |
| 2 MiB 下载 | 1.57 MB/s | 1.56 MB/s |
| RRBOX 进程 CPU | 1081 ms | 790 ms |
| 服务内重建 | 121 ms | 222 ms |

该样本 HEV CPU 约低 27%，吞吐接近，首字节约慢 18%。不代表所有设备、运营商、协议或 VPS 都有相同结果，因此继续保持 **System 默认，HEV 可选**。[查看验证口径](docs/TECHNICAL-VALIDATION.md)。

## 安装

打开 [最新正式 Release](https://github.com/Xiaowu7z/RR-BOX/releases/latest)，选择 `RRBOX-1.0.0-arm64-v8a.apk`。首次连接授权系统 VPN；通知、相机和后台电池优化权限按对应功能需要设置。

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.rr.client%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FXiaowu7z%2FRR-BOX%22%2C%22author%22%3A%22Xiaowu7z%22%2C%22name%22%3A%22RRBOX%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22RRBOX-%5B0-9%5D%2B%5C%5C%5C%5C.%5B0-9%5D%2B%5C%5C%5C%5C.%5B0-9%5D%2B-arm64-v8a%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%7D%22%2C%22overrideSource%22%3A%22GitHub%22%2C%22allowIdChange%22%3Afalse%7D"><img alt="Add to Obtainium" src="https://img.shields.io/badge/Add_to_Obtainium-6750A3?style=for-the-badge"></a>

**本次继续使用 1.0.0 / 100，但 APK 内容已更新。** 已安装 1.0.0 的用户应重新下载覆盖安装，不要先卸载，否则会丢失本地配置。App 内更新按版本号比较，Obtainium 也可能不提示同版本替换；不能把“已是最新版”当作本次修复包已经安装的证明。通过 Release 的 `BUILD-REPORT.md`、源码提交和 `SHA256SUMS.txt` 区分构建。

## 导入和重命名

节点页右上角 **＋ → 从剪贴板导入**：带路径或查询参数的 HTTP/HTTPS 地址先走订阅下载，验证返回内容后建立订阅组；普通节点分享链接走本地导入。根地址或带认证的资源地址存在歧义时，会询问作为订阅还是 HTTP 代理节点。扫码、图片和文本入口使用相同分类逻辑。

节点右侧 **⋮ → 重命名** 只改变显示名称。订阅刷新会优先匹配原节点身份，单纯重命名不会冻结服务器或密码；手动编辑过连接参数的节点仍保留整份本地覆盖，可通过“恢复订阅值”清除。完全重复、无法唯一匹配的节点不会强行继承旧覆盖。

添加订阅的名称 / 地址在提交、取消和再次打开时都会清空。

## 安全与兼容性

TLS 默认校验证书。使用自签证书的节点应优先配置可信证书；确需跳过时，在节点编辑中显式启用“跳过 TLS 证书校验（不安全）”，或由订阅 / Raw 配置明确指定。旧版本 HY2 / TUIC 隐式跳过校验的行为已取消，相关节点需要检查这一设置。

明确输入 HTTPS 不会自动降级跟随 HTTP 重定向。省略协议头的自建订阅仍按 HTTPS、HTTP 顺序尝试；HTTP 明文会暴露订阅地址与内容，仅用于明确可信的环境。普通导入限制 8 MiB / 2048 个节点，Network Lab Raw 单次限制 256 个。

Raw 导入提取可识别的 outbound，**不是无损导入整个客户端配置**，不会照搬自定义 inbound、完整 route 或任意 detour 链。WireGuard endpoint、Tor 以及复杂引用配置不能仅凭“核心支持”推断为此客户端已支持。导入和保存以实际解析与 libbox 校验结果为准。

PIN 是界面访问控制，不是节点数据库的独立加密；Root、系统提权和已解锁设备不在保护范围内。日志自动脱敏为尽力处理，反馈前仍需检查地址、账户和订阅信息。详见 [安全与隐私说明](SECURITY.md)。

## 构建与验证

固定 sing-box v1.14.0 / HEV commit、Go 1.26.7、NDK r28、Gradle 8.10.2、JDK 17 和 Android API 35。正式流水线运行源码检查、全部 Release 单元测试、Android Lint 和签名 APK 构建，并提供检测报告、文件哈希及版本 / ABI 校验结果。

核心源码可追溯不等同于逐字节可复现：在线规则集、部分构建动作和运行环境仍可变化，每个产物以对应报告和哈希为准。[构建与交付说明](docs/BUILDING.md)。

第三方来源与许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。自有代码目前未另外声明项目级许可证，请勿将仓库公开误认为任意再分发授权。
