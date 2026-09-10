<div align="center">

<img src="app/src/main/res/drawable-nodpi/ic_rrbox_launcher.webp" width="128" alt="RRBOX" />

# RRBOX

**Android 原生代理客户端 · System / HEV · 可选 Root 模式 · 智能分流与规则更新**

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#安装)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-00E5FF)](#安装)
[![Stable](https://img.shields.io/github/v/release/Xiaowu7z/RR-BOX?label=Stable)](https://github.com/Xiaowu7z/RR-BOX/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/Xiaowu7z/RR-BOX/build-v2.yml?branch=main&label=Build)](https://github.com/Xiaowu7z/RR-BOX/actions/workflows/build-v2.yml)

[下载正式 APK](https://github.com/Xiaowu7z/RR-BOX/releases/latest) · [使用说明](docs/USER-GUIDE.md) · [本次检查](docs/CODE-AUDIT.md) · [技术验证](docs/TECHNICAL-VALIDATION.md) · [更新记录](CHANGELOG.md)

</div>

## 项目定位

RRBOX 为用户自己的节点和订阅提供 Android VPN / 代理连接，不提供线路、账户或服务器。以 **数据面正确性、移动网络稳定性、可测量性能** 为优先级，不替用户自动挑选地区或擅自切换业务节点。

当前正式版本 **1.0.4（104）**，仅发布 **arm64-v8a**，最低 Android 8.0。默认使用 System TUN，不需要 Root；HEV native 数据面与需要授权的 Root 高级模式均为可选项。

## 1.0.4 正式版

本版在 1.0.3 基础上补齐应用自动选择预设，并检查关闭智能分流后的应用接管边界，保留 Root 完整接管与 TCP 回程修复、分流规则在线更新、X 旧目的地址恢复及微信 IPv6 直连恢复，同时保留石墨蓝界面、日志导出、节点分享和分组改名。

- **自动选择应用**：“仅选中应用”内一键匹配已安装的常用海外应用及谷歌服务，保留手动勾选与取消，批量保存后重新应用一次；包含无启动图标的 Google Play 服务（FCM 后台推送）、Google 服务框架、Play 商店及 Chrome / Edge；补齐常用 Telegram 分支、Grok 和 Google 云应用。界面显示谷歌推送组件是否已选。未选应用业务直接联网。
- **关闭智能分流的选中模式**：System / HEV / Root 均将选中应用的互联网连接交给代理，浏览器国内网页也不会再自动直连；选中名单与智能分流开关各自保存。Root 的跨应用 DNS 接管仅匹配物理 DNS 地址的 TCP / UDP 53 端口，避免误接管未选应用访问同一服务器的其他业务。Android 共享 UID 的应用可能联动，系统共享 DNS 不承诺逐应用归属。
- **ChatGPT 分流优化**：依据 OpenAI 官方网络说明补充登录、验证码、上传和配置服务的精确域名；System / HEV / Root 共用代理 DNS 与路由策略，ChatGPT 包身份可用时增加公网代理保护。保持 HTTPS 透传与证书校验。
- **JARVIS 助手直连**：智能分流开启时，明确指定的包名 `app.jarvis.assistant` 优先直连；`api.deepseek.com` 的连接和 DNS 使用直连策略。System / Root 需能识别应用身份，HEV 依靠域名规则。应用访问其他服务也按该包名直连，不自动切换代理。
- **Root 接管与恢复**：按 UID 接管 IPv4 / IPv6，保留应用分流、自身出口防循环和断开清理；修复 System stack 内部 TCP 回包路径，并将物理路由变化纳入切网判断。
- **分流规则在线更新**：通过“立即更新分流规则”获取签名的中国域名 / IP 和自定义策略；校验后应用，启动失败时恢复旧配置。普通规则数据可独立更新，新的客户端功能仍需升级 APK。
- **X 地址恢复**：对已识别且规则允许的精确 X 服务域名重新解析，减少快捷开关重连后应用沿用旧公网地址造成的断网；保留原端口和现有分流优先级。
- **微信网络兼容**：Android 13 及以上的 Root 模式，在物理网络快照明确具有 IPv4 地址及默认路由、缺少公网 IPv6 地址或默认路由时，为符合条件的微信 TCP 连接重新解析并回退。正常双栈、纯 IPv6、状态未知及 System / HEV 模式不启用这项恢复；微信 UDP 不改写。

微信兼容判断依据本机地址与路由快照，不是外网 IPv6 连通性探测。自动化验证覆盖核心收发及恢复边界，不等同于所有手机、Wi-Fi 和微信业务均已实测通过。

## 核心能力

| 能力 | 实现与边界 |
|---|---|
| System / HEV 双引擎 | 默认 System TUN；HEV 通过 native/lwIP → 本地 SOCKS5 → sing-box 转发，可切回 System |
| 移动网络连续性 | 监听 Android 物理网络事件；健康 VPN 不因普通切网反复重启；本地数据面停止时尝试恢复 |
| 大陆与应用分流 | 内置中国域名 / IP SRS；全部代理、仅选中应用代理、选中应用绕过；在线更新签名分流规则 |
| 节点与订阅 | 剪贴板、扫码、二维码图片、文本、JSON / YAML；单节点和订阅地址的链接、二维码及文件分享 |
| 本地管理 | 节点与订阅分组重命名、节点编辑、ICMP Ping、本地节点删除、订阅节点覆盖与恢复 |
| Quick Settings | 快捷开关；启动前核对当前节点和配置，避免误用旧缓存；不枚举已安装应用 |
| Network Lab | 网络诊断、A/B、Raw 校验；日志保留数量可配置或不限，本地保存、全历史搜索和 TXT 导出 |
| Root 高级模式（可选） | 原生 TUN 接入稳定内核，按 UID 接管 IPv4 / IPv6，保留应用分流；[兼容性验证与回滚说明](docs/ROOT-LAB.md) |
| 交付与隐私 | 固定核心源码构建、Release 单元测试与 Lint、签名 / SHA-256 报告、可选 PIN 锁、日志自动脱敏 |

## 双引擎与历史实测

```text
System: Android TUN → sing-box system stack → routing / DNS / outbound
HEV:    Android TUN → HEV native/lwIP → loopback SOCKS5 → sing-box outbound
```

保留 1.0 封板前使用旧 mapped DNS 的同机 A/B 记录：相同设备与节点，每个引擎 3 轮固定 2 MiB HTTPS，以 3 次有效运行的 run-level 中位数汇总。**这是历史样本，不代表当前真实 DNS 修复构建的性能。**

| 指标 | System | HEV |
|---|---:|---:|
| TLS | 591 ms | 610 ms |
| HTTPS 首字节 | 859 ms | 1010 ms |
| 2 MiB 下载 | 1.57 MB/s | 1.56 MB/s |
| RRBOX 进程 CPU | 1081 ms | 790 ms |
| 服务内重建 | 121 ms | 222 ms |

该样本 HEV CPU 约低 27%，吞吐接近，首字节约慢 18%。不代表所有设备、运营商、协议或 VPS 都有相同结果，因此继续保持 **System 默认，HEV 可选**。[查看验证口径](docs/TECHNICAL-VALIDATION.md)。

## 安装

打开 [最新正式 Release](https://github.com/Xiaowu7z/RR-BOX/releases/latest)，选择 `RRBOX-1.0.4-arm64-v8a.apk`。使用默认 System 或 HEV 模式时，首次连接授权系统 VPN；选择 Root 模式时需另行授予 Root 权限。通知、相机和后台电池优化权限按对应功能需要设置。

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.rr.client%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FXiaowu7z%2FRR-BOX%22%2C%22author%22%3A%22Xiaowu7z%22%2C%22name%22%3A%22RRBOX%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22RRBOX-%5B0-9%5D%2B%5C%5C%5C%5C.%5B0-9%5D%2B%5C%5C%5C%5C.%5B0-9%5D%2B-arm64-v8a%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%7D%22%2C%22overrideSource%22%3A%22GitHub%22%2C%22allowIdChange%22%3Afalse%7D"><img alt="Add to Obtainium" src="https://img.shields.io/badge/Add_to_Obtainium-6750A3?style=for-the-badge"></a>

**从 1.0.0 / 1.0.1 / 1.0.2 / 1.0.3 升级到 1.0.4，请直接覆盖安装，无需卸载。** 包名与正式签名保持一致，覆盖安装保留本地节点、订阅和设置。版本号提升为 1.0.4 / 104，App 内“检查更新”可识别这次升级，Obtainium 也可跟踪正式 Release。安装后重新连接 RRBOX；“立即更新分流规则”只更新规则数据，不能替代本次 APK 升级。

Release 同时提供 `BUILD-REPORT.md`、源码提交和 `SHA256SUMS.txt`，用于核对安装包来源与具体构建。

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
