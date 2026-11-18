#!/usr/bin/env python3
"""Compile ALL video production sources against actual public Android SDK; run host state tests."""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile
from source_contracts import check_source_contracts

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--android-sdk', type=Path, required=True,
                    help='Real android.jar or extracted public SDK class directory (API 35+)')
args = parser.parse_args()
sdk = args.android_sdk.resolve()
if not sdk.exists() or (sdk.is_dir() and not (sdk / 'android/app/Service.class').is_file()):
    parser.error('Android SDK not found or lacks real android.app.Service')
root = Path(__file__).resolve().parents[2]
tests = Path(__file__).resolve().parent
production = root / 'app/src/main/java/com/sevtinge/hyperceiler/video'
check_source_contracts(production)
with tempfile.TemporaryDirectory(prefix='hc-video-') as temp:
    output = Path(temp) / 'classes'
    output.mkdir()
    subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-Xlint:all',
                    '-classpath', str(sdk), '-d', str(output),
                    *map(str, sorted(production.glob('*.java'))),
                    *map(str, sorted(tests.glob('*Test.java')))], check=True)
    print('PASS all video production Java compile against real public Android SDK', flush=True)
    # No Android SDK stubs at runtime: exercised policy/gate production classes are Android-free.
    for name in ('VideoRecordingPolicyTest', 'VideoResourceGateTest', 'VideoMainCameraPolicyTest', 'VideoDiagnosticsTest'):
        subprocess.run(['java', '-ea', '-classpath', str(output),
                        'com.sevtinge.hyperceiler.video.' + name,
                        str(tests / 'main-camera-metadata.properties')], check=True)
