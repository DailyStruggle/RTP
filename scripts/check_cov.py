import xml.etree.ElementTree as ET

tree = ET.parse('rtp-core/build/reports/jacoco/test/jacocoTestReport.xml')
root = tree.getroot()
for pkg in root.findall('.//package'):
    if pkg.get('name') == 'io/github/dailystruggle/rtp/common/commands/menu':
        print('Package:', pkg.get('name'))
        for c in pkg.findall('counter'):
            m = int(c.get('missed'))
            cov = int(c.get('covered'))
            pct = (cov / (m + cov) * 100) if (m + cov) > 0 else 100.0
            print(f"  {c.get('type')}: missed={m}, covered={cov}, pct={pct:.1f}%")
        print('\nClasses:')
        for cls in pkg.findall('class'):
            c_name = cls.get('name').replace('io/github/dailystruggle/rtp/common/commands/menu/', '')
            ins = cls.find("./counter[@type='INSTRUCTION']")
            br = cls.find("./counter[@type='BRANCH']")
            i_m = int(ins.get('missed')) if ins is not None else 0
            i_c = int(ins.get('covered')) if ins is not None else 0
            b_m = int(br.get('missed')) if br is not None else 0
            b_c = int(br.get('covered')) if br is not None else 0
            i_pct = (i_c / (i_m + i_c) * 100) if (i_m + i_c) > 0 else 100.0
            b_pct = (b_c / (b_m + b_c) * 100) if (b_m + b_c) > 0 else 100.0
            if i_m > 0 or b_m > 0:
                print(f"{c_name:55} INS: {i_pct:5.1f}% (m={i_m:4}, c={i_c:4}) | BR: {b_pct:5.1f}% (m={b_m:2}, c={b_c:2})")
