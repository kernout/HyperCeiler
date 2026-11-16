#!/usr/bin/env python3
"""Compile the four production camera sources and execute their pure Java checks."""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--android-classes', required=True, type=Path,
                    help='directory of extracted public Android API 35+ classes')
parser.add_argument('--hidden-api-jar', required=True, type=Path,
                    help='HiddenApiBypass 6.1 classes.jar (extracted from its AAR)')
args = parser.parse_args()
android = args.android_classes.resolve()
hidden = args.hidden_api_jar.resolve()
if not (android / 'android/app/Service.class').is_file():
    parser.error('--android-classes must contain android/app/Service.class')
if not hidden.is_file():
    parser.error('--hidden-api-jar does not exist')
root = Path(__file__).resolve().parents[2]
tests = Path(__file__).resolve().parent
production = root / 'app/src/main/java/com/sevtinge/hyperceiler/camera'
with tempfile.TemporaryDirectory(prefix='hc-screen-off-camera-') as temp:
    output = Path(temp) / 'classes'
    output.mkdir()
    subprocess.run([
        'javac', '--release', '17', '-encoding', 'UTF-8', '-classpath',
        os.pathsep.join(map(str, [android, hidden])), '-d', str(output),
        *map(str, sorted(production.glob('*.java'))),
        str(tests / 'ScreenOffCameraSelectorTest.java'),
        str(tests / 'ScreenOffServiceAdmissionTest.java'),
    ], check=True)
    print('PASS production camera sources compile (public Android API classes)', flush=True)
    for name in ('ScreenOffCameraSelectorTest', 'ScreenOffServiceAdmissionTest'):
        subprocess.run([
            'java', '-classpath', os.pathsep.join(map(str, [output, android, hidden])),
            'com.sevtinge.hyperceiler.camera.' + name,
        ], check=True)
