"""Test the emitted production lock code in Android mksh, without Root or active config.

Run LiveRoutingCheck first, then python tests/android-live-lock-check.py SERIAL.
"""
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid

serial = sys.argv[1]
directory = '/data/local/tmp/minclash-lock-check-' + uuid.uuid4().hex

def command(script):
    return ['adb', '-s', serial, 'shell', '-T', 'sh -c ' + shlex.quote(script)]

def run(script):
    return subprocess.run(command(script), capture_output=True, text=True, timeout=15)

source = (Path(__file__).resolve().parents[1] / 'build/checks-0.2.3/live-script.sh').read_text()
prefix = source.split('failure=MINCLASH_LIVE_UNCHANGED_MODULE')[0]
prefix = prefix.replace('/data/adb/minclash', directory)
prefix = prefix.replace('. "$MODDIR/root-env.sh" || abort', 'BB=/system/bin/toybox')
prefix += '\necho ACQUIRED\n'
holder = None
try:
    assert run('mkdir -p ' + shlex.quote(directory)).returncode == 0
    legacy = run('exec 9>' + directory + '/action.lock; /system/bin/flock -n 9')
    assert legacy.returncode != 0 and 'Bad file descriptor' in legacy.stderr, legacy
    result = run(prefix)
    assert result.returncode == 0 and 'ACQUIRED' in result.stdout, result
    # The acquired lock must remain owned by the parent shell, not vanish with flock.
    holder = subprocess.Popen(command(prefix + 'sleep 2'), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    assert holder.stdout.readline().strip() == 'ACQUIRED'
    began = time.monotonic()
    result = run(prefix)
    assert result.returncode == 0 and 'ACQUIRED' in result.stdout, result
    assert time.monotonic() - began >= 1, 'lock did not remain held by the shell'
    holder.communicate(timeout=5)
    # Exercise the BusyBox fallback command shape using toybox's flock applet.
    result = run(prefix.replace('/system/bin/flock', '/nonexistent/android-flock'))
    assert result.returncode == 0 and 'ACQUIRED' in result.stdout, result
    print('PASS: reproduced old mksh bad descriptor; production lock succeeds, holds exclusivity, waits automatically and supports fallback.')
finally:
    if holder is not None:
        holder.communicate(timeout=5)
    run('rm -f ' + directory + '/action.lock; rmdir ' + directory)
