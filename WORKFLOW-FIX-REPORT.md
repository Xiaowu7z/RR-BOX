# RR Client workflow 修正报告

本包只修正构建与签名链路，不声明 Android 实机功能已经通过。

主要调整：

- 固定 sing-box v1.14.0 与完整 commit `0b8995879f29a9b98ee027bc17b75e101445b238`。
- 使用 Go 1.26.7、Android NDK r28、JDK 17。
- 使用上游 `make lib_install` 和官方 `build_libbox`，不再修改 `with_gvisor`、`with_tailscale` 或 `with_naive_outbound`。
- 删除每次 CI 临时生成随机签名的 fallback；四个签名 Secret 缺一即失败。
- 固定选择 `app/build/outputs/apk/release/app-release.apk`，不再使用 `find | head -n 1`。
- 增加 AAR/APK 的签名、包名、版本、ABI、SHA-256 和 Xposed/SystemUI 条目门禁。
- 将工作流文件统一为 `.github/workflows/build-v2.yml`。
- 改进本地签名生成脚本，签名只创建一次并保存于 `local-secrets/`。

仍需后续处理：

- `BoxServiceWrapper.kt` 是否完全匹配 sing-box v1.14.0 生成的 Kotlin/Java API，必须以实际 AAR 编译错误为准继续修复。
- `RRVpnService.kt` 的状态流量字段含义必须经过官方 API 与实机对账，不能仅凭字段名宣称是“仅 proxy outbound”流量。
- 必须在 OnePlus 目标机执行 ADB 安装、启动、VPN 联网和真实流量验证。
