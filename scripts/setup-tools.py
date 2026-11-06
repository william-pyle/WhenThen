"""Install isolated, checksum-verified Android build tools. No global PATH changes."""
import concurrent.futures, hashlib, json, pathlib, urllib.request, zipfile, xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.local/tools'
TOOLS.mkdir(parents=True, exist_ok=True)

def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'WhenThen-build'}), timeout=120).read()

def install(name, url, checksum, algorithm, destination):
    archive = TOOLS / (name + '.zip')
    if not archive.exists():
        print('Downloading ' + name, flush=True)
        archive.write_bytes(get(url))
    actual = hashlib.new(algorithm, archive.read_bytes()).hexdigest()
    if actual.lower() != checksum.lower():
        raise RuntimeError('Checksum mismatch: ' + name)
    destination.mkdir(parents=True, exist_ok=True)
    if not (destination / '.installed').exists():
        with zipfile.ZipFile(archive) as z:
            z.extractall(destination)
        (destination / '.installed').write_text(actual)
    print('Verified and installed ' + name, flush=True)

repo = ET.fromstring(get('https://dl.google.com/android/repository/repository2-1.xml'))
jobs = []
for path in ['cmdline-tools;19.0']:
    p = next(p for p in repo.findall('remotePackage') if p.get('path') == path)
    a = next(a for a in p.findall('archives/archive') if a.findtext('host-os') == 'windows')
    jobs.append(('commandline19', 'https://dl.google.com/android/repository/' + a.findtext('complete/url'), a.findtext('complete/checksum'), 'sha1', TOOLS / 'sdk/cmdline-tools/19-unpack'))
r = json.loads(get('https://api.github.com/repos/adoptium/temurin17-binaries/releases/latest'))
j = next(a for a in r['assets'] if a['name'].startswith('OpenJDK17U-jdk_x64_windows_hotspot_') and a['name'].endswith('.zip'))
jurl = j['browser_download_url']
jhash = get(jurl + '.sha256.txt').decode().split()[0]
jobs.append(('jdk17', jurl, jhash, 'sha256', TOOLS / 'java'))
gurl = 'https://services.gradle.org/distributions/gradle-8.13-bin.zip'
jobs.append(('gradle-8.13', gurl, get(gurl + '.sha256').decode().strip(), 'sha256', TOOLS / 'gradle'))
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(lambda j: install(*j), jobs))
unpacked = TOOLS / 'sdk/cmdline-tools/19-unpack/cmdline-tools'
installed = TOOLS / 'sdk/cmdline-tools/19.0'
if unpacked.exists() and not installed.exists():
    unpacked.rename(installed)
print('Tool bootstrap complete.', flush=True)
