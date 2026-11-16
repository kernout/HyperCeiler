import android.app.ActivityManager;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import com.android.server.input.shortcut.singlekeyrule.VolumeDownKeyRule;
import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.rules.systemframework.volume.ScreenOffCamera;

public class ScreenOffCameraHookTest {
    private static int assertions;
    private static final String CLICK = "onMiuiMultiPress";
    private static final String COUNT = "getMiuiMaxMultiPressCount";
    private record Fixture(Context context, Handler handler, VolumeDownKeyRule rule) {}

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static void reset() {
        BaseHook.missingPrivacy=false; BaseHook.badPrivacyApi=false; BaseHook.hooks.clear(); BaseHook.deoptimized.clear(); BaseHook.failDeopt=null; BaseHook.throwDeopt=false; BaseHook.missing=false; BaseHook.mismatch=false; BaseHook.failHook=null;
        PrefsBridge.enabled=true; PrefsBridge.throwing=false; Build.VERSION.SDK_INT=37;
        SystemClock.now=0; SystemClock.elapsed=80000; ActivityManager.user=10; XposedLog.logs.clear();
        Binder.identity=99; Binder.clears=0; Binder.restores=0;
    }

    private static Fixture install() {
        reset();
        new ScreenOffCamera().init();
        check(BaseHook.hooks.size()==2, "only two exact new-rule methods installed");
        Context context = new Context(); Handler handler = new Handler();
        return new Fixture(context, handler, new VolumeDownKeyRule(context, handler));
    }

    private static void click(Fixture f) throws Exception { BaseHook.call(f.rule, CLICK, new Object(), 123L, 2, 0); }

    public static void main(String[] args) throws Exception {
        reset(); PrefsBridge.enabled=null; new ScreenOffCamera().init();
        check(BaseHook.hooks.isEmpty(), "default preference is disabled");
        reset(); Build.VERSION.SDK_INT=34; new ScreenOffCamera().init();
        check(BaseHook.hooks.isEmpty(), "SDK gate");
        check(XposedLog.logs.stream().anyMatch(s->s.contains("UNSUPPORTED")), "SDK unsupported logged");
        reset(); BaseHook.missing=true; new ScreenOffCamera().init();
        check(BaseHook.hooks.isEmpty(), "no fallback when new class absent");
        check(XposedLog.logs.stream().anyMatch(s->s.contains("UNSUPPORTED")), "absent class logged");
        reset(); BaseHook.mismatch=true; new ScreenOffCamera().init();
        check(BaseHook.hooks.isEmpty(), "validate complete API before installing hooks");
        reset(); BaseHook.failHook=CLICK; new ScreenOffCamera().init();
        check(BaseHook.hooks.isEmpty(), "never install count hook without interception");
        reset(); BaseHook.failHook=COUNT; new ScreenOffCamera().init();
        check(BaseHook.hooks.isEmpty(), "rollback capture hook on count hook failure");

        for(String caller: new String[]{"onKeyGesture", "handleMessage", "startDelayedPress",
                "cancelDelayedPress", "getMaxMultiPressCount", "interceptKeyDown", "interceptKeyUp", "interceptKey"}) {
            reset(); BaseHook.failDeopt=caller; new ScreenOffCamera().init();
            check(BaseHook.hooks.isEmpty(), "deopt rejection prevents hook installation: "+caller);
            check(XposedLog.logs.stream().anyMatch(s->s.contains("UNSUPPORTED")), "deopt rejection logged");
        }
        reset(); BaseHook.throwDeopt=true; new ScreenOffCamera().init();
        check(BaseHook.hooks.isEmpty(), "deopt exception prevents any hook installation");

        Fixture f=install();
        java.util.Set<String> names=new java.util.HashSet<>();
        for(var m: BaseHook.deoptimized) names.add(m.getDeclaringClass().getName()+"#"+m.getName());
        check(names.equals(java.util.Set.of(
            "com.android.server.policy.MiuiSingleKeyRule#onKeyGesture",
            "com.android.server.policy.MiuiSingleKeyRule#getMaxMultiPressCount",
            "com.android.server.policy.SingleKeyGestureDetector$KeyHandler#handleMessage",
            "com.android.server.policy.SingleKeyGestureDetector#startDelayedPress",
            "com.android.server.policy.SingleKeyGestureDetector#cancelDelayedPress",
            "com.android.server.policy.SingleKeyGestureDetector#interceptKeyDown",
            "com.android.server.policy.SingleKeyGestureDetector#interceptKeyUp",
            "com.android.server.policy.SingleKeyGestureDetector#interceptKey")), "deopt exactly verified caller chain");
        for(int count: new int[]{0,1,3}) {
            BaseHook.call(f.rule,CLICK,new Object(),123L,count,0);
        }
        check(f.rule.nonDoubleCalls==3 && f.rule.nativeCalls==0 && f.handler.queue.isEmpty(),
            "single/non-double callbacks are never intercepted or dispatched");
        check((int)BaseHook.call(f.rule,COUNT)==2, "screen off recognizes double click with OEM camera disabled");
        check(f.rule.maxCalls==0, "screen off count does not consult OEM camera setting");
        f.context.power.interactive=true;
        check((int)BaseHook.call(f.rule,COUNT)==1 && f.rule.maxCalls==1, "interactive OEM max count retained");
        click(f);
        check(f.rule.nativeCalls==1 && f.handler.queue.isEmpty(), "interactive original action retained");
        BaseHook.call(f.rule,CLICK,new Object(),123L,1,0);
        check(f.rule.nonDoubleCalls==4 && f.rule.nativeCalls==1 && f.handler.queue.isEmpty(),
            "interactive single callback remains untouched");
        f.context.power.interactive=false;
        click(f);
        check(f.rule.nativeCalls==1 && f.context.starts==0 && f.handler.queue.size()==1,
            "screen off suppresses native and dispatches asynchronously using base handler/context");
        // Busy applies even beyond cooldown while the dispatch is still queued.
        SystemClock.now=10000; click(f);
        check(f.handler.queue.size()==1, "pending dispatch blocks another capture");
        ActivityManager.user=12;
        f.handler.drain();
        check(f.context.starts==1 && f.context.lastUser.id==12, "resolve foreground user at dispatch");
        check(f.context.privacy.globalCalls==1 && f.context.privacy.combinedCalls==1
            && f.context.privacy.identity==1000, "global/combined privacy checked as system identity");
        check(Boolean.TRUE.equals(f.context.lastIntent.extras.get("hyperceiler.sensor_privacy_checked")),
            "successful dispatch carries privacy attestation");
        check(Long.valueOf(80000).equals(f.context.lastIntent.extras.get("hyperceiler.sensor_privacy_checked_at")),
            "privacy timestamp uses elapsedRealtime, not uptimeMillis");
        check(f.context.identityAtStart==1000 && Binder.identity==99 && Binder.clears==1 && Binder.restores==1,
            "clear and restore Binder identity around IPC");
        check(f.context.lastIntent.action.equals("com.sevtinge.hyperceiler.action.SCREEN_OFF_CAPTURE"), "capture action");
        check(f.context.lastIntent.component.pkg.equals("com.sevtinge.hyperceiler")
            && f.context.lastIntent.component.cls.equals("com.sevtinge.hyperceiler.camera.ScreenOffCaptureService"),
            "explicit module service component");

        f=install(); click(f); f.handler.drain();
        SystemClock.now=2499; click(f);
        check(f.context.starts==1 && f.handler.queue.isEmpty() && f.rule.nativeCalls==0, "cooldown suppresses repeat and native");
        SystemClock.now=2500; click(f); f.handler.drain();
        check(f.context.starts==2 && f.rule.nativeCalls==0, "capture resumes at cooldown boundary");

        for (int gate=0; gate<5; gate++) {
            f=install();
            switch(gate) {
                case 0 -> f.rule.flags(0x01000000);
                case 1 -> f.rule.audio=true;
                case 2 -> f.rule.subscreen=true;
                case 3 -> f.rule.brokenAudio=true;
                case 4 -> f.rule.brokenSubscreen=true;
            }
            click(f); f.handler.drain();
            check(f.context.starts==0 && f.rule.nativeCalls==0, "OEM gate/failure suppresses native: "+gate);
        }
        f=install(); f.rule.flags(0x00000100); click(f); f.handler.drain();
        check(f.context.starts==1, "unrelated policy flags do not reject physical key");

        f=install(); PrefsBridge.enabled=false;
        check((int)BaseHook.call(f.rule,COUNT)==1, "disabled preference preserves OEM max count");
        click(f); f.handler.drain();
        check(f.context.starts==0 && f.rule.nativeCalls==0, "disabled after installation never falls through offscreen");
        f=install(); PrefsBridge.throwing=true; click(f);
        check(f.rule.nativeCalls==0 && f.handler.queue.isEmpty(), "prefs exception after suppression");
        f=install(); f.context.power=null; click(f);
        check(f.rule.nativeCalls==0 && f.handler.queue.isEmpty(), "unreadable power fails closed");

        for (int gate=0; gate<5; gate++) {
            f=install(); click(f);
            switch(gate) {
                case 0 -> f.context.power.interactive=true;
                case 1 -> f.rule.audio=true;
                case 2 -> f.rule.subscreen=true;
                case 3 -> f.rule.flags(0x01000000);
                case 4 -> PrefsBridge.enabled=false;
            }
            f.handler.drain();
            check(f.context.starts==0 && f.rule.nativeCalls==0, "queued task rechecks gate: "+gate);
            check(Binder.identity==99 && Binder.clears==Binder.restores, "early return restores identity");
        }
        f=install(); click(f); ActivityManager.user=-1; f.handler.drain();
        check(f.context.starts==0 && f.rule.nativeCalls==0 && Binder.identity==99, "invalid user never falls back to owner");

        for (int failure=0; failure<2; failure++) {
            f=install(); f.handler.reject=failure==0; f.handler.throwing=failure==1;
            click(f);
            check(f.context.starts==0 && f.rule.nativeCalls==0, "post rejection/exception stays suppressed");
            f.handler.reject=false; f.handler.throwing=false; SystemClock.now=2500;
            click(f); f.handler.drain();
            check(f.context.starts==1, "post failure releases busy state");
        }
        for (int failure=0; failure<2; failure++) {
            f=install(); f.context.throwStart=failure==0; f.context.nullStart=failure==1;
            click(f); f.handler.drain();
            check(f.context.starts==1 && f.rule.nativeCalls==0 && Binder.identity==99,
                "start rejection/null result remains suppressed and restores identity");
            f.context.throwStart=false; f.context.nullStart=false; SystemClock.now=2500;
            click(f); f.handler.drain();
            check(f.context.starts==2, "start failure releases busy state");
        }
        for(int privacyCase=0; privacyCase<10; privacyCase++) {
            f=install(); click(f);
            // Change privacy while the task is queued: query must happen at dispatch.
            switch(privacyCase) {
                case 0 -> f.context.privacy.all=true;
                case 1 -> f.context.privacy.software=true;
                case 2 -> f.context.privacy.hardware=true;
                case 3 -> { f.context.privacy.software=true; f.context.privacy.hardware=true; }
                case 4 -> f.context.privacy.throwGlobal=true;
                case 5 -> f.context.privacy.throwCombined=true;
                case 6 -> f.context.privacy=null;
                case 7 -> BaseHook.missingPrivacy=true;
                case 8 -> BaseHook.badPrivacyApi=true;
                case 9 -> f.context.privacy.switchUser=true;
            }
            f.handler.drain();
            check(f.context.starts==0 && f.context.lastIntent==null && f.rule.nativeCalls==0,
                "privacy block/error/user switch never dispatches or wakes via original: "+privacyCase);
            check(Binder.identity==99 && Binder.clears==Binder.restores,
                "privacy rejection restores identity: "+privacyCase);
            BaseHook.missingPrivacy=false; BaseHook.badPrivacyApi=false;
            f.context.privacy=new android.hardware.SensorPrivacyManager();
            SystemClock.now=2500; SystemClock.elapsed=90000;
            click(f); f.handler.drain();
            check(f.context.starts==1 && f.rule.nativeCalls==0,
                "privacy rejection releases pending state and recovers after cooldown: "+privacyCase);
            check(Long.valueOf(90000).equals(f.context.lastIntent.extras.get("hyperceiler.sensor_privacy_checked_at")),
                "successful retry carries fresh dispatch-time attestation: "+privacyCase);
        }
        f=install(); f.context.power.interactive=true; f.context.privacy.throwGlobal=true;
        click(f);
        check(f.rule.nativeCalls==1 && f.context.privacy.globalCalls==0,
            "privacy gate only applies to module screen-off dispatch; interactive path unchanged");
        System.out.println("PASS ScreenOffCameraHookTest: "+assertions+" assertions");
    }
}
