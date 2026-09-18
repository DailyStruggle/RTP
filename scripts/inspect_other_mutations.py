import xml.etree.ElementTree as ET
import glob

files = glob.glob('rtp-core/build/reports/pitest/**/mutations.xml', recursive=True)
tree = ET.parse(files[0])
root = tree.getroot()

classes = ['MemoryTracker', 'HeapPressureMonitor', 'PlaceholderProvider', 'ChunkyRTPShape', 'ChunkyChecker', 'ParsePermissions', 'GradientExpander']

for c in classes:
    print(f"\n=================== {c} ===================")
    for m in root.findall('mutation'):
        clazz = m.find('mutatedClass').text
        if clazz.endswith(c):
            line = m.find('lineNumber').text
            mutator = m.find('mutator').text.split('.')[-1]
            desc = m.find('description').text
            status = m.get('status')
            if status != 'KILLED':
                print(f"Line {line:4} | {status:11} | {mutator:30} | {desc}")
