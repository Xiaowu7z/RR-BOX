from pathlib import Path
import re

wf = Path('.github/workflows/build-v2.yml')
s = wf.read_text()
s = s.replace('0.9.8', '1.0.0')
s = s.replace('VERSION_CODE: "98"', 'VERSION_CODE: "100"')
s = s.replace('versionCode = 98', 'versionCode = 100')
s = s.replace("versionCode='98'", "versionCode='100'")
s = s.replace('- Version code: 98', '- Version code: 100')
needle = "          grep -q 'class RRQuickTileService' app/src/main/java/com/rr/client/vpn/RRQuickTileService.kt\n"
extra = needle + "          grep -q 'LocalNodeDeletionPolicy.canDelete' app/src/main/java/com/rr/client/MainActivity.kt\n          grep -q 'activeRuntimeNodeId' app/src/main/java/com/rr/client/vpn/RRVpnService.kt\n          grep -q 'NetworkContinuityObserver' app/src/main/java/com/rr/client/lab/NetworkContinuityObserver.kt\n          grep -q 'ACTION_LAB_DROP_DATA_PLANE' app/src/main/java/com/rr/client/vpn/RRVpnService.kt\n          grep -q 'object RawLocalNodeImporter' app/src/main/java/com/rr/client/lab/RawLocalNodeImporter.kt\n          grep -q 'Quick tile fast path' app/src/main/java/com/rr/client/vpn/RRQuickTileController.kt\n"
if needle not in s:
    raise SystemExit('quick tile regression anchor missing')
s = s.replace(needle, extra, 1)
body = '''          body: |
            ## RRBOX 1.0.0 正式版

            RRBOX 1.0 正式冻结核心数据面，重点完成移动网络稳定性、可观测性和日常操作体验的工程化收口。

            ### 1.0 核心
            - System TUN 继续作为默认稳定引擎。
            - HEV native/lwIP 成为正式高性能可选引擎，固化 8500 MTU、mapped DNS、SOCKS5 pipeline 与 best-effort client TCP Fast Open。
            - Network Lab 保留 System vs HEV 同节点固定负载 A/B、路径计数、CPU/PSS、物理网络诊断、启动自检和脱敏日志。
            - 网络连续性守护实时识别 Wi-Fi / 蜂窝切换；健康数据面不重启，真实异常才使用已验证 Runtime Cache 自动恢复。
            - 用户主动断开后不会被守护擅自重新连接；Network Lab 提供可控恢复演练。
            - Android Quick Settings 快捷开关使用 Runtime Cache fast path，点击即时反馈，实机缓存准备约 4–21 ms。
            - 支持分享链接、Clash/Mihomo、订阅、二维码/图片二维码和 sing-box Raw outbound / outbounds[] / 完整 config 导入。
            - VPN 运行期间只保护实际正在使用的本地节点，其他本地节点可以直接删除，不重启当前连接。
            - App 内更新与 Obtainium 跟踪正式 GitHub Release。

            ### 实机 A/B 取舍
            三次有效同机 A/B 的 run-level 中位数：System TLS 591 ms / TTFB 859 ms / 1.57 MB/s / CPU 1081 ms；HEV TLS 610 ms / TTFB 1010 ms / 1.56 MB/s / CPU 790 ms。HEV 在这组测试中 CPU 约低 27%，吞吐同档，但 TTFB 较慢，因此 1.0 仍保持 System 默认、HEV 可选，不宣称任何引擎在所有网络中必然更快。

            当前正式 APK 仅发布 arm64-v8a，最低 Android 8.0。
'''
pattern = re.compile(r'          body: \|\n.*?          files: \|\n', re.S)
if not pattern.search(s):
    raise SystemExit('release body block not found')
s = pattern.sub(body + '          files: |\n', s, count=1)
wf.write_text(s)

for path in [
    '.github/workflows/release-tag-sync.yml',
    '.github/workflows/finalize-v1.yml',
    '.finalize-v1-trigger',
    'scripts/finalize_v1.py',
    'scripts/finalize_v1_release.py',
]:
    Path(path).unlink(missing_ok=True)
