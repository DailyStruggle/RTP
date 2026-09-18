import xml.etree.ElementTree as ET
import glob

files = glob.glob('rtp-core/build/reports/pitest/**/mutations.xml', recursive=True)
tree = ET.parse(files[0])
root = tree.getroot()

print("=== HeapPressureMonitor Surviving/NoCov ===")
for m in root.findall('mutation'):
    clazz = m.find('mutatedClass').text
    if clazz.endswith('HeapPressureMonitor'):
        status = m.get('status')
        if status != 'KILLED':
            line = m.find('lineNumber').text
            mutator = m.find('mutator').text.split('.')[-1]
            desc = m.find('description').text
            print(f"Line {line:4} | {status:10} | {mutator:30} | {desc}")
