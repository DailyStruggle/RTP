import xml.etree.ElementTree as ET

tree = ET.parse('rtp-core/build/reports/jacoco/test/jacocoTestReport.xml')
root = tree.getroot()
for p in root.findall('.//package'):
    if 'tools' in p.get('name'):
        print('=== PACKAGE:', p.get('name'), '===')
        classes = []
        for cl in p.findall('class'):
            ci = cl.find('counter[@type="INSTRUCTION"]')
            cb = cl.find('counter[@type="BRANCH"]')
            if ci is not None:
                cov = int(ci.get('covered'))
                miss = int(ci.get('missed'))
                tot = cov + miss
                pct = cov / tot * 100 if tot else 0
                bcov = int(cb.get('covered')) if cb is not None else 0
                bmiss = int(cb.get('missed')) if cb is not None else 0
                btot = bcov + bmiss
                bpct = bcov / btot * 100 if btot else 0
                classes.append((cl.get('name'), cov, tot, pct, miss, bcov, btot, bpct))
        classes.sort(key=lambda x: x[4], reverse=True)
        for c in classes:
            print(f"{c[0]:65} | Inst: {c[1]:4}/{c[2]:4} ({c[3]:5.1f}%) | Missed: {c[4]:4} | Branch: {c[5]:2}/{c[6]:2} ({c[7]:5.1f}%)")
