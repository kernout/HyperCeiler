#!/usr/bin/env python3
"""Compile and execute the production hook against deterministic OEM/Android fixtures.
Optional --android-classes / --android-jar compiles against the real public Android SDK.
No source-string assertions, Android device changes, Gradle or downloads.
"""
from pathlib import Path
import argparse
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--android-jar', type=Path)
parser.add_argument('--android-classes', type=Path, help='extracted public Android SDK class directory')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
tests = Path(__file__).resolve().parent
rule = root / 'library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/systemframework/volume/ScreenOffCamera.java'
sources = {
'android/app/ActivityManager.java': '''package android.app;
public class ActivityManager { public static int user = 10; public static int getCurrentUser() { return user; } }''',
'android/content/ComponentName.java': '''package android.content;
public class ComponentName { public final String pkg, cls; public ComponentName(String p, String c) { pkg=p; cls=c; } }''',
'android/content/Intent.java': '''package android.content;
public class Intent { public final String action; public ComponentName component;
public final java.util.Map<String,Object> extras=new java.util.HashMap<>();
public Intent putExtra(String key,boolean value) { extras.put(key,value); return this; }
public Intent putExtra(String key,long value) { extras.put(key,value); return this; }
public Intent(String a) { action=a; } public Intent setComponent(ComponentName c) { component=c; return this; } }''',
'android/content/Context.java': '''package android.content;
import android.os.*;
public class Context {
 public static final String POWER_SERVICE="power";
 public android.hardware.SensorPrivacyManager privacy=new android.hardware.SensorPrivacyManager();
 public PowerManager power = new PowerManager(); public int starts; public Intent lastIntent;
 public UserHandle lastUser; public boolean throwStart, nullStart; public long identityAtStart;
 public Object getSystemService(String name) { return power; }
 public ComponentName startForegroundServiceAsUser(Intent i, UserHandle u) {
  starts++; lastIntent=i; lastUser=u; identityAtStart=Binder.identity;
  if(throwStart) throw new SecurityException("FGS rejected");
  return nullStart ? null : i.component;
 }
}''',
'android/hardware/SensorPrivacyManager.java': """package android.hardware;
import android.content.Context; import android.os.Binder;
public class SensorPrivacyManager {
 public boolean all, software, hardware, throwGlobal, throwCombined, switchUser;
 public int globalCalls, combinedCalls; public long identity;
 public static SensorPrivacyManager getInstance(Context context) { return context.privacy; }
 public boolean isAllSensorPrivacyEnabled() {
  globalCalls++; identity=Binder.identity;
  if(throwGlobal) throw new SecurityException("OBSERVE_SENSOR_PRIVACY denied"); return all;
 }
 public boolean areAnySensorPrivacyTogglesEnabled(int sensor) {
  if(sensor!=2) throw new AssertionError("Must query CAMERA");
  combinedCalls++; identity=Binder.identity;
  if(throwCombined) throw new IllegalStateException("privacy service dead");
  if(switchUser) android.app.ActivityManager.user++;
  return software || hardware;
 }
}""",
'android/os/Message.java': 'package android.os; public class Message {}',
'android/view/KeyEvent.java': 'package android.view; public class KeyEvent {}',
'android/os/PowerManager.java': '''package android.os;
public class PowerManager { public boolean interactive; public boolean isInteractive() { return interactive; } }''',
'android/os/Build.java': '''package android.os;
public class Build { public static class VERSION { public static int SDK_INT=37; } }''',
'android/os/SystemClock.java': '''package android.os;
public class SystemClock { public static long now, elapsed; public static long uptimeMillis() { return now; }
public static long elapsedRealtime() { return elapsed; } }''',
'android/os/Binder.java': '''package android.os;
public class Binder { public static long identity=99; public static int clears, restores;
public static long clearCallingIdentity() { clears++; long old=identity; identity=1000; return old; }
public static void restoreCallingIdentity(long token) { restores++; identity=token; } }''',
'android/os/UserHandle.java': '''package android.os;
public class UserHandle { public final int id; private UserHandle(int i) { id=i; }
public static UserHandle of(int id) { return new UserHandle(id); } }''',
'android/os/Handler.java': '''package android.os;
import java.util.*;
public class Handler { public final Queue<Runnable> queue=new ArrayDeque<>(); public boolean reject, throwing;
public boolean post(Runnable r) { if(throwing) throw new IllegalStateException("dead handler");
 if(reject) return false; queue.add(r); return true; }
public void drain() { while(!queue.isEmpty()) queue.remove().run(); } }''',
'io/github/libxposed/api/XposedInterface.java': '''package io.github.libxposed.api;
public interface XposedInterface { interface HookHandle { void unhook(); } }''',
'io/github/lingqiqi5211/ezhooktool/xposed/common/HookParam.java': '''package io.github.lingqiqi5211.ezhooktool.xposed.common;
public class HookParam { private final Object target; private final Object[] args; public boolean intercepted; public Object result;
public HookParam(Object t, Object... a) { target=t; args=a; } public Object getThisObject() { return target; }
public Object[] getArgs() { return args; }
public void setResult(Object value) { intercepted=true; result=value; } }''',
'io/github/lingqiqi5211/ezhooktool/xposed/java/IMethodHook.java': '''package io.github.lingqiqi5211.ezhooktool.xposed.java;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
public interface IMethodHook { default void before(HookParam p) {} default void after(HookParam p) {} }''',
'com/sevtinge/hyperceiler/common/log/XposedLog.java': '''package com.sevtinge.hyperceiler.common.log;
import java.util.*;
public class XposedLog { public static final List<String> logs=new ArrayList<>();
public static void i(String t,String p,String m) { logs.add(m); }
public static void w(String t,String p,String m) { logs.add(m); }
public static void w(String t,String p,String m,Throwable e) { logs.add(m); }
public static void e(String t,String p,String m) { logs.add(m); }
public static void e(String t,String p,String m,Throwable e) { logs.add(m); } }''',
'com/sevtinge/hyperceiler/common/utils/PrefsBridge.java': '''package com.sevtinge.hyperceiler.common.utils;
public class PrefsBridge { public static Boolean enabled; public static boolean throwing;
public static boolean getBoolean(String key,boolean fallback) {
if(!key.equals("system_framework_volume_screen_off_camera")) throw new AssertionError(key);
if(throwing) throw new IllegalStateException("prefs unavailable"); return enabled==null ? fallback : enabled; } }''',
'com/sevtinge/hyperceiler/libhook/base/BaseHook.java': '''package com.sevtinge.hyperceiler.libhook.base;
import java.lang.reflect.*; import java.util.*;
import io.github.libxposed.api.XposedInterface;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
public abstract class BaseHook {
 public final String TAG=getClass().getSimpleName(); public abstract void init();
 public static final Map<Method, IMethodHook> hooks=new HashMap<>();
 public static boolean missing, mismatch, throwDeopt, missingPrivacy, badPrivacyApi; public static String failHook, failDeopt;
 public static final List<Method> deoptimized=new ArrayList<>();
 public static boolean deoptimize(Method method) {
  if(throwDeopt) throw new IllegalStateException("deopt failed");
  deoptimized.add(method); return !method.getName().equals(failDeopt);
 }
 public ClassLoader getClassLoader() { return getClass().getClassLoader(); }
 public static Class<?> findClassIfExists(String name,ClassLoader loader) {
  if(missing) return null;
  if(name.equals("android.hardware.SensorPrivacyManager")) {
   if(missingPrivacy) return null;
   if(badPrivacyApi) return String.class;
  }
  if(mismatch && name.endsWith("VolumeDownKeyRule")) return IncompatibleRule.class;
  try { return Class.forName(name,false,loader); } catch(Exception e) { return null; }
 }
 public static XposedInterface.HookHandle hookMethod(Method method,IMethodHook callback) {
  if(method.getName().equals(failHook)) return null;
  hooks.put(method,callback); return () -> hooks.remove(method);
 }
 public static Object call(Object rule,String name,Object... args) throws Exception {
  Class<?>[] types=name.equals("onMiuiMultiPress")
    ? new Class<?>[]{Object.class,long.class,int.class,int.class} : new Class<?>[0];
  Method m=rule.getClass().getDeclaredMethod(name,types); m.setAccessible(true);
  HookParam p=new HookParam(rule,args); IMethodHook h=hooks.get(m);
  if(h!=null) h.before(p); Object result=p.intercepted ? p.result : m.invoke(rule,args);
  if(h!=null) h.after(p); return result;
 }
 public static class IncompatibleRule extends com.android.server.policy.MiuiSingleKeyRule {
  public IncompatibleRule() { super(null,null); }
 }
}''',
'com/android/server/policy/MiuiSingleKeyRule.java': '''package com.android.server.policy;
import android.content.Context; import android.os.Handler;
public class MiuiSingleKeyRule { private final Context mContext; private final Handler mHandler;
 public MiuiSingleKeyRule(Context c,Handler h) { mContext=c; mHandler=h; }
 public int getMaxMultiPressCount() { return 1; }
 public void onKeyGesture(SingleKeyGestureEvent event) {}
}''',
'com/android/server/policy/SingleKeyGestureEvent.java': 'package com.android.server.policy; public class SingleKeyGestureEvent {}',
'com/android/server/policy/SingleKeyGestureDetector.java': """package com.android.server.policy;
import android.view.KeyEvent; import android.os.Message;
public class SingleKeyGestureDetector {
 public static class SingleKeyRule {}
 private void startDelayedPress(SingleKeyRule rule,int count,int display) {}
 private void cancelDelayedPress(SingleKeyRule rule,int count) {}
 private void interceptKeyDown(KeyEvent event) {}
 private boolean interceptKeyUp(KeyEvent event) { return true; }
 void interceptKey(KeyEvent event,boolean interactive,int display) {}
 public static class KeyHandler { public void handleMessage(Message msg) {} }
}""",
'com/android/server/input/shortcut/singlekeyrule/VolumeDownKeyRule.java': '''package com.android.server.input.shortcut.singlekeyrule;
import android.content.Context; import android.os.Handler;
public class VolumeDownKeyRule extends com.android.server.policy.MiuiSingleKeyRule {
 // Deliberately shadow base fields to catch accidental subclass field access.
 private final Context mContext=null; private final Handler mHandler=null; private int mPolicyFlag;
 public boolean audio, subscreen, brokenAudio, brokenSubscreen; public int nativeCalls, maxCalls, nonDoubleCalls;
 public VolumeDownKeyRule(Context c,Handler h) { super(c,h); }
 public void flags(int flags) { mPolicyFlag=flags; }
 private boolean isAudioActive() { if(brokenAudio) throw new IllegalStateException(); return audio; }
 private boolean isCameraShowInSubscreen() { if(brokenSubscreen) throw new IllegalStateException(); return subscreen; }
 // Simulate ART having inlined the private camera method into this branch.
 // No volumeDownKeyDoubleClick method exists for the hook to intercept.
 protected void onMiuiMultiPress(Object event,long downTime,int count,int display) {
  if(count==2) nativeCalls++; else nonDoubleCalls++;
 }
 protected int getMiuiMaxMultiPressCount() { maxCalls++; return 1; }
}''',
}
with tempfile.TemporaryDirectory(prefix='hc-screen-off-camera-') as temp:
    temp = Path(temp)
    paths = []
    for name, content in sources.items():
        path = temp / 'src' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        paths.append(path)
    javac, java = shutil.which('javac'), shutil.which('java')
    if not javac or not java:
        raise SystemExit('A JDK is required (javac/java not found)')
    android_api = args.android_classes or args.android_jar
    if android_api:
        # Exclude all Android and OEM fixtures to expose accidental hidden-API calls.
        api_paths = [p for p in paths if '/android/' not in str(p.relative_to(temp / 'src'))
                     and not str(p.relative_to(temp / 'src')).startswith('android/')
                     and p.name != 'BaseHook.java']
        api_base = temp / 'api-src/com/sevtinge/hyperceiler/libhook/base/BaseHook.java'
        api_base.parent.mkdir(parents=True)
        api_base.write_text('''package com.sevtinge.hyperceiler.libhook.base;
import java.lang.reflect.Method; import io.github.libxposed.api.XposedInterface;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;
public abstract class BaseHook {
public final String TAG=getClass().getSimpleName(); public abstract void init();
public ClassLoader getClassLoader() { return getClass().getClassLoader(); }
public static Class<?> findClassIfExists(String n,ClassLoader l) { return null; }
public static XposedInterface.HookHandle hookMethod(Method m,IMethodHook h) { return null; }
public static boolean deoptimize(Method m) { return true; }
}''')
        subprocess.run([javac, '-encoding', 'UTF-8', '-cp', str(android_api), '-d', str(temp / 'api-classes')]
                       + [str(p) for p in api_paths + [api_base, rule]], check=True)
        print('PASS public Android SDK API compile', flush=True)
    subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(temp / 'classes')]
                   + [str(p) for p in paths + [rule, tests / 'ScreenOffCameraHookTest.java']], check=True)
    subprocess.run([java, '-ea', '-cp', str(temp / 'classes'), 'ScreenOffCameraHookTest'], check=True)
