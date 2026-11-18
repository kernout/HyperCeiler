"""Host-only executable API fixture: run REAL VideoCameraConfig.select, never hardware.

Not an Android API compatibility substitute: run.py compiles ALL production against
real API 35 first. This separate classpath supplies deterministic metadata faults,
including the observed getKeys -> getExtendedSceneModeCapabilities NPE.
"""
from pathlib import Path
import subprocess
import tempfile

SOURCES = {
'android/graphics/ImageFormat.java': '''package android.graphics;
public final class ImageFormat { public static final int PRIVATE = 34; }''',
'android/util/Range.java': '''package android.util;
public final class Range<T extends Comparable<? super T>> {
 private final T lower, upper;
 public Range(T l,T u) {lower=l;upper=u;}
 public boolean contains(T v) {return lower.compareTo(v)<=0 && upper.compareTo(v)>=0;}
 @Override public boolean equals(Object o) {return o instanceof Range<?> r && lower.equals(r.lower)&&upper.equals(r.upper);}
 @Override public int hashCode(){return lower.hashCode()*31+upper.hashCode();}
}''',
'android/util/Size.java': '''package android.util;
public final class Size { final int w,h; public Size(int w,int h){this.w=w;this.h=h;}
 @Override public boolean equals(Object o){return o instanceof Size s&&s.w==w&&s.h==h;}
 @Override public int hashCode(){return w*31+h;}
}''',
'android/hardware/camera2/CaptureRequest.java': '''package android.hardware.camera2;
import android.util.Range;
public final class CaptureRequest {
 public static final class Key<T> {}
 public static final Key<Range<Integer>> CONTROL_AE_TARGET_FPS_RANGE=new Key<>();
}''',
'android/hardware/camera2/CaptureResult.java': '''package android.hardware.camera2;
import android.util.Range;
public final class CaptureResult {
 public static final class Key<T> {}
 public static final Key<Range<Integer>> CONTROL_AE_TARGET_FPS_RANGE=new Key<>();
}''',
'android/hardware/camera2/impl/CameraMetadataNative.java': '''package android.hardware.camera2.impl;
public final class CameraMetadataNative {
 public static void getExtendedSceneModeCapabilities() {
  throw new NullPointerException("missing ROM extended scene metadata (private fixture detail)");
 }
}''',
'android/hardware/camera2/CameraCharacteristics.java': '''package android.hardware.camera2;
import java.util.*;
import android.util.Range;
import android.hardware.camera2.params.StreamConfigurationMap;
public final class CameraCharacteristics {
 public static final int LENS_FACING_BACK=1, REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA=11;
 public static final Key<Integer> LENS_FACING=new Key<>("facing",Integer.class), SENSOR_ORIENTATION=new Key<>("orientation",Integer.class);
 public static final Key<int[]> REQUEST_AVAILABLE_CAPABILITIES=new Key<>("capabilities",int[].class);
 public static final Key<StreamConfigurationMap> SCALER_STREAM_CONFIGURATION_MAP=new Key<>("streams",StreamConfigurationMap.class);
 @SuppressWarnings({"rawtypes","unchecked"})
 public static final Key<Range<Integer>[]> CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES=new Key("fps",Range[].class);
 public static final class Key<T> {public final String name; public final Class<T> type;
  public Key(String n,Class<T> t){name=n;type=t;} public String getName(){return name;}}
 public final Map<String,Object> values=new HashMap<>();
 public final Set<String> reads=new HashSet<>();
 public final Set<String> physical=new LinkedHashSet<>();
 public RuntimeException physicalFailure;
 public boolean fpsEcho, physicalFps, resultsMissing;
 public RuntimeException scalarTypeFailure;
 public static int getKeysCalls;
 public static Throwable getKeysFailure;
 // Null reproduces root derived-key NPE; a list models app-UID filtered enumeration.
 public static List<Key<?>> enumeratedKeys;
 public <T> T get(Key<T> key) {
  reads.add(key.name+":"+key.type.getSimpleName());
  Object value=values.get(key.name);
  if(value instanceof RuntimeException e) throw e;
  if(value instanceof LinkageError e) throw e;
  if(key.name.equals("com.xiaomi.cameraid.role.cameraId") && key.type==Integer.class && scalarTypeFailure!=null) throw scalarTypeFailure;
  return value==null?null:key.type.cast(value);
 }
 public List<Key<?>> getKeys() {
  getKeysCalls++;
  if(getKeysFailure instanceof RuntimeException e)throw e;
  if(getKeysFailure instanceof LinkageError e)throw e;
  if(enumeratedKeys!=null)return enumeratedKeys;
  android.hardware.camera2.impl.CameraMetadataNative.getExtendedSceneModeCapabilities();
  return List.of();
 }
 public Set<String> getPhysicalCameraIds(){if(physicalFailure!=null)throw physicalFailure;return physical;}
 public List<CaptureRequest.Key<?>> getAvailablePhysicalCameraRequestKeys(){return physicalFps?List.of(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE):null;}
 public List<CaptureResult.Key<?>> getAvailableCaptureResultKeys(){return resultsMissing?null:fpsEcho?List.of(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE):List.of();}
}''',
'android/hardware/camera2/CameraManager.java': '''package android.hardware.camera2;
import java.util.*;
public final class CameraManager {
 public final Map<String,CameraCharacteristics> metadata=new LinkedHashMap<>();
 public final Map<String,RuntimeException> failures=new HashMap<>();
 public final List<String> queried=new ArrayList<>();
 public String[] ids={"0","1"}; public RuntimeException idsFailure;
 public String[] getCameraIdList(){if(idsFailure!=null)throw idsFailure;return ids;}
 public CameraCharacteristics getCameraCharacteristics(String id){queried.add(id);if(failures.containsKey(id))throw failures.get(id);return metadata.get(id);}
 // No openCamera method: fixture cannot acquire sensors.
}''',
'android/hardware/camera2/params/StreamConfigurationMap.java': '''package android.hardware.camera2.params;
import android.util.Size;
public final class StreamConfigurationMap {
 public boolean size=true; public long duration=16666666L;
 public Size[] getOutputSizes(int format){return size?new Size[]{new Size(1920,1080)}:new Size[0];}
 public long getOutputMinFrameDuration(int format,Size size){return duration;}
}''',
'android/media/MediaFormat.java': '''package android.media;
import java.util.*;
public final class MediaFormat {
 public static final String MIMETYPE_VIDEO_AVC="video/avc",KEY_FRAME_RATE="fps",KEY_BIT_RATE="bitrate",KEY_I_FRAME_INTERVAL="iframe",KEY_COLOR_FORMAT="color";
 public final Map<String,Integer> values=new HashMap<>();
 public static MediaFormat createVideoFormat(String mime,int w,int h){if(!mime.equals(MIMETYPE_VIDEO_AVC)||w!=1920||h!=1080)throw new AssertionError("strict format");return new MediaFormat();}
 public void setInteger(String key,int value){values.put(key,value);}
}''',
'android/media/MediaCodecList.java': '''package android.media;
public final class MediaCodecList {
 public static final int REGULAR_CODECS=0; public static boolean supported=true; public static RuntimeException failure;
 public MediaCodecList(int mode){if(mode!=REGULAR_CODECS)throw new AssertionError("regular only");}
 public MediaCodecInfo[] getCodecInfos(){if(failure!=null)throw failure;return supported?new MediaCodecInfo[]{new MediaCodecInfo()}:new MediaCodecInfo[0];}
}''',
'android/media/MediaCodecInfo.java': '''package android.media;
import android.util.Range;
public final class MediaCodecInfo {
 public boolean isEncoder(){return true;}
 public CodecCapabilities getCapabilitiesForType(String mime){return new CodecCapabilities();}
 public static final class CodecCapabilities {
  public static final int COLOR_FormatSurface=2130708361;
  public VideoCapabilities getVideoCapabilities(){return new VideoCapabilities();}
  public boolean isFormatSupported(MediaFormat f){return Integer.valueOf(60).equals(f.values.get(MediaFormat.KEY_FRAME_RATE))&&Integer.valueOf(20000000).equals(f.values.get(MediaFormat.KEY_BIT_RATE))&&Integer.valueOf(COLOR_FormatSurface).equals(f.values.get(MediaFormat.KEY_COLOR_FORMAT));}
 }
 public static final class VideoCapabilities {
  public boolean areSizeAndRateSupported(int w,int h,double fps){return w==1920&&h==1080&&fps==60;}
  public Range<Integer> getBitrateRange(){return new Range<>(1,50000000);}
 }
}''',
}


def run_select_fixture(production: Path, tests: Path) -> None:
    with tempfile.TemporaryDirectory(prefix='hc-video-select-fixture-') as temp:
        root = Path(temp)
        for name, text in SOURCES.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        classes = root / 'classes'
        classes.mkdir()
        subprocess.run(['javac', '--release', '17', '-Xlint:all', '-d', str(classes),
                        *map(str, sorted(root.rglob('*.java'))),
                        *[str(production / name) for name in ('VideoCameraConfig.java',
                            'VideoMainCameraPolicy.java', 'VideoRecordingPolicy.java', 'VideoDiagnostics.java')],
                        str(tests / 'fixtures/VideoCameraConfigSelectTest.java')], check=True)
        subprocess.run(['java', '-ea', '-classpath', str(classes),
                        'com.sevtinge.hyperceiler.video.VideoCameraConfigSelectTest'], check=True)
