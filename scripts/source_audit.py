#!/usr/bin/env python3
"""Inventory every tracked source, check document targets/format and freeze release identity."""
import ast
import hashlib
import json
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parent.parent
paths = subprocess.check_output(['git', 'ls-files'], cwd=root, text=True).splitlines()
# Also include not-yet-committed source files during local preflight.
paths = sorted(set(paths) | set(subprocess.check_output(
    ['git', 'ls-files', '--others', '--exclude-standard'], cwd=root, text=True).splitlines()))
paths = [p for p in paths if (root / p).is_file()]
assert not any(p.endswith(('.jks', '.keystore')) for p in paths), 'Signing key must never be tracked'
manifest = []
for name in paths:
    path = root / name
    data = path.read_bytes()
    manifest.append({'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    if name.endswith('.xml'):
        ET.fromstring(data)
    elif name.endswith('.json'):
        json.loads(data)
    elif name.endswith('.py'):
        ast.parse(data, filename=name)
    elif name.endswith('.sh'):
        subprocess.run(['bash', '-n', str(path)], check=True)
    if name.endswith(('.md', '.kt', '.kts', '.yml', '.sh', '.py', '.json', '.toml')):
        text = data.decode('utf-8')
        # Check only complete private-key headers; never print potentially sensitive contents.
        assert not re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', text), name
        if name.endswith('.md'):
            for target in re.findall(r'\]\(([^)]+)\)', text):
                if '://' in target or target.startswith(('#', 'mailto:')):
                    continue
                dest = path.parent / target.split('#')[0]
                assert dest.exists(), f'{name}: missing local documentation target {target}'
version = (root / 'app/build.gradle.kts').read_text()
assert 'versionName = "1.0.1"' in version and 'versionCode = 101' in version
obtainium = json.loads((root / 'obtainium.json').read_text())
assert obtainium['url'] == 'https://github.com/Xiaowu7z/RR-BOX'
settings = json.loads(obtainium['additionalSettings'])
assert settings['includePrereleases'] is False
assert re.search(settings['apkFilterRegEx'], 'RRBOX-1.0.1-arm64-v8a.apk')
assert not re.search(settings['apkFilterRegEx'], 'RRBOX-1.0.1-debug.apk')
report = {
    'source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
    'tracked_and_local_files': len(paths),
    'production_kotlin_files': sum(p.startswith('app/src/main/') and p.endswith('.kt') for p in paths),
    'test_kotlin_files': sum(p.startswith('app/src/test/') and p.endswith('.kt') for p in paths),
    'checks': ['source inventory', 'XML', 'JSON', 'Python syntax', 'shell syntax',
               'local Markdown targets', 'version lock', 'Obtainium APK filter', 'private-key header scan'],
    'files': manifest,
}
out = root / 'build-reports'
out.mkdir(exist_ok=True)
(out / 'SOURCE-AUDIT.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k != 'files'}, ensure_ascii=False))
