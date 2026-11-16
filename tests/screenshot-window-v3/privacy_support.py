#!/usr/bin/env python3
"""Compile the production Java hooks and execute their callbacks with bounded host fixtures.

No Android SDK, Gradle, device media, module installation or network access is used.
--rom optionally verifies the inspected ROM smali contracts, not live hook execution.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook'
RES = ROOT / 'library/core/src/main/res'
MEDIA = JAVA / 'rules/systemframework/others/BlockMediaStoreNotificationsOS4.java'
SHOT = JAVA / 'rules/systemui/other/ScreenshotSaveToXmlOS4.java'

SOURCES = {
'android/os/Build.java': '''package android.os;
public class Build { public static class VERSION { public static int SDK_INT=37; } }''',
'android/net/Uri.java': '''package android.net;
/** Minimal tolerant Android-style scheme/encoded-authority fixture, not an Android runtime. */
public final class Uri {
 private final String text; private Uri(String t){text=t;}
 public static Uri parse(String t){return new Uri(t);}
 public String getScheme(){int c=text.indexOf(':');return c<0?null:text.substring(0,c);}
 public String getEncodedAuthority(){
  int c=text.indexOf(':');if(c<0||!text.startsWith("//",c+1))return null;
  int start=c+3,end=text.length();for(int i=start;i<text.length();i++){
   char x=text.charAt(i);if(x=='/'||x=='?'||x=='#'){end=i;break;}}
  return text.substring(start,end);
 }
 public String toString(){return text;}
}''',
'android/content/ContentValues.java': '''package android.content;
public class ContentValues extends java.util.HashMap<String,Object> {
 public void put(String k,String v){super.put(k,v);}
 public void put(String k,Integer v){super.put(k,v);}
 public void put(String k,Long v){super.put(k,v);}
}''',
'android/graphics/Bitmap.java': '''package android.graphics;
public class Bitmap { public enum CompressFormat {JPEG,PNG,WEBP,WEBP_LOSSY,WEBP_LOSSLESS} }''',
'android/database/IContentObserver.java': '''package android.database;
public interface IContentObserver {}''',
'com/sevtinge/hyperceiler/common/utils/PrefsBridge.java': '''package com.sevtinge.hyperceiler.common.utils;
public class PrefsBridge {
 public static final java.util.Map<String,Boolean> values=new java.util.HashMap<>();
 public static boolean getBoolean(String k,boolean d){return values.getOrDefault(k,d);}
}''',
'io/github/lingqiqi5211/ezhooktool/xposed/common/HookParam.java': '''package io.github.lingqiqi5211.ezhooktool.xposed.common;
public class HookParam {
 private final Object[] args; private Object result; private Throwable error; private boolean early;
 public HookParam(Object[] a){args=a;} public Object[] getArgs(){return args;}
 public Object getResult(){return result;} public Throwable getThrowable(){return error;}
 public void setResult(Object r){result=r;error=null;early=true;}
 public boolean early(){return early;}
 public void original(Object r,Throwable t){result=r;error=t;}
}''',
'io/github/lingqiqi5211/ezhooktool/xposed/java/IMethodHook.java': '''package io.github.lingqiqi5211.ezhooktool.xposed.java;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
public interface IMethodHook { default void before(HookParam p){} default void after(HookParam p){} }''',
'com/sevtinge/hyperceiler/libhook/base/BaseHook.java': '''package com.sevtinge.hyperceiler.libhook.base;
import java.lang.reflect.*;import java.util.*;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;
public abstract class BaseHook {
 public abstract void init(); public static final Map<Method,IMethodHook> hooks=new LinkedHashMap<>();
 public static final List<String> deoptimized=new ArrayList<>();
 public static Class<?> findClass(String n){try{return Class.forName(n);}catch(Exception e){throw new IllegalStateException(e);}}
 public static Object hookMethod(Method m,IMethodHook h){hooks.put(m,h);return new Object();}
 public static void deoptimizeMethods(Class<?> c,String... names){
  for(String n:names){
   boolean found=false;for(Method m:c.getDeclaredMethods())if(m.getName().equals(n))found=true;
   if(!found)throw new AssertionError("deopt caller absent in fixture: "+c.getName()+"#"+n);
   deoptimized.add(c.getName()+"#"+n);
  }
 }
 public static Object invoke(Method m,Object receiver,Object... args) throws Throwable {
  IMethodHook h=hooks.get(m);HookParam p=new HookParam(args);if(h!=null)h.before(p);
  if(!p.early()){
   try{m.setAccessible(true);p.original(m.invoke(receiver,args),null);}
   catch(InvocationTargetException e){p.original(null,e.getCause());}
  }
  if(h!=null)h.after(p);
  if(p.getThrowable()!=null)throw p.getThrowable();return p.getResult();
 }
}''',
'com/android/server/content/ContentService.java': '''package com.android.server.content;
import java.util.*;import java.lang.reflect.*;import android.net.Uri;import android.database.IContentObserver;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
/** Model of the verified ROM ordering. Not a replacement for its implementation. */
public class ContentService {
 public static final Method COLLECT;
 static{try{COLLECT=ObserverCollector.class.getDeclaredMethod("collect",IContentObserver.class,int.class,
  boolean.class,Uri.class,int.class,int.class);}catch(Exception e){throw new AssertionError(e);}}
 public record Event(IContentObserver observer,int uid,boolean self,Uri uri,int flags,int user){}
 public static class ObserverCollector {
  public final List<Event> events=new ArrayList<>();
  public void collect(IContentObserver o,int uid,boolean self,Uri uri,int flags,int user){
   events.add(new Event(o,uid,self,uri,flags,user));}
 }
 public static class ObserverNode {
  public void collectObserversLocked(){} private void collectMyObserversLocked(){}
 }
 public final ObserverCollector collector=new ObserverCollector();
 public int validation,dispatch,sync,cache,clear,restore;
 public void notifyChange(Uri[] uris,IContentObserver observer,boolean self,int flags,int user,int sdk,String pkg) throws Throwable {
  for(Uri uri:uris){
   validation++;
   if(user==-1&&!pkg.equals("system"))throw new SecurityException("cross-user permission");
   if(pkg.equals("denied"))throw new SecurityException("provider access");
   if(uri==null)throw new NullPointerException("uri");
   if(!"content".equals(uri.getScheme())||uri.getEncodedAuthority()==null
      ||uri.getEncodedAuthority().isEmpty())throw new IllegalArgumentException("uri");
   BaseHook.invoke(COLLECT,collector,observer,12345,self,uri,flags,user);
  }
  clear++;try{dispatch++;sync++;cache++;}finally{restore++;}
 }
}''',
'com/android/systemui/screenshot/ImageExporter.java': '''package com.android.systemui.screenshot;
import android.content.ContentValues;import android.graphics.Bitmap;import java.time.ZonedDateTime;
public class ImageExporter {
 public static int calls;public static ContentValues last;
 public static final IllegalArgumentException FAILURE=new IllegalArgumentException("native failure");
 public static ContentValues createMetadata(ZonedDateTime time,Bitmap.CompressFormat format,String name){
  calls++;if(name.equals("THROW"))throw FAILURE;if(name.equals("NULL"))return null;
  ContentValues v=new ContentValues();v.put("relative_path","Pictures/Screenshots/");
  v.put("_display_name",name);v.put("mime_type",format==Bitmap.CompressFormat.JPEG?"image/jpeg":
    format==Bitmap.CompressFormat.PNG?"image/png":"image/webp");
  v.put("date_added",time.toEpochSecond());v.put("date_modified",time.toEpochSecond());
  v.put("date_expires",time.toEpochSecond()+86400);v.put("is_pending",1);last=v;return v;
 }
 public static class Task { public void execute(){} }
}''',
}

TEST = r'''import java.lang.reflect.*;
import java.time.ZonedDateTime;
import java.util.*;
import android.net.Uri;
import android.content.ContentValues;
import android.graphics.Bitmap.CompressFormat;
import android.database.IContentObserver;
import com.android.server.content.ContentService;
import com.android.systemui.screenshot.ImageExporter;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.rules.systemframework.others.BlockMediaStoreNotificationsOS4;
import com.sevtinge.hyperceiler.libhook.rules.systemui.other.ScreenshotSaveToXmlOS4;

public class ScreenshotPrivacyTest {
 static int checks;static final IContentObserver OBS=new IContentObserver(){};
 static final String MP=BlockMediaStoreNotificationsOS4.PREF,SP=ScreenshotSaveToXmlOS4.PREF;
 static final Method META;
 static{try{META=ImageExporter.class.getDeclaredMethod("createMetadata",ZonedDateTime.class,
   CompressFormat.class,String.class);}catch(Exception e){throw new AssertionError(e);}}
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static Uri uri(String s){return s==null?null:Uri.parse(s);}
 static void install(boolean media,boolean shot){
  BaseHook.hooks.clear();BaseHook.deoptimized.clear();PrefsBridge.values.clear();
  PrefsBridge.values.put(MP,media);PrefsBridge.values.put(SP,shot);
  new BlockMediaStoreNotificationsOS4().init();new ScreenshotSaveToXmlOS4().init();
 }
 static List<ContentService.Event> collect(Uri u,int uid,int user,int flags,boolean self,IContentObserver o) throws Throwable{
  ContentService.ObserverCollector c=new ContentService.ObserverCollector();
  Object[] args={o,uid,self,u,flags,user};Object[] original=args.clone();
  Object result=BaseHook.invoke(ContentService.COLLECT,c,args);
  check(result==null,"void result");check(Arrays.equals(args,original),"argument array unchanged");
  return c.events;
 }
 static ContentValues metadata(CompressFormat format,String name) throws Throwable {
  return (ContentValues)BaseHook.invoke(META,null,ZonedDateTime.parse("2026-01-02T03:04:05Z"),format,name);
 }
 static void mediaTests() throws Throwable {
  install(true,false);
  check(BaseHook.hooks.size()==1&&BaseHook.hooks.containsKey(ContentService.COLLECT),"only exact collector hooked");
  check(BaseHook.deoptimized.contains("com.android.server.content.ContentService#notifyChange"),"deopt validated entry caller");
  String[] blocked={"content://media","content://media/","content://media/external/images/media/7",
   "content://media/internal/images/media","content://media/external_primary/images/media",
   "content://media/external/audio/media/9","content://media/external/video/media/8",
   "content://media/external/file/42","content://media/external","content://0@media/external/images/media/1",
   "content://10@media/external/file","content://2147483647@media/", "content://media/?x=1#root"};
  for(String s:blocked)check(collect(uri(s),54321,-1,0x8003,true,OBS).isEmpty(),"media root/type/volume/user: "+s);
  String[] allowed={null,"","media","media/path","content:media","content:/media","content:///media",
   "content://","content://mediastore/images","content://media.evil/images","content://othermedia/images",
   "content://com.android.providers.media.documents/document/image:1","content://media:80/images",
   "content://user@media/images","content://-1@media/images","content://+1@media/images",
   "content://@media/images","content://1@@media/images","content://2147483648@media/images",
   "content://10@media.evil/images","content://10@othermedia/images","content://MEDIA/images",
   "content://%6dedia/images","content://10%40media/images","content://média/images",
   "content://media /images","content://sms/1","https://media/images","file://media/a.png"};
  for(String s:allowed){Uri u=uri(s);var e=collect(u,67890,10,17,false,OBS);
   check(e.size()==1&&e.get(0).uri()==u,"lookalike/null/malformed pass through: "+s);}
  for(int uid:new int[]{0,1000,10001,12345,1012345})
   for(int user:new int[]{-1,0,10}){
    IContentObserver o=new IContentObserver(){};
    check(collect(uri("content://media/external/images/media/1"),uid,user,3,false,o).isEmpty(),"global uid/user policy");
    var e=collect(uri("content://sms/1"),uid,user,0x8003,true,o).get(0);
    check(e.uid()==uid&&e.user()==user&&e.flags()==0x8003&&e.self()&&e.observer()==o,"non-media tuple preserved");
   }
  Uri a=uri("content://sms/2"),b=uri("content://settings/system"),m=uri("content://media/external/file/2");
  for(Uri[] input:new Uri[][]{ {m,a,m,b},{a,m,b,m},{m,m},{a,b},{} }){
   Uri[] copy=input.clone();ContentService s=new ContentService();
   s.notifyChange(input,OBS,false,0x8003,-1,37,"system");
   List<Uri> want=Arrays.stream(input).filter(x->x!=m).toList();
   check(s.collector.events.stream().map(ContentService.Event::uri).toList().equals(want),"mixed URI batch preserved");
   check(Arrays.equals(input,copy),"original batch unchanged");
   check(s.validation==input.length,"every URI validated");
   check(s.dispatch==1&&s.sync==1&&s.cache==1&&s.clear==1&&s.restore==1,"original post-validation work retained");
  }
  for(String pkg:new String[]{"denied","ordinary"}){
   ContentService s=new ContentService();boolean denied=false;
   try{s.notifyChange(new Uri[]{m},OBS,false,3,pkg.equals("ordinary")?-1:0,37,pkg);}
   catch(SecurityException expected){denied=true;}
   check(denied&&s.validation==1&&s.dispatch==0,"original permissions enforced");
  }
  for(Uri bad:new Uri[]{null,uri("not-a-content-uri"),uri("content:///missing")}){
   ContentService s=new ContentService();boolean rejected=false;
   try{s.notifyChange(new Uri[]{bad},OBS,false,3,0,37,"system");}
   catch(NullPointerException|IllegalArgumentException expected){rejected=true;}
   check(rejected&&s.dispatch==0,"original malformed URI validation retained");
  }
 }
 static void screenshotTests() throws Throwable {
  install(false,true);check(BaseHook.hooks.size()==1&&BaseHook.hooks.containsKey(META),"only native metadata hooked");
  check(BaseHook.deoptimized.contains("com.android.systemui.screenshot.ImageExporter$Task#execute"),"Task.execute deoptimized");
  for(CompressFormat format:CompressFormat.values()){
   install(false,false);String name="Screenshot_20260102."+format;
   ContentValues baseline=metadata(format,name);Map<String,Object> original=new HashMap<>(baseline);
   install(false,true);int before=ImageExporter.calls;ContentValues out=metadata(format,name);
   check(ImageExporter.calls==before+1,"native original invoked once");
   check(out==ImageExporter.last,"same ContentValues object");
   check("Pictures/xml/".equals(out.get("relative_path")),"xml is folder");
   check(out.size()==original.size(),"no inserted or deleted fields");
   for(var e:original.entrySet())if(!e.getKey().equals("relative_path"))
    check(Objects.equals(out.get(e.getKey()),e.getValue()),"preserved "+e.getKey()+" for "+format);
   check(baseline.equals(original),"earlier metadata/history untouched");
  }
  check(metadata(CompressFormat.PNG,"NULL")==null,"null result retained");
  boolean threw=false;try{metadata(CompressFormat.PNG,"THROW");}
  catch(IllegalArgumentException e){threw=e==ImageExporter.FAILURE;}
  check(threw,"native exporter exception identity retained");
 }
 static void switches() throws Throwable {
  for(boolean media:new boolean[]{false,true})for(boolean shot:new boolean[]{false,true}){
   install(media,shot);check(BaseHook.hooks.size()==(media?1:0)+(shot?1:0),"independent switches");
   Uri u=uri("content://media/external/images/media/1");
   check(collect(u,12345,-1,3,true,OBS).size()==(media?0:1),"notification switch");
   check((shot?"Pictures/xml/":"Pictures/Screenshots/").equals(metadata(CompressFormat.PNG,"screen.png").get("relative_path")),"path switch");
  }
  BaseHook.hooks.clear();PrefsBridge.values.clear();BaseHook.deoptimized.clear();
  new BlockMediaStoreNotificationsOS4().init();new ScreenshotSaveToXmlOS4().init();
  check(BaseHook.hooks.isEmpty()&&BaseHook.deoptimized.isEmpty(),"both missing prefs default off");
  ContentService s=new ContentService();Uri[] batch={uri("content://media"),uri("content://sms/1")};
  s.notifyChange(batch,OBS,true,17,-1,37,"system");
  check(s.collector.events.size()==2&&s.collector.events.get(0).uri()==batch[0]&&s.collector.events.get(1).uri()==batch[1],"switch off original batch order/identity");
  android.os.Build.VERSION.SDK_INT=36;install(true,true);
  check(BaseHook.hooks.isEmpty()&&BaseHook.deoptimized.isEmpty(),"SDK36 no hooks or deopt");
  android.os.Build.VERSION.SDK_INT=37;
 }
 public static void main(String[] args) throws Throwable {
  mediaTests();screenshotTests();switches();System.out.println("PASS ScreenshotPrivacyTest checks="+checks);
 }
}
'''


def static_checks():
    checks = 0
    def require(value, message):
        nonlocal checks
        checks += 1
        if not value:
            raise AssertionError(message)

    ns = '{http://schemas.android.com/apk/res/android}'
    keys = [('framework_other.xml', 'system_framework_block_media_store_notifications'),
            ('system_ui_other.xml', 'system_ui_screenshot_save_to_xml')]
    for xml, key in keys:
        tree = ET.parse(RES / 'xml' / xml)
        found = [n for n in tree.iter() if n.get(ns + 'key') == 'prefs_key_' + key]
        require(len(found) == 1 and found[0].get(ns + 'defaultValue') == 'false', key + ' default off')
        for locale in ['values', 'values-zh-rCN']:
            entries = ET.parse(RES / locale / 'strings_app.xml').getroot()
            for field in ['title', 'summary']:
                ref = found[0].get(ns + field).removeprefix('@string/')
                require(sum(n.get('name') == ref for n in entries) == 1, locale + ':' + ref)
    for app, rule, pref in [('SystemFramework/SystemFrameworkB.java', 'BlockMediaStoreNotificationsOS4', keys[0][1]),
                            ('SystemUI/SystemUIB.java', 'ScreenshotSaveToXmlOS4', keys[1][1])]:
        text = (JAVA / 'app' / app).read_text()
        start = text.index('initHook(new com.sevtinge.hyperceiler.libhook.rules.' +
                           ('systemframework.others.' if rule.startswith('Block') else 'systemui.other.') + rule)
        registration = text[start:text.index(';', start)]
        require('SDK_INT >= 37' in registration, rule + ' SDK loader gate')
        require('HyperOSVersion(4f)' in registration, rule + ' ROM loader gate')
        require('getBoolean("' + pref + '", false)' in registration, rule + ' pref loader gate')
    require('param.setResult(null)' not in SHOT.read_text(), 'screenshot original not replaced')
    require('values.put("relative_path", "Pictures/xml/")' in SHOT.read_text(), 'only intended metadata change')
    require('getArgs()[3]' in MEDIA.read_text(), 'collector URI argument index')
    require('"notifyChange", new IMethodHook' not in MEDIA.read_text(), 'no validation entry before replacement')
    print(f'PASS resources/loaders static checks={checks}')
    return checks


def rom_checks(rom):
    service = rom / 'services.jar/classes2/com/android/server/content'
    screen = rom / 'MiuiSystemUI.apk/classes2/com/android/systemui/screenshot'
    files = [service / 'ContentService.smali', service / 'ContentService$ObserverNode.smali',
             service / 'ContentService$ObserverCollector.smali', screen / 'ImageExporter.smali',
             screen / 'ImageExporter$Task.smali']
    text = [f.read_text() for f in files]
    def method(src, signature):
        return src[src.index(signature):].split('.end method', 1)[0]
    notify = method(text[0], '.method public notifyChange([Landroid/net/Uri;Landroid/database/IContentObserver;ZIIILjava/lang/String;)V')
    assert all(f'.param p{n}, "{name}"' in notify for n, name in [(4,'flags'),(5,'userId'),(6,'targetSdkVersion')])
    assert notify.index('->handleIncomingUser(') < notify.index('->checkContentProviderAccess(') < notify.index('->collectObserversLocked(') < notify.index('->clearCallingIdentity(') < notify.index('->dispatch(')
    assert 'SecurityException;' in notify and '->invalidateCacheLocked(' in notify and '->restoreCallingIdentity(' in notify
    node = method(text[1], '.method private collectMyObserversLocked(')
    assert node.index('"selfChange":Z') < node.index('->-$$Nest$fgetuserHandle(') < node.index('->collect(')
    assert node.index('->notifyForDescendants:Z') < node.index('->collect(')
    signature = '.method public collect(Landroid/database/IContentObserver;IZLandroid/net/Uri;II)V'
    collect = method(text[2], signature)
    assert all(f'.param p{n}, "{name}"' in collect for n, name in [(1,'observer'),(2,'uid'),(3,'selfChange'),(4,'uri'),(5,'flags'),(6,'userId')])
    assert '->add(Ljava/lang/Object;)Z' in collect
    assert text[1].count('ObserverCollector;->collect(') == 1
    meta = method(text[3], '.method public static createMetadata(Ljava/time/ZonedDateTime;Landroid/graphics/Bitmap$CompressFormat;Ljava/lang/String;)Landroid/content/ContentValues;')
    assert all('"'+key+'"' in meta for key in ['relative_path','_display_name','mime_type','is_pending','date_added','date_modified','date_expires'])
    entry = method(text[3], '.method public static -$$Nest$smcreateEntry(')
    assert '->createMetadata(' in entry and 'ContentResolver;->insert(' in entry
    assert '->-$$Nest$smcreateEntry(' in method(text[4], '.method public final execute(')
    print('PASS ROM contracts: validation -> per-observer collector -> dispatch; metadata -> insert; original caller names')
    return {str(f): hashlib.sha256(f.read_bytes()).hexdigest() for f in files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rom', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    java_home = os.environ.get('JAVA_HOME')
    javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    if not javac or not java:
        raise SystemExit('JDK required; no dependency installation is performed')
    checks = static_checks()
    hashes = rom_checks(args.rom) if args.rom else {}
    # Temporary classes are removed automatically. Keep only explicit report/evidence.
    with tempfile.TemporaryDirectory(prefix='hc-screenshot-privacy-') as temp:
        base = Path(temp)
        generated = []
        for name, source in SOURCES.items():
            path = base / 'src' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding='utf-8')
            generated.append(path)
        test = base / 'src/ScreenshotPrivacyTest.java'
        test.write_text(TEST, encoding='utf-8')
        # The ROM's R8 synthetic method name is not legal Java syntax; caller existence is
        # checked against real smali above, and against the requested string in this fixture.
        stub = base / 'src/com/sevtinge/hyperceiler/libhook/base/BaseHook.java'
        content = stub.read_text()
        content = content.replace('boolean found=false;', 'boolean found=c.getName().equals("com.android.systemui.screenshot.ImageExporter")&&n.equals("-$$Nest$smcreateEntry");')
        stub.write_text(content, encoding='utf-8')
        subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(base / 'classes'), *map(str, generated), str(test), str(MEDIA), str(SHOT)], check=True)
        result = subprocess.run([java, '-ea', '-cp', str(base / 'classes'), 'ScreenshotPrivacyTest'], check=True, text=True, capture_output=True)
        print(result.stdout, end='')
        print(result.stderr, end='')
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps({
            'status': 'PASS', 'host_result': result.stdout.strip(), 'static_checks': checks,
            'production_sha256': {str(f.relative_to(ROOT)): hashlib.sha256(f.read_bytes()).hexdigest() for f in [MEDIA, SHOT]},
            'rom_sha256': hashes,
            'limits': 'Host fixtures plus optional smali contract checks only; no APK build or live Android hook verification.'
        }, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
