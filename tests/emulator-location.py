"""Verify production LocationManager registration and place transitions on the emulator."""
import pathlib,subprocess,time,json,uuid,re,xml.etree.ElementTree as E,os
ROOT=pathlib.Path(__file__).resolve().parents[1]
ADB=os.environ.get('ADB',str(pathlib.Path(os.environ.get('ANDROID_HOME', ROOT/'.local/tools/sdk'))/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb')))
(ROOT/'test-results').mkdir(exist_ok=True)

def adb(*args,input=None):
    p=subprocess.run([ADB,'-s','emulator-5554',*args],input=input,capture_output=True,timeout=40)
    if p.returncode:raise RuntimeError(p.stderr.decode(errors='replace'))
    return p.stdout
def rules():
    result=[]
    for name,kind in [('Geo entered','waypoint_enter'),('Geo left','waypoint_exit')]:
        result.append({'id':str(uuid.uuid4()),'name':name,'enabled':True,'priority':0,'cooldownSeconds':10,'when':{'type':kind,'options':{'waypoint':'Emulator test place','latitude':'40','longitude':'-88','radiusFeet':'250'}},'then':[{'type':'show_message','options':{'message':name}}]})
    return {'version':1,'rules':result}
assert adb('shell','getprop','ro.kernel.qemu').strip()==b'1', 'Isolated emulator required'
for permission in ['POST_NOTIFICATIONS','ACCESS_COARSE_LOCATION','ACCESS_FINE_LOCATION','ACCESS_BACKGROUND_LOCATION']:
    adb('shell','pm','grant','com.jazcosolutions.whenthen','android.permission.'+permission)
adb('shell','am','force-stop','com.jazcosolutions.whenthen')
adb('shell','run-as','com.jazcosolutions.whenthen','sh','-c',"'cat > files/rules.json'",input=json.dumps(rules()).encode())
adb('emu','geo','fix','-88','40.003')
adb('shell','am','start','-n','com.jazcosolutions.whenthen/.MainActivity');time.sleep(1)
adb('shell','uiautomator','dump','/sdcard/whenthen-ui.xml');tree=E.fromstring(adb('shell','cat','/sdcard/whenthen-ui.xml'))
n=next((n for n in tree.iter('node') if n.get('text')=='Start monitoring'),None)
if n is not None:
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
print('Establishing outside baseline',flush=True);time.sleep(20)
print('Entering the 250-foot boundary',flush=True)
for _ in range(9):adb('emu','geo','fix','-88','40');time.sleep(5)
print('Leaving the boundary',flush=True)
for _ in range(9):adb('emu','geo','fix','-88','40.003');time.sleep(5)
history=json.loads(adb('shell','run-as','com.jazcosolutions.whenthen','cat','files/history.json'));observed={e['title'] for e in history if e['status']=='dispatched'};expected={'Geo entered','Geo left'}
result={'device':'Android 16 / API 36 emulator','expected':sorted(expected),'observed':sorted(observed&expected),'passed':expected.issubset(observed),'method':'Actual emulator GPS fixes through production LocationManager; baseline, accurate repeated inside fixes and accurate repeated outside fixes','history':history}
(ROOT/'test-results/emulator-location.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k!='history'},indent=2))
if not result['passed']:raise SystemExit(1)
