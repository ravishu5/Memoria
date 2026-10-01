#!/usr/bin/env python3
"""Recover an enabled accessibility service after Android instrumentation teardown."""
import argparse
import os
import re
from pathlib import Path
import shutil
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--keep-screen', action='store_true', help='Reconnect without opening Aevra or changing the current screen')
parser.add_argument('--serial', required=True, help='ADB serial of the intended test device')
parser.add_argument('--adb', default=shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb'))
args = parser.parse_args()
adb = [args.adb, '-s', args.serial]

def command(*parts):
    return subprocess.run(adb + list(parts), capture_output=True, text=True, check=True).stdout

service = 'dev.miniscreenpipe/dev.miniscreenpipe.CaptureService'
current = command('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services').strip()
services = [part for part in current.split(':') if part and part != 'null']
aliases = {service, 'dev.miniscreenpipe/.CaptureService'}
if not any(part in aliases for part in services):
    raise SystemExit('Aevra capture is disabled. Enable it in Android Accessibility settings first.')
others = [part for part in services if part not in aliases]
services = list(dict.fromkeys(others + [service]))
if not args.keep_screen:
    command('shell', 'am', 'start', '-n', 'dev.miniscreenpipe/.MainActivity')
command('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(others) if others else "''")
time.sleep(1)
command('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(services))
command('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
for _ in range(15):
    state = command('shell', 'dumpsys', 'activity', 'services', 'dev.miniscreenpipe')
    accessibility = command('shell', 'dumpsys', 'accessibility')
    bound = re.search(r'Bound services:\s*\{(.*?)\}', accessibility, re.S)
    crashed = re.search(r'Crashed services:\s*\{(.*?)\}', accessibility, re.S)
    labels = ('Aevra capture', 'Mini Screenpipe capture')
    is_bound = bound is not None and any(label in bound.group(1) for label in labels)
    is_crashed = crashed is not None and (service in crashed.group(1) or any(label in crashed.group(1) for label in labels))
    if 'ServiceRecord' in state and 'app=ProcessRecord' in state and is_bound and not is_crashed:
        print('Aevra reconnected and bound. The saved Start/Pause choice is preserved. Reopen Settings to refresh a cached warning.')
        break
    time.sleep(1)
else:
    raise SystemExit('Service did not reconnect. Check app crash logs.')
