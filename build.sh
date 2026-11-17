#!/usr/bin/env bash
# Build youzai-video.apk without Gradle/Android Studio.
# Requires: JDK 17, Android SDK (platform android-35, build-tools 34.0.0).
# Point ANDROID_HOME / JAVA_HOME at your installs if they are not the defaults.
# media3 dependencies are fetched by fetch_deps.py on first build (needs curl + network).
set -e
export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/android/jdk}"
export PATH=$JAVA_HOME/bin:$PATH
BT="${ANDROID_HOME:-$HOME/workspace/android/sdk}/build-tools/34.0.0"
AJAR="${ANDROID_HOME:-$HOME/workspace/android/sdk}/platforms/android-35/android.jar"
cd "$(dirname "$0")"
ROOT=$PWD
APP=$ROOT/app
DEPS=$ROOT/deps
BUILD=$APP/build
rm -rf $BUILD
mkdir -p $BUILD/gen $BUILD/classes $BUILD/dex $BUILD/libres $DEPS/jars $DEPS/extracted

if ! ls $DEPS/*.aar >/dev/null 2>&1; then echo "== fetch deps (media3)"; python3 $ROOT/fetch_deps.py; fi
echo "== unpack deps"
python3 - <<'EOF'
import os, zipfile, shutil, glob
DEPS = os.path.join(os.getcwd(), "deps")
JARS = os.path.join(DEPS, "jars"); EXT = os.path.join(DEPS, "extracted")
shutil.rmtree(JARS, ignore_errors=True); shutil.rmtree(EXT, ignore_errors=True)
os.makedirs(JARS); os.makedirs(EXT)
EXCLUDE = ("kotlin-stdlib-common", "listenablefuture-9999", "checker-qual",
           "error_prone_annotations", "j2objc-annotations", "jsr305", "annotations-13.0")
for jar in glob.glob(os.path.join(DEPS, "*.jar")):
    base = os.path.basename(jar)
    if base.startswith(EXCLUDE): continue
    shutil.copy(jar, os.path.join(JARS, base))
for aar in glob.glob(os.path.join(DEPS, "*.aar")):
    name = os.path.basename(aar)[:-4]
    z = zipfile.ZipFile(aar)
    names = z.namelist()
    if "classes.jar" in names:
        with z.open("classes.jar") as f, open(os.path.join(JARS, name + ".jar"), "wb") as o:
            shutil.copyfileobj(f, o)
    outdir = os.path.join(EXT, name)
    for n in names:
        if n.startswith("res/") and not n.endswith("/"):
            target = os.path.join(outdir, n)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with z.open(n) as f, open(target, "wb") as o:
                shutil.copyfileobj(f, o)
        elif n == "R.txt":
            os.makedirs(outdir, exist_ok=True)
            with z.open(n) as f, open(os.path.join(outdir, "R.txt"), "wb") as o:
                shutil.copyfileobj(f, o)
print("jars:", len(os.listdir(JARS)), "extracted:", len(os.listdir(EXT)))
EOF

echo "== aapt2 compile"
$BT/aapt2 compile --dir $APP/res -o $BUILD/app_res.zip
LIBZIPS=""
for d in $DEPS/extracted/*/; do
  if [ -d "$d/res" ] && [ -n "$(find "$d/res" -type f | head -1)" ]; then
    name=$(basename "$d")
    $BT/aapt2 compile --dir "$d/res" -o "$BUILD/libres/$name.zip"
    LIBZIPS="$LIBZIPS $BUILD/libres/$name.zip"
  fi
done

echo "== aapt2 link"
$BT/aapt2 link -o $BUILD/unsigned.apk -I $AJAR --manifest $APP/AndroidManifest.xml \
  -A $APP/assets $BUILD/app_res.zip $LIBZIPS --java $BUILD/gen --auto-add-overlay \
  --output-text-symbols $BUILD/symbols.txt

echo "== generate library R classes"
python3 - <<'EOF'
import os, re
ROOT = os.getcwd()
BUILD = os.path.join(ROOT, "app/build")
PKG = {
    "media3-common-1.4.1": "androidx.media3.common",
    "media3-container-1.4.1": "androidx.media3.container",
    "media3-database-1.4.1": "androidx.media3.database",
    "media3-datasource-1.4.1": "androidx.media3.datasource",
    "media3-decoder-1.4.1": "androidx.media3.decoder",
    "media3-exoplayer-1.4.1": "androidx.media3.exoplayer",
    "media3-exoplayer-hls-1.4.1": "androidx.media3.exoplayer.hls",
    "media3-extractor-1.4.1": "androidx.media3.extractor",
    "core-1.8.0": "androidx.core",
    "core-runtime-2.1.0": "androidx.arch.core",
    "lifecycle-runtime-2.3.1": "androidx.lifecycle",
    "versionedparcelable-1.1.1": "androidx.versionedparcelable",
    "exifinterface-1.3.6": "androidx.exifinterface",
    "annotation-experimental-1.3.1": "androidx.annotation.experimental",
}
def parse_symbols(path):
    syms = {}
    if not os.path.exists(path): return syms
    text = open(path, encoding="utf-8", errors="replace").read()
    text = text.replace("\n", " ")
    for m in re.finditer(r"int\[\]\s+(\w+)\s+(\w+)\s*\{([^}]*)\}", text):
        syms[(m.group(1), m.group(2))] = "{ " + m.group(3).strip() + " }"
    for m in re.finditer(r"(?<!\[\])\bint\s+(\w+)\s+(\w+)\s+(0x[0-9a-fA-F]+|\d+)", text):
        syms.setdefault((m.group(1), m.group(2)), m.group(3))
    return syms
final = parse_symbols(os.path.join(BUILD, "symbols.txt"))
ext_dir = os.path.join(ROOT, "deps/extracted")
for name, pkg in PKG.items():
    rtxt = os.path.join(ext_dir, name, "R.txt")
    if not os.path.exists(rtxt): continue
    want = parse_symbols(rtxt)
    by_type = {}
    for (typ, nm), val in want.items():
        if typ == "styleable" and isinstance(val, str) and val.startswith("{"):
            fin = final.get((typ, nm))
            by_type.setdefault(typ, []).append((nm, fin if fin else val, True))
        else:
            fin = final.get((typ, nm))
            if fin is None: continue
            by_type.setdefault(typ, []).append((nm, fin, False))
    if not by_type: continue
    out = [f"package {pkg};", "public final class R {"]
    for typ, entries in sorted(by_type.items()):
        out.append(f"  public static final class {typ} {{")
        for nm, val, is_arr in entries:
            if is_arr: out.append(f"    public static final int[] {nm} = new int[] {val};")
            else: out.append(f"    public static final int {nm} = {val};")
        out.append("  }")
    out.append("}")
    dest = os.path.join(BUILD, "gen", *pkg.split("."), "R.java")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    open(dest, "w").write("\n".join(out))
    print("R:", pkg)
EOF

echo "== javac"
CP=$(ls $DEPS/jars/*.jar | tr '\n' ':')
find $APP/src $BUILD/gen -name '*.java' > $BUILD/sources.txt
javac -encoding UTF-8 --release 8 -cp "$AJAR:$CP" -d $BUILD/classes @$BUILD/sources.txt

echo "== dex"
cd $BUILD/classes && jar cf $BUILD/classes.jar . && cd $APP
$BT/d8 --min-api 26 --lib $AJAR --output $BUILD/dex $BUILD/classes.jar $DEPS/jars/*.jar

echo "== add dex + align + sign"
cd $BUILD/dex && zip -q -j $BUILD/unsigned.apk classes.dex && cd $APP
$BT/zipalign -f 4 $BUILD/unsigned.apk $BUILD/aligned.apk
KEYSTORE="${KEYSTORE:-$ROOT/youzai.keystore}"
if [ ! -f "$KEYSTORE" ]; then
  echo "== generating local signing key (first build only, never commit it)"
  keytool -genkeypair -keystore "$KEYSTORE" -alias youzai -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Youzai Video"
fi
$BT/apksigner sign --ks "$KEYSTORE" --ks-pass pass:android \
  --key-pass pass:android --out $ROOT/youzai-video.apk $BUILD/aligned.apk

echo "== verify"
$BT/apksigner verify --print-certs $ROOT/youzai-video.apk | head -3
$BT/aapt2 dump badging $ROOT/youzai-video.apk | head -6
ls -la $ROOT/youzai-video.apk
unzip -l $ROOT/youzai-video.apk | grep -E "classes.dex|index.html|AndroidManifest"
echo BUILD_OK
