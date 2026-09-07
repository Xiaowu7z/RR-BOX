#!/usr/bin/env python3
import json, pathlib, xml.etree.ElementTree as ET
r=pathlib.Path(__file__).resolve().parent.parent
suites=list((r/'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'))
assert suites, 'No JUnit reports were produced'
totals=dict(tests=0, failures=0, errors=0, skipped=0)
for p in suites:
    attrs=ET.parse(p).getroot().attrib
    for k in totals: totals[k] += int(attrs.get(k,0))
assert totals['tests'] > 0 and totals['failures']==0 and totals['errors']==0, totals
lint=r/'app/build/reports/lint-results-release.xml'
assert lint.exists(), 'Lint XML report missing'
issues=ET.parse(lint).getroot().findall('issue')
counts={k:sum(i.attrib.get('severity')==k for i in issues) for k in ('Fatal','Error','Warning','Information')}
assert counts['Fatal']==0 and counts['Error']==0, counts
report={'junit_suites':len(suites),**totals,'lint':counts,'real_device_test':False}
(r/'dist').mkdir(exist_ok=True)
(r/'dist/TEST-REPORT.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
