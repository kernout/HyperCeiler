import android.app.ActivityManager;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import com.android.server.policy.MiuiPhoneWindowManager;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.rules.systemframework.volume.*;

public class VolumeVideoChordTest {
    static int assertions;
    static final String PREF="system_framework_volume_video_chord";
    static void check(boolean b,String message){assertions++;if(!b)throw new AssertionError(message);}
    static final VolumeVideoChordState.Source UP=new VolumeVideoChordState.Source(1,"board-gpio",257);
    static final VolumeVideoChordState.Source DOWN=new VolumeVideoChordState.Source(4,"board-resin",257);
    static VolumeVideoChordState.Event ev(int k,int a,long d,long t){return new VolumeVideoChordState.Event(k,a,0,d,t,k==25?DOWN:UP,true,true,10);}
    static int accept(VolumeVideoChordState s,int k,int a,long d,long t){return s.accept(ev(k,a,d,t),true);}
    static int chord(VolumeVideoChordState s,long t,int first,long slop){
        int second=first==24?25:24;
        check(accept(s,first,0,t,t)==0,"first DOWN passes");
        check(accept(s,second,0,t+slop,t+slop)==3,"split second DOWN claims overlapping pair");
        check(s.volumeUpDevice()==1,"recorder cancellation uses UP device, not second event");
        check(accept(s,first,1,t,t+slop+10)==1,"first release consumed, not complete");
        return accept(s,second,1,t+slop,t+slop+20);
    }
    static void pureState(){
        for(int first:new int[]{24,25})for(long slop:new long[]{0,1,119,120}){
            var s=new VolumeVideoChordState();check(chord(s,1000,first,slop)==5,"one full single chord triggers");
            check(chord(s,1300,first==24?25:24,slop)==5,"next single chord toggles once");
            check(accept(s,24,1,1300,1460)==0,"extra release never retriggers");
        }
        var s=new VolumeVideoChordState();accept(s,24,0,1000,1000);
        check(accept(s,25,0,1121,1121)==0,"121ms not simultaneous");
        check(accept(s,24,1,1000,1130)==0&&accept(s,25,1,1121,1140)==0,"non-chord legacy releases pass");
        s=new VolumeVideoChordState();accept(s,24,0,1000,1000);accept(s,24,1,1000,1010);
        check(accept(s,25,0,1020,1020)==0,"nonoverlapping taps are not chord");
        for(int invalid=0;invalid<8;invalid++){
            s=new VolumeVideoChordState();accept(s,24,0,1000,1000);accept(s,25,0,1020,1020);
            if(invalid==0)s.cancel();
            else if(invalid==1)s.accept(new VolumeVideoChordState.Event(24,0,1,1000,1021,UP,true,true,10),true);
            else if(invalid==2)s.accept(new VolumeVideoChordState.Event(26,0,0,1021,1021,UP,true,true,10),true);
            else if(invalid==3)s.accept(new VolumeVideoChordState.Event(24,0,0,1000,1021,UP,true,true,10),true);
            else if(invalid==4)s.accept(new VolumeVideoChordState.Event(24,0,0,1021,1021,new VolumeVideoChordState.Source(9,"other",257),true,true,10),true);
            else if(invalid==5)s.accept(new VolumeVideoChordState.Event(24,0,0,1021,1021,UP,false,true,10),true);
            else if(invalid==6)s.accept(new VolumeVideoChordState.Event(24,0,0,1000,1021,UP,true,false,10),true);
            else s.accept(ev(24,0,1000,1021),false);
            check(accept(s,24,1,1000,1030)==1&&accept(s,25,1,1020,1040)==1,"cancel drains every per-device owned UP "+invalid);
            check(!s.hasOwnedPress(),"ownership drained");
        }
        s=new VolumeVideoChordState();accept(s,24,0,1000,1000);accept(s,25,0,1020,1020);
        check(s.accept(new VolumeVideoChordState.Event(24,1,0,1000,1030,UP,true,true,11),true)==1,"user switch drains old UP");
        check(accept(s,25,1,1020,1040)==1,"user switch cannot trigger, drains other UP");
        s=new VolumeVideoChordState();accept(s,24,0,1000,1000);accept(s,25,0,1020,1020);
        check(accept(s,24,1,1000,1500)==1&&accept(s,25,1,1020,1510)==1,"long press consumed without trigger");
        s=new VolumeVideoChordState();accept(s,24,0,1000,1000);accept(s,25,0,1020,1020);s.deviceChanged(1);
        check(s.hasOwnedPress()&&accept(s,24,1,1000,1030)==0,"removed UP reclaimed, DOWN retained");
        check(accept(s,25,1,1020,1040)==1&&!s.hasOwnedPress(),"surviving device release drains after removal");
        check(chord(s,1200,24,0)==5,"device recovery allows fresh simultaneous chord");
    }
    record Fixture(Context c,Handler h,MiuiPhoneWindowManager p){}
    static void reset()throws Exception{
        for(String[] field:new String[][]{{"VolumeVideoChord","active"},{"ScreenOffRecorder","activeRecorder"},{"ScreenOffCamera","chordCooperative"}}){
            var f=Class.forName("com.sevtinge.hyperceiler.libhook.rules.systemframework.volume."+field[0]).getDeclaredField(field[1]);f.setAccessible(true);f.set(null,f.getType()==boolean.class?false:null);
        }
        PrefsBridge.values.clear();BaseHook.hooks.clear();BaseHook.deoptimized.clear();BaseHook.failHook=BaseHook.failDeopt=BaseHook.missing=null;
        BaseHook.reverse=false;ActivityManager.user=10;SystemClock.now=1000;SystemClock.elapsed=90000;
        Binder.identity=99;Binder.clears=Binder.restores=0;SplitInputFixture.install();
        com.sevtinge.hyperceiler.common.log.XposedLog.logs.clear();
        miui.enterprise.EnterpriseManagerStub.ENTERPRISE_ACTIVATED=false;com.android.server.input.UnderwaterInputController.active=false;
    }
    static Fixture install(boolean recorder,boolean camera,boolean interactive)throws Exception{
        reset();PrefsBridge.values.put(PREF,true);
        PrefsBridge.values.put("system_framework_volume_screen_off_recorder",recorder);
        PrefsBridge.values.put("system_framework_volume_screen_off_camera",camera);
        new VolumeVideoChord().init();if(recorder)new ScreenOffRecorder().init();if(camera)new ScreenOffCamera().init();
        Context c=new Context();c.power.interactive=interactive;if(interactive)c.displays.display.state=2;
        Handler h=new Handler();return new Fixture(c,h,new MiuiPhoneWindowManager(c,h));
    }
    static int key(Fixture f,int k,int a,long d,long t)throws Exception{return key(f,new KeyEvent(k,a,d,t),0);}
    static int key(Fixture f,KeyEvent e,int flags)throws Exception{
        SystemClock.now=Math.max(SystemClock.now,e.time);SystemClock.elapsed=SystemClock.now+89000;
        return (Integer)BaseHook.call(f.p,"interceptKeyBeforeQueueing",e,flags);
    }
    static void pair(Fixture f,long t,int first)throws Exception{
        int second=first==24?25:24;
        key(f,first,0,t,t);check(key(f,second,0,t+20,t+20)==0,"hook claims second down");
        check(key(f,first,1,t,t+30)==0,"hook consumes first up");
        check(key(f,second,1,t+20,t+40)==0,"hook consumes second up");
    }
    static void toggle(Fixture f,long t)throws Exception{pair(f,t,24);}
    static void advance(Fixture f,long t){SystemClock.now=t;SystemClock.elapsed=t+89000;f.h.drain();}
    static void integration()throws Exception{
        reset();new VolumeVideoChord().init();check(BaseHook.hooks.isEmpty(),"pref defaults off");
        for(String missing:new String[]{"com.android.server.policy.MiuiPhoneWindowManager","android.hardware.SensorPrivacyManager","com.android.server.policy.SingleKeyGestureDetector"}){
            reset();PrefsBridge.values.put(PREF,true);BaseHook.missing=missing;new VolumeVideoChord().init();check(BaseHook.hooks.isEmpty(),"unsupported ABI no fallback");
        }
        reset();PrefsBridge.values.put(PREF,true);BaseHook.failDeopt="interceptKeyBeforeQueueing";new VolumeVideoChord().init();check(BaseHook.hooks.isEmpty(),"deopt rejection no hook");
        reset();PrefsBridge.values.put(PREF,true);BaseHook.failHook="interceptKeyBeforeQueueing";new VolumeVideoChord().init();check(BaseHook.hooks.values().stream().allMatch(java.util.List::isEmpty),"installation rejection rolls back camera guard");
        for(boolean reverse:new boolean[]{false,true})for(boolean recorder:new boolean[]{false,true})for(boolean interactive:new boolean[]{false,true}){
            Fixture f=install(recorder,true,interactive);BaseHook.reverse=reverse;
            // Bootstrap recorder eligibility without introducing a physical key.
            key(f,26,1,1000,1000);f.h.drain();f.p.oem.clear();
            toggle(f,1100);f.h.drain();
            check(f.c.starts==1,"single split chord starts one FGS across hook orders/screens");
            check(f.p.mSingleKeyGestureDetector.resets==1&&!f.p.mSingleKeyGestureDetector.unpairedUp,"OEM state reset, no orphan releases");
            check(f.p.rule.nativeCalls==0,"video chord never reaches camera OEM double branch");
            check(f.c.lastIntent.action.equals("com.sevtinge.hyperceiler.action.VOLUME_VIDEO_TOGGLE"),"fixed service action");
            check(f.c.lastIntent.component.cls.equals("com.sevtinge.hyperceiler.video.VolumeVideoService"),"explicit service");
            check(f.c.lastUser.id==10&&f.c.lastIntent.extras.get("request_user").equals(10),"current-user FGS and extra");
            java.util.UUID.fromString((String)f.c.lastIntent.extras.get("request_id"));
            check(f.c.lastIntent.extras.get("requested_at").equals(90140L),"elapsedRealtime captured on completion");
            check(f.c.privacy.combinedCalls==2&&f.c.privacy.identity==1000&&Binder.identity==99,"CAMERA privacy under cleared identity/restored");
            // Old down-only double click still works; second DOWN callback held until chord ambiguity ends.
            key(f,25,0,1500,1500);key(f,25,1,1500,1510);key(f,25,0,1600,1600);
            check(f.c.starts==1,"legacy camera waits through slop");key(f,25,1,1600,1610);advance(f,1721);
            check(interactive?f.p.rule.nativeCalls==1:f.c.starts==2,"single-key camera preserved after chord");
            if(recorder&&!interactive){
                key(f,24,0,2000,2000);key(f,24,1,2000,2010);key(f,24,0,2100,2100);key(f,24,1,2100,2110);f.h.drain();
                check(f.c.starts==3&&f.c.lastIntent.action.endsWith("SCREEN_OFF_RECORDING_TOGGLE"),"old recorder usable after chord ownership transfer");
            }
        }
        // Final release at +9.9s may be delivered +200ms later: preserve event time.
        Fixture delayed=install(false,false,true);
        key(delayed,25,0,1300,1300); key(delayed,24,0,1320,1320);
        key(delayed,25,1,1300,1330);
        SystemClock.now=1540;
        key(delayed,24,1,1320,1340); delayed.h.drain();
        check(delayed.c.starts==1,"delayed physical chord delivered");
        check(delayed.c.lastIntent.extras.get("requested_at").equals(90340L),
            "requested_at excludes 200ms input delivery delay at minimum-duration boundary");
        // DOWN-first recorder claim must release legacy ownership, not just cancel its phase.
        Fixture f=install(true,true,false);key(f,26,1,1000,1000);f.h.drain();pair(f,1100,24);f.h.drain();
        key(f,24,0,1500,1500);key(f,24,1,1500,1510);key(f,24,0,1600,1600);key(f,24,1,1600,1610);f.h.drain();
        check(f.c.starts==2&&f.c.lastIntent.action.endsWith("SCREEN_OFF_RECORDING_TOGGLE"),"standalone UP double press works after one chord");
        for(boolean reverse:new boolean[]{false,true})for(int first:new int[]{24,25}){
            f=install(true,true,false);BaseHook.reverse=reverse;key(f,26,1,1000,1000);f.h.drain();
            pair(f,1100,first);f.h.drain();
            key(f,24,0,1500,1500);key(f,24,1,1500,1510);key(f,24,0,1600,1600);key(f,24,1,1600,1610);f.h.drain();
            check(f.c.starts==2&&f.c.lastIntent.action.endsWith("SCREEN_OFF_RECORDING_TOGGLE"),"recorder pressed map recovered after either split-chord order");
        }
        // Existing OEM camera count=2 callback from first DOWN must be cancelled when second key arrives.
        for(boolean interactive:new boolean[]{false,true}){
            f=install(false,true,interactive);key(f,25,0,1100,1100);key(f,25,1,1100,1110);
            key(f,25,0,1200,1200);check(f.c.starts==0&&f.p.rule.nativeCalls==0,"OEM count2 callback deferred");
            key(f,24,0,1220,1220);key(f,25,1,1200,1230);key(f,24,1,1220,1240);advance(f,1400);
            check(f.c.starts==1&&f.p.rule.nativeCalls==0,"claim cancels deferred camera callback without dropping video");
        }
        // Invalid intervening keys must not discard a deferred ordinary camera.
        for(boolean camera:new boolean[]{false,true})for(int interruption=0;interruption<3;interruption++){
            f=install(false,camera,true);key(f,25,0,1100,1100);key(f,25,1,1100,1110);
            key(f,25,0,1200,1200);
            if(interruption==0)key(f,26,0,1210,1210);
            else if(interruption==1){KeyEvent injected=new KeyEvent(24,0,1210,1210);key(f,injected,0x01000000);}
            else {KeyEvent repeat=new KeyEvent(25,0,1200,1210);repeat.repeat=1;key(f,repeat,0);}
            key(f,25,1,1200,1230);advance(f,1321);
            check(f.p.rule.nativeCalls==1&&f.c.starts==0,"ordinary native camera retained after rejected ambiguity "+camera+"/"+interruption);
        }
        // Failed camera deferral must leave the original callback OEM-owned.
        f=install(false,false,true);key(f,25,0,1100,1100);key(f,25,1,1100,1110);
        f.h.reject=true;key(f,25,0,1200,1200);
        check(f.p.rule.nativeCalls==1,"Handler rejection does not swallow ordinary native camera callback");
        // Cancellation while keys are still owned drains each independent device UP.
        for(int cancellation=0;cancellation<4;cancellation++){
            f=install(true,true,false);key(f,26,1,1000,1000);f.h.drain();
            key(f,24,0,1100,1100);key(f,25,0,1120,1120);
            switch(cancellation){
                case 0->ActivityManager.user=11;
                case 1->PrefsBridge.values.put(PREF,false);
                case 2->f.c.users.open=false;
                case 3->{for(var receiver:f.c.receivers)receiver.onReceive(f.c,new android.content.Intent("android.intent.action.USER_SWITCHED"));}
            }
            check(key(f,24,1,1100,1130)==0&&key(f,25,1,1120,1140)==0,"all cancelled owned releases swallowed "+cancellation);
            f.h.drain();check(f.c.starts==0,"cancelled chord cannot dispatch "+cancellation);
        }
        f=install(false,false,true);key(f,24,0,1100,1100);key(f,25,0,1120,1120);f.c.users.throwing=true;
        check(key(f,24,1,1100,1130)==0&&key(f,25,1,1120,1140)==0,"live policy exception still drains exact owned physical releases");
        f.h.drain();check(f.c.starts==0,"policy exception never dispatches");f.c.users.throwing=false;
        pair(f,1500,24);f.h.drain();check(f.c.starts==1,"new chord works after exception drain");
        // Hot unplug/reconfiguration reclaims only the changed role.
        f=install(false,false,true);key(f,24,0,1100,1100);key(f,25,0,1120,1120);
        for(var listener:f.c.inputs.listeners)listener.onInputDeviceRemoved(1);
        InputDevice.devices.remove(1);
        check(key(f,25,1,1120,1140)==0,"pmic_resin UP still owned after gpio removal");
        f.h.drain();check(f.c.starts==0,"hot unplug cancels chord");
        for(int gate=0;gate<10;gate++){
            f=install(false,false,true);toggle(f,1100);
            switch(gate){case 0->f.c.privacy.all=true;case 1->f.c.privacy.software=true;case 2->f.c.privacy.hardware=true;
                case 3->f.c.privacy.throwGlobal=true;case 4->f.c.privacy.throwCombined=true;case 5->f.c.privacy.switchUser=true;
                case 6->ActivityManager.user=11;case 7->f.c.users.open=false;case 8->PrefsBridge.values.put(PREF,false);
                case 9->SystemClock.now+=401;}
            f.h.drain();check(f.c.starts==0,"dispatch live/privacy/user gate "+gate);check(Binder.identity==99,"identity restored after gate "+gate);
        }
        for(int sourceCase=0;sourceCase<9;sourceCase++){
            f=install(false,false,true);
            for(long t:new long[]{1100,1300}){
                KeyEvent first=new KeyEvent(24,0,t,t);key(f,first,0);
                KeyEvent second=new KeyEvent(25,0,t+20,t+20);int flags=0;
                switch(sourceCase){case 0->flags=0x01000000;case 1->second.flags=0x400;case 2->second.device=2;
                    case 3->second.scan=115;case 4->second.source=0x1010;case 5->second.display=2;
                    case 6->second.meta=1;case 7->second.repeat=1;case 8->InputDevice.devices.get(4).external=true;}
                key(f,second,flags);key(f,24,1,t,t+30);key(f,25,1,t+20,t+40);
                InputDevice.devices.get(4).external=false;
            }
            f.h.drain();check(f.c.starts==0,"reject injected/other device/source/flags "+sourceCase);
        }
        for(int roleCase=0;roleCase<10;roleCase++){
            f=install(false,false,true);InputDevice d=InputDevice.devices.get(4);
            switch(roleCase){
                case 0->d.name="gpio-keys";
                case 1->d.virtual=true;
                case 2->d.external=true;
                case 3->d.keyboard=false;
                case 4->d.down=false;
                case 5->d.up=true;
                case 6->d.descriptor="";
                case 7->{InputDevice extra=new InputDevice();extra.name="pmic_resin";extra.descriptor="another-resin";extra.up=false;extra.down=true;InputDevice.devices.put(8,extra);}
                case 8->InputDevice.devices.remove(4);
                case 9->{InputDevice.devices.get(1).up=false;}
            }
            key(f,24,0,1100,1100);key(f,25,0,1120,1120);key(f,24,1,1100,1130);key(f,25,1,1120,1140);f.h.drain();
            check(f.c.starts==0,"exact enumerated physical board-role gate "+roleCase);
        }
        // A genuine single press toggles; repeated UP and dual-hook route do not.
        f=install(true,true,true);pair(f,1100,24);f.h.drain();check(f.c.starts==1,"first click toggles");
        key(f,24,1,1100,1141);key(f,25,1,1120,1142);f.h.drain();check(f.c.starts==1,"duplicate UP never toggles");
        pair(f,1500,25);f.h.drain();check(f.c.starts==2,"second click sends service stop/toggle even below ten seconds");
        var logs=com.sevtinge.hyperceiler.common.log.XposedLog.logs;
        check(logs.stream().anyMatch(v->v.contains("Installed single simultaneous"))&&logs.stream().anyMatch(v->v.contains("accepted/full-release"))&&logs.stream().anyMatch(v->v.contains("dispatched/foreground-service")),"installation acceptance dispatch diagnostics");
        f=install(false,false,true);key(f,24,0,1100,1100);ActivityManager.user=11;key(f,25,0,1120,1120);key(f,24,1,1100,1130);key(f,25,1,1120,1140);f.h.drain();check(f.c.starts==0,"chord cannot cross users");
        f=install(false,false,true);pair(f,1100,24);for(var listener:f.c.inputs.listeners)listener.onInputDeviceChanged(1);f.h.drain();check(f.c.starts==0,"device callback invalidates candidate");
        f=install(false,false,true);toggle(f,1100);for(var receiver:f.c.receivers)receiver.onReceive(f.c,new android.content.Intent("android.intent.action.USER_SWITCHED"));f.h.drain();check(f.c.starts==0,"user callback invalidates queued dispatch");
        f=install(false,false,true);toggle(f,1100);f.h.reject=true;f.h.drain();check(f.c.starts==1,"existing queued dispatch independent of subsequent Handler rejection");
        f=install(false,false,true);f.h.reject=true;toggle(f,1100);check(f.c.starts==0,"Handler rejection does not dispatch");f.h.reject=false;toggle(f,1700);f.h.drain();check(f.c.starts==1,"Handler failure releases reservation");
        f=install(false,false,true);toggle(f,1100);f.c.throwStart=true;f.h.drain();check(Binder.identity==99,"FGS exception restores identity");f.c.throwStart=false;toggle(f,1700);f.h.drain();check(f.c.starts==2,"FGS failure releases reservation");
    }
    public static void main(String[] args)throws Exception{pureState();integration();System.out.println("PASS VolumeVideoChordTest: "+assertions+" assertions (pure state + 5 production classes)");}
}
