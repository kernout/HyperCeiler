#!/usr/bin/env python3
"""Compile the actual five hooks against real Android 35 / EzHook / Xposed APIs.
Only project-local collaborators are fixtures; android.*, EzHook and Xposed never
come from stubs. Override dependency locations with VIDEO_CHORD_API_CP if needed.
"""
import os
from pathlib import Path
import runpy
import subprocess
import tempfile

here = Path(__file__).resolve().parent
x = runpy.run_path(str(here / 'run.py'))
cp = os.environ.get('VIDEO_CHORD_API_CP', os.pathsep.join([
    '/workspace/power-settings-compile/android-35.jar',
    '/workspace/power-settings-compile/hook-xposed-102-1.3.0.jar',
    '/workspace/native-power-inspect/test-deps/api-102.0.0.jar',
    '/opt/eta/apk-analysis/current/jadx/lib/jadx-1.5.6-all.jar',
]))
for entry in cp.split(os.pathsep):
    if not Path(entry).is_file():
        raise SystemExit('Missing real API dependency: ' + entry)
with tempfile.TemporaryDirectory(prefix='video-chord-real-api-') as t:
    t = Path(t)
    paths = []
    for name, text in x['sources'].items():
        if not name.startswith('com/sevtinge/') or name.endswith('VolumeVideoChord.java'):
            continue
        if name.endswith('BaseHook.java'):
            text = '''package com.sevtinge.hyperceiler.libhook.base;
import java.lang.reflect.Method;import io.github.libxposed.api.XposedInterface;import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;
public abstract class BaseHook {public final String TAG=getClass().getSimpleName();public abstract void init();public ClassLoader getClassLoader(){return getClass().getClassLoader();}public static Class<?> findClassIfExists(String s,ClassLoader l){return null;}public static XposedInterface.HookHandle hookMethod(Method m,IMethodHook h){return null;}public static boolean deoptimize(Method m){return true;}}
'''
        p = t / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
        paths.append(p)
    subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', cp,
                    '-d', str(t / 'out'), *map(str, paths + x['production'])], check=True)
print('PASS five production hooks: real Android35 / EzHook / Xposed102 compilation')
