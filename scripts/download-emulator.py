"""Resumable range download for the official large emulator image."""
import concurrent.futures, hashlib, pathlib, urllib.request, zipfile, xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]/'.local/tools'
URL='https://dl.google.com/android/repository/sys-img/google_apis/x86_64-36_r07.zip'
SIZE=1895447397
SHA1='c6bf44bdcd885bb902b4ba752d111a073ad7a817'
CHUNK=16*1024*1024
parts=ROOT/'image-parts';parts.mkdir(parents=True,exist_ok=True)
def part(i):
    start=i*CHUNK;end=min(SIZE,start+CHUNK)-1;path=parts/str(i)
    if path.exists() and path.stat().st_size==end-start+1:return
    for attempt in range(4):
        try:
            req=urllib.request.Request(URL,headers={'Range':f'bytes={start}-{end}'})
            with urllib.request.urlopen(req,timeout=90) as response:
                assert response.status==206
                data=response.read();assert len(data)==end-start+1
                path.write_bytes(data)
            return
        except Exception:
            if attempt==3:raise
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
    for i,_ in enumerate(pool.map(part,range((SIZE+CHUNK-1)//CHUNK))):
        if i%12==0:print(f'Image download: {min(SIZE,(i+1)*CHUNK)*100//SIZE}%',flush=True)
archive=ROOT/'android36-image.zip'
with archive.open('wb') as out:
    for i in range((SIZE+CHUNK-1)//CHUNK):out.write((parts/str(i)).read_bytes())
h=hashlib.sha1()
with archive.open('rb') as f:
    while b:=f.read(8*1024*1024):h.update(b)
assert h.hexdigest()==SHA1,'Emulator image checksum mismatch'
target=ROOT/'sdk/system-images/android-36/google_apis';target.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(archive) as z:z.extractall(target)
# The official image includes source.properties; avdmanager imports that metadata.
# Do not rewrite namespace-qualified package.xml with an incomplete XML namespace map.
print('Android 36 image verified and extracted.',flush=True)
