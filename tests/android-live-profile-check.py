"""Android shell regression for full production framing; isolated files and mock reload.
Run LiveRoutingCheck first. No Root or active profile access is required.
"""
from pathlib import Path
import subprocess,shlex,hashlib,uuid,sys
base='/data/local/tmp/minclash-frame-'+uuid.uuid4().hex
serial=sys.argv[1]
def run(s,data=None):
 return subprocess.run(['adb','-s',serial,'shell','-T','sh -c '+shlex.quote(s)],input=data,capture_output=True,timeout=30)
def put(name,data):
 r=run('cat > '+base+'/'+name,data);assert r.returncode==0,r.stderr
def digest(name):
 return run('sha256sum '+base+'/'+name).stdout.decode().split()[0]
run('mkdir -p '+base+'/bin')
source=b"mode: rule\nrules: ['MATCH,DIRECT']\n"
try:
 put('state',b'running\n');put('control.sock',b'')
 put('root-env.sh',b'BB=/system/bin/toybox\n')
 put('bin/socket-request',b'''#!/system/bin/sh
request=$(cat)
path=$(printf '%s' "$request" | sed -n 's/.*"path":"\\([^"]*\\)".*/\\1/p')
cp "$path" "${path%/*}/observed" || exit 1
printf 'HTTP/1.1 204 No Content\\r\\n\\r\\n'
''')
 run('chmod 755 '+base+'/bin/socket-request')
 s=(Path(__file__).resolve().parents[1]/'build/checks-0.2.3/live-script.sh').read_text().replace('/data/adb/modules/minclash',base).replace('/data/adb/minclash',base).replace('[ -S "$DATA/control.sock" ]','[ -f "$DATA/control.sock" ]')
 for count in (1,128,8000):
  saved=source+('# 保存\n'*count).encode();live=source+('# 运行副本\n'*count).encode()
  packet=(str(len(saved))+'\n'+str(len(live))+'\n'+hashlib.sha256(source).hexdigest()+'\n').encode()+saved+live
  for kind,data in [('valid',packet),('truncated',packet[:-1]),('trailing',packet+b'extra')]:
   put('config.yaml',source)
   run('rm -f '+base+'/observed')
   r=run(s,data)
   if kind=='valid':
    assert r.returncode==0,(r.stdout,r.stderr)
    assert digest('config.yaml')==hashlib.sha256(saved).hexdigest()
    assert digest('observed')==hashlib.sha256(live).hexdigest()
   else:
    assert r.returncode!=0 and b'MINCLASH_LIVE_UNCHANGED' in r.stdout,(r.stdout,r.stderr)
    assert digest('config.yaml')==hashlib.sha256(source).hexdigest()
    assert run('test ! -e '+base+'/observed').returncode==0
   assert not run('ls '+base+'/live-*').stdout.strip(), 'staged files leaked'
 print('PASS: Android full save, exact persistent/runtime UTF-8 frames at three sizes, truncated/trailing rejection before reload, cleanup.')
finally:
 run('rm -f '+base+'/config.yaml '+base+'/state '+base+'/control.sock '+base+'/root-env.sh '+base+'/bin/socket-request '+base+'/action.lock '+base+'/observed; rmdir '+base+'/bin '+base)
