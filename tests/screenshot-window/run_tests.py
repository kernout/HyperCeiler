#!/usr/bin/env python3
"""Host callback tests; does not validate native SurfaceFlinger composition."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
rule_dir = root / 'library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/systemframework/display'
sources = {
'android/util/Log.java': '''package android.util; public class Log { public static int i(String t,String m){return 0;} public static int e(String t,String m,Throwable e){return 0;} }''',
'android/os/Build.java': '''package android.os; public class Build { public static class VERSION { public static int SDK_INT=37; } }''',
'android/os/Binder.java': '''package android.os; public class Binder { public static int uid=10197; public static int getCallingUid(){return uid;} }''',
'android/content/pm/PackageManager.java': '''package android.content.pm; public class PackageManager { public String[] getPackagesForUid(int uid){return uid==10197?new String[]{"com.android.systemui"}:uid==10151?new String[]{"com.miui.screenshot"}:new String[]{"other.app"};} }''',
'android/content/Context.java': '''package android.content; public class Context { public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();} }''',
'android/view/WindowManager.java': '''package android.view; public class WindowManager { public static class LayoutParams { public int type; public String packageName; public LayoutParams(int t,String p){type=t;packageName=p;} } }''',
'android/view/SurfaceControl.java': '''package android.view;
import java.util.*;
public class SurfaceControl {
 public static final List<SurfaceControl> copies=new ArrayList<>();
 public final String name; public boolean valid=true; public final SurfaceControl original;
 public SurfaceControl(String n){name=n;original=null;}
 public SurfaceControl(SurfaceControl s,String callsite){name=s.name;original=s;copies.add(this);}
 public boolean isValid(){return valid;} public void release(){valid=false;}
}''',
'io/github/lingqiqi5211/ezhooktool/xposed/common/HookParam.java': '''package io.github.lingqiqi5211.ezhooktool.xposed.common;
public class HookParam { private final Object receiver; private final Object[] args;
 public HookParam(Object o,Object[] a){receiver=o;args=a;} public Object getThisObject(){return receiver;} public Object[] getArgs(){return args;} }''',
'io/github/lingqiqi5211/ezhooktool/xposed/java/IMethodHook.java': '''package io.github.lingqiqi5211.ezhooktool.xposed.java;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
public interface IMethodHook { default void before(HookParam p){} default void after(HookParam p){} }''',
'com/sevtinge/hyperceiler/common/log/XposedLog.java': '''package com.sevtinge.hyperceiler.common.log;
public class XposedLog { public static void i(String t,String p,String m){} public static void w(String t,String p,String m,Throwable e){throw new AssertionError(m,e);} }''',
'com/sevtinge/hyperceiler/common/utils/PrefsBridge.java': '''package com.sevtinge.hyperceiler.common.utils;
public class PrefsBridge { public static boolean statusbar,overlay,freeform; public static boolean getBoolean(String s){return s.endsWith("hide_icon")?statusbar:s.endsWith("overlay")?overlay:freeform;} }''',
'com/sevtinge/hyperceiler/libhook/base/BaseHook.java': '''package com.sevtinge.hyperceiler.libhook.base;
import java.lang.reflect.*; import java.util.*;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
public abstract class BaseHook {
 public abstract void init(); public static final Map<String,IMethodHook> hooks=new HashMap<>();
 public static Class<?> findClass(String n){try{return Class.forName(n);}catch(Exception e){throw new RuntimeException(e);}}
 public static class Handle { final String k; Handle(String k){this.k=k;} public void unhook(){hooks.remove(k);} }
 public static Handle findAndHookMethod(Class<?> c,String m,Object... args){String k=c.getName()+"#"+m;hooks.put(k,(IMethodHook)args[args.length-1]);return new Handle(k);}
 public static Object invoke(Object o,String name,Class<?>[] types,Object... args) throws Exception {
  IMethodHook h=hooks.get(o.getClass().getName()+"#"+name); HookParam p=new HookParam(o,args);
  if(h!=null)h.before(p);
  try{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
  catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}
  finally{if(h!=null)h.after(p);}
 }
}''',
'android/window/ScreenCaptureInternal.java': '''package android.window;
import android.view.SurfaceControl;
public class ScreenCaptureInternal {
 public static class ScreenCaptureListener {}
 public static class CaptureArgs { public static class Builder { private SurfaceControl[] mExcludeLayers;
  public void setExcludeLayers(SurfaceControl[] s){mExcludeLayers=s;}
  public SurfaceControl[] exclusions(){return mExcludeLayers;}
 } }
 public static class LayerCaptureArgs extends CaptureArgs { public final SurfaceControl[] surfaces;
  LayerCaptureArgs(SurfaceControl[] s){surfaces=s;}
  public static class Builder extends CaptureArgs.Builder { public LayerCaptureArgs build(){return new LayerCaptureArgs(exclusions());} }
 }
}''',
'com/android/server/wm/ConfigurationContainer.java': '''package com.android.server.wm; class ConfigurationContainer { int mode=1; int getWindowingMode(){return mode;} }''',
'com/android/server/wm/WindowContainer.java': '''package com.android.server.wm;
import android.view.SurfaceControl; import java.util.*; import java.util.function.Consumer;
public class WindowContainer extends ConfigurationContainer {
 public SurfaceControl surface; public final List<WindowState> windows=new ArrayList<>();
 public SurfaceControl getSurfaceControl(){return surface;}
 void forAllWindows(Consumer<WindowState> c,boolean top){for(WindowState w:windows)c.accept(w);}
}''',
'com/android/server/wm/TaskFragment.java': '''package com.android.server.wm; class TaskFragment extends WindowContainer { Task rootTask; Task getRootTask(){return rootTask==null?(Task)this:rootTask;} }''',
'com/android/server/wm/Task.java': '''package com.android.server.wm;
import android.view.SurfaceControl;
public class Task extends TaskFragment { public Task(int mode,String n){this.mode=mode;surface=new SurfaceControl(n);} }''',
'com/android/server/wm/WindowState.java': '''package com.android.server.wm;
import android.view.*;
public class WindowState extends WindowContainer { final WindowManager.LayoutParams mAttrs; final Task task;
 public WindowState(int t,String p,Task task){mAttrs=new WindowManager.LayoutParams(t,p);this.task=task;surface=new SurfaceControl(p);} Task getTask(){return task;}
}''',
'com/android/server/wm/RootWindowContainer.java': '''package com.android.server.wm;
public class RootWindowContainer extends WindowContainer { public final WindowContainer display=new WindowContainer(); WindowContainer getDisplayContent(int id){return id==0?display:null;} }''',
'com/android/server/wm/WindowManagerService.java': '''package com.android.server.wm;
import android.content.Context; import android.view.SurfaceControl; import android.window.ScreenCaptureInternal.*;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
public class WindowManagerService {
 final Object mGlobalLock=new Object(); final RootWindowContainer mRoot=new RootWindowContainer(); final Context mContext=new Context();
 public final SurfaceControl cast=new SurfaceControl("cast"); public LayerCaptureArgs output; public boolean fail;
 public WindowContainer display(){return mRoot.display;}
 public void captureDisplay(int id,CaptureArgs args,ScreenCaptureListener listener) throws Exception {
  LayerCaptureArgs.Builder b=new LayerCaptureArgs.Builder();b.setExcludeLayers(new SurfaceControl[]{cast});
  output=(LayerCaptureArgs)BaseHook.invoke(b,"build",new Class<?>[0]);
  if(fail)throw new IllegalStateException("capture failure");
  for(SurfaceControl s:output.surfaces){if(!s.isValid())throw new AssertionError("released before submission");}
  // Model the inspected WMS release behavior: its argument list is released.
  for(SurfaceControl s:output.surfaces)if(s.original!=null)s.release();
 }
}''',
'com/sevtinge/hyperceiler/libhook/rules/systemframework/display/ScreenshotExclusionTest.java': '''package com.sevtinge.hyperceiler.libhook.rules.systemframework.display;
import android.os.*; import android.view.*; import android.window.ScreenCaptureInternal.*;
import com.android.server.wm.*;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
public class ScreenshotExclusionTest {
 static int checks;
 static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static void capture(WindowManagerService s) throws Exception {BaseHook.invoke(s,"captureDisplay",new Class[]{int.class,CaptureArgs.class,ScreenCaptureListener.class},0,new CaptureArgs(),new ScreenCaptureListener());}
 static WindowManagerService fixture(){
  WindowManagerService s=new WindowManagerService(); Task small=new Task(5,"small-task"), full=new Task(1,"full-task");
  s.display().windows.add(new WindowState(2000,"statusbar",null));
  s.display().windows.add(new WindowState(2038,"third-party-overlay",null));
  s.display().windows.add(new WindowState(2006,"system-overlay",null));
  s.display().windows.add(new WindowState(2032,"accessibility-overlay",null));
  s.display().windows.add(new WindowState(1,"small-app",small));
  s.display().windows.add(new WindowState(2,"small-dialog",small));
  s.display().windows.add(new WindowState(1,"fullscreen",full));
  return s;
 }
 static void install(boolean o,boolean f){BaseHook.hooks.clear();SurfaceControl.copies.clear();PrefsBridge.statusbar=false;PrefsBridge.overlay=o;PrefsBridge.freeform=f;new ScreenshotCaptureWindowExclusion().init();}
 public static void main(String[] args) throws Exception {
  install(true,true);WindowManagerService s=fixture();capture(s);
  check(s.output.surfaces.length==4,"cast plus two overlays plus one deduplicated task");
  check(s.output.surfaces[0]==s.cast,"existing cast exclusion retained");
  check(SurfaceControl.copies.size()==3,"task deduplicated across main/dialog windows");
  for(SurfaceControl c:SurfaceControl.copies){check(!c.valid,"copies released");check(c.original.valid,"WMS-owned original remains valid");}
  for(WindowState w:s.display().windows)check(w.surface.valid,"live windows untouched");
  Binder.uid=10151;SurfaceControl.copies.clear();capture(s);check(SurfaceControl.copies.size()==3,"MIUI screenshot caller covered");
  Binder.uid=23456;SurfaceControl.copies.clear();capture(s);check(s.output.surfaces.length==1&&SurfaceControl.copies.isEmpty(),"unrelated callers untouched");
  Binder.uid=10197;SurfaceControl.copies.clear();s.fail=true;try{capture(s);throw new AssertionError("expected failure");}catch(IllegalStateException expected){}
  for(SurfaceControl c:SurfaceControl.copies)check(!c.valid&&c.original.valid,"cleanup on capture exception");
  LayerCaptureArgs.Builder standalone=new LayerCaptureArgs.Builder();standalone.setExcludeLayers(new SurfaceControl[]{s.cast});
  LayerCaptureArgs outside=(LayerCaptureArgs)BaseHook.invoke(standalone,"build",new Class[0]);check(outside.surfaces.length==1,"scope removed after exception");
  install(true,false);s=fixture();capture(s);check(s.output.surfaces.length==3,"overlay toggle excludes no tasks");
  install(false,true);s=fixture();capture(s);check(s.output.surfaces.length==2,"freeform toggle excludes no overlays");
  install(false,false);s=fixture();capture(s);check(s.output.surfaces.length==1,"disabled settings preserve original list");
  install(false,false);PrefsBridge.statusbar=true;BaseHook.hooks.clear();new ScreenshotCaptureWindowExclusion().init();
  s=fixture();capture(s);check(s.output.surfaces.length==2,"statusbar-only excludes exactly one surface");
  check(s.output.surfaces[1].original.name.equals("statusbar"),"statusbar type 2000 selected");
  install(true,true);PrefsBridge.statusbar=true;BaseHook.hooks.clear();new ScreenshotCaptureWindowExclusion().init();
  s=fixture();capture(s);check(s.output.surfaces.length==5,"statusbar and overlays coexist");
  for(SurfaceControl c:SurfaceControl.copies)check(c.original.valid,"combined path retains original surfaces");
  Build.VERSION.SDK_INT=36;BaseHook.hooks.clear();new ScreenshotCaptureWindowExclusion().init();check(BaseHook.hooks.isEmpty(),"older SDK skips new hook");
  System.out.println("PASS: "+checks+" callback/lifetime assertions");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='screenshot-window-tests-') as work:
    work = Path(work)
    for name, code in sources.items():
        p = work / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(code)
    for name in ['ScreenshotCaptureWindowExclusion.java', 'ScreenshotWindowPolicy.java']:
        target = work / 'com/sevtinge/hyperceiler/libhook/rules/systemframework/display' / name
        target.write_text((rule_dir / name).read_text())
    output = work / 'classes'
    subprocess.run(['javac', '-d', str(output)] + [str(p) for p in work.rglob('*.java')], check=True)
    subprocess.run(['java', '-cp', str(output), 'com.sevtinge.hyperceiler.libhook.rules.systemframework.display.ScreenshotExclusionTest'], check=True)
