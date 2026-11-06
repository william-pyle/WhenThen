"""Destructive fixtures on emulator-5554 ONLY: real reboot and APK replacement after a GUI pause.

Install the debug APK first. Set ADB to its executable if using a custom SDK.
No physical phone is selected and no audio or outgoing message is sent.
"""
import json, os, pathlib, re, subprocess, time, xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
ADB = os.environ.get('ADB', str(pathlib.Path(os.environ.get('ANDROID_HOME', ROOT / '.local/tools/sdk')) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')))
PACKAGE = 'com.jazcosolutions.whenthen'

def adb(*args, input=None, timeout=60):
    result = subprocess.run([ADB, '-s', 'emulator-5554', *args], input=input, capture_output=True, timeout=timeout)
    if result.returncode: raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout

def active():
    text = adb('shell', 'dumpsys', 'activity', 'services', PACKAGE).decode()
    return '.MonitorService' in text and 'isForeground=true' in text

def wait_for(check, seconds=45):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        try:
            if check(): return
        except (RuntimeError, subprocess.TimeoutExpired): pass
        time.sleep(1)
    raise AssertionError('Timed out waiting for the expected monitoring state')

def write(name, data):
    adb('shell', 'run-as', PACKAGE, 'sh', '-c', '"cat > ' + name + '"', input=data)

def read(name): return adb('shell', 'run-as', PACKAGE, 'cat', name)

def pause_in_gui():
    adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
    time.sleep(1)
    for _ in range(6):
        adb('shell', 'uiautomator', 'dump', '/sdcard/whenthen-lifecycle.xml')
        tree = ET.fromstring(adb('exec-out', 'cat', '/sdcard/whenthen-lifecycle.xml'))
        for node in tree.iter('node'):
            if node.get('text') == 'Pause monitoring':
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                if y2 > y1:
                    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                    wait_for(lambda: not active())
                    assert b'name="monitorWanted" value="false"' in read('shared_prefs/settings.xml')
                    adb('shell', 'input', 'keyevent', '3')
                    return
        size = list(map(int, re.findall(r'\d+', adb('shell', 'wm', 'size').decode())[-2:]))
        adb('shell', 'input', 'swipe', str(size[0]//2), str(int(size[1]*.78)), str(size[0]//2), str(int(size[1]*.30)), '350')
    raise AssertionError('Pause monitoring button not found')

assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == b'1', 'Isolated emulator required'
adb('shell', 'am', 'force-stop', PACKAGE)
for permission in ['POST_NOTIFICATIONS', 'RECEIVE_SMS']:
    adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.' + permission)
adb('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files', 'shared_prefs')
rule = {'id': 'lifecycle-preserved-rule', 'name': 'Preserve across restart and update', 'enabled': True,
        'when': {'type': 'sms_received', 'options': {'contact': '15555550100'}},
        'then': [{'type': 'show_message', 'options': {'message': 'Only a synthetic emulator event can match this fixture'}}]}
rules = {'version': 1, 'rules': [rule]}
templates = {'schema': 'whenthen-templates/1', 'templates': [{'id': 'lifecycle-template', 'name': 'Preserved template', 'description': 'Emulator fixture', 'rule': dict(rule, enabled=False)}]}
write('files/rules.json', json.dumps(rules).encode())
write('files/templates.json', json.dumps(templates).encode())
write('shared_prefs/settings.xml', b'<?xml version="1.0" encoding="utf-8"?><map><boolean name="monitorWanted" value="true" /></map>')
adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
wait_for(active)
pause_in_gui()
print('Paused in the GUI; rebooting the isolated emulator.', flush=True)
adb('reboot')
time.sleep(2)
adb('wait-for-device', timeout=120)
wait_for(lambda: adb('shell', 'getprop', 'sys.boot_completed').strip() == b'1', seconds=90)
adb('shell', 'input', 'keyevent', '82')
wait_for(active)
assert any(e['detail'].startswith('Phone restarted') for e in json.loads(read('files/history.json')))
assert json.loads(read('files/rules.json')) == rules
assert json.loads(read('files/templates.json')) == templates
print('Actual reboot resumed monitoring after GUI pause; rule and template retained.', flush=True)
pause_in_gui()
adb('install', '-r', str(ROOT / 'app/build/outputs/apk/debug/app-debug.apk'))
wait_for(active)
assert any(e['detail'].startswith('App updated') for e in json.loads(read('files/history.json')))
assert json.loads(read('files/rules.json')) == rules
assert json.loads(read('files/templates.json')) == templates
result = {'device': 'Isolated API 36 emulator', 'rebootAfterGuiStop': 'passed', 'actualApkReplacementAfterGuiStop': 'passed', 'savedRulePreserved': True, 'savedTemplatePreserved': True, 'physicalPhoneChanged': False}
(ROOT / 'test-results').mkdir(exist_ok=True)
(ROOT / 'test-results/emulator-lifecycle.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2), flush=True)
