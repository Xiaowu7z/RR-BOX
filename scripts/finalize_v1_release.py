from pathlib import Path
import re


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise SystemExit(f"missing {label}: {needle!r}")


# Promote the production signed build/release workflow to RRBOX 1.0.0 / versionCode 100.
wf = Path('.github/workflows/build-v2.yml')
s = wf.read_text()
require(s, '0.9.8', '0.9.8 workflow version')
require(s, 'VERSION_CODE: "98"', 'workflow version code')
require(s, 'versionCode = 98', 'source version assertion')
require(s, "versionCode='98'", 'APK version assertion')
s = s.replace('0.9.8', '1.0.0')
s = s.replace('VERSION_CODE: "98"', 'VERSION_CODE: "100"')
s = s.replace('versionCode = 98', 'versionCode = 100')
s = s.replace("versionCode='98'", "versionCode='100'")
s = s.replace('- Version code: 98', '- Version code: 100')

# Lock the 1.0 closure features into the signed CI path so release cannot regress silently.
needle = "          grep -q 'class RRQuickTileService' app/src/main/java/com/rr/client/vpn/RRQuickTileService.kt\n"
require(s, needle, 'quick tile regression anchor')
extra = needle + (
    "          grep -q 'LocalNodeDeletionPolicy.canDelete' app/src/main/java/com/rr/client/MainActivity.kt\n"
    "          grep -q 'activeRuntimeNodeId' app/src/main/java/com/rr/client/vpn/RRVpnService.kt\n"
    "          grep -q 'class NetworkContinuityMonitor' app/src/main/java/com/rr/client/vpn/NetworkContinuityMonitor.kt\n"
    "          grep -q 'NetworkContinuityObserver' app/src/main/java/com/rr/client/lab/NetworkContinuityObserver.kt\n"
    "          grep -q 'ACTION_LAB_DROP_DATA_PLANE' app/src/main/java/com/rr/client/vpn/RRVpnService.kt\n"
    "          grep -q 'object ProtocolCapabilityRegistry' app/src/main/java/com/rr/client/subscription/ProtocolCapabilityRegistry.kt\n"
    "          grep -q 'object RawLocalNodeImporter' app/src/main/java/com/rr/client/lab/RawLocalNodeImporter.kt\n"
    "          grep -q 'Quick tile fast path' app/src/main/java/com/rr/client/vpn/RRQuickTileController.kt\n"
)
s = s.replace(needle, extra, 1)

body = '''          body: |
            ## RRBOX 1.0.0 正式版

            RRBOX 从测试阶段进入 1.0 正式产品线。本版冻结核心数据面与更新通道，后续优先根据真实设备、真实运营商和真实节点反馈做稳定性迭代。

            ### 1.0 核心
            - System TUN 继续作为默认稳定数据面；HEV native/lwIP 作为正式高性能可选数据面，保留一键回退。
            - 内置事件驱动的 Wi-Fi / 蜂窝网络连续性守护；健康切网不乱重启，数据面异常时使用已验证 Runtime Cache 恢复。
            - 协议导入覆盖 VLESS、VMess、Hysteria1/2、TUIC、AnyTLS、Naive、Trojan、Shadowsocks、SOCKS、HTTP(S)、SSH、ShadowTLS、Snell；复杂或新字段可走原生 sing-box Raw outbound。
            - Quick Settings 快捷开关命中 Runtime Cache 后跳过重复数据库/App 枚举/规则构建，并在失败时回滚状态。
            - VPN 连接期间只保护实际正在运行的本地节点；其他本地节点仍可正常清理，不重启当前连接。
            - Network Lab 保留可复现的 System vs HEV A/B、物理网络诊断、恢复演练、启动自检、日志与 Raw 校验。
            - 图标、状态栏 Small Icon 与通知资源沿用已经实机验证通过的资源，不做无关改动。
            - 正式签名 APK、App 内更新与 Obtainium 统一跟踪 GitHub Stable Release。

            ### 实机 A/B 取舍
            三次有效同机 A/B 的 run-level 中位数：System TLS 591 ms / TTFB 859 ms / 1.57 MB/s / CPU 1081 ms；HEV TLS 610 ms / TTFB 1010 ms / 1.56 MB/s / CPU 790 ms。HEV 在这组测试中 CPU 约低 27%，吞吐同档，但 TTFB 较慢，因此 1.0 仍保持 System 默认、HEV 可选，不宣称任何引擎在所有网络中必然更快。

            当前正式 APK 仅发布 arm64-v8a，最低 Android 8.0。
'''
pattern = re.compile(r'          body: \|\n.*?          files: \|\n', re.S)
if not pattern.search(s):
    raise SystemExit('release body block not found')
s = pattern.sub(body + '          files: |\n', s, count=1)
wf.write_text(s)


# Remove the last user-facing candidate/experimental naming left from the research phase.
lab_path = Path('app/src/main/java/com/rr/client/lab/NetworkLabActivity.kt')
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
    require(lab, old, 'Network Lab history wording')
    lab = lab.replace(old, new, 1)
lab_path.write_text(lab)


# One-time migration scaffolding and obsolete 0.9.x retag automation must not ship in 1.0.
for path in [
    '.github/workflows/release-tag-sync.yml',
    '.github/workflows/finalize-v1.yml',
    '.finalize-v1-trigger',
    'scripts/finalize_v1.py',
    'scripts/finalize_v1_release.py',
]:
    Path(path).unlink(missing_ok=True)
