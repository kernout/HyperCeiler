package com.sevtinge.hyperceiler.libhook.rules.systemframework.display;

import android.os.*;
import android.view.*;
import android.window.ScreenCaptureInternal.*;
import android.content.pm.PackageManager;
import com.android.server.wm.*;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import java.util.*;

/** Executes the real hook with host fixtures; never represents real compositor pixels. */
public class IslandExtraTest {
    static int checks;
    static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
    static WindowState island(String name, String pkg, CharSequence title, int type, int uid) {
        return new WindowState(type, name, null).owner(pkg).named(title).uid(uid);
    }
    static WindowManagerService fixture() {
        WindowManagerService s = new WindowManagerService();
        s.display().windows.add(island("island", "miui.systemui.plugin", "DynamicIslandWindow", 2009, 10197));
        return s;
    }
    static void capture(WindowManagerService s) throws Exception { capture(s, 0); }
    static void capture(WindowManagerService s, int display) throws Exception {
        BaseHook.invoke(s, "captureDisplay", new Class[]{int.class, CaptureArgs.class, ScreenCaptureListener.class},
                display, new CaptureArgs(), new ScreenCaptureListener());
    }
    static LayerCaptureArgs build() throws Exception {
        LayerCaptureArgs.Builder b = new LayerCaptureArgs.Builder();
        b.setExcludeLayers(new SurfaceControl[]{new SurfaceControl("existing")});
        return (LayerCaptureArgs) BaseHook.invoke(b, "build", new Class[0]);
    }
    static void install(int mask) {
        Build.VERSION.SDK_INT=37; Binder.uid=10197;
        ScreenshotExclusionTest.install(mask);
    }
    static void preciseIdentity() throws Exception {
        Object[][] variants = {
            {"miui.systemui.plugin", "DynamicIslandWindow", 2009, 10197, true},
            {"com.android.systemui", new StringBuilder("DynamicIslandWindow"), 2009, 110197, true},
            {"other.app", "DynamicIslandWindow", 2009, 10197, false},
            {null, "DynamicIslandWindow", 2009, 10197, false},
            {"miui.systemui.plugin.fake", "DynamicIslandWindow", 2009, 10197, false},
            {"com.android.systemui.fake", "DynamicIslandWindow", 2009, 10197, false},
            {"miui.systemui.plugin", "PrefixDynamicIslandWindow", 2009, 10197, false},
            {"miui.systemui.plugin", "DynamicIslandWindowSuffix", 2009, 10197, false},
            {"miui.systemui.plugin", "dynamicislandwindow", 2009, 10197, false},
            {"miui.systemui.plugin", " DynamicIslandWindow", 2009, 10197, false},
            {"miui.systemui.plugin", "", 2009, 10197, false},
            {"miui.systemui.plugin", null, 2009, 10197, false},
            {"miui.systemui.plugin", "DynamicIslandWindow", 2000, 10197, false},
            {"miui.systemui.plugin", "DynamicIslandWindow", 2038, 10197, false},
            {"miui.systemui.plugin", "DynamicIslandWindow", 2003, 10197, false},
            {"miui.systemui.plugin", "system_ui_focus_notification_decor_window", 2009, 10197, false},
            {"miui.systemui.plugin", "GlowUpperViewRoot", 2009, 10197, false},
            {"miui.systemui.plugin", "DynamicIslandWindow", 2009, 1000, false},
            {"com.android.systemui", "DynamicIslandWindow", 2009, 23456, false},
            {"miui.systemui.plugin", "DynamicIslandWindow", 2009, 55555, false},
            {"miui.systemui.plugin", "DynamicIslandWindow", 2009, 66666, false}
        };
        for (Object[] v : variants) {
            install(8); WindowManagerService s=new WindowManagerService();
            WindowState w=island("candidate", (String)v[0], (CharSequence)v[1], (int)v[2], (int)v[3]);
            s.display().windows.add(w); capture(s);
            check((s.output.surfaces.length==2)==(boolean)v[4], "exact identity " + Arrays.toString(v));
            check(w.surface.valid && w.surface.releases==0, "never release owner surface");
        }
        install(8); WindowManagerService s=fixture();
        s.display().windows.add(island("failed", "miui.systemui.plugin", "DynamicIslandWindow", 2009, 77777));
        PackageManager.failOwner=true; capture(s); PackageManager.failOwner=false;
        check(s.output.surfaces.length==2, "owner lookup failure fails closed for candidate only");
        install(8); s=fixture(); s.display().windows.get(0).surface.valid=false; capture(s);
        check(s.output.surfaces.length==1 && SurfaceControl.copies.isEmpty(), "invalid island skipped");
        install(8); s=fixture(); s.display().windows.get(0).surface=null; capture(s);
        check(s.output.surfaces.length==1, "null island surface skipped");
    }
    static void cleanupAndScopes() throws Exception {
        install(8);
        check(BaseHook.hooks.size()==2, "island-only installs capture and builder hooks");
        check(BaseHook.exactBuild.getReturnType()==LayerCaptureArgs.class, "exact data build method");
        check(build().surfaces.length==1 && SurfaceControl.copies.isEmpty(), "outside capture untouched");
        WindowManagerService s=fixture(); s.emptyExisting=true; capture(s);
        check(s.output.surfaces.length==1, "null existing exclusions supported");
        check(SurfaceControl.copies.get(0).releases==1, "scope owns exactly one release");
        install(8); s=fixture(); s.repeat=true; s.display().windows.add(s.display().windows.get(0)); capture(s);
        check(s.output.surfaces[0]==s.cast, "existing exclusion retained before additions");
        check(s.cast.valid && s.cast.releases==0, "existing exclusion not released");
        check(s.output.surfaces.length==3 && SurfaceControl.copies.size()==2, "window identity dedup each build; previous build additions retained");
        for(SurfaceControl c:SurfaceControl.copies) check(c.releases==1 && c.original.valid, "repeat-build copy cleanup");
        install(8); s=fixture(); capture(s,99);
        check(s.output.surfaces.length==1 && SurfaceControl.copies.isEmpty(), "missing display untouched");
        install(8); s=fixture(); s.fail=true;
        try {capture(s);throw new AssertionError("expected capture failure");} catch(IllegalStateException expected) {}
        check(SurfaceControl.copies.size()==1 && SurfaceControl.copies.get(0).releases==1, "throwing capture cleanup");
        check(build().surfaces.length==1, "failed capture scope removed");
        for(boolean innerAllowed:new boolean[]{true,false}) for(boolean innerFails:new boolean[]{true,false}) {
            install(8);
            WindowManagerService outer=fixture(),inner=fixture(); inner.fail=innerFails;
            outer.during=()->{
                SurfaceControl outerCopy=SurfaceControl.copies.get(0);
                check(outerCopy.valid && outerCopy.releases==0, "outer live before nested capture");
                Binder.uid=innerAllowed?10151:23456;
                try {
                    try {capture(inner);check(!innerFails,"inner expected success");}
                    catch(IllegalStateException e) {check(innerFails,"inner expected failure");}
                    check(inner.output.surfaces.length==(innerAllowed?2:1), "inner independent caller scope");
                    check(outerCopy.valid && outerCopy.releases==0, "inner cleanup never releases outer");
                    if(innerAllowed)check(SurfaceControl.copies.get(1).releases==1,"inner copy released");
                    // Direct builder invocation must resume the enclosing capture scope.
                    check(build().surfaces.length==2, "outer restored after nested capture");
                } catch(Exception e) {throw new RuntimeException(e);}
                finally {Binder.uid=10197;}
            };
            capture(outer);
            for(SurfaceControl c:SurfaceControl.copies)check(c.releases==1 && c.original.valid,"all nested copies released once");
            check(build().surfaces.length==1,"nested scope completely removed");
        }
        for(int sdk:new int[]{33,36}) for(int mask:new int[]{8,15}) {
            install(mask); BaseHook.hooks.clear();Build.VERSION.SDK_INT=sdk;
            new ScreenshotCaptureWindowExclusion().init();
            check(BaseHook.hooks.isEmpty(), "old SDK stays disabled");
        }
        install(0);check(BaseHook.hooks.isEmpty(),"all OFF installs no hooks");
    }
    public static void main(String[] args) throws Exception {
        preciseIdentity(); cleanupAndScopes();
        System.out.println("PASS: " + checks + " precise island identity/scope/resource assertions (real production hook, host Android model)");
    }
}
