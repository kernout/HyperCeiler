#!/usr/bin/env python3
"""Reproduce 1e90e4a's real split-input failure using its actual hook/state.
Read-only git show; never checkout, patch, build or write the original sources.
"""
from pathlib import Path
import runpy
import subprocess
import tempfile

here = Path(__file__).resolve().parent
x = runpy.run_path(str(here / 'run.py'))
root = x['root']
base = x['base']
revision = '1e90e4a3fc5f9b65fd406246fab791a1a38ca3c5'
with tempfile.TemporaryDirectory(prefix='video-chord-old-split-') as t:
    t = Path(t)
    paths = []
    for name, text in x['sources'].items():
        p = t / 'src' / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
        paths.append(p)
    for source in x['production']:
        if source.name in ('VolumeVideoChord.java', 'VolumeVideoChordState.java', 'ScreenOffRecorder.java'):
            text = subprocess.check_output(['git', 'show', revision + ':' + str(source.relative_to(root))], cwd=root, text=True)
            p = t / source.name
            p.write_text(text)
            paths.append(p)
        else:
            paths.append(source)
    test = t / 'OldSplitRegression.java'
    test.write_text('''import android.content.Context;import android.os.*;import android.view.*;
import com.android.server.policy.MiuiPhoneWindowManager;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.rules.systemframework.volume.VolumeVideoChord;
public class OldSplitRegression {
 static void key(MiuiPhoneWindowManager p,int k,int a,long d,long at)throws Exception{
  SystemClock.now=at;SystemClock.elapsed=at+89000;
  BaseHook.call(p,"interceptKeyBeforeQueueing",new KeyEvent(k,a,d,at),0);
 }
 public static void main(String[] args)throws Exception{
  SplitInputFixture.install();
  PrefsBridge.values.put("system_framework_volume_video_chord",true);
  android.app.ActivityManager.user=10;new VolumeVideoChord().init();
  Context c=new Context();Handler h=new Handler();MiuiPhoneWindowManager p=new MiuiPhoneWindowManager(c,h);
  // Give the OLD double-chord recognizer its required two complete chords.
  for(long t:new long[]{1100,1300}){
   key(p,24,0,t,t);key(p,25,0,t+20,t+20);key(p,24,1,t,t+30);key(p,25,1,t+20,t+40);
  }
  h.drain();if(c.starts!=0||p.mSingleKeyGestureDetector.resets!=0)throw new AssertionError("old split fixture unexpectedly accepted");
  if(BaseHook.hooks.isEmpty())throw new AssertionError("hook failed to install: not a valid reproduction");
  System.out.println("PASS old 1e90e4a regression reproduced: installed, 2 split chords, 0 claims, 0 FGS");
 }
}''')
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(t / 'out'), *map(str, paths + [test])], check=True)
    subprocess.run(['java', '-ea', '-cp', str(t / 'out'), 'OldSplitRegression'], check=True)
