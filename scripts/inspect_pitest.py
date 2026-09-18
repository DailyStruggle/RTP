import xml.etree.ElementTree as ET
import glob

files = glob.glob('rtp-core/build/reports/pitest/**/mutations.xml', recursive=True)
if not files:
    print("No mutations.xml found")
else:
    tree = ET.parse(files[0])
    root = tree.getroot()
    total = len(root.findall('mutation'))
    killed = len([m for m in root.findall('mutation') if m.get('status') in ('KILLED', 'TIMED_OUT')])
    survived = len([m for m in root.findall('mutation') if m.get('status') == 'SURVIVED'])
    no_cov = len([m for m in root.findall('mutation') if m.get('status') == 'NO_COVERAGE'])
    print(f"Total: {total}, Killed: {killed}, Survived: {survived}, No Coverage: {no_cov}")
    score = killed / total * 100 if total else 0
    print(f"Mutation score: {score:.2f}%")
    by_class = {}
    for m in root.findall('mutation'):
        clazz = m.find('mutatedClass').text
        status = m.get('status')
        if clazz not in by_class:
            by_class[clazz] = {'total': 0, 'killed': 0, 'survived': 0, 'no_cov': 0}
        by_class[clazz]['total'] += 1
        if status in ('KILLED', 'TIMED_OUT'):
            by_class[clazz]['killed'] += 1
        elif status == 'SURVIVED':
            by_class[clazz]['survived'] += 1
        elif status == 'NO_COVERAGE':
            by_class[clazz]['no_cov'] += 1
    print("\nPer class breakdown:")
    for clazz, d in sorted(by_class.items(), key=lambda x: x[1]['total'], reverse=True):
        sc = d['killed'] / d['total'] * 100 if d['total'] else 0
        print(f"{clazz:60} | Killed: {d['killed']:3}/{d['total']:3} ({sc:5.1f}%) | Survived: {d['survived']:3} | NoCov: {d['no_cov']:3}")
