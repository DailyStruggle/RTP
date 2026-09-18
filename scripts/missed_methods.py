import xml.etree.ElementTree as ET

tree = ET.parse('rtp-core/build/reports/jacoco/test/jacocoTestReport.xml')
root = tree.getroot()
for cls in root.findall(".//class[@name='io/github/dailystruggle/rtp/common/commands/menu/MenuRedeemSubcommand']"):
    print('MenuRedeemSubcommand missed branches:')
    for m in cls.findall('method'):
        name = m.get('name')
        line = m.get('line')
        br = m.find("./counter[@type='BRANCH']")
        b_m = int(br.get('missed')) if br is not None else 0
        b_c = int(br.get('covered')) if br is not None else 0
        if b_m > 0:
            print(f"  {name:40} (L{str(line):4}): missed br={b_m:2}, cov={b_c:2}")
