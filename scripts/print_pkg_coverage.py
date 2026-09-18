import sys
import xml.etree.ElementTree as ET

tree = ET.parse('rtp-core/build/reports/jacoco/test/jacocoTestReport.xml')
root = tree.getroot()

print('=== OVERALL RTP-CORE ===')
for c in root.findall('counter'):
    cov = int(c.get('covered'))
    miss = int(c.get('missed'))
    tot = cov + miss
    pct = cov / tot * 100 if tot else 0
    print(f"{c.get('type'):12} | {cov:6}/{tot:6} | {pct:6.2f}% | Missed: {miss}")

pkgs = []
for p in root.findall('.//package'):
    p_name = p.get('name')
    ci = p.find('counter[@type="INSTRUCTION"]')
    cl = p.find('counter[@type="LINE"]')
    cb = p.find('counter[@type="BRANCH"]')
    if ci is not None:
        pkgs.append({
            'name': p_name,
            'inst_cov': int(ci.get('covered')),
            'inst_miss': int(ci.get('missed')),
            'line_cov': int(cl.get('covered')) if cl is not None else 0,
            'line_miss': int(cl.get('missed')) if cl is not None else 0,
            'branch_cov': int(cb.get('covered')) if cb is not None else 0,
            'branch_miss': int(cb.get('missed')) if cb is not None else 0,
        })

print('\n=== ALL PACKAGES IN SELECTION.REGION ===')
for p in pkgs:
    if 'selection/region' in p['name']:
        tot_i = p['inst_cov'] + p['inst_miss']
        pct_i = p['inst_cov'] / tot_i * 100 if tot_i else 0
        tot_l = p['line_cov'] + p['line_miss']
        pct_l = p['line_cov'] / tot_l * 100 if tot_l else 0
        tot_b = p['branch_cov'] + p['branch_miss']
        pct_b = p['branch_cov'] / tot_b * 100 if tot_b else 0
        print(f"{p['name']:65} | Inst: {p['inst_cov']:5}/{tot_i:5} ({pct_i:5.1f}%) | Line: {p['line_cov']:4}/{tot_l:4} ({pct_l:5.1f}%) | Branch: {pct_b:5.1f}%")

pkgs.sort(key=lambda x: x['inst_miss'], reverse=True)
print('\n=== TOP 10 LARGEST REMAINING GAPS (MISSED INSTRUCTIONS) ===')
for p in pkgs[:10]:
    tot_i = p['inst_cov'] + p['inst_miss']
    pct_i = p['inst_cov'] / tot_i * 100 if tot_i else 0
    tot_l = p['line_cov'] + p['line_miss']
    pct_l = p['line_cov'] / tot_l * 100 if tot_l else 0
    print(f"{p['name']:60} | Missed Inst: {p['inst_miss']:5} | Cov: {p['inst_cov']:5}/{tot_i:5} ({pct_i:5.1f}%) | Line: {pct_l:5.1f}%")
