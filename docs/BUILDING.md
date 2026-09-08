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

只有 `main` 上以 `release: RRBOX 1.0.2` 开头的发布提交能更新 Stable Release；其他分支构建不发布正式 APK。发布前运行单元测试、Lint、原生核心收发回归及 APK 签名与版本校验，发布后核对远端 APK digest 与本地产物 SHA-256，并确认 `v1.0.2` 的 Git tag 指向构建源码。

首次发布创建 `v1.0.2`；重跑时只允许同一 tag 已经指向当前准确提交，否则停止发布。已有 `v1.0.0`、`v1.0.1` 及其他历史 tag 不移动。

`RRBOX-1.0.2-arm64-v8a.apk`、`SHA256SUMS.txt`、`BUILD-REPORT.md`、`TEST-REPORT.json`、`SIGNATURE-REPORT.txt` 和 `PACKAGE-REPORT.txt` 随 Release 发布。正式资产还包含源码、核心路由、X 地址恢复、微信 IPv6 回退、HEV DNS / 原生 DNS 及 Root 接管 / TCP 收发报告。CI artifact 另含详细测试和 Lint 报告。原生测试在隔离环境中验证实现边界，不能代替 Android 实机业务验收。

预期正式签名证书 SHA-256：

```text
fe1368cf16ee9e8b56199655d0b1e2606a6ec9b8f3d4ac5e16e8cf66e180d816
```

本次版本为 **1.0.2 / 102**，高于此前 **1.0.1 / 101**；App 内版本比较可检测本次升级，正式 APK 命名符合 App 与 Obtainium 的资产过滤规则。包名及正式签名保持一致，允许保留数据覆盖安装。构建追溯仍以源码提交、构建报告及 APK 哈希为准。

## GitHub About 简介

README 与 GitHub 仓库 `description` 是两个独立字段。期望元数据保存在 `.github/repository-metadata.json`。`scripts/sync_repository_metadata.sh` 只通过正常 GitHub 管理权限更新，并在无权限时失败退出；普通 Actions `GITHUB_TOKEN` 的 contents:write 不等于 Administration:write，不会把 403 当成功。脚本不会保存或输出令牌。
