import xml.etree.ElementTree as ET

tree = ET.parse('rtp-core/build/reports/jacoco/test/jacocoTestReport.xml')
root = tree.getroot()

classes = [
    'io/github/dailystruggle/rtp/common/commands/menu/CommandTreeMenuBuilder',
    'io/github/dailystruggle/rtp/common/commands/menu/VisualizationsSubmenuBuilder',
    'io/github/dailystruggle/rtp/common/commands/menu/MenuWiringSupportInstaller',
    'io/github/dailystruggle/rtp/common/commands/menu/MenuConcreteCommandLeavesB',
    'io/github/dailystruggle/rtp/common/commands/menu/MenuConcreteCommandLeaves'
]

for c_target in classes:
    for cls in root.findall(f".//class[@name='{c_target}']"):
        print("=== " + c_target.split('/')[-1] + " ===")
        for m in cls.findall('method'):
            name = m.get('name')
            desc = m.get('desc')
            line = m.get('line')
            ins = m.find("./counter[@type='INSTRUCTION']")
            br = m.find("./counter[@type='BRANCH']")
            i_m = int(ins.get('missed')) if ins is not None else 0
            i_c = int(ins.get('covered')) if ins is not None else 0
            b_m = int(br.get('missed')) if br is not None else 0
            b_c = int(br.get('covered')) if br is not None else 0
            if i_m > 0 or b_m > 0:
                print(f"{name:40} (L{str(line):4}): missed ins={i_m:3}, cov={i_c:3} | missed br={b_m:2}, cov={b_c:2}")
