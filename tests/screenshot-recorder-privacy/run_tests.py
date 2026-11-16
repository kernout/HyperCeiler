#!/usr/bin/env python3
"""Real production Java compilation/callback execution on JDK25; explicit Android fixtures.
No Gradle, network or device writes. Preserved v1 fixtures are isolated test inputs.
"""
from pathlib import Path
import hashlib, json, os, runpy, subprocess, tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(os.environ['HC_TEST_OUTPUT_DIR']) if 'HC_TEST_OUTPUT_DIR' in os.environ else Path(tempfile.mkdtemp(prefix='hc-recorder-report-'))
OUT.mkdir(parents=True, exist_ok=True)
JAVA = ROOT / 'library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook'
OLD = Path(__file__).with_name('legacy_fixture.py')
legacy = runpy.run_path(str(OLD))
sources = dict(legacy['SOURCES'])
sources['android/net/Uri.java'] = sources['android/net/Uri.java'].replace(' public String toString()', ''' public String getPath(){return java.net.URI.create(text).getPath();}
 public String toString()''')
sources['com/sevtinge/hyperceiler/libhook/base/BaseHook.java'] = sources['com/sevtinge/hyperceiler/libhook/base/BaseHook.java'].replace('boolean found=false;', 'boolean found=c.getName().equals("com.android.systemui.screenshot.ImageExporter")&&n.equals("-$$Nest$smcreateEntry");')
sources['android/content/Context.java'] = 'package android.content; public class Context {}'
sources['android/os/Environment.java'] = '''package android.os;
public class Environment { public static java.io.File getExternalStorageDirectory(){return new java.io.File("/storage/emulated/0");} }'''
sources['android/content/ContentResolver.java'] = '''package android.content;
import android.net.Uri;
public class ContentResolver {
 public ContentValues received; public Uri result=Uri.parse("content://media/external/video/media/73");
 public RuntimeException failure;
 public Uri insert(Uri uri,ContentValues values){received=values;if(failure!=null)throw failure;return result;}
}'''
sources['k0/r.java'] = '''package k0;
import android.content.Context;
public class r {
 public static String generated="/private/.Screenrecorder-2026-11-16-08-10-12-123.mp4";
 public static String i(Context c){return generated;}
 public static String c(String path){return path==null?null:path.replace(".Screenrecorder", "Screenrecorder");}
 public static void a(){} public static void b(){}
}'''
sources['d0/d.java'] = '''package d0;
import android.content.*;import android.net.Uri;import com.sevtinge.hyperceiler.libhook.base.BaseHook;
public class d {
 public String path;public ContentValues values;public ContentResolver resolver=new ContentResolver();
 public Uri uri=Uri.parse("content://media/external/video/media");
 public Runnable nested;public RuntimeException fail;
 private void B() throws Throwable {
  path=(String)BaseHook.invoke(k0.r.class.getDeclaredMethod("i",Context.class),null,new Context());
  if(nested!=null)nested.run();if(fail!=null)throw fail;
  values=new ContentValues();values.put("relative_path","DCIM/ScreenRecorder");
  values.put("_display_name",new java.io.File(path).getName());values.put("is_pending",1);
  values.put("mime_type","video/mp4");values.put("date_added",9123L);values.put("sound",3);
  BaseHook.invoke(ContentResolver.class.getDeclaredMethod("insert",Uri.class,ContentValues.class),resolver,uri,values);
 }
 private boolean C(){return true;}
}'''
sources['d0/b.java'] = 'package d0; public class b { protected void p(boolean a,boolean b){} }'
sources['l0/k.java'] = 'package l0; public class k { private boolean T(){return true;} }'
sources['com/miui/screenrecorder/service/RecorderService.java'] = '''package com.miui.screenrecorder.service;
public class RecorderService { public static class e { protected void a(){} } }'''
sources['com/android/systemui/screenrecord/ScreenMediaRecorder.java'] = '''package com.android.systemui.screenrecord;
import android.content.*;import android.net.Uri;import com.sevtinge.hyperceiler.libhook.base.BaseHook;
public class ScreenMediaRecorder {
 public ContentResolver resolver=new ContentResolver();public ContentValues values;public Uri uri=Uri.parse("content://media/external_primary/video/media");
 public String name="screen-20261116-081012-999.mp4";
 public Uri createRecordingUri() throws Throwable {
  values=new ContentValues();values.put("_display_name",name);values.put("mime_type","video/mp4");values.put("date_added",234L);values.put("datetaken",567L);
  return (Uri)BaseHook.invoke(ContentResolver.class.getDeclaredMethod("insert",Uri.class,ContentValues.class),resolver,uri,values);
 }
}'''
sources['com/android/systemui/screenrecord/service/ScreenRecordingService.java'] = 'package com.android.systemui.screenrecord.service; public class ScreenRecordingService { public void stopRecording(){} }'
sources['com/android/systemui/screenrecord/RecordingService$$ExternalSyntheticLambda1.java'] = 'package com.android.systemui.screenrecord; public class RecordingService$$ExternalSyntheticLambda1 { public void run(){} }'
for cls,method in [('ScreenshotController','saveScreenshotInBackground'),('scroll/LongScreenshotActivity','startExport'),('appclips/AppClipsViewModel$$ExternalSyntheticLambda1','run')]:
    package, _, name = ('com/android/systemui/screenshot/'+cls).rpartition('/')
    sources[package+'/'+name+'.java'] = 'package '+package.replace('/','.')+'; public class '+name+' {public void '+method+'(){}}'
image = sources['com/android/systemui/screenshot/ImageExporter.java']
image = image.replace('public class ImageExporter {','''public class ImageExporter {
 public static String createFilename(java.time.ZonedDateTime t,android.graphics.Bitmap.CompressFormat f,int display){
  return "Screenshot_20261116-081012"+(display==0?"":"-display-"+display)+"."+f.name().toLowerCase(java.util.Locale.ROOT);
 }''')
sources['com/android/systemui/screenshot/ImageExporter.java']=image

TEST=r'''import java.lang.reflect.*;import java.util.*;
import android.content.*;import android.net.Uri;import android.os.Build;import android.graphics.Bitmap;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.rules.media.CaptureFileNames;
import com.sevtinge.hyperceiler.libhook.rules.screenrecorder.RecorderSaveToXmlOS4;
import com.sevtinge.hyperceiler.libhook.rules.systemui.other.ScreenshotSaveToXmlOS4;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import com.android.systemui.screenshot.ImageExporter;
public class RecorderPrivacyTest {
 static int checks;
 static void ok(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static void eq(Object a,Object b,String s){ok(Objects.equals(a,b),s+" got="+a+" expected="+b);}
 static void reset(){PrefsBridge.values.clear();BaseHook.hooks.clear();BaseHook.deoptimized.clear();Build.VERSION.SDK_INT=37;}
 static Object invoke(Class<?> c,String name,Object receiver,Class<?>[] types,Object... args)throws Throwable{return BaseHook.invoke(c.getDeclaredMethod(name,types),receiver,args);}
 static void flags(boolean save,boolean names){PrefsBridge.values.put(RecorderSaveToXmlOS4.SAVE,save);PrefsBridge.values.put(RecorderSaveToXmlOS4.NAMES,names);}
 static void shotFlags(boolean save,boolean names){PrefsBridge.values.put(ScreenshotSaveToXmlOS4.PREF,save);PrefsBridge.values.put(ScreenshotSaveToXmlOS4.NAMES,names);}
 static Object callInsert(ContentResolver r,Uri u,ContentValues v)throws Throwable{return invoke(ContentResolver.class,"insert",r,new Class[]{Uri.class,ContentValues.class},u,v);}
 static void names(){
  String[][] cases={{null,null},{"",""},{"Screenshot_","Screenshot_"},{"Screenshot_.png","Screenshot_.png"},{"Screenshot_   ","Screenshot_   "},{"Screenshot_文件😀.webp","文件😀.webp"},{"Screenshot_20261116-081012-display-2.png","20261116-081012-display-2.png"},{"xScreenshot_1.png","xScreenshot_1.png"},{"screenshot_1.png","screenshot_1.png"},{"Screenshot_aScreenshot_b.PNG","aScreenshot_b.PNG"},{"Screenshot_a/b.png","Screenshot_a/b.png"},{"Screenshot_..","Screenshot_.."},{"Screenshot_\\name","Screenshot_\\name"}};
  for(String[] p:cases)eq(CaptureFileNames.screenshot(p[0]),p[1],"shot exact name");
  for(String[] p:cases)eq(CaptureFileNames.recording(p[0]),p[0],"recorder leaves screenshots");
  String[] suffix={"2026-11-16-08-10-12-123.mp4","2026-11-16-08-10-12-123 (1).mp4","唯一😀.mkv","aScreenrecorder_b.mp4"};
  for(String s:suffix)eq(CaptureFileNames.recording("Screenrecorder_"+s),s,"recorder suffix bytes");
  for(String s:new String[]{"Screenrecorder_","Screenrecorder_.mp4","Screenrecorder_  ","ScreenRecorder_x.mp4","ScreenRecorder-2026.mp4","Screen-2026.mp4",".Screenrecorder_2026.mp4","xScreenrecorder_y.mp4"})eq(CaptureFileNames.recording(s),s,"fail open exact prefix");
  eq(CaptureFileNames.recordingPath("/aScreenrecorder_b/Screenrecorder_时间.mp4"),"/aScreenrecorder_b/时间.mp4","basename only");
 }
 static void screenshots()throws Throwable{
  for(boolean save:new boolean[]{false,true})for(boolean names:new boolean[]{false,true}){
   reset();shotFlags(save,names);new ScreenshotSaveToXmlOS4().init();
   if(!save&&!names){eq(BaseHook.hooks.size(),0,"shot off no hooks");continue;}
   Method meta=ImageExporter.class.getDeclaredMethod("createMetadata",java.time.ZonedDateTime.class,Bitmap.CompressFormat.class,String.class);
   for(Bitmap.CompressFormat f:Bitmap.CompressFormat.values()){
    ContentValues original=ImageExporter.createMetadata(java.time.ZonedDateTime.parse("2026-11-16T08:10:12+08:00"),f,"Screenshot_文件-1."+f.name());
    ContentValues snapshot=new ContentValues();snapshot.putAll(original);
    HookParam p=new HookParam(new Object[]{null,f,"Screenshot_文件-1."+f.name()});p.original(original,null);BaseHook.hooks.get(meta).after(p);
    ok(p.getResult()==original,"metadata object identity");
    if(save)snapshot.put("relative_path","Pictures/xml/");
    eq(original,snapshot,"all screenshot fields");
   }
   RuntimeException failure=new RuntimeException("metadata failed");HookParam p=new HookParam(new Object[3]);p.original(null,failure);BaseHook.hooks.get(meta).after(p);ok(p.getThrowable()==failure,"shot exception identity");
   p=new HookParam(new Object[3]);p.original(null,null);BaseHook.hooks.get(meta).after(p);ok(p.getResult()==null,"null unchanged");
   if(names){Object n=invoke(ImageExporter.class,"createFilename",null,new Class[]{java.time.ZonedDateTime.class,Bitmap.CompressFormat.class,int.class},null,Bitmap.CompressFormat.PNG,4);eq(n,"20261116-081012-display-4.png","factory preserves display id");}
  }
 }
 static void recordings()throws Throwable{
  for(boolean sys:new boolean[]{false,true})for(boolean save:new boolean[]{false,true})for(boolean names:new boolean[]{false,true}){
   reset();flags(save,names);new RecorderSaveToXmlOS4(sys).init();
   if(!save&&!names){eq(BaseHook.hooks.size(),0,"record off no hooks");continue;}
   ContentResolver outside=new ContentResolver();ContentValues untouched=new ContentValues();untouched.put("relative_path","Movies/other");untouched.put("_display_name","Screenrecorder_old.mp4");callInsert(outside,Uri.parse("content://media/external/video/media"),untouched);eq(untouched.get("relative_path"),"Movies/other","outside scope");eq(untouched.get("_display_name"),"Screenrecorder_old.mp4","outside name");
   if(sys){
    var recorder=new com.android.systemui.screenrecord.ScreenMediaRecorder();recorder.name="Screenrecorder_20261116-081012-123.mp4";
    Object result=invoke(recorder.getClass(),"createRecordingUri",recorder,new Class[0]);ok(result==recorder.resolver.result,"system URI identity");ok(recorder.values==recorder.resolver.received,"system CV identity");
    eq(recorder.values.get("relative_path"),save?"Pictures/xml/":null,"system path");eq(recorder.values.get("_display_name"),names?"20261116-081012-123.mp4":recorder.name,"system name");eq(recorder.values.size(),save?5:4,"system no extra fields");eq(recorder.values.get("mime_type"),"video/mp4","system format");eq(recorder.values.get("date_added"),234L,"date preserved");
    recorder.name="screen-2026.mp4";invoke(recorder.getClass(),"createRecordingUri",recorder,new Class[0]);eq(recorder.values.get("_display_name"),names?"2026.mp4":recorder.name,"actual system prefix switch");
    recorder.resolver.failure=new IllegalStateException("provider denied");try{invoke(recorder.getClass(),"createRecordingUri",recorder,new Class[0]);throw new AssertionError();}catch(IllegalStateException e){ok(e==recorder.resolver.failure,"insert error identity");}
   }else{
    k0.r.generated="/cache/Screenrecorder_20261116-081012-123.mp4";d0.d controller=new d0.d();
    controller.nested=()->{try{ContentValues v=new ContentValues();v.put("relative_path","unrelated");v.put("_display_name","Screenrecorder_unrelated.mp4");Thread t=new Thread(()->{try{callInsert(new ContentResolver(),Uri.parse("content://media/external/video/media"),v);}catch(Throwable e){throw new AssertionError(e);}});t.start();t.join();eq(v.get("relative_path"),"unrelated","thread local isolation");}catch(Exception e){throw new AssertionError(e);}};
    invoke(d0.d.class,"B",controller,new Class[0]);ok(controller.values==controller.resolver.received,"miui CV identity");
    String expected=(save?"/storage/emulated/0/Pictures/xml/":"/cache/")+(names?"20261116-081012-123.mp4":"Screenrecorder_20261116-081012-123.mp4");eq(controller.path,expected,"generated path and metadata agree");eq(controller.values.get("_display_name"),new java.io.File(expected).getName(),"miui basename");eq(controller.values.get("relative_path"),save?"Pictures/xml/":"DCIM/ScreenRecorder","miui path");eq(controller.values.get("is_pending"),1,"pending untouched");eq(controller.values.get("sound"),3,"sound untouched");eq(controller.values.size(),6,"no field dropped");
    eq(invoke(k0.r.class,"c",null,new Class[]{String.class},"/old/Screenrecorder_history.mp4"),"/old/Screenrecorder_history.mp4","history c call unchanged");
    k0.r.generated="/cache/.Screenrecorder-2026-11-16-08-10-12-999.mp4";invoke(d0.d.class,"B",controller,new Class[0]);String finish=(String)invoke(k0.r.class,"c",null,new Class[]{String.class},controller.path);ok(finish.endsWith(names?"/2026-11-16-08-10-12-999.mp4":"/Screenrecorder-2026-11-16-08-10-12-999.mp4"),"native final publish prefix switch");ok(finish.startsWith(save?"/storage/emulated/0/Pictures/xml/":"/cache/"),"final folder stays");
    controller.fail=new IllegalStateException("prestart failed");try{invoke(d0.d.class,"B",controller,new Class[0]);throw new AssertionError();}catch(IllegalStateException e){ok(e==controller.fail,"start error identity");}
   }
   ContentValues after=new ContentValues();after.put("relative_path","After/error");callInsert(new ContentResolver(),Uri.parse("content://media/external/video/media"),after);eq(after.get("relative_path"),"After/error","scope cleaned after failure");
  }
 }
 static void URIguards()throws Throwable{
  reset();flags(true,true);new RecorderSaveToXmlOS4(true).init();var r=new com.android.systemui.screenrecord.ScreenMediaRecorder();
  for(String uri:new String[]{"content://media/external/images/media","content://media/external/video/media/1","content://other/external/video/media","file:///external/video/media","content://media.evil/external/video/media"}){r.uri=Uri.parse(uri);invoke(r.getClass(),"createRecordingUri",r,new Class[0]);ok(!r.values.containsKey("relative_path"),"non-recording collection preserved");eq(r.values.get("_display_name"),r.name,"unrelated name preserved");}
 }
 static void singleStrip()throws Throwable{
  reset();shotFlags(false,true);new ScreenshotSaveToXmlOS4().init();
  Method filename=ImageExporter.class.getDeclaredMethod("createFilename",java.time.ZonedDateTime.class,Bitmap.CompressFormat.class,int.class);
  HookParam p=new HookParam(new Object[3]);p.original("Screenshot_Screenshot_middle.png",null);BaseHook.hooks.get(filename).after(p);eq(p.getResult(),"Screenshot_middle.png","single screenshot prefix");
  reset();flags(true,true);new RecorderSaveToXmlOS4().init();d0.d c=new d0.d();k0.r.generated="/cache/Screenrecorder_Screenrecorder_middle.mp4";invoke(d0.d.class,"B",c,new Class[0]);eq(c.values.get("_display_name"),"Screenrecorder_middle.mp4","single recorder prefix at insert");eq(invoke(k0.r.class,"c",null,new Class[]{String.class},c.path),c.path,"no second stripping at publish");
  k0.r.generated="/cache/.Screenrecorder_时间-123.mp4";invoke(d0.d.class,"B",c,new Class[0]);eq(invoke(k0.r.class,"c",null,new Class[]{String.class},c.path),"/storage/emulated/0/Pictures/xml/时间-123.mp4","hidden name final publish");
 }
 static void emptyGenerated()throws Throwable{
  reset();flags(true,true);new RecorderSaveToXmlOS4().init();Method start=d0.d.class.getDeclaredMethod("B");
  for(String bad:new String[]{"","/","/cache/.","/cache/..","/cache/   "}){
   HookParam scope=new HookParam(new Object[0]);BaseHook.hooks.get(start).before(scope);
   k0.r.generated=bad;eq(invoke(k0.r.class,"i",null,new Class[]{Context.class},new Context()),bad,"invalid generated path unchanged");
   BaseHook.hooks.get(start).after(scope);
  }
 }
 static void productVariants()throws Throwable{
  String[] prefixes={"Screenrecorder_","Screenrecorder-","screen-"};
  String[] good={"2026-11-16-08-10-12-001.mp4","20261116-081012-999 (2).MP4","时间😀.mkv","a.Screenrecorder_middle.webm","screen-2026.mp4","Screenrecorder_2026.mp4"};
  String[] bad={""," ",".","..",".mp4","...mp4","/x.mp4","x/y.mp4","x\\y.mp4","x\0.mp4","abc123hash","x."," .mp4","x. ",".hidden.png"};
  for(String prefix:prefixes){
   for(String suffix:good){eq(CaptureFileNames.recording(prefix+suffix),suffix,"all exact recorder variants");eq(CaptureFileNames.screenshot(prefix+suffix),prefix+suffix,"screenshot ignores recorder variants");}
   for(String suffix:bad)eq(CaptureFileNames.recording(prefix+suffix),prefix+suffix,"unsafe suffix keeps original");
   for(String other:prefixes)eq(CaptureFileNames.recording(prefix+other+"timestamp-123.mp4"),other+"timestamp-123.mp4","only one mixed recorder prefix");
  }
  for(String name:new String[]{"ScreenRecorder_2026.mp4","ScreenRecorder-2026.mp4","Screen-2026.mp4","SCREEN-2026.mp4","screen_2026.mp4","xscreen-2026.mp4","xScreenrecorder-2026.mp4","/Screenrecorder-2026.mp4",".Screenrecorder-2026.mp4"})eq(CaptureFileNames.recording(name),name,"negative exact starting prefix");
  for(String suffix:bad)eq(CaptureFileNames.screenshot("Screenshot_"+suffix),"Screenshot_"+suffix,"shot unsafe suffix keeps original");
  for(String name:new String[]{"Screenshot-2026.png","screenshot_2026.png","xScreenshot_2026.png","SCREENSHOT_2026.png"})eq(CaptureFileNames.screenshot(name),name,"no guessed screenshot variants");
  for(boolean save:new boolean[]{false,true})for(boolean names:new boolean[]{false,true}){
   reset();flags(save,names);new RecorderSaveToXmlOS4(true).init();var recorder=new com.android.systemui.screenrecord.ScreenMediaRecorder();
   for(String prefix:prefixes){
    recorder.name=prefix+"20261116-081012-123 (1).mp4";invoke(recorder.getClass(),"createRecordingUri",recorder,new Class[0]);
    eq(recorder.values.get("_display_name"),names?"20261116-081012-123 (1).mp4":recorder.name,"system variants names independent");eq(recorder.values.get("relative_path"),save?"Pictures/xml/":null,"system variants folder independent");eq(recorder.values.get("mime_type"),"video/mp4","system variants MIME unchanged");
   }
   reset();flags(save,names);new RecorderSaveToXmlOS4().init();d0.d c=new d0.d();
   for(String prefix:prefixes){
    String suffix="时间-123 (2).mp4";k0.r.generated="/cache/"+prefix+suffix;invoke(d0.d.class,"B",c,new Class[0]);
    String folder=save?"/storage/emulated/0/Pictures/xml/":"/cache/";
    eq(c.path,folder+(names?suffix:prefix+suffix),"MIUI visible variants path");eq(c.values.get("_display_name"),names?suffix:prefix+suffix,"MIUI visible variants metadata");eq(invoke(k0.r.class,"c",null,new Class[]{String.class},c.path),c.path,"MIUI visible variants no double strip");eq(c.values.get("is_pending"),1,"MIUI variants pending unchanged");
   }
   for(String prefix:new String[]{"Screenrecorder_","Screenrecorder-"}){
    String suffix="2026-11-16-08-10-12-321.mp4";k0.r.generated="/cache/."+prefix+suffix;invoke(d0.d.class,"B",c,new Class[0]);
    String folder=save?"/storage/emulated/0/Pictures/xml/":"/cache/";
    eq(c.path,folder+"."+prefix+suffix,"hidden pending path native");eq(c.values.get("_display_name"),"."+prefix+suffix,"hidden pending metadata native");
    eq(invoke(k0.r.class,"c",null,new Class[]{String.class},c.path),folder+(names?suffix:prefix+suffix),"MIUI hidden final publication variants");
    eq(invoke(k0.r.class,"c",null,new Class[]{String.class},c.path),folder+(names?suffix:prefix+suffix),"repeated hidden callback stable");
    String history="/history/"+prefix+suffix;eq(invoke(k0.r.class,"c",null,new Class[]{String.class},history),history,"untracked history variants untouched");
   }
  }
  reset();flags(true,true);new RecorderSaveToXmlOS4().init();d0.d c=new d0.d();
  for(String prefix:new String[]{"Screenrecorder_","Screenrecorder-"}){
   k0.r.generated="/cache/."+prefix+"screen-2026.mp4";invoke(d0.d.class,"B",c,new Class[0]);eq(invoke(k0.r.class,"c",null,new Class[]{String.class},c.path),"/storage/emulated/0/Pictures/xml/screen-2026.mp4","hidden publication strips exactly once");
   k0.r.generated="/cache/."+prefix+".mp4";invoke(d0.d.class,"B",c,new Class[0]);eq(invoke(k0.r.class,"c",null,new Class[]{String.class},c.path),"/storage/emulated/0/Pictures/xml/"+prefix+".mp4","hidden unsafe suffix preserves published original");
  }
 }
 static void callbackNegatives()throws Throwable{
  String[] bad={""," ",".","..",".mp4","...mp4","/x.mp4","x/y.mp4","x\\y.mp4","x\0.mp4","abc123hash","x."," .mp4","x. ",".hidden.png"};
  reset();flags(true,true);new RecorderSaveToXmlOS4(true).init();var r=new com.android.systemui.screenrecord.ScreenMediaRecorder();
  for(String prefix:new String[]{"Screenrecorder_","Screenrecorder-","screen-"})for(String suffix:bad){
   r.name=prefix+suffix;invoke(r.getClass(),"createRecordingUri",r,new Class[0]);eq(r.values.get("_display_name"),r.name,"SystemUI unsafe callback preserves original name");
  }
  reset();shotFlags(false,true);new ScreenshotSaveToXmlOS4().init();Method filename=ImageExporter.class.getDeclaredMethod("createFilename",java.time.ZonedDateTime.class,Bitmap.CompressFormat.class,int.class);
  for(String suffix:bad){String original="Screenshot_"+suffix;HookParam p=new HookParam(new Object[3]);p.original(original,null);BaseHook.hooks.get(filename).after(p);eq(p.getResult(),original,"screenshot unsafe callback preserves original name");}
 }
 public static void main(String[] a)throws Throwable{
  names();screenshots();recordings();URIguards();reset();Build.VERSION.SDK_INT=36;flags(true,true);shotFlags(true,true);new RecorderSaveToXmlOS4().init();new RecorderSaveToXmlOS4(true).init();new ScreenshotSaveToXmlOS4().init();eq(BaseHook.hooks.size(),0,"unsupported SDK no hook");
  singleStrip();emptyGenerated();productVariants();callbackNegatives();System.out.println("PASS RecorderPrivacyTest checks="+checks);
 }
}'''

def main():
    android='{http://schemas.android.com/apk/res/android}'
    res=ROOT/'library/core/src/main/res'
    for file,keys in [('system_ui_other.xml',['system_ui_screenshot_save_to_xml','system_ui_screenshot_remove_filename_prefix']),('screenrecorder.xml',['screenrecorder_save_to_xml','screenrecorder_remove_filename_prefix'])]:
        tree=ET.parse(res/'xml'/file)
        for key in keys:
            nodes=[n for n in tree.iter() if n.get(android+'key')=='prefs_key_'+key]
            assert len(nodes)==1 and nodes[0].get(android+'defaultValue')=='false'
    for lang in ['values','values-zh-rCN']:
        tree=ET.parse(res/lang/'strings_app.xml');names=[n.get('name') for n in tree.getroot()];assert len(names)==len(set(names))
    production=[JAVA/'rules/media/CaptureFileNames.java',JAVA/'rules/systemui/other/ScreenshotSaveToXmlOS4.java',JAVA/'rules/screenrecorder/RecorderSaveToXmlOS4.java',JAVA/'rules/systemframework/others/BlockMediaStoreNotificationsOS4.java']
    home=Path(os.environ['JAVA_HOME']) if 'JAVA_HOME' in os.environ else Path(__import__('shutil').which('javac')).parent.parent
    outputs=[]
    with tempfile.TemporaryDirectory(prefix='host-',dir=OUT) as tmp:
        b=Path(tmp);generated=[]
        for name,source in {**sources,'ScreenshotPrivacyTest.java':legacy['TEST'],'RecorderPrivacyTest.java':TEST}.items():
            p=b/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source,encoding='utf-8');generated.append(p)
        subprocess.run([str(home/'bin/javac'),'-encoding','UTF-8','-d',str(b/'classes'),*map(str,generated+production)],check=True)
        for cls in ['ScreenshotPrivacyTest','RecorderPrivacyTest']:
            c=subprocess.run([str(home/'bin/java'),'-ea','-cp',str(b/'classes'),cls],text=True,capture_output=True);print(c.stdout,end='');print(c.stderr,end='');c.check_returncode();outputs.append(c.stdout.strip())
    print('PASS XML/default-off/resource checks; actual production Java compilation and callbacks')
    (OUT/'host-tests.json').write_text(json.dumps({'status':'PASS','jdk':str(home),'host_results':outputs,'production_sha256':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in production},'limits':'Bounded host fixtures. No Android runtime, Gradle/R8/APK build or device verification.'},ensure_ascii=False,indent=2)+'\n')

if __name__=='__main__':main()
