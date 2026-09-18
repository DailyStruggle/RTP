import xml.etree.ElementTree as ET

tree = ET.parse('rtp-core/build/reports/jacoco/test/jacocoTestReport.xml')
root = tree.getroot()
for pkg in root.findall('.//package'):
    if pkg.get('name') == 'io/github/dailystruggle/rtp/common/commands/menu':
        print('\nClasses with missed branches:')
        for cls in pkg.findall('class'):
            c_name = cls.get('name').replace('io/github/dailystruggle/rtp/common/commands/menu/', '')
            br = cls.find("./counter[@type='BRANCH']")
            b_m = int(br.get('missed')) if br is not None else 0
            b_c = int(br.get('covered')) if br is not None else 0
            if b_m > 0:
                b_pct = (b_c / (b_m + b_c) * 100) if (b_m + b_c) > 0 else 100.0
                print(f"{c_name:55} BR: {b_pct:5.1f}% (m={b_m:2}, c={b_c:2})")
