import xml.etree.ElementTree as ET

tree = ET.parse('rtp-core/build/reports/jacoco/test/jacocoTestReport.xml')
root = tree.getroot()

for p in root.findall('.//package'):
    if p.get('name') == 'io/github/dailystruggle/rtp/common/tools':
        for cl in p.findall('class'):
            if cl.get('name').endswith('PlaceholderProvider'):
                print("Methods in PlaceholderProvider:")
                methods = []
                for m in cl.findall('method'):
                    ci = m.find('counter[@type="INSTRUCTION"]')
                    cov = int(ci.get('covered')) if ci is not None else 0
                    miss = int(ci.get('missed')) if ci is not None else 0
                    tot = cov + miss
                    pct = cov / tot * 100 if tot else 0
                    methods.append((m.get('name'), m.get('desc'), cov, tot, pct, miss))
                methods.sort(key=lambda x: x[5], reverse=True)
                for name, desc, cov, tot, pct, miss in methods:
                    if miss > 0:
                        print(f"  {name:30} | Inst: {cov:4}/{tot:4} ({pct:5.1f}%) | Missed: {miss:4}")
