#!/usr/bin/env python3
"""JDK25 compile of real production hooks with explicitly bounded host models."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parents[1]
ROOT = Path(os.environ['HC_TEST_OUTPUT_DIR']) if 'HC_TEST_OUTPUT_DIR' in os.environ else Path(tempfile.mkdtemp(prefix='hc-callback-report-'))
ROOT.mkdir(parents=True, exist_ok=True)
JAVA = SOURCE/'library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook'
RES = SOURCE/'library/core/src/main/res'
ROM = None
os.environ['PYTHONDONTWRITEBYTECODE'] = '1'
spec = importlib.util.spec_from_file_location('privacy_support', HERE/'privacy_support.py')
privacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(privacy)
privacy.ROOT, privacy.JAVA, privacy.RES = SOURCE, JAVA, RES
privacy.MEDIA = JAVA/'rules/systemframework/others/BlockMediaStoreNotificationsOS4.java'
privacy.SHOT = JAVA/'rules/systemui/other/ScreenshotSaveToXmlOS4.java'

STUBS = {
'android/util/ArraySet.java': '''package android.util;
public class ArraySet<E> extends java.util.LinkedHashSet<E> {}''',
'android/os/RemoteException.java': '''package android.os;
public class RemoteException extends Exception { public RemoteException(String s){super(s);} }''',
'android/app/IScreenCaptureObserver.java': '''package android.app;
public interface IScreenCaptureObserver {void onScreenCaptured() throws android.os.RemoteException;}''',
'android/app/Activity.java': '''package android.app;
public class Activity {public interface ScreenCaptureCallback {void onScreenCaptured();}}''',
'android/app/ScreenCaptureCallbackHandler.java': '''package android.app;
import java.util.*;import java.util.concurrent.Executor;
/** Model of the inspected app handler, not a real Binder or Android ArrayMap. */
public class ScreenCaptureCallbackHandler implements IScreenCaptureObserver {
 public record Registration(Executor executor,Activity.ScreenCaptureCallback callback){}
 public final Map<Activity.ScreenCaptureCallback,Registration> registrations=new LinkedHashMap<>();
 public void register(Executor e,Activity.ScreenCaptureCallback c){
  if(registrations.containsKey(c))throw new IllegalStateException("already registered");
  registrations.put(c,new Registration(e,c));}
 public void unregister(Activity.ScreenCaptureCallback c){
  if(!registrations.containsKey(c))throw new IllegalStateException("not registered");registrations.remove(c);}
 public void onScreenCaptured(){for(Registration r:registrations.values())r.executor.execute(()->r.callback.onScreenCaptured());}
}''',
'com/android/server/wm/ActivityRecord.java': '''package com.android.server.wm;
import android.app.IScreenCaptureObserver;import android.os.RemoteException;import java.util.*;
/** Model of inspected ActivityRecord broadcast ordering. No actual RemoteCallbackList. */
public class ActivityRecord {
 public final int user;public final String component;public boolean visible=true;
 public List<IScreenCaptureObserver> callbacks;public int begin,finish,original;public boolean active;
 public ActivityRecord(int u,String c){user=u;component=c;}
 public boolean isRegisteredForScreenCaptureCallback(){return callbacks!=null&&!callbacks.isEmpty();}
 public void registerCaptureObserver(IScreenCaptureObserver o){
  if(o==null)throw new NullPointerException("RemoteCallbackList null registration model");
  if(callbacks==null)callbacks=new ArrayList<>();if(!callbacks.contains(o))callbacks.add(o);}
 public void unregisterCaptureObserver(IScreenCaptureObserver o){if(callbacks!=null)callbacks.remove(o);}
 public void binderDied(IScreenCaptureObserver o){if(callbacks!=null)callbacks.remove(o);}
 void reportScreenCaptured(){
  original++;if(callbacks==null)return;
  if(active)throw new IllegalStateException("broadcast already active");active=true;begin++;
  List<IScreenCaptureObserver> snapshot=new ArrayList<>(callbacks);
  for(IScreenCaptureObserver o:snapshot){try{o.onScreenCaptured();}catch(RemoteException ignored){}}
  finish++;active=false;
 }
}''',
'com/android/server/wm/WindowManagerService.java': '''package com.android.server.wm;
import android.util.ArraySet;import java.util.*;import java.lang.reflect.*;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
/** Host model of actual WMS permission -> display -> visible/registered -> report -> list. */
public class WindowManagerService {
 public static final Method REPORT;
 static {try{REPORT=ActivityRecord.class.getDeclaredMethod("reportScreenCaptured");}catch(Exception e){throw new AssertionError(e);}}
 public boolean statusBarPermission=true;public int permissionChecks,lock,unlock,checks;
 public final Map<Integer,List<ActivityRecord>> displays=new HashMap<>();
 public List<String> notifyScreenshotListeners(int display) throws Throwable {
  permissionChecks++;if(!statusBarPermission)throw new SecurityException("STATUS_BAR_SERVICE");
  lock++;try{
   List<ActivityRecord> records=displays.get(display);if(records==null)return new ArrayList<>();
   ArraySet<String> apps=new ArraySet<>();
   for(ActivityRecord a:records){checks++;lambda$notifyScreenshotListeners$40(apps,a);}
   return List.copyOf(apps);
  }finally{unlock++;}
 }
 static void lambda$notifyScreenshotListeners$40(ArraySet<String> apps,ActivityRecord a) throws Throwable {
  if(!apps.contains(a.component)&&a.visible&&a.isRegisteredForScreenCaptureCallback()){
   BaseHook.invoke(REPORT,a);apps.add(a.component);}
 }
}''',
'com/android/server/wm/WindowManagerService$$ExternalSyntheticLambda44.java': '''package com.android.server.wm;
public final class WindowManagerService$$ExternalSyntheticLambda44 implements java.util.function.Consumer<Object>{
 public void accept(Object value){}
}''',
'android/window/ScreenCaptureInternal.java': '''package android.window;
/** Independent image-result listener fixture, not official app detection callback. */
public class ScreenCaptureInternal {
 public interface ScreenCaptureListener {void onScreenCaptureComplete(byte[] image,int status);}
 public static int displayCalls,layerCalls;
 public static void captureDisplay(ScreenCaptureListener l){displayCalls++;l.onScreenCaptureComplete(new byte[]{1,2,3},0);}
 public static void captureLayers(ScreenCaptureListener l){layerCalls++;l.onScreenCaptureComplete(new byte[]{4,5,6},0);}
}''',
}

TEST = r'''package com.sevtinge.hyperceiler.libhook.rules.systemframework.others;
import android.app.*;import android.os.*;import android.net.Uri;import android.util.ArraySet;
import android.content.ContentValues;import android.graphics.Bitmap.CompressFormat;
import android.window.ScreenCaptureInternal;import java.lang.reflect.*;import java.time.ZonedDateTime;
import java.util.*;import java.util.concurrent.*;
import com.android.server.wm.*;import com.android.server.content.ContentService;
import com.android.systemui.screenshot.ImageExporter;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.rules.systemui.other.ScreenshotSaveToXmlOS4;

public class CallbackTest {
 static int checks;
 static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 interface Run {void go() throws Throwable;}
 static Throwable expect(Class<? extends Throwable> type,Run r) throws Throwable {
  try{r.go();}catch(Throwable t){check(type.isInstance(t),"exception "+type+" got "+t);return t;}
  throw new AssertionError("expected "+type);
 }
 static final Method REPORT=WindowManagerService.REPORT;
 static void reset(boolean enabled){
  BaseHook.hooks.clear();BaseHook.deoptimized.clear();BaseHook.failInstall=false;
  PrefsBridge.values.clear();Build.VERSION.SDK_INT=37;
  PrefsBridge.values.put(BlockScreenCaptureCallbacksOS4.PREF,enabled);
 }
 static void install(boolean enabled){reset(enabled);new BlockScreenCaptureCallbacksOS4().init();}
 static class QueueExecutor implements Executor {
  final List<Runnable> queued=new ArrayList<>();int submitted;
  public void execute(Runnable r){submitted++;queued.add(r);}
  void drain(){while(!queued.isEmpty())queued.remove(0).run();}
 }
 static ActivityRecord record(int user,String component,IScreenCaptureObserver o){
  ActivityRecord a=new ActivityRecord(user,component);if(o!=null)a.registerCaptureObserver(o);return a;}
 static WindowManagerService wms(ActivityRecord... records){
  WindowManagerService w=new WindowManagerService();w.displays.put(0,List.of(records));return w;}
 public static class WrongName {public void other(){}}
 public static class WrongReturn {public boolean reportScreenCaptured(){return true;}}
 public static class WrongArg {public void reportScreenCaptured(int x){}}
 public static class WrongStatic {public static void reportScreenCaptured(){}}
 public abstract static class WrongAbstract {public abstract void reportScreenCaptured();}
 static void guards() throws Throwable {
  check(!ScreenCaptureCallbackPolicy.isObserverDispatch(null),"null Method guard");
  check(ScreenCaptureCallbackPolicy.isObserverDispatch(REPORT),"actual shape");
  check(!ScreenCaptureCallbackPolicy.isObserverDispatch(WrongName.class.getDeclaredMethod("other")),"name");
  check(!ScreenCaptureCallbackPolicy.isObserverDispatch(WrongReturn.class.getDeclaredMethod("reportScreenCaptured")),"return");
  check(!ScreenCaptureCallbackPolicy.isObserverDispatch(WrongArg.class.getDeclaredMethod("reportScreenCaptured",int.class)),"args");
  check(!ScreenCaptureCallbackPolicy.isObserverDispatch(WrongStatic.class.getDeclaredMethod("reportScreenCaptured")),"static");
  check(!ScreenCaptureCallbackPolicy.isObserverDispatch(WrongAbstract.class.getDeclaredMethod("reportScreenCaptured")),"abstract");
  for(int sdk:new int[]{33,34,36,37,38})for(boolean on:new boolean[]{false,true}){
   reset(on);Build.VERSION.SDK_INT=sdk;new BlockScreenCaptureCallbacksOS4().init();
   check(BaseHook.hooks.size()==(sdk==37&&on?1:0),"SDK/default gate");
  }
  reset(false);PrefsBridge.values.clear();new BlockScreenCaptureCallbacksOS4().init();
  check(BaseHook.hooks.isEmpty()&&BaseHook.deoptimized.isEmpty(),"absent key default false");
  reset(true);BaseHook.failInstall=true;
  expect(IllegalStateException.class,()->new BlockScreenCaptureCallbacksOS4().init());
  check(BaseHook.hooks.isEmpty(),"failed hook not silently accepted");
  install(true);check(BaseHook.hooks.keySet().equals(Set.of(REPORT)),"only report hooked");
  check(BaseHook.deoptimized.size()==3,"three exact caller deoptimizations");
 }
 static void callbackAndScope() throws Throwable {
  for(boolean on:new boolean[]{false,true}){
   install(on);QueueExecutor queue=new QueueExecutor();int[] called={0};
   ScreenCaptureCallbackHandler h=new ScreenCaptureCallbackHandler();
   h.register(queue,()->called[0]++);h.register(queue,()->called[0]++);
   ActivityRecord a=record(0,"app/.Main",h);List<IScreenCaptureObserver> original=a.callbacks;
   WindowManagerService w=wms(a);List<String> apps=w.notifyScreenshotListeners(0);
   check(apps.equals(List.of("app/.Main")),"system notification list retained");
   expect(UnsupportedOperationException.class,()->apps.add("x"));
   check(queue.submitted==(on?0:2),"executor scheduling occurs only on original broadcast");
   check(called[0]==0,"queue remains asynchronous");queue.drain();
   check(called[0]==(on?0:2),"all Activity callbacks off/on");
   check(a.callbacks==original&&a.callbacks.size()==1,"no observer copy/clear/replacement");
   check(a.begin==(on?0:1)&&a.finish==(on?0:1)&&!a.active,"broadcast cleanup/skip");
   check(w.permissionChecks==1&&w.lock==1&&w.unlock==1,"permission/lock cleanup retained");
   a.unregisterCaptureObserver(h);check(!a.isRegisteredForScreenCaptureCallback(),"unregister unaffected");
   a.registerCaptureObserver(h);a.binderDied(h);check(!a.isRegisteredForScreenCaptureCallback(),"binder death model unaffected");
   expect(NullPointerException.class,()->a.registerCaptureObserver(null));
   ActivityRecord empty=new ActivityRecord(0,"empty");BaseHook.invoke(REPORT,empty);
   check(empty.begin==0&&empty.finish==0,"null list safe");
   a.registerCaptureObserver(h);a.callbacks.clear();BaseHook.invoke(REPORT,a);
   check(a.begin==(on?0:2)&&a.finish==(on?0:2),"empty list lifecycle");
  }
  for(boolean on:new boolean[]{false,true})for(int user:new int[]{0,10,999})
   for(String pkg:new String[]{"android","com.example.a","com.example.b"}){
    install(on);int[] count={0};ActivityRecord a=record(user,pkg+"/.Main",()->count[0]++);
    check(wms(a).notifyScreenshotListeners(0).size()==1,"every selected user/app listed");
    check(count[0]==(on?0:1),"no package/user filtering");
   }
  install(true);int[] received={0};ActivityRecord a=record(0,"a",()->received[0]++);
  ActivityRecord b=record(10,"b",()->received[0]++),hidden=record(0,"hidden",()->received[0]++);
  hidden.visible=false;ActivityRecord duplicate=record(10,"a",()->received[0]++);
  ActivityRecord unregistered=new ActivityRecord(0,"none");
  WindowManagerService w=wms(a,b,hidden,duplicate,unregistered);
  check(w.notifyScreenshotListeners(0).equals(List.of("a","b")),"visible registered filtering and dedup retained");
  check(received[0]==0&&hidden.original==0&&duplicate.original==0,"hidden/nonselected never called");
  check(w.notifyScreenshotListeners(99).isEmpty(),"missing display empty list");
  w.statusBarPermission=false;expect(SecurityException.class,()->w.notifyScreenshotListeners(0));
  check(w.lock==2&&w.unlock==2,"permission before lock even when on");
  install(false);received[0]=0;
  check(wms(a,b,hidden,duplicate,unregistered).notifyScreenshotListeners(0).size()==2,"off selection");
  check(received[0]==2,"off selected originals only");
 }
 static void exceptionsAndExecutor() throws Throwable {
  for(boolean on:new boolean[]{false,true}){
   install(on);int[] after={0};ActivityRecord a=new ActivityRecord(0,"a");
   a.registerCaptureObserver(()->{throw new RemoteException("dead app");});
   a.registerCaptureObserver(()->after[0]++);BaseHook.invoke(REPORT,a);
   check(after[0]==(on?0:1),"original RemoteException catch/continue or skipped");
   check(!a.active&&a.begin==a.finish,"RemoteException cleanup");
   ActivityRecord poisoned=record(0,"null-observer",()->{});poisoned.callbacks.set(0,null);
   if(on){BaseHook.invoke(REPORT,poisoned);check(poisoned.begin==0,"skip avoids touching null observer");}
   else {expect(NullPointerException.class,()->BaseHook.invoke(REPORT,poisoned));
    check(poisoned.active&&poisoned.finish==0,"ROM has no finally for unexpected local runtime exception");}
   ScreenCaptureCallbackHandler rejected=new ScreenCaptureCallbackHandler();
   rejected.register(r->{throw new RejectedExecutionException("closed");},()->{});
   ActivityRecord ra=record(0,"reject",rejected);
   if(on){BaseHook.invoke(REPORT,ra);check(ra.begin==0,"no executor rejection when no scheduling");}
   else expect(RejectedExecutionException.class,()->BaseHook.invoke(REPORT,ra));
   QueueExecutor q=new QueueExecutor();ScreenCaptureCallbackHandler throwing=new ScreenCaptureCallbackHandler();
   RuntimeException sentinel=new IllegalArgumentException("app callback");throwing.register(q,()->{throw sentinel;});
   BaseHook.invoke(REPORT,record(0,"throws",throwing));
   if(on){q.drain();check(q.submitted==0,"blocked task never submitted");}
   else check(expect(IllegalArgumentException.class,q::drain)==sentinel,"original callback exception on executor");
  }
  install(false);QueueExecutor q=new QueueExecutor();int[] count={0};
  ScreenCaptureCallbackHandler h=new ScreenCaptureCallbackHandler();Activity.ScreenCaptureCallback cb=()->count[0]++;
  h.register(q,cb);expect(IllegalStateException.class,()->h.register(q,cb));
  ActivityRecord a=record(0,"queued",h);BaseHook.invoke(REPORT,a);
  install(true);BaseHook.invoke(REPORT,a);h.unregister(cb);q.drain();
  check(count[0]==1&&q.submitted==1,"already queued tasks cannot be cancelled by server hook");
  expect(IllegalStateException.class,()->h.unregister(cb));
  // Existing registrations survive disabling by the normal next initialization.
  h.register(Runnable::run,cb);install(false);BaseHook.invoke(REPORT,a);
  check(count[0]==2,"normal reinit OFF resumes existing observers");
 }
 /** Extra validation host model: these checks are NOT claimed to exist in ROM's empty register method. */
 static class HostRegistration {
  int permission,user,token,current;
  void register(boolean allowed,int callerUser,ActivityRecord target,boolean currentActivity,IScreenCaptureObserver o){
   permission++;if(!allowed)throw new SecurityException("DETECT_SCREEN_CAPTURE host model");
   user++;if(target!=null&&callerUser!=target.user)throw new SecurityException("user host model");
   token++;if(target==null)return;
   current++;if(!currentActivity)throw new SecurityException("current Activity host model");
   target.registerCaptureObserver(o);
  }
 }
 static void validationHostModel() throws Throwable {
  for(boolean on:new boolean[]{false,true}){
   install(on);HostRegistration reg=new HostRegistration();ActivityRecord a=new ActivityRecord(10,"owner");
   expect(SecurityException.class,()->reg.register(false,10,a,true,()->{}));
   expect(SecurityException.class,()->reg.register(true,0,a,true,()->{}));
   reg.register(true,10,null,true,()->{});
   expect(SecurityException.class,()->reg.register(true,10,a,false,()->{}));
   expect(NullPointerException.class,()->reg.register(true,10,a,true,null));
   int[] calls={0};reg.register(true,10,a,true,()->calls[0]++);BaseHook.invoke(REPORT,a);
   check(calls[0]==(on?0:1),"post-validation report hook only");
   check(reg.permission==6&&reg.user==5&&reg.token==4&&reg.current==3,"host checks always unchanged");
  }
 }
 static void coexistenceAndSaving() throws Throwable {
  Method metadata=ImageExporter.class.getDeclaredMethod("createMetadata",ZonedDateTime.class,CompressFormat.class,String.class);
  for(boolean callback:new boolean[]{false,true})for(boolean media:new boolean[]{false,true})
   for(boolean folder:new boolean[]{false,true}){
    install(callback);PrefsBridge.values.put(BlockMediaStoreNotificationsOS4.PREF,media);
    PrefsBridge.values.put(ScreenshotSaveToXmlOS4.PREF,folder);
    new BlockMediaStoreNotificationsOS4().init();new ScreenshotSaveToXmlOS4().init();
    int[] calls={0},saved={0};ActivityRecord a=record(10,"app/.Main",()->calls[0]++);
    WindowManagerService w=wms(a);
    for(boolean layers:new boolean[]{false,true}){
     ScreenCaptureInternal.ScreenCaptureListener listener=(image,status)->{
      check(image.length==3&&status==0,"capture image listener still receives pixels");
      try{
       ContentValues values=(ContentValues)BaseHook.invoke(metadata,null,ZonedDateTime.parse("2026-11-01T00:00:00Z"),CompressFormat.PNG,"shot.png");
       check(values.get("relative_path").equals(folder?"Pictures/xml/":"Pictures/Screenshots/"),"save directory independent");
       check(values.get("_display_name").equals("shot.png")&&values.get("mime_type").equals("image/png"),"save image fields preserved");
       check(values.get("is_pending").equals(1),"pending metadata preserved");saved[0]++;
       ContentService cs=new ContentService();cs.notifyChange(new Uri[]{Uri.parse("content://media/external/images/media/7")},null,false,0,10,37,"system");
       check(cs.collector.events.size()==(media?0:1),"media observer independent");
       check(cs.sync==1&&cs.cache==1&&cs.restore==1,"media original cleanup");
      }catch(Throwable t){throw new AssertionError(t);}
     };
     if(layers)ScreenCaptureInternal.captureLayers(listener);else ScreenCaptureInternal.captureDisplay(listener);
     check(w.notifyScreenshotListeners(0).equals(List.of("app/.Main")),"system UI list not suppressed");
    }
    check(saved[0]==2&&calls[0]==(callback?0:2),"captureDisplay/captureLayers save vs app detection independent");
    check(BaseHook.hooks.size()==(callback?1:0)+(media?1:0)+(folder?1:0),"independent keys all 8 configurations");
   }
 }
 public static void main(String[] args) throws Throwable {
  guards();callbackAndScope();exceptionsAndExecutor();validationHostModel();coexistenceAndSaving();
  System.out.println("PASS CallbackTest checks="+checks+"; actual production Java callbacks; host models only");
 }
}
'''


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def static_checks():
    ns = '{http://schemas.android.com/apk/res/android}'
    key = 'system_framework_block_screen_capture_callbacks'
    prefs = [x for x in ET.parse(RES/'xml/framework_other.xml').iter() if x.get(ns+'key') == 'prefs_key_'+key]
    assert len(prefs)==1 and prefs[0].get(ns+'defaultValue')=='false'
    assert prefs[0].get(ns+'dependency') is None
    for locale in ['values','values-zh-rCN']:
        strings = ET.parse(RES/locale/'strings_app.xml').getroot()
        for ref in ['title','summary']:
            name=prefs[0].get(ns+ref).removeprefix('@string/')
            assert sum(x.get('name')==name for x in strings)==1
    loader=(JAVA/'app/SystemFramework/SystemFrameworkB.java').read_text()
    pos=loader.index('initHook(new com.sevtinge.hyperceiler.libhook.rules.systemframework.others.BlockScreenCaptureCallbacksOS4()')
    block=loader[pos:loader.index(';',pos)]
    assert 'SDK_INT == 37' in block and 'isHyperOSVersion(4f)' in block
    assert f'getBoolean("{key}", false)' in block
    hook=(JAVA/'rules/systemframework/others/BlockScreenCaptureCallbacksOS4.java').read_text()
    assert hook.count('hookMethod(')==1 and 'hookMethod(report,' in hook
    assert 'getDeclaredMethod("reportScreenCaptured")' in hook
    assert 'param.setResult(null)' in hook
    print('PASS callback resources/loader/default false/exact single dispatch hook')


def rom_checks():
    # Persist concise line-numbered evidence rather than whole framework class dumps.
    selected = {
      'services.jar/classes4/com/android/server/wm/ActivityRecord.smali': ['reportScreenCaptured(', 'registerCaptureObserver(', 'unregisterCaptureObserver(', 'isRegisteredForScreenCaptureCallback('],
      'services.jar/classes4/com/android/server/wm/WindowManagerService.smali': ['notifyScreenshotListeners(', 'lambda$notifyScreenshotListeners$40('],
      'services.jar/classes4/com/android/server/wm/ActivityTaskManagerService.smali': ['registerScreenCaptureObserver(', 'unregisterScreenCaptureObserver('],
      'framework.jar/classes/android/app/Activity.smali': ['registerScreenCaptureCallback(', 'unregisterScreenCaptureCallback('],
      'framework.jar/classes/android/app/ScreenCaptureCallbackHandler.smali': ['registerScreenCaptureCallback(', 'unregisterScreenCaptureCallback('],
      'framework.jar/classes/android/app/ScreenCaptureCallbackHandler$ScreenCaptureObserver.smali': ['onScreenCaptured(', 'lambda$onScreenCaptured$0('],
      'framework.jar/classes/android/app/IScreenCaptureObserver$Stub$Proxy.smali': ['onScreenCaptured('],
      'framework.jar/classes5/android/window/ScreenCaptureInternal.smali': ['captureDisplay(', 'captureLayers('],
      'MiuiSystemUI.apk/classes2/com/android/systemui/screenshot/MessageContainerController$onScreenshotTaken$1.smali': ['invokeSuspend('],
    }
    methods={}; evidence=[]; hashes={}
    for rel,names in selected.items():
        p=ROM/rel; lines=p.read_text().splitlines(); hashes[rel]=sha(p)
        for i,line in enumerate(lines):
            if line.startswith('.method ') and any(n in line for n in names):
                end=next(j for j in range(i,len(lines)) if lines[j]=='.end method')
                body='\n'.join(lines[i:end+1]);methods[(rel,line)]=body
                evidence.append(f'\nFILE {p}:{i+1}\n'+'\n'.join(f'{j+1}: {lines[j]}' for j in range(i,end+1)))
    def body(file,name):
        return next(v for (k,sig),v in methods.items() if k.endswith(file) and (name+'(') in sig)
    report=body('ActivityRecord.smali','reportScreenCaptured')
    assert report.index('->beginBroadcast()I')<report.index('->onScreenCaptured()V')<report.index('->finishBroadcast()V')
    assert '.catch Landroid/os/RemoteException;' in report and '.catchall' not in report
    register=body('ActivityTaskManagerService.smali','registerScreenCaptureObserver')
    assert [s.strip() for s in register.splitlines() if s.strip() and not s.strip().startswith('.')]==['return-void']
    unreg=body('ActivityTaskManagerService.smali','unregisterScreenCaptureObserver')
    assert unreg.index('DETECT_SCREEN_CAPTURE')<unreg.index('->forTokenLocked(')<unreg.index('->unregisterCaptureObserver(')
    notify=body('WindowManagerService.smali','notifyScreenshotListeners')
    assert notify.index('STATUS_BAR_SERVICE')<notify.index('->getDisplayContent(')<notify.index('->forAllActivities(')<notify.index('->copyOf(')
    dispatch=body('WindowManagerService.smali','lambda$notifyScreenshotListeners$40')
    assert dispatch.index('->contains(')<dispatch.index('->isVisible()')<dispatch.index('->isRegisteredForScreenCaptureCallback()')<dispatch.index('->reportScreenCaptured()')<dispatch.index('->add(')
    handler=body('ScreenCaptureCallbackHandler.smali','registerScreenCaptureCallback')
    assert 'IActivityTaskManager;->registerScreenCaptureObserver' in handler
    activity_client=ROM/'framework.jar/classes/android/app/ActivityClient.smali'
    assert 'registerScreenCaptureObserver' not in activity_client.read_text()
    observer=body('ScreenCaptureCallbackHandler$ScreenCaptureObserver.smali','onScreenCaptured')
    assert 'Executor;->execute(Ljava/lang/Runnable;)V' in observer
    assert not any('notifyScreenCaptured(' in p.read_text() for p in (ROM/'services.jar/classes4/com/android/server/wm').glob('*.smali'))
    proxy=body('IScreenCaptureObserver$Stub$Proxy.smali','onScreenCaptured')
    assert 'const/4 v3, 0x1' in proxy and '->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z' in proxy
    assert 'IScreenCaptureObserver' not in body('ScreenCaptureInternal.smali','captureDisplay')
    (ROOT/'evidence/rom-methods.txt').write_text('\n'.join(evidence)+'\n', encoding='utf-8')
    print('PASS ROM exact signatures/order; empty register explicitly confirmed; callback vs image listener separated')
    return hashes


def main():
    static_checks()
    static_count=privacy.static_checks()
    rom_hashes={}
    if ROM is not None:
        (ROOT/'evidence').mkdir(exist_ok=True)
        privacy.rom_checks(ROM)
        rom_hashes=rom_checks()
    else:
        print('SKIP optional ROM contracts: pass --rom with an external decoded ROM directory')
    sources=dict(privacy.SOURCES)
    sources.update(STUBS)
    # The synthetic ImageExporter name is illegal Java source; inspect real smali above.
    base=sources['com/sevtinge/hyperceiler/libhook/base/BaseHook.java']
    base=base.replace('public abstract void init();', 'public abstract void init(); public static boolean failInstall;')
    base=base.replace('hooks.put(m,h);return new Object();', 'if(failInstall)return null;hooks.put(m,h);return new Object();')
    base=base.replace('boolean found=false;', 'boolean found=c.getName().equals("com.android.systemui.screenshot.ImageExporter")&&n.equals("-$$Nest$smcreateEntry");')
    sources['com/sevtinge/hyperceiler/libhook/base/BaseHook.java']=base
    sources['com/sevtinge/hyperceiler/libhook/rules/systemframework/others/CallbackTest.java']=TEST
    sources['ScreenshotPrivacyTest.java']=privacy.TEST
    jdk=Path(os.environ['JAVA_HOME']) if 'JAVA_HOME' in os.environ else Path(shutil.which('javac')).parent.parent
    production=[JAVA/'rules/systemframework/others/BlockScreenCaptureCallbacksOS4.java',
                JAVA/'rules/systemframework/others/ScreenCaptureCallbackPolicy.java', JAVA/'rules/media/CaptureFileNames.java', privacy.MEDIA, privacy.SHOT]
    outputs=[]
    # Temporary output strictly within this task; cleanup even on javac/test failure.
    with tempfile.TemporaryDirectory(prefix='host-',dir=ROOT) as folder:
        tmp=Path(folder);paths=[]
        for name,text in sources.items():
            p=tmp/'src'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(p)
        javac=[str(jdk/'bin/javac'),'-encoding','UTF-8','-d',str(tmp/'classes'),*map(str,paths),*map(str,production)]
        result=subprocess.run(javac,text=True,capture_output=True)
        outputs.append('COMMAND: JDK25 javac -encoding UTF-8 (5 production Java + host fixtures)\n'+result.stdout+result.stderr)
        if result.returncode:
            (ROOT/'TESTING.txt').write_text('\n'.join(outputs),encoding='utf-8');raise SystemExit(result.returncode)
        for test in ['com.sevtinge.hyperceiler.libhook.rules.systemframework.others.CallbackTest','ScreenshotPrivacyTest']:
            result=subprocess.run([str(jdk/'bin/java'),'-ea','-cp',str(tmp/'classes'),test],text=True,capture_output=True)
            print(result.stdout,end='');print(result.stderr,end='')
            outputs.append('TEST: '+test+'\n'+result.stdout+result.stderr)
            if result.returncode:
                (ROOT/'TESTING.txt').write_text('\n'.join(outputs),encoding='utf-8');raise SystemExit(result.returncode)
    report={'status':'PASS','java_home':str(jdk),'production_sha256':{str(p.relative_to(SOURCE)):sha(p) for p in production},
            'result':'\n'.join(outputs),'existing_privacy_static_checks':static_count,'rom_sha256':rom_hashes,
            'limits':['Host fixtures and smali contracts; no live Android/Xposed verification.',
                      'HostRegistration permission/user/token/current-Activity checks are extra models only: ROM register is empty.',
                      'Image saving exercised through independent image listener plus real metadata after hook, no real pixels or MediaStore writes.',
                      'Unexpected local runtime exception behavior models ROM lacking finally; real app observer uses Binder oneway.',
                      'JDK compiles real 4 production hooks; BaseHook/EzHookTool/Android APIs stubbed, not full APK build.']}
    (ROOT/'host-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (ROOT/'TESTING.txt').write_text('\n'.join(outputs),encoding='utf-8')
    print('PASS temporary host compilation outputs cleaned')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rom', type=Path, help='optional external decoded ROM directory; never bundled')
    ROM=parser.parse_args().rom
    main()
