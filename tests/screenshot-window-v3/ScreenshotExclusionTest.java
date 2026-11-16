package com.sevtinge.hyperceiler.libhook.rules.systemframework.display;
import java.util.*;import android.os.*;import android.view.*;import android.window.ScreenCaptureInternal.*;
import com.android.server.wm.*;import com.sevtinge.hyperceiler.libhook.base.BaseHook;import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
public class ScreenshotExclusionTest {
 static int checks;static List<SurfaceControl> visuals=new ArrayList<>();static List<Task> tasks=new ArrayList<>();static Map<SurfaceControl,SurfaceControl> parents=new IdentityHashMap<>();
 static void check(boolean b,String msg){checks++;if(!b)throw new AssertionError(msg);}
 static SurfaceControl child(String name,SurfaceControl p){SurfaceControl s=new SurfaceControl(name).attach(p);visuals.add(s);return s;}
 static WindowManagerService fixture(){
  visuals.clear();tasks.clear();parents.clear();WindowManagerService s=new WindowManagerService();Task shared=new Task(1,"shared-root");tasks.add(shared);
  for(int mode:new int[]{5,5,1,2,6}){
   String n=mode==5?(tasks.size()==1?"free-a":"free-b"):("other-"+mode);Task task=new Task(mode,n).shareRoot(shared);tasks.add(task);s.display().leafTasks.add(task);
   WindowState main=new WindowState(1,n+"-main",task),dialog=new WindowState(2,n+"-dialog",task);s.display().windows.add(main);s.display().windows.add(dialog);
   visuals.add(task.surface);visuals.add(main.surface);visuals.add(dialog.surface);child(n+"-background",task.surface);child(n+"-caption",task.surface);child(n+"-shadow",task.surface);child(n+"-pin-icon",task.surface);
  }
  s.display().windows.add(new WindowState(2000,"statusbar",null));s.display().windows.add(new WindowState(2038,"overlay",null));s.display().windows.add(new WindowState(2006,"system-overlay",null));s.display().windows.add(new WindowState(2032,"accessibility",null));
  Task orphan=new Task(5,"free-orphan-pin").shareRoot(shared);tasks.add(orphan);s.display().leafTasks.add(orphan);visuals.add(orphan.surface);child("free-orphan-pin-icon",orphan.surface);child("free-orphan-background",orphan.surface);
  s.display().windows.add(new WindowState(2027,"pin-bubbles",null).owner("com.android.systemui").named("MiuiBubbles"));
  s.display().windows.add(new WindowState(2027,"wrong-owner",null).owner("other.app").named("MiuiBubbles"));
  s.display().windows.add(new WindowState(2027,"wrong-title",null).owner("com.android.systemui").named("GestureStubRight"));
  s.display().windows.add(new WindowState(2032,"wrong-type",null).owner("com.android.systemui").named("MiuiBubbles"));
  s.display().windows.add(new WindowState(2027,"null-title",null).owner("com.android.systemui").named(null));
  s.display().windows.add(new WindowState(2027,"suffix-title",null).owner("com.android.systemui").named("MiuiBubbles-other"));
  s.display().windows.add(new WindowState(2009,"island-plugin",null).owner("miui.systemui.plugin").named("DynamicIslandWindow"));
  s.display().windows.add(new WindowState(2009,"island-systemui",null).owner("com.android.systemui").named("DynamicIslandWindow").uid(110197));
  for(SurfaceControl v:visuals)parents.put(v,v.parent);return s;
 }
 static void capture(WindowManagerService s)throws Exception{BaseHook.invoke(s,"captureDisplay",new Class[]{int.class,CaptureArgs.class,ScreenCaptureListener.class},0,new CaptureArgs(),new ScreenCaptureListener());}
 static void install(int mask){BaseHook.hooks.clear();SurfaceControl.copies.clear();PrefsBridge.statusbar=(mask&1)!=0;PrefsBridge.overlay=(mask&2)!=0;PrefsBridge.freeform=(mask&4)!=0;PrefsBridge.island=(mask&8)!=0;new ScreenshotCaptureWindowExclusion().init();}
 public static void main(String[] argv)throws Exception{
  for(int mask=0;mask<16;mask++){
   Binder.uid=10197;install(mask);WindowManagerService s=fixture();capture(s);Set<String> names=new HashSet<>();for(SurfaceControl v:s.output.surfaces)names.add(v.name);
   check(names.contains("pin-bubbles")==((mask&4)!=0),"pin bubble uses small-window toggle independently of overlay");
   check(!names.contains("wrong-owner")&&!names.contains("wrong-title")&&!names.contains("wrong-type")&&!names.contains("null-title")&&!names.contains("suffix-title"),"precise owner/type/title gate");
   check(names.contains("island-plugin")==((mask&8)!=0),"exact plugin island independent toggle");
   check(names.contains("island-systemui")==((mask&8)!=0),"SystemUI island, non-hardcoded multiuser UID");
   check(names.contains("cast"),"existing cast excluded");check(!names.contains("shared-root"),"shared root retained");check(names.contains("statusbar")==((mask&1)!=0),"statusbar toggle");check(names.contains("overlay")==((mask&2)!=0),"overlay toggle");check(names.contains("system-overlay")==((mask&2)!=0),"system overlay toggle");
   for(SurfaceControl v:visuals){boolean free=v.name.startsWith("free-");check(v.excludedBy(s.output.surfaces)==(free&&(mask&4)!=0),"whole task tree only: "+v.name);check(v.valid&&v.releases==0&&v.parent==parents.get(v),"original surface/parent untouched");}
   check(s.output.surfaces.length==1+((mask&1)!=0?1:0)+((mask&2)!=0?2:0)+((mask&4)!=0?4:0)+((mask&8)!=0?2:0),"one exclude per task despite main/dialog");
   for(Task t:tasks)check(t.foregroundPin&&t.active&&t.stateWrites==0,"foreground lifecycle unchanged");for(SurfaceControl v:SurfaceControl.copies)check(!v.valid&&v.original.valid,"copies released, originals live");
   Binder.uid=23456;SurfaceControl.copies.clear();capture(s);check(s.output.surfaces.length==1&&SurfaceControl.copies.isEmpty(),"non-screenshot caller unaffected");
   Binder.uid=10151;capture(s);check(s.output.surfaces.length==1+((mask&1)!=0?1:0)+((mask&2)!=0?2:0)+((mask&4)!=0?4:0)+((mask&8)!=0?2:0),"MIUI screenshot caller covered");
   s.fail=true;try{capture(s);throw new AssertionError("must fail");}catch(IllegalStateException expected){}for(SurfaceControl v:SurfaceControl.copies)check(!v.valid&&v.original.valid,"exception cleanup");
   LayerCaptureArgs.Builder b=new LayerCaptureArgs.Builder();b.setExcludeLayers(new SurfaceControl[]{s.cast});LayerCaptureArgs out=(LayerCaptureArgs)BaseHook.invoke(b,"build",new Class[0]);check(out.surfaces.length==1,"scope restored after failed capture");
  }
  Binder.uid=10197;install(4);WindowManagerService s=fixture();tasks.get(1).surface.valid=false;capture(s);Set<String> names=new HashSet<>();for(SurfaceControl v:s.output.surfaces)names.add(v.name);check(!names.contains("free-a")&&names.contains("free-b"),"invalid target skipped, no root fallback");
  WindowContainer.failEnumeration=true;Binder.uid=10197;install(4);s=fixture();capture(s);names.clear();for(SurfaceControl v:s.output.surfaces)names.add(v.name);check(names.contains("free-a")&&names.contains("free-b")&&names.contains("pin-bubbles"),"optional task enumeration failure preserves existing exclusions");check(!names.contains("free-orphan-pin"),"failed optional path does not exclude arbitrary task");WindowContainer.failEnumeration=false;
  Build.VERSION.SDK_INT=36;BaseHook.hooks.clear();new ScreenshotCaptureWindowExclusion().init();check(BaseHook.hooks.isEmpty(),"old SDK skipped");
  System.out.println("PASS: "+checks+" pin/leaf-task/model callback assertions; NOT real SurfaceFlinger or device lifecycle verification");
 }
}