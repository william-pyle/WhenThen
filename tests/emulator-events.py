"""Exercise Android receivers on the isolated emulator, never on the owner's phone."""
import json,pathlib,subprocess,time,xml.etree.ElementTree as ET,re,os
ROOT=pathlib.Path(__file__).resolve().parents[1]
ADB=os.environ.get('ADB',str(pathlib.Path(os.environ.get('ANDROID_HOME', ROOT/'.local/tools/sdk'))/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb')))
(ROOT/'test-results').mkdir(exist_ok=True)

def adb(*args):
    result=subprocess.run([ADB,'-s','emulator-5554',*args],capture_output=True,timeout=60)
    if result.returncode:raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout
def ui():
    adb('shell','uiautomator','dump','/sdcard/whenthen-ui.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/whenthen-ui.xml'))
def tap(text):
    for n in ui().iter('node'):
        if n.get('text')==text:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.6);return
    raise RuntimeError('Missing UI control: '+text)
def history():return json.loads(adb('shell','run-as','com.jazcosolutions.whenthen','cat','files/history.json'))
assert adb('shell','getprop','ro.kernel.qemu').strip()==b'1', 'Isolated emulator required'
fixture=adb('shell','am','instrument','-w','-e','class','com.jazcosolutions.whenthen.RuntimeTest#prepareHostIntegrationFixtures','com.jazcosolutions.whenthen.test/androidx.test.runner.AndroidJUnitRunner').decode(errors='replace')
assert 'OK (1 test)' in fixture, fixture
adb('shell','am','start','-n','com.jazcosolutions.whenthen/.MainActivity');time.sleep(1)
texts=[n.get('text') for n in ui().iter('node')]
if 'Start monitoring' in texts:tap('Start monitoring')
time.sleep(4)
adb('emu','sms','send','+15551234567','HELP');time.sleep(1.5)
adb('emu','gsm','call','+15551234567');time.sleep(2);adb('emu','gsm','cancel','+15551234567');time.sleep(3)
adb('emu','gsm','call','+15551234567');time.sleep(2);adb('emu','gsm','cancel','+15551234567');time.sleep(3)
adb('shell','svc','wifi','disable');time.sleep(5)
adb('shell','svc','wifi','enable');time.sleep(10)
entries=history();dispatched={e['title'] for e in entries if e['status']=='dispatched'}
expected={'Incoming call','Repeated calls','Missed call','Incoming text','Exact emergency text','Wi-Fi left','Wi-Fi joined'}
result={'device':'Android 16 / API 36 isolated emulator','method':'Real emulator telephony/SMS console events and Wi-Fi adapter transitions through production Android receivers','expected':sorted(expected),'observed':sorted(dispatched),'missing':sorted(expected-dispatched),'passed':expected.issubset(dispatched),'history':entries}
(ROOT/'test-results/emulator-events.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k!='history'},indent=2),flush=True)
if not result['passed']:raise SystemExit(1)
