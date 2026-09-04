from pathlib import Path
import re


def require_replace(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing expected text for {label}: {old!r}")
    return text.replace(old, new)


# 1) Promote the production CI/release workflow from 0.9.8 to 1.0.0 / 100.
workflow_path = Path(".github/workflows/build-v2.yml")
workflow = workflow_path.read_text()
workflow = require_replace(workflow, "0.9.8", "1.0.0", "workflow version")
workflow = require_replace(workflow, 'VERSION_CODE: "98"', 'VERSION_CODE: "100"', "workflow versionCode env")
workflow = require_replace(workflow, "versionCode = 98", "versionCode = 100", "workflow source versionCode assertion")
workflow = require_replace(workflow, "versionCode='98'", "versionCode='100'", "workflow APK versionCode assertion")
workflow = require_replace(workflow, "Version code: 98", "Version code: 100", "workflow build report versionCode")

# Lock the 1.0 product-closure regressions into the signed build.
anchor = "          grep -q 'android.permission.BIND_QUICK_SETTINGS_TILE' app/src/main/AndroidManifest.xml\n"
extra_checks = """          grep -q 'android.permission.BIND_QUICK_SETTINGS_TILE' app/src/main/AndroidManifest.xml
          grep -q 'LocalNodeDeletionPolicy.canDelete' app/src/main/java/com/rr/client/MainActivity.kt
          grep -q 'activeRuntimeNodeId' app/src/main/java/com/rr/client/vpn/RRVpnService.kt
          grep -q 'class NetworkContinuityMonitor' app/src/main/java/com/rr/client/vpn/NetworkContinuityMonitor.kt
          grep -q 'object ProtocolCapabilityRegistry' app/src/main/java/com/rr/client/subscription/ProtocolCapabilityRegistry.kt
          grep -q 'RawLocalNodeImporter' app/src/main/java/com/rr/client/lab/NetworkLabActivity.kt
"""
workflow = require_replace(workflow, anchor, extra_checks, "1.0 regression checks")

release_body = """          body: |
            ## RRBOX 1.0.0 正式版

            RRBOX 从测试阶段进入 1.0 正式产品线。本版冻结核心数据面与更新通道，后续优先根据真实设备、真实运营商和真实节点反馈做稳定性迭代。

            ### 1.0 核心
            - System TUN 继续作为默认稳定数据面；HEV native/lwIP 作为正式高性能可选数据面，保留一键回退。
            - 内置事件驱动的 Wi-Fi / 蜂窝网络连续性守护；健康切网不乱重启，数据面异常时使用已验证 Runtime Cache 恢复。
            - 协议导入覆盖 VLESS、VMess、Hysteria1/2、TUIC、AnyTLS、Naive、Trojan、Shadowsocks、SOCKS、HTTP(S)、SSH、ShadowTLS、Snell；复杂或新字段可走原生 sing-box Raw outbound。
            - Quick Settings 快捷开关命中 Runtime Cache 后跳过重复数据库/App 枚举/规则构建，并在失败时回滚状态。
            - VPN 连接期间只保护实际正在运行的本地节点；其他本地节点仍可正常清理。
            - Network Lab 保留可复现的 System vs HEV A/B、物理网络诊断、恢复演练、启动自检、日志与 Raw 校验。
            - 图标、状态栏 Small Icon 与通知资源沿用已经实机验证通过的资源，不做无关改动。
            - 正式签名 APK、App 内更新与 Obtainium 统一跟踪 GitHub Stable Release。

            当前正式 APK 仅发布 arm64-v8a。
          files: |
"""
workflow, count = re.subn(r"          body: \|\n.*?          files: \|\n", release_body, workflow, count=1, flags=re.S)
if count != 1:
    raise SystemExit("unable to replace 1.0 release body")
workflow_path.write_text(workflow)


# 2) Remove the last product-facing candidate/experimental wording from benchmark history.
lab_path = Path("app/src/main/java/com/rr/client/lab/NetworkLabActivity.kt")
lab = lab_path.read_text()
replacements = {
    '"${item.nodeTag}: v2.8 已验证 ${item.executionOrder.orEmpty()} · System ${item.system.httpsDownloadMedianBps?.let(TrafficSampler::formatSpeed) ?: "--"} / HEV-C ${item.hev.httpsDownloadMedianBps?.let(TrafficSampler::formatSpeed) ?: "--"}"':
        '"${item.nodeTag}: 当前已验证 ${item.executionOrder.orEmpty()} · System ${item.system.httpsDownloadMedianBps?.let(TrafficSampler::formatSpeed) ?: "--"} / HEV ${item.hev.httpsDownloadMedianBps?.let(TrafficSampler::formatSpeed) ?: "--"}"',
    '"${item.nodeTag}: v2.7 普通 HEV 基线 · 不进入 v2.8 candidate 统计"':
        '"${item.nodeTag}: 旧 HEV 基线 · 不进入当前 A/B 统计"',
    '"${item.nodeTag}: v2.6 旧测量 · 不进入 v2.8 candidate 统计"':
        '"${item.nodeTag}: 旧测量 · 不进入当前 A/B 统计"',
    '"${item.nodeTag}: v2.5 旧实验 · 不进入 v2.8 candidate 统计"':
        '"${item.nodeTag}: 旧基准 · 不进入当前 A/B 统计"',
    '"${item.nodeTag}: v2.4 旧实验 · 不进入 v2.8 candidate 统计"':
        '"${item.nodeTag}: 旧基准 · 不进入当前 A/B 统计"',
    '"${item.nodeTag}: v2.3 旧实验 · 不进入 v2.8 candidate 统计"':
        '"${item.nodeTag}: 旧基准 · 不进入当前 A/B 统计"',
    '"${item.nodeTag}: v2.2 旧实验 · 不进入 v2.8 candidate 统计"':
        '"${item.nodeTag}: 旧基准 · 不进入当前 A/B 统计"',
    '"${item.nodeTag}: v2.1 旧实验 · 不进入 v2.8 candidate 统计"':
        '"${item.nodeTag}: 旧基准 · 不进入当前 A/B 统计"',
    '"${item.nodeTag}: v2 旧实验 · 不进入 v2.8 candidate 统计"':
        '"${item.nodeTag}: 旧基准 · 不进入当前 A/B 统计"',
}
for old, new in replacements.items():
    if old not in lab:
        raise SystemExit(f"missing Network Lab history copy: {old}")
    lab = lab.replace(old, new, 1)
lab_path.write_text(lab)


# 3) One-time scaffolding and obsolete 0.9.x retag workflow must not ship in the 1.0 tree.
for stale in (
    ".finalize-v1-trigger",
    ".github/workflows/release-tag-sync.yml",
    ".github/workflows/finalize-v1.yml",
    "scripts/finalize_v1.py",
):
    path = Path(stale)
    if path.exists():
        path.unlink()
