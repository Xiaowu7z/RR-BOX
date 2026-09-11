# 构建、校验与交付

## 固定依赖

正式流水线 `.github/workflows/build-v2.yml` 固定 sing-box v1.14.0（`0b8995879f29a9b98ee027bc17b75e101445b238`）、HEV（`64cc609f945253b0e9ebc56317d544268f3c68c1`）、Go 1.26.7、NDK r28、Gradle 8.10.2、JDK 17、API 35 / Build Tools 35.0.0。只构建 arm64-v8a，最低 API 26。

libbox AAR 与 HEV JNI 从固定源码生成。中国规则集从维护分支获取，因此不同日期构建的规则内容可能不同。CI 记录规则、原生库与 APK 哈希；“固定核心”不表示 APK 逐字节可复现。

## 本地或 CI 验证

准备好 Android SDK/NDK、Gradle 以及 `app/libs/libbox.aar`、HEV JNI 后运行：

```sh
python3 scripts/source_audit.py
bash scripts/source-regressions.sh
gradle --no-daemon --max-workers=2 --stacktrace clean :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

Release 签名只在 CI 中通过已有 Secrets 注入，不在源码中保存。所需 Secret 名称：`RR_KEYSTORE_BASE64`、`RR_KEYSTORE_PASSWORD`、`RR_KEY_ALIAS`、`RR_KEY_PASSWORD`。缺少签名配置时不得把未签名 APK 当作正式覆盖安装包。

## 发布门槛

只有 `main` 上以 `release: RRBOX 1.0.7` 开头的发布提交能更新 Stable Release；其他分支构建不发布正式 APK。发布前运行单元测试、Lint、原生核心收发回归及 APK 签名与版本校验，发布后核对远端 APK digest 与本地产物 SHA-256，并确认 `v1.0.7` 的 Git tag 指向构建源码。

首次发布创建 `v1.0.7`；重跑时只允许同一 tag 已经指向当前准确提交，否则停止发布。已有 `v1.0.0`、`v1.0.1`、`v1.0.2`、`v1.0.3`、`v1.0.4`、`v1.0.5`、`v1.0.6` 及其他历史 tag 不移动。

`RRBOX-1.0.7-arm64-v8a.apk`、`SHA256SUMS.txt`、`BUILD-REPORT.md`、`TEST-REPORT.json`、`SIGNATURE-REPORT.txt` 和 `PACKAGE-REPORT.txt` 随 Release 发布。正式资产还包含源码、核心路由、X 地址恢复、微信 IPv6 回退、HEV DNS / 原生 DNS 及 Root 接管 / TCP 收发报告。CI artifact 另含详细测试和 Lint 报告。原生测试在隔离环境中验证实现边界，不能代替 Android 实机业务验收。

预期正式签名证书 SHA-256：

```text
fe1368cf16ee9e8b56199655d0b1e2606a6ec9b8f3d4ac5e16e8cf66e180d816
```

本次版本为 **1.0.7 / 107**，高于此前 **1.0.6 / 106**；App 内版本比较可检测本次升级，正式 APK 命名符合 App 与 Obtainium 的资产过滤规则。包名及正式签名保持一致，允许保留数据覆盖安装。构建追溯仍以源码提交、构建报告及 APK 哈希为准。

1.0.4 增加 `ALLOW_LIST + smartRouting=false` 三引擎配置及快捷重连回归，确认谷歌后台推送组件在推荐名单内。当时的 Root 物理 DNS 规则依赖系统 `ip rule` 解析 `ipproto` / `dport`，1.0.7 改为通过 Netlink 设置同样精确的规则；内核拒绝时仍启动失败并回滚，不回退到整地址接管。原生隔离测试检查未选 UID 对同一 DNS 服务器非 53 端口的连接保持直连。

现有传输边界继续适用：HEV 使用 IPv4 隧道；Root 将私网、回环及组播留在系统网络，仅显式 DNS 与内部 TCP 回程地址有精确例外。因此三引擎验证的是选中名单与互联网代理策略，不代表局域网和 IPv6 逐包行为完全相同。FCM 本身不要求代理，只需当前网络能连通推送服务器；预设包含 GMS，用户取消后不会被自动选择重新勾上。

1.0.5 新增本机节点删除回归：仅修改节点列表字段，保留订阅元数据；验证单节点 / 整组删除、分组隔离、活动节点保护及更新订阅恢复。布局变更需在 Android 实机确认字体与显示缩放效果。

## GitHub About 简介

README 与 GitHub 仓库 `description` 是两个独立字段。期望元数据保存在 `.github/repository-metadata.json`。`scripts/sync_repository_metadata.sh` 只通过正常 GitHub 管理权限更新，并在无权限时失败退出；普通 Actions `GITHUB_TOKEN` 的 contents:write 不等于 Administration:write，不会把 403 当成功。脚本不会保存或输出令牌。

1.0.6 增加应用指定节点与多出口验证。主节点保持默认出口；有效绑定优先于智能分流，缺失或无效的辅助节点只拒绝绑定应用。System / Root 的未知应用身份连接和 HEV 转换前的未知身份会话均拒绝，避免落入主线。HEV 此项能力要求 Android 10 以上，其 UDP 会话检查含身份/出口变化，启用时存在额外身份查询开销。原始应用身份的 Android 成功率与真实业务性能需要实机验收。

1.0.7 针对手机反馈补充 HEV Java 调用的真实线程边界检查，以及拒绝 `ipproto` / `dport` 命令参数的 Android 兼容环境。Root DNS 规则直接通过 Netlink 编码、回读和清理；原有非 DNS 端口、未选 UID 与保护自身出口的边界必须通过。HEV 的 JVM 测试验证 JNI 调用线程和 HEV 协程栈的分离，不替代 Android ART 与手机网络实测。

Android 11 以上的退出原因补录使用系统 [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)；只保留本应用退出时间、原因、状态码和白名单格式的引擎阶段，不读取 tombstone 内存或网络内容。
