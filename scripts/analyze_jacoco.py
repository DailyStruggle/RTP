import xml.etree.ElementTree as ET

tree = ET.parse('platforms/rtp-proxy/rtp-proxy-common/build/reports/jacoco/test/jacocoTestReport.xml')
root = tree.getroot()

classes = []
for p in root.findall('package'):
    for cls in p.findall('class'):
        cls_name = cls.attrib['name']
        cls_counters = {c.attrib['type']: (int(c.attrib['missed']), int(c.attrib['covered'])) for c in cls.findall('counter')}
        c_inst_m, c_inst_c = cls_counters.get('INSTRUCTION', (0, 0))
        c_br_m, c_br_c = cls_counters.get('BRANCH', (0, 0))
        classes.append((cls_name, c_inst_m, c_inst_c, c_br_m, c_br_c))

print("\n--- TOP MISSED INSTRUCTIONS ---")
for name, im, ic, bm, bc in sorted(classes, key=lambda x: x[1], reverse=True)[:15]:
    tot = im + ic
    pct = ic / tot * 100 if tot else 100
    print(f"{name}: missed {im}/{tot} inst ({pct:.1f}%), missed {bm} branches")

print("\n--- TOP MISSED BRANCHES ---")
for name, im, ic, bm, bc in sorted(classes, key=lambda x: x[3], reverse=True)[:15]:
    tot = bm + bc
    pct = bc / tot * 100 if tot else 100
    print(f"{name}: missed {bm}/{tot} branches ({pct:.1f}%), missed {im} inst")

total_inst_m = sum(int(c.attrib['missed']) for c in root.findall('.//counter[@type=\'INSTRUCTION\']'))
total_inst_c = sum(int(c.attrib['covered']) for c in root.findall('.//counter[@type=\'INSTRUCTION\']'))
total_br_m = sum(int(c.attrib['missed']) for c in root.findall('.//counter[@type=\'BRANCH\']'))
total_br_c = sum(int(c.attrib['covered']) for c in root.findall('.//counter[@type=\'BRANCH\']'))
tot_inst = total_inst_m + total_inst_c
tot_br = total_br_m + total_br_c
print(f"TOTAL INST: {total_inst_c / tot_inst * 100:.2f}% ({total_inst_c}/{tot_inst}), needed 85% = {int(0.85 * tot_inst + 1)}, delta = {int(0.85 * tot_inst + 1) - total_inst_c}")
print(f"TOTAL BR:   {total_br_c / tot_br * 100:.2f}% ({total_br_c}/{tot_br}), needed 67% = {int(0.67 * tot_br + 1)}, delta = {int(0.67 * tot_br + 1) - total_br_c}")
