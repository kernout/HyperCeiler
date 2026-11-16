#!/usr/bin/env python3
"""Compile actual Java hook + exact extracted registration expression with bounded Android stubs."""
from pathlib import Path
import hashlib,json,os,re,shutil,subprocess,tempfile,xml.etree.ElementTree as ET
F=Path(__file__).resolve().parent
S=F.parents[1]
T=Path(os.environ['HC_TEST_OUTPUT_DIR']) if 'HC_TEST_OUTPUT_DIR' in os.environ else Path(tempfile.mkdtemp(prefix='hc-v3-report-'))
T.mkdir(parents=True,exist_ok=True)
J=S/'library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook'
D=J/'rules/systemframework/display'
PK='com/sevtinge/hyperceiler/libhook/rules/systemframework/display/'
TOOL=Path(os.environ['JAVA_HOME'])/'bin' if 'JAVA_HOME' in os.environ else Path(shutil.which('javac')).parent
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
loader=(J/'app/SystemFramework/SystemFrameworkB.java').read_text()
match=re.search(r'initHook\(new ScreenshotCaptureWindowExclusion\(\),\s*(.*?)\);',loader,re.S)
assert match
expression=match.group(1)
assert 'SDK_INT >= 37' in expression and 'system_ui_status_bar_hide_island' in expression
ns='{http://schemas.android.com/apk/res/android}'
prefs=[p for p in ET.parse(S/'library/core/src/main/res/xml/system_ui_status_bar.xml').iter() if p.get(ns+'key')=='prefs_key_system_ui_status_bar_hide_island']
assert len(prefs)==1 and prefs[0].get(ns+'defaultValue')=='false' and prefs[0].get(ns+'dependency') is None
for locale in ['values','values-zh-rCN']:
    strings=ET.parse(S/f'library/core/src/main/res/{locale}/strings_app.xml').getroot()
    for kind in ['title','summary']:
        name=prefs[0].get(ns+kind).removeprefix('@string/')
        assert sum(x.get('name')==name for x in strings)==1
    # Check no resource name collisions, including inherited v2 resources.
    names=[x.get('name') for x in strings if x.get('name')]
    assert len(names)==len(set(names))
loader_test='''package com.sevtinge.hyperceiler.libhook.rules.systemframework.display;
import android.os.Build;import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
public class RegistrationGateTest {
 static boolean gate(){return EXPRESSION;}
 public static void main(String[] args){int n=0;
 for(int sdk:new int[]{33,36,37,38})for(int mask=0;mask<16;mask++){
  ScreenshotExclusionTest.install(mask);Build.VERSION.SDK_INT=sdk;
  if(gate()!=(sdk>=37&&mask!=0))throw new AssertionError("loader sdk="+sdk+" mask="+mask);n++;
 }
 System.out.println("PASS: "+n+" actual extracted SystemFrameworkB registration combinations");
 }
}'''.replace('EXPRESSION',expression)
fixtures=json.loads((F/'fixtures.json').read_text())
fixtures[PK+'RegistrationGateTest.java']=loader_test
fixtures[PK+'ScreenshotExclusionTest.java']=(F/'ScreenshotExclusionTest.java').read_text()
fixtures[PK+'IslandExtraTest.java']=(F/'IslandExtraTest.java').read_text()
# Generated source retained to show exactly what was compiled for the registration gate.
(T/'RegistrationGateTest.java').write_text(loader_test)
report={'status':'RUNNING','production':{str(p.relative_to(S)):sha(p) for p in [D/'ScreenshotCaptureWindowExclusion.java',D/'ScreenshotWindowPolicy.java',J/'app/SystemFramework/SystemFrameworkB.java']},'limits':['Real hook Java executes; Android/WMS/SurfaceControl/BaseHook are host fixtures, not real Xposed/SurfaceFlinger.','Gate test compiles exact extracted production expression; entire SystemFrameworkB and APK are not compiled.','No installation, device UI, pixels, recording, or lifecycle claim from host fixture.'],'runs':[]}
def run(label,rule,tests,sources=None,expect_failure=False):
    with tempfile.TemporaryDirectory(prefix='hc-island-') as tmp:
        w=Path(tmp)
        code=dict(fixtures if sources is None else sources)
        code[PK+'ScreenshotCaptureWindowExclusion.java']=rule
        code[PK+'ScreenshotWindowPolicy.java']=(D/'ScreenshotWindowPolicy.java').read_text()
        for name,body in code.items():
            p=w/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
        compile_cmd=[str(TOOL/'javac'),'-encoding','UTF-8','-d',str(w/'classes'),*map(str,w.rglob('*.java'))]
        result=subprocess.run(compile_cmd,text=True,capture_output=True)
        assert result.returncode==0,label+result.stdout+result.stderr
        outputs=[]
        for test in tests:
            r=subprocess.run([str(TOOL/'java'),'-ea','-cp',str(w/'classes'),'com.sevtinge.hyperceiler.libhook.rules.systemframework.display.'+test],text=True,capture_output=True)
            outputs.append({'test':test,'returncode':r.returncode,'stdout':r.stdout,'stderr':r.stderr})
            if expect_failure and r.returncode: break
            assert r.returncode==0,label+r.stdout+r.stderr
        if expect_failure: assert any(r['returncode'] for r in outputs), 'Undetected mutation '+label
        report['runs'].append({'label':label,'rule_sha256':hashlib.sha256(rule.encode()).hexdigest(),'compiled':True,'expected_failure':expect_failure,'outputs':outputs})
        print(label, ':', 'rejected as expected' if expect_failure else 'PASS')
        if not expect_failure:
            for out in outputs: print(out['stdout'].strip())
rule=(D/'ScreenshotCaptureWindowExclusion.java').read_text()
run('production v3',rule,['RegistrationGateTest','ScreenshotExclusionTest','IslandExtraTest'])
mutations={
 'missing island install gate':rule.replace(' && !hideIsland) return;', ') return;'),
 'ignore island toggle':rule.replace('hideIsland = PrefsBridge.getBoolean("system_ui_status_bar_hide_island", false);','hideIsland = true;'),
 'wrong window type':rule.replace('lp.type != 2009','lp.type != 2000'),
 'reject plugin package':rule.replace('"miui.systemui.plugin".equals(lp.packageName)','"invalid.plugin".equals(lp.packageName)'),
 'substring title':rule.replace('!"DynamicIslandWindow".contentEquals(lp.getTitle())','!lp.getTitle().toString().contains("DynamicIslandWindow")'),
 'skip owner check':rule.replace('getPackagesForUid(ownerUid.getInt(window))','getPackagesForUid(10197)'),
 'discard existing exclusions':rule.replace('addExisting(merged, (SurfaceControl[]) builderExcludeLayers.get(builder));',''),
 'leak copies':rule.replace('copy.release();',''),
 'lose nested scope':rule.replace('else scope.set(current.previous);','else scope.remove();')
}
for label,mutated in mutations.items():
    assert mutated!=rule,label
    run('mutation '+label,mutated,['ScreenshotExclusionTest','IslandExtraTest'],expect_failure=True)
# Compare pin behavior with the unmodified 8-combination inherited fixture on true v2 and v3.
pin=dict(fixtures)
pin[PK+'ScreenshotExclusionTest.java']=(F/'PinRegressionTest.java.txt').read_text()
pin.pop(PK+'IslandExtraTest.java');pin.pop(PK+'RegistrationGateTest.java')
base=F/'v2-exclusion.java.txt'
run('v2 original pin regression',base.read_text(),['ScreenshotExclusionTest'],pin)
run('v3 identical pin regression',rule,['ScreenshotExclusionTest'],pin)
report['status']='PASS'
(T/'island-host-report.json').write_text(json.dumps(report,indent=2)+'\n')
print('PASS XML/default/locale uniqueness; report:',T/'island-host-report.json')
