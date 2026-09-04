from pathlib import Path


def require(text: str, needle: str) -> None:
    if needle not in text:
        raise SystemExit(f"missing Network Lab wording: {needle!r}")


# Clean the last product-facing research/candidate names from Network Lab history.
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
    require(lab, old)
    lab = lab.replace(old, new, 1)
lab_path.write_text(lab)

# Non-workflow one-time scaffolding can be removed by the Actions token safely.
for path in [
    '.finalize-v1-trigger',
    'scripts/finalize_v1.py',
    'scripts/finalize_v1_release.py',
]:
    Path(path).unlink(missing_ok=True)
