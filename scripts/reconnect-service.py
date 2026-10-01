#!/usr/bin/env python3
"""Recover an enabled accessibility service after Android instrumentation teardown."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True, help='ADB serial of the intended test device')
parser.add_argument('--adb', default=shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb'))
args = parser.parse_args()
adb = [args.adb, '-s', args.serial]

def command(*parts):
    return subprocess.run(adb + list(parts), capture_output=True, text=True, check=True).stdout

service = 'dev.miniscreenpipe/dev.miniscreenpipe.CaptureService'
current = command('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services').strip()
services = [part for part in current.split(':') if part and part != 'null']
if service not in services:
    raise SystemExit('Mini Screenpipe is disabled. Enable it in Android Accessibility settings first.')
others = [part for part in services if part != service]
command('shell', 'am', 'start', '-n', 'dev.miniscreenpipe/.MainActivity')
command('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(others) if others else "''")
time.sleep(1)
command('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(services))
command('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
for _ in range(15):
    state = command('shell', 'dumpsys', 'activity', 'services', 'dev.miniscreenpipe')
    if 'ServiceRecord' in state and 'app=ProcessRecord' in state:
        print('Mini Screenpipe reconnected. Recording starts paused. Reopen Settings to refresh a cached warning.')
        break
    time.sleep(1)
else:
    raise SystemExit('Service did not reconnect. Check app crash logs.')
