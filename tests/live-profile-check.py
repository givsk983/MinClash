"""Run the actual App reload shell with synthetic files and a localhost-only Mihomo.

Windows fixture shims replace the Android socket-file check and socket transport;
framing, CAS, locking decisions, HTTP response checks and atomic replacement use
the production shell emitted by LiveRoutingCheck. Never touches a phone/config.
"""
from pathlib import Path
import hashlib
import http.client
import json
import os
import socket
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / 'build/checks-0.2.3/live-fixture'
WORK.mkdir(parents=True, exist_ok=True)
git = shutil.which('git')
bash_candidates = [os.environ.get('MINCLASH_BASH'), shutil.which('bash'),
                   str(Path(git).parent.parent / 'bin/bash.exe') if git else None]
BASH = next((Path(p) for p in bash_candidates if p and Path(p).is_file()), None)
if BASH is None:
    raise RuntimeError('Install Git Bash or set MINCLASH_BASH to bash.exe.')
MOD = WORK / 'module'
DATA = WORK / 'data'
(MOD / 'bin').mkdir(parents=True, exist_ok=True)
DATA.mkdir(exist_ok=True)
CORE = WORK / 'mihomo.exe'
with zipfile.ZipFile(ROOT / 'build/downloads/mihomo-windows-amd64-v1-v1.19.33.zip') as archive:
    name = next(n for n in archive.namelist() if n.endswith('.exe'))
    CORE.write_bytes(archive.read(name))
with socket.socket() as port_socket:
    port_socket.bind(('127.0.0.1', 0))
    PORT = port_socket.getsockname()[1]

source = f'''mode: rule
log-level: silent
external-controller: 127.0.0.1:{PORT}
tun: {{enable: false}}
dns: {{enable: false}}
proxy-groups: [{{name: Test, type: select, proxies: [DIRECT, REJECT]}}]
rules: ['MATCH,DIRECT']
'''.encode()
saved = source.replace(b"['MATCH,DIRECT']", b"['PROCESS-NAME,com.example.app,REJECT', 'MATCH,DIRECT']")
runtime = saved.replace(b'mode: rule', b'mode: global')
PROFILE = DATA / 'config.yaml'
PROFILE.write_bytes(source)
(DATA / 'state').write_text('running\n')
(DATA / 'control.sock').touch()
(MOD / 'root-env.sh').write_text('''export PATH=/usr/bin:/bin
BB=bb
bb() {
    if [ "$1" = mv ] && [ "${TEST_MOVE_FAIL:-0}" = 1 ]; then return 1; fi
    command "$@"
}
lock_fd() { [ "${TEST_LOCK_DENIED:-0}" != 1 ]; }
''', newline='\n')

helper = WORK / 'transport.py'
helper.write_text('''import http.client, json, os, pathlib, sys
raw=sys.stdin.buffer.read()
header,body=raw.split(b"\\r\\n\\r\\n",1)
lines=header.decode().split("\\r\\n")
method,path,_=lines[0].split()
headers=dict(line.split(": ",1) for line in lines[1:])
assert int(headers['Content-Length'])==len(body)
payload=json.loads(body)
assert pathlib.Path(payload['path']).is_file()
with open(os.environ['TEST_CALLS'],'a') as out: out.write('reload\\n')
if os.environ.get('TEST_RESPONSE')=='bad-status':
    sys.stdout.buffer.write(b'HTTP/1.1 2040 Invalid\\r\\n\\r\\n');sys.exit(0)
conn=http.client.HTTPConnection('127.0.0.1',int(os.environ['TEST_PORT']),timeout=10)
conn.request(method,path,body,headers)
reply=conn.getresponse();data=reply.read()
if os.environ.get('TEST_RESPONSE')=='lost-reply':sys.exit(1)
sys.stdout.buffer.write(('HTTP/1.1 '+str(reply.status)+' '+reply.reason+'\\r\\nContent-Length: '+str(len(data))+'\\r\\n\\r\\n').encode()+data)
''', encoding='utf-8')
(MOD / 'bin/socket-request').write_text(f'#!/usr/bin/bash\nexec "{Path(sys.executable).as_posix()}" "{helper.as_posix()}"\n', newline='\n')
script = (ROOT / 'build/checks-0.2.3/live-script.sh').read_text()
script = script.replace('/data/adb/modules/minclash', MOD.as_posix()).replace('/data/adb/minclash', DATA.as_posix())
script = script.replace('[ -S "$DATA/control.sock" ]', '[ -f "$DATA/control.sock" ]')
SCRIPT = WORK / 'reload.sh'
SCRIPT.write_text(script, newline='\n')
subprocess.run([str(BASH), '-n', str(SCRIPT)], check=True)
CALLS = WORK / 'calls.txt'
CALLS.write_text('')
process = None
log = (WORK / 'core.log').open('wb')

def api(path, method='GET'):
    conn = http.client.HTTPConnection('127.0.0.1', PORT, timeout=3)
    conn.request(method, path)
    reply = conn.getresponse()
    data = reply.read()
    assert reply.status in (200, 204), (path, reply.status)
    return json.loads(data) if data else None

def start():
    global process
    process = subprocess.Popen([str(CORE), '-d', str(DATA), '-f', str(PROFILE)], stdout=log, stderr=log)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if process.poll() is not None: raise AssertionError('core exited; inspect fixture core.log')
        try: api('/configs'); return
        except (OSError, http.client.HTTPException): time.sleep(.05)
    raise AssertionError('fixture core did not start')

def stop():
    if process and process.poll() is None:
        process.terminate()
        process.wait(timeout=10)

def packet(new=saved, live=runtime, old=None):
    old = PROFILE.read_bytes() if old is None else old
    return (str(len(new))+'\n'+str(len(live))+'\n'+hashlib.sha256(old).hexdigest()+'\n').encode()+new+live

def invoke(data=None, **extra):
    env = dict(os.environ, TEST_PORT=str(PORT), TEST_CALLS=str(CALLS), MSYS_NO_PATHCONV='1', **extra)
    return subprocess.run([str(BASH), str(SCRIPT)], input=packet() if data is None else data,
                          capture_output=True, timeout=20, env=env)

def failed(result, marker):
    assert result.returncode != 0 and marker.encode() in result.stdout, (result.returncode, result.stdout, result.stderr)
    assert PROFILE.read_bytes() == source, 'failure changed persistent profile'
    assert not list(DATA.glob('live-*')), 'staged files leaked'

try:
    start()
    pid = process.pid
    began = time.monotonic()
    result = invoke()
    elapsed = time.monotonic() - began
    assert result.returncode == 0, (result.stdout, result.stderr)
    assert process.pid == pid and process.poll() is None, 'core restarted'
    assert PROFILE.read_bytes() == saved, 'persistent profile differs from framed bytes'
    assert api('/configs')['mode'] == 'global', 'session mode lost'
    assert any(r.get('payload') == 'com.example.app' for r in api('/rules')['rules']), 'rule was not applied'
    api('/connections', 'DELETE')
    assert not list(DATA.glob('live-*'))
    print(f'PASS: real Mihomo reload, same PID, mode/rules, DELETE connections and atomic persistence ({elapsed*1000:.0f} ms; Windows synthetic fixture only).')

    # Return to the known old file and core, emulating the production cold recovery.
    stop(); PROFILE.write_bytes(source); start()
    count = CALLS.read_text().count('reload')
    failed(invoke(packet(old=b'outdated')), 'MINCLASH_LIVE_UNCHANGED')
    failed(invoke(TEST_LOCK_DENIED='1'), 'MINCLASH_LIVE_UNCHANGED')
    failed(invoke(packet()[:-1]), 'MINCLASH_LIVE_UNCHANGED')
    failed(invoke(packet()+b'extra'), 'MINCLASH_LIVE_UNCHANGED')
    failed(invoke(b'2097153\n1\ninvalid\nx'), 'MINCLASH_LIVE_UNCHANGED')
    failed(invoke(b'not-a-length\n1\ninvalid\nx'), 'MINCLASH_LIVE_UNCHANGED')
    assert CALLS.read_text().count('reload') == count, 'preflight failure reached live API'
    failed(invoke(packet(b'invalid: [', b'invalid: [')), 'MINCLASH_LIVE_UNCERTAIN')
    assert api('/configs')['mode'] == 'rule', 'parse failure mutated live config'
    failed(invoke(TEST_RESPONSE='bad-status'), 'MINCLASH_LIVE_UNCERTAIN')
    for failure in ({'TEST_RESPONSE': 'lost-reply'}, {'TEST_MOVE_FAIL': '1'}):
        failed(invoke(**failure), 'MINCLASH_LIVE_UNCERTAIN')
        assert api('/configs')['mode'] == 'global', 'fixture did not reach ambiguous state'
        stop(); start()
        assert api('/configs')['mode'] == 'rule', 'cold recovery did not restore old persistent config'
    # Large UTF-8 payloads also check that the two head commands don't consume each other's bytes.
    padded = saved + ('# 中文分流配置\n' * 8000).encode()
    live_padded = padded.replace(b'mode: rule', b'mode: global')
    result = invoke(packet(padded, live_padded))
    assert result.returncode == 0 and PROFILE.read_bytes() == padded, (result.stdout, result.stderr)
    print('PASS: stale checksum, lock denial, truncated/trailing input, rejected YAML, invalid HTTP status, lost reply, persistence failure, cold recovery, large UTF-8 frames and cleanup.')
finally:
    stop()
    log.close()
