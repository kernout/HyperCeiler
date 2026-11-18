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
    static final VolumeVideoChordState.Source BOARD=new VolumeVideoChordState.Source(1,"gpio",257);
    static VolumeVideoChordState.Event ev(int k,int a,long d,long t){return new VolumeVideoChordState.Event(k,a,0,d,t,BOARD,true,true,10);}
    static int accept(VolumeVideoChordState s,int k,int a,long d,long t){return s.accept(ev(k,a,d,t),true);}
    static int chord(VolumeVideoChordState s,long t,int first,long slop){
        int second=first==24?25:24;
        check(accept(s,first,0,t,t)==0,"first DOWN passes");
        check(accept(s,second,0,t+slop,t+slop)==3,"second DOWN claims overlapping pair");
        check(accept(s,first,1,t,t+slop+10)==1,"first release consumed, not complete");
        return accept(s,second,1,t+slop,t+slop+20);
    }
    static void pureState(){
        for(int first:new int[]{24,25})for(long slop:new long[]{0,1,119,120}){
            var s=new VolumeVideoChordState();check(chord(s,1000,first,slop)==1,"one complete chord no trigger");
            check(chord(s,1300,first==24?25:24,slop)==5,"two chords trigger once after both releases");
            check(chord(s,1600,first,slop)==1,"third chord begins a new pair");
        }
        for(int gap:new int[]{349,350,351}){
            var s=new VolumeVideoChordState();chord(s,1000,24,20);
            check(chord(s,1040+gap,25,20)==(gap<=350?5:1),"350ms last-release to next-first-down boundary");
        }
        var s=new VolumeVideoChordState();accept(s,24,0,1000,1000);
        check(accept(s,25,0,1121,1121)==0,"121ms not simultaneous");
        check(accept(s,24,1,1000,1130)==0&&accept(s,25,1,1121,1140)==0,"non-chord legacy releases pass");
        s=new VolumeVideoChordState();accept(s,24,0,1000,1000);accept(s,24,1,1000,1010);
        check(accept(s,25,0,1020,1020)==0,"nonoverlapping taps are not a chord");
        s=new VolumeVideoChordState();accept(s,24,0,1000,1000);accept(s,25,0,1020,1020);
        check(accept(s,24,1,1000,1030)==1,"partial release remains owned");
        check(accept(s,24,0,1040,1040)==0,"repress before complete release invalidates chord");
        accept(s,25,1,1020,1050);accept(s,24,1,1040,1060);
        check(chord(s,1100,24,20)==1,"partial release did not count");
        for(int invalid=0;invalid<9;invalid++){
            s=new VolumeVideoChordState();chord(s,1000,24,20);accept(s,24,0,1200,1200);
            var e=ev(25,0,1220,1220);
            e=switch(invalid){
                case 0->new VolumeVideoChordState.Event(25,0,1,1220,1220,BOARD,true,true,10);
                case 1->new VolumeVideoChordState.Event(25,0,0,1220,1220,BOARD,false,true,10);
                case 2->new VolumeVideoChordState.Event(25,0,0,1220,1220,new VolumeVideoChordState.Source(2,"other",257),true,true,10);
                case 3->new VolumeVideoChordState.Event(25,0,0,1220,1220,BOARD,true,true,11);
                case 4->new VolumeVideoChordState.Event(25,0,0,1220,1220,BOARD,true,false,10);
                case 5->new VolumeVideoChordState.Event(25,0,0,1220,1219,BOARD,true,true,10);
                case 6->new VolumeVideoChordState.Event(25,0,0,1220,1221,BOARD,true,true,10);
                case 7->new VolumeVideoChordState.Event(26,0,0,1220,1220,BOARD,true,true,10);
                default->e;
            };
            int r=s.accept(e,invalid!=8);check((r&4)==0,"invalid event cannot trigger");
            check((accept(s,24,1,1200,1300)&4)==0,"invalid sequence release cannot trigger");
        }
        s=new VolumeVideoChordState();accept(s,24,0,1000,1000);accept(s,25,0,1020,1020);
        s.cancel();check(accept(s,24,1,1000,1030)==1&&accept(s,25,1,1020,1040)==1,"cancel retains both owned releases");
        check(!s.hasOwnedPress(),"cancel drains ownership");
        s=new VolumeVideoChordState();accept(s,24,0,1000,1000);accept(s,25,0,1020,1020);
        check(accept(s,24,1,1000,1500)==1&&accept(s,25,1,1020,1510)==1,"long press consumed without completing");
        check(chord(s,1600,24,20)==1,"long press is not the first completed chord");
        s=new VolumeVideoChordState();accept(s,24,0,1000,1000);accept(s,25,0,1020,1020);s.deviceChanged(1);
        check(!s.hasOwnedPress()&&accept(s,24,1,1000,1030)==0,"removed device cannot retain ownership");
    }
    record Fixture(Context c,Handler h,MiuiPhoneWindowManager p){}
    static void reset()throws Exception{
        for(String[] field:new String[][]{{"VolumeVideoChord","active"},{"ScreenOffRecorder","activeRecorder"},{"ScreenOffCamera","chordCooperative"}}){
            var f=Class.forName("com.sevtinge.hyperceiler.libhook.rules.systemframework.volume."+field[0]).getDeclaredField(field[1]);f.setAccessible(true);f.set(null,f.getType()==boolean.class?false:null);
        }
        PrefsBridge.values.clear();BaseHook.hooks.clear();BaseHook.deoptimized.clear();BaseHook.failHook=BaseHook.failDeopt=BaseHook.missing=null;
        BaseHook.reverse=false;ActivityManager.user=10;SystemClock.now=1000;SystemClock.elapsed=90000;
        Binder.identity=99;Binder.clears=Binder.restores=0;InputDevice.devices.clear();InputDevice.devices.put(1,new InputDevice());
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
    static void toggle(Fixture f,long t)throws Exception{pair(f,t,24);pair(f,t+200,25);}
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
            check(f.c.starts==1,"double chord starts one FGS across hook orders/screens");
            check(f.p.mSingleKeyGestureDetector.resets==2&&!f.p.mSingleKeyGestureDetector.unpairedUp,"OEM state reset, no orphan releases");
            check(f.p.rule.nativeCalls==0,"video chord never reaches camera OEM double branch");
            check(f.c.lastIntent.action.equals("com.sevtinge.hyperceiler.action.VOLUME_VIDEO_TOGGLE"),"fixed service action");
            check(f.c.lastIntent.component.cls.equals("com.sevtinge.hyperceiler.video.VolumeVideoService"),"explicit service");
            check(f.c.lastUser.id==10&&f.c.lastIntent.extras.get("request_user").equals(10),"current-user FGS and extra");
            java.util.UUID.fromString((String)f.c.lastIntent.extras.get("request_id"));
            check(f.c.lastIntent.extras.get("requested_at").equals(90340L),"elapsedRealtime captured on completion");
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
        // DOWN-first recorder claim must release legacy ownership, not just cancel its phase.
        Fixture f=install(true,true,false);key(f,26,1,1000,1000);f.h.drain();pair(f,1100,24);
        key(f,24,0,1500,1500);key(f,24,1,1500,1510);key(f,24,0,1600,1600);key(f,24,1,1600,1610);f.h.drain();
        check(f.c.starts==1&&f.c.lastIntent.action.endsWith("SCREEN_OFF_RECORDING_TOGGLE"),"standalone UP double press works after only one chord");
        // Existing OEM camera count=2 callback from first DOWN must be cancelled when second key arrives.
        for(boolean interactive:new boolean[]{false,true}){
            f=install(false,true,interactive);key(f,25,0,1100,1100);key(f,25,1,1100,1110);
            key(f,25,0,1200,1200);check(f.c.starts==0&&f.p.rule.nativeCalls==0,"OEM count2 callback deferred");
            key(f,24,0,1220,1220);key(f,25,1,1200,1230);key(f,24,1,1220,1240);advance(f,1400);
            check(f.c.starts==0&&f.p.rule.nativeCalls==0,"claim cancels deferred camera callback");
        }
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
                    case 6->second.meta=1;case 7->second.repeat=1;case 8->InputDevice.devices.get(1).external=true;}
                key(f,second,flags);key(f,24,1,t,t+30);key(f,25,1,t+20,t+40);
                InputDevice.devices.get(1).external=false;
            }
            f.h.drain();check(f.c.starts==0,"reject injected/other device/source/flags "+sourceCase);
        }
        f=install(false,false,true);pair(f,1100,24);ActivityManager.user=11;pair(f,1300,25);f.h.drain();check(f.c.starts==0,"chords cannot cross users");
        f=install(false,false,true);pair(f,1100,24);for(var listener:f.c.inputs.listeners)listener.onInputDeviceChanged(1);pair(f,1300,25);f.h.drain();check(f.c.starts==0,"device callback invalidates candidate");
        f=install(false,false,true);toggle(f,1100);for(var receiver:f.c.receivers)receiver.onReceive(f.c,new android.content.Intent("android.intent.action.USER_SWITCHED"));f.h.drain();check(f.c.starts==0,"user callback invalidates queued dispatch");
        f=install(false,false,true);toggle(f,1100);f.h.reject=true;f.h.drain();check(f.c.starts==1,"existing queued dispatch independent of subsequent Handler rejection");
        f=install(false,false,true);f.h.reject=true;toggle(f,1100);check(f.c.starts==0,"Handler rejection does not dispatch");f.h.reject=false;toggle(f,1700);f.h.drain();check(f.c.starts==1,"Handler failure releases reservation");
        f=install(false,false,true);toggle(f,1100);f.c.throwStart=true;f.h.drain();check(Binder.identity==99,"FGS exception restores identity");f.c.throwStart=false;toggle(f,1700);f.h.drain();check(f.c.starts==2,"FGS failure releases reservation");
    }
    public static void main(String[] args)throws Exception{pureState();integration();System.out.println("PASS VolumeVideoChordTest: "+assertions+" assertions (pure state + 5 production classes)");}
}
