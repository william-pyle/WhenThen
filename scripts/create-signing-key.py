"""Create the private, project-specific APK update key once; never print secrets."""
import pathlib, secrets, subprocess, os
root=pathlib.Path(__file__).resolve().parents[1]
tools=root/'.local/tools';props=tools/'signing.properties';key=tools/'whenthen-release.p12'
if props.exists() and key.exists():
    print('Existing WhenThen signing identity retained.')
else:
    if props.exists() or key.exists():raise RuntimeError('Incomplete existing signing identity; inspect before replacing anything.')
    password=secrets.token_urlsafe(32)
    env=os.environ.copy();env['WHENTHEN_KEY_PASSWORD']=password
    java=next((tools/'java').glob('jdk-*'))
    subprocess.run([str(java/'bin/keytool.exe'),'-genkeypair','-keystore',str(key),'-storetype','PKCS12','-alias','whenthen','-storepass:env','WHENTHEN_KEY_PASSWORD','-keypass:env','WHENTHEN_KEY_PASSWORD','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=WhenThen Personal, OU=Local App Development','-noprompt'],env=env,check=True,capture_output=True)
    props.write_text(f'storeFile={key.as_posix().replace(":",chr(92)+":")}\nstorePassword={password}\nkeyAlias=whenthen\nkeyPassword={password}\n',encoding='ascii')
    print('Created private WhenThen update key in ignored local tool storage.')
