#!/usr/bin/env python3
"""Compile real chord + recorder + camera hooks against deterministic ROM fixtures.
No phone access, Gradle, network, camera or microphone. Uses temporary output only.
"""
import ast
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
here = Path(__file__).resolve().parent
# Reuse Android/OEM fixture declarations only; never execute the old runner.
module = ast.parse((root / 'tests/screen-off-camera-hook/run.py').read_text())
sources = next(ast.literal_eval(n.value) for n in module.body
               if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'sources' for t in n.targets))
sources['android/hardware/SensorPrivacyManager.java'] = sources['android/hardware/SensorPrivacyManager.java'].replace('sensor!=2', 'sensor!=2 && sensor!=1')
sources.update({
'android/util/Log.java': 'package android.util;public class Log {public static int w(String t,String m){return 0;}}',
'android/content/Intent.java': '''package android.content;
public class Intent { public static final String ACTION_USER_UNLOCKED="android.intent.action.USER_UNLOCKED";
 public final String action; public ComponentName component; public final java.util.Map<String,Object> extras=new java.util.HashMap<>();
 public Intent(String a){action=a;} public Intent setComponent(ComponentName c){component=c;return this;}
 public Intent putExtra(String k,boolean v){extras.put(k,v);return this;} public Intent putExtra(String k,int v){extras.put(k,v);return this;}
 public Intent putExtra(String k,long v){extras.put(k,v);return this;} public Intent putExtra(String k,String v){extras.put(k,v);return this;}}
''',
'android/content/IntentFilter.java': 'package android.content; public class IntentFilter { public IntentFilter(String s){} public void addAction(String s){} }',
'android/content/BroadcastReceiver.java': 'package android.content; public abstract class BroadcastReceiver { public abstract void onReceive(Context c,Intent i); }',
'android/content/Context.java': '''package android.content;
import android.os.*;
public class Context {
 public static final String POWER_SERVICE="power", USER_SERVICE="user", INPUT_SERVICE="input", DISPLAY_SERVICE="display";
 public static final int RECEIVER_NOT_EXPORTED=4;
 public android.hardware.SensorPrivacyManager privacy=new android.hardware.SensorPrivacyManager();
 public PowerManager power=new PowerManager(); public UserManager users=new UserManager();
 public android.hardware.input.InputManager inputs=new android.hardware.input.InputManager();
 public android.hardware.display.DisplayManager displays=new android.hardware.display.DisplayManager();
 public int starts; public Intent lastIntent; public UserHandle lastUser; public long identityAtStart;
 public boolean throwStart, nullStart; public java.util.List<BroadcastReceiver> receivers=new java.util.ArrayList<>();
 public Object getSystemService(String s){return switch(s){case POWER_SERVICE->power;case USER_SERVICE->users;case INPUT_SERVICE->inputs;case DISPLAY_SERVICE->displays;default->null;};}
 public Intent registerReceiverAsUser(BroadcastReceiver b,UserHandle u,IntentFilter f,String p,Handler h,int flags){receivers.add(b);return null;}
 public ComponentName startForegroundServiceAsUser(Intent i,UserHandle u){starts++;lastIntent=i;lastUser=u;identityAtStart=Binder.identity;if(throwStart)throw new SecurityException();return nullStart?null:i.component;}
}''',
'android/os/UserManager.java': 'package android.os; public class UserManager {public boolean open=true; public boolean isUserUnlocked(UserHandle u){return open;}}',
'android/os/Handler.java': '''package android.os;
import java.util.*;
public class Handler {
 record Job(long at,long order,Runnable r){} private final List<Job> jobs=new ArrayList<>(); private long order;
 public boolean reject, throwing;
 public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long delay){if(throwing)throw new IllegalStateException();if(reject)return false;jobs.add(new Job(SystemClock.now+delay,order++,r));return true;}
 public void removeCallbacks(Runnable r){jobs.removeIf(j->j.r==r);}
 public void drain(){for(;;){Job n=jobs.stream().filter(j->j.at<=SystemClock.now).min(Comparator.comparingLong(Job::at).thenComparingLong(Job::order)).orElse(null);if(n==null)return;jobs.remove(n);n.r.run();}}
}''',
'android/view/KeyEvent.java': '''package android.view;
public class KeyEvent {
 public static final int KEYCODE_VOLUME_UP=24, ACTION_DOWN=0, ACTION_UP=1;
 public int key,action,repeat,device=1,scan,source=257,meta,flags,display=0; public long down,time;
 public KeyEvent(int k,int a,long d,long t){key=k;action=a;down=d;time=t;scan=k==24?115:114;}
 public int getKeyCode(){return key;} public int getAction(){return action;} public int getRepeatCount(){return repeat;}
 public int getDeviceId(){return device;} public int getScanCode(){return scan;} public int getSource(){return source;}
 public int getMetaState(){return meta;} public int getFlags(){return flags;} public int getDisplayId(){return display;}
 public long getDownTime(){return down;} public long getEventTime(){return time;}
}''',
'android/view/InputDevice.java': '''package android.view;
import java.util.*;
public class InputDevice {
 public static final int SOURCE_KEYBOARD=257; public static final Map<Integer,InputDevice> devices=new HashMap<>();
 public String name="gpio-keys",descriptor="board-gpio"; public boolean virtual,external;
 public static InputDevice getDevice(int id){return devices.get(id);} public static int[] getDeviceIds(){return devices.keySet().stream().mapToInt(i->i).toArray();}
 public String getName(){return name;} public String getDescriptor(){return descriptor;}
 public boolean isVirtual(){return virtual;} public boolean isExternal(){return external;} public boolean supportsSource(int s){return s==257;}
}''',
'android/view/Display.java': '''package android.view; public class Display {
public static final int DEFAULT_DISPLAY=0,STATE_UNKNOWN=0,STATE_OFF=1,STATE_DOZE=3,STATE_DOZE_SUSPEND=4; public int state=STATE_OFF;public int getState(){return state;}}''',
'android/view/ViewConfiguration.java': 'package android.view; public class ViewConfiguration {public static int getMultiPressTimeout(){return 350;}public static int getLongPressTimeout(){return 500;}}',
'android/hardware/display/DisplayManager.java': '''package android.hardware.display;
import android.view.Display; import android.os.Handler;
public class DisplayManager {public Display display=new Display();public Display getDisplay(int i){return display;}
public interface DisplayListener {void onDisplayAdded(int i);void onDisplayRemoved(int i);void onDisplayChanged(int i);}
public void registerDisplayListener(DisplayListener l,Handler h){}}
''',
'android/hardware/input/InputManager.java': '''package android.hardware.input;
import android.os.Handler;public class InputManager {
public interface InputDeviceListener {void onInputDeviceAdded(int i);void onInputDeviceRemoved(int i);void onInputDeviceChanged(int i);}
public java.util.List<InputDeviceListener> listeners=new java.util.ArrayList<>();public void registerInputDeviceListener(InputDeviceListener l,Handler h){listeners.add(l);}}
''',
'com/sevtinge/hyperceiler/common/utils/PrefsBridge.java': '''package com.sevtinge.hyperceiler.common.utils;
public class PrefsBridge {public static final java.util.Map<String,Boolean> values=new java.util.HashMap<>();public static boolean getBoolean(String k,boolean d){return values.getOrDefault(k,d);}}
''',
'com/sevtinge/hyperceiler/libhook/base/BaseHook.java': '''package com.sevtinge.hyperceiler.libhook.base;
import java.lang.reflect.*;import java.util.*;
import io.github.libxposed.api.XposedInterface;import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
public abstract class BaseHook {
 public final String TAG=getClass().getSimpleName();public abstract void init();
 public static final Map<Method,List<IMethodHook>> hooks=new LinkedHashMap<>();
 public static String failHook,failDeopt,missing; public static boolean reverse;
 public static final List<Method> deoptimized=new ArrayList<>();
 public ClassLoader getClassLoader(){return getClass().getClassLoader();}
 public static Class<?> findClassIfExists(String n,ClassLoader l){if(n.equals(missing))return null;try{return Class.forName(n,false,l);}catch(Exception e){return null;}}
 public static boolean deoptimize(Method m){deoptimized.add(m);return !m.getName().equals(failDeopt);}
 public static XposedInterface.HookHandle hookMethod(Method m,IMethodHook h){if(m.getName().equals(failHook))return null;hooks.computeIfAbsent(m,k->new ArrayList<>()).add(h);return ()->hooks.get(m).remove(h);}
 public static HookParam before(Object o,String name,Class<?>[] types,Object...args)throws Exception{
 Method m=o.getClass().getDeclaredMethod(name,types);HookParam p=new HookParam(o,args);
 List<IMethodHook> hs=new ArrayList<>(hooks.getOrDefault(m,List.of()));if(reverse)Collections.reverse(hs);
 // Exercise runtimes that run every callback even after a previous setResult.
 for(IMethodHook h:hs)h.before(p);return p;}
 public static Object call(Object o,String name,Object...args)throws Exception{
 Class<?>[] t=name.equals("onMiuiMultiPress")?new Class<?>[]{Object.class,long.class,int.class,int.class}:
 name.equals("interceptKeyBeforeQueueing")?new Class<?>[]{android.view.KeyEvent.class,int.class}:new Class<?>[0];
 Method m=o.getClass().getDeclaredMethod(name,t);m.setAccessible(true);
 // Camera fixture bridges its method through before(), including reflection replay.
 if(name.equals("onMiuiMultiPress"))return m.invoke(o,args);
 HookParam p=before(o,name,t,args);return p.intercepted?p.result:m.invoke(o,args);}
}''',
'com/android/server/input/shortcut/singlekeyrule/VolumeDownKeyRule.java': '''package com.android.server.input.shortcut.singlekeyrule;
import android.content.Context;import android.os.Handler;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
public class VolumeDownKeyRule extends com.android.server.policy.MiuiSingleKeyRule {
 private int mPolicyFlag; public boolean audio,subscreen;public int nativeCalls,maxCalls;
 public VolumeDownKeyRule(Context c,Handler h){super(c,h);}
 public boolean isAudioActive(){return audio;}public boolean isCameraShowInSubscreen(){return subscreen;}
 public int getMiuiMaxMultiPressCount(){maxCalls++;return 1;}
 public void onMiuiMultiPress(Object o,long t,int count,int id){try{
 var p=BaseHook.before(this,"onMiuiMultiPress",new Class<?>[]{Object.class,long.class,int.class,int.class},o,t,count,id);
 if(!p.intercepted)nativeCalls++;
 }catch(Exception e){throw new RuntimeException(e);}}
}''',
'com/android/server/policy/SingleKeyGestureDetector.java': '''package com.android.server.policy;
import android.view.KeyEvent;import android.os.Message;
public class SingleKeyGestureDetector {
 public int resets,activeKey,count; public long previous=-10000;public boolean unpairedUp;
 public void reset(){resets++;activeKey=count=0;previous=-10000;}
 private void interceptKeyDown(KeyEvent e){} private boolean interceptKeyUp(KeyEvent e){return false;}
 public void interceptKey(KeyEvent e,boolean b,int n){}
 private void startDelayedPress(SingleKeyRule r,int c,int d){} private void cancelDelayedPress(SingleKeyRule r,int c){}
 public static class SingleKeyRule {} public static class KeyHandler {public void handleMessage(Message m){}}
}''',
'com/android/server/policy/MiuiPhoneWindowManager.java': '''package com.android.server.policy;
import android.content.Context;import android.os.Handler;import android.view.KeyEvent;
public class MiuiPhoneWindowManager {
 public Context mContext;public Handler mHandler;public boolean mSystemBooted=true;
 public SingleKeyGestureDetector mSingleKeyGestureDetector=new SingleKeyGestureDetector();
 public Dp mMiuiInputManagerInternal=new Dp();public Proximity mProximitySensor=new Proximity(),mBackScreenProximitySensor=new Proximity();
 public com.android.server.input.shortcut.singlekeyrule.VolumeDownKeyRule rule;
 public java.util.List<KeyEvent> oem=new java.util.ArrayList<>();
 public MiuiPhoneWindowManager(Context c,Handler h){mContext=c;mHandler=h;rule=new com.android.server.input.shortcut.singlekeyrule.VolumeDownKeyRule(c,h);}
 public void systemBooted(){}
 public int interceptKeyBeforeQueueing(KeyEvent e,int flags){oem.add(e);var d=mSingleKeyGestureDetector;
 if(e.key!=24&&e.key!=25)return 1;
 if(e.action==0&&e.repeat==0){if(d.activeKey!=e.key){d.activeKey=e.key;d.count=0;}
 d.count=e.down-d.previous<=350?d.count+1:1;d.previous=e.down;
 if(e.key==25&&d.count==2)rule.onMiuiMultiPress(new Object(),e.down,2,0);
 }else if(e.action==1&&d.activeKey==0)d.unpairedUp=true;
 return 1;}
 public static class Dp {public boolean mode;public boolean isDpInMonitorMode(){return mode;}}
 public static class Proximity {public boolean held;public boolean isHeld(){return held;}}
}''',
'com/android/server/wm/InputManagerCallback.java': 'package com.android.server.wm;public class InputManagerCallback {public int interceptKeyBeforeQueueing(android.view.KeyEvent e,int f){return 1;}}',
'com/android/server/input/InputManagerService.java': 'package com.android.server.input;public class InputManagerService {public int interceptKeyBeforeQueueing(android.view.KeyEvent e,int f){return 1;}}',
'com/android/server/input/UnderwaterInputController.java': 'package com.android.server.input;public class UnderwaterInputController {public static boolean active;public static UnderwaterInputController getInstance(){return new UnderwaterInputController();}public boolean isActive(){return active;}}',
'miui/enterprise/EnterpriseManagerStub.java': 'package miui.enterprise;public class EnterpriseManagerStub {public static boolean ENTERPRISE_ACTIVATED;}',
})
base = root / 'library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/systemframework/volume'
production = [base / (n + '.java') for n in ['VolumeVideoChordState','VolumeVideoChord','ScreenOffRecorderState','ScreenOffRecorder','ScreenOffCamera']]
with tempfile.TemporaryDirectory(prefix='video-chord-') as tmp:
    tmp = Path(tmp)
    fixtures = []
    for name, text in sources.items():
        p = tmp / 'src' / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
        fixtures.append(p)
    subprocess.run([shutil.which('javac'), '-encoding', 'UTF-8', '-d', str(tmp / 'classes'),
                    *map(str,fixtures + production + [here/'VolumeVideoChordTest.java'])], check=True)
    subprocess.run([shutil.which('java'), '-ea', '-cp', str(tmp/'classes'), 'VolumeVideoChordTest'],check=True)
