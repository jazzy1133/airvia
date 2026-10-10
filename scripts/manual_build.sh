#!/bin/bash
# Manual APK build for Outro (no Gradle)
# Requires: JDK 17, Android SDK, kotlin-compiler-embeddable 2.1.0, dependencies in libs/
set -euo pipefail

export JAVA_HOME=/home/hatch/jdk/jdk-17.0.20.1+1
export PATH=$JAVA_HOME/bin:$PATH

PROJECT=~/workspace/airvia
LIBS=$PROJECT/libs
BUILD=$PROJECT/build-manual
ANDROID_SDK=~/android-sdk
PLATFORM=$ANDROID_SDK/platforms/android-34/android.jar
BUILD_TOOLS=$ANDROID_SDK/build-tools/34.0.0
# Kotlin 2.1.0 via kotlin-compiler-embeddable (has org.jetbrains.kotlin.com.intellij shading
# that the Compose compiler plugin requires; the standalone kotlinc dist does not).
K2_LIB=~/kotlinc/kotlin-2.1.0/kotlinc/lib
EMBED_JAR=~/kplugins/embed/kotlin-compiler-embeddable-2.1.0.jar
RUNCP="$EMBED_JAR:$K2_LIB/kotlin-stdlib.jar:$K2_LIB/kotlinx-coroutines-core-jvm.jar:$K2_LIB/trove4j.jar:$K2_LIB/annotations-13.0.jar:$K2_LIB/kotlin-reflect.jar:$K2_LIB/kotlin-script-runtime.jar"
KOTLINC="java -cp $RUNCP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

mkdir -p $BUILD/{classes,res-flat,dex,apk,aar-extract}

echo "=== Step 1: Extract resolved AARs ==="
RESOLVED=$LIBS/resolved-artifacts.txt
if [ ! -f "$RESOLVED" ]; then
  echo "FATAL: $RESOLVED missing; run download_deps.py first."
  exit 1
fi
while IFS=: read -r g a v ext; do
  [ "$ext" = "aar" ] || continue
  aar="$LIBS/$g/$a/$v/$a-$v.aar"
  name="$a-$v"
  outdir=$BUILD/aar-extract/$name
  if [ ! -d "$outdir" ]; then
    mkdir -p $outdir
    unzip -q -o "$aar" -d $outdir
  fi
done < "$RESOLVED"
echo "Extracted: $(ls $BUILD/aar-extract | wc -l) AARs"

echo "=== Step 2: Build classpath from resolved artifacts ==="
CP=""
while IFS=: read -r g a v ext; do
  if [ "$ext" = "aar" ]; then
    cj="$BUILD/aar-extract/$a-$v/classes.jar"
    [ -f "$cj" ] && CP="$CP:$cj"
  else
    jar="$LIBS/$g/$a/$v/$a-$v.jar"
    [ -f "$jar" ] && CP="$CP:$jar"
  fi
done < "$RESOLVED"
# android.jar
CP="$CP:$PLATFORM"
# Remove leading colon
CP=${CP#:}
echo "Classpath entries: $(echo $CP | tr ':' '\n' | wc -l)"

echo "=== Step 3: Compile Kotlin sources ==="
# Compose plugin for Kotlin 2.1.0 (K2-native CompilerPluginRegistrar) via -Xplugin.
# Serialization plugin from the 2.1.0 dist via -Xplugin.
COMPOSE_PLUGIN=~/kplugins/kotlin-compose-compiler-plugin-embeddable-2.1.0.jar
SERIAL_PLUGIN=~/kotlinc/kotlin-2.1.0/kotlinc/lib/kotlin-serialization-compiler-plugin.jar

PLUGIN_ARGS="-Xplugin=$COMPOSE_PLUGIN -Xplugin=$SERIAL_PLUGIN"
echo "Using compose plugin: $COMPOSE_PLUGIN"
echo "Using serialization plugin: $SERIAL_PLUGIN"

SOURCES=$(find $PROJECT/app/src/main/java -name "*.kt")
echo "Compiling $(echo "$SOURCES" | wc -l) Kotlin sources..."
if ! $KOTLINC $SOURCES \
  -cp "$CP" \
  -d $BUILD/classes \
  -jvm-target 17 \
  -no-stdlib \
  $PLUGIN_ARGS > $BUILD/kotlinc.log 2>&1; then
  echo "KOTLIN COMPILATION FAILED:"
  grep -E "error:|warning: " $BUILD/kotlinc.log | head -40
  echo "Full log: $BUILD/kotlinc.log"
  exit 1
fi
echo "Kotlin compilation OK."
{ grep -cE "warning: " $BUILD/kotlinc.log || true; } | xargs -I{} echo "warnings: {}"

echo "=== Step 4: Compile resources with aapt2 ==="
$BUILD_TOOLS/aapt2 compile --dir $PROJECT/app/src/main/res -o $BUILD/res-flat.zip
rm -rf $BUILD/res-out
mkdir -p $BUILD/res-out
unzip -q -o $BUILD/res-flat.zip -d $BUILD/res-out

# ALL AAR libraries get REAL compiled resources (their code loads resources
# at runtime - Compose UI popups, Material3, Media3, androidx.core, etc.).
# Synthetic/fake R IDs caused Resources$NotFoundException crashes.
# These are excluded from synthetic R generation below; their R classes
# get real IDs from aapt2's link output.
# Deduplicate AAR resources: multiple libraries can define the same
# (config, type, name) resource (e.g. androidx.media and media3-session both
# define TextAppearance.Compat.Notification styles). Gradle merges these
# (app wins, then first library wins); aapt2 link errors on them. So stage
# filtered copies of each AAR's res/ with only the winning definition kept.
echo "Deduplicating AAR resources..."
python3 << 'PYEOF'
import os, re, shutil

build = '/home/hatch/workspace/airvia/build-manual'
aar_base = os.path.join(build, 'aar-extract')
app_res = '/home/hatch/workspace/airvia/app/src/main/res'
dedup_base = os.path.join(build, 'aar-res-dedup')
shutil.rmtree(dedup_base, ignore_errors=True)
os.makedirs(dedup_base, exist_ok=True)

# resource key -> owner that first defined it
seen = {}

VALUE_TYPES = r'style|string|color|dimen|bool|integer|int-array|string-array|array|attr|declare-styleable|id|plurals|fraction'

def res_names_in_values_file(path):
    """Extract (type, name) pairs from a values XML file."""
    try:
        with open(path, 'r', encoding='utf-8', errors='ignore') as f:
            content = f.read()
    except Exception:
        return []
    results = []
    for m in re.finditer(r'<(%s)\s[^>]*?\bname="([^"]+)"' % VALUE_TYPES, content):
        results.append((m.group(1), m.group(2)))
    return results

def index_res_dir(resdir, owner):
    """Record all resources in a res dir as owned by `owner` (no filtering)."""
    for root, _, files in os.walk(resdir):
        reldir = os.path.relpath(root, resdir)
        for fn in files:
            src = os.path.join(root, fn)
            if reldir.startswith('values'):
                for (t, n) in res_names_in_values_file(src):
                    seen.setdefault((reldir, t, n), owner)
            else:
                seen.setdefault((reldir, fn), owner)

def remove_named_element(content, t, n):
    """Remove the <t name="n">...</t> or <t name="n"/> element. Returns new content."""
    # Block form
    pattern = r'\s*<%s\s[^>]*?\bname="%s"[^>]*>.*?</%s>' % (t, re.escape(n), t)
    content, cnt = re.subn(pattern, '', content, flags=re.DOTALL)
    if cnt == 0:
        # Self-closing form (attributes may follow the name)
        pattern2 = r'\s*<%s\s[^>]*?\bname="%s"[^>]*/>' % (t, re.escape(n))
        content, _ = re.subn(pattern2, '', content)
    return content

# The app's own resources win over all libraries (standard Android priority).
if os.path.isdir(app_res):
    index_res_dir(app_res, 'app')

libs = sorted(d for d in os.listdir(aar_base) if os.path.isdir(os.path.join(aar_base, d, 'res')))
for lib in libs:
    resdir = os.path.join(aar_base, lib, 'res')
    staged = os.path.join(dedup_base, lib, 'res')
    for root, _, files in os.walk(resdir):
        reldir = os.path.relpath(root, resdir)
        for fn in files:
            src = os.path.join(root, fn)
            if reldir.startswith('values'):
                names = res_names_in_values_file(src)
                if not names:
                    dst = os.path.join(staged, reldir, fn)
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    shutil.copy2(src, dst)
                    continue
                new_names = [(t, n) for (t, n) in names if (reldir, t, n) not in seen]
                if not new_names:
                    continue  # whole file duplicates something higher-priority
                if len(new_names) == len(names):
                    for (t, n) in names:
                        seen[(reldir, t, n)] = lib
                    dst = os.path.join(staged, reldir, fn)
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    shutil.copy2(src, dst)
                else:
                    with open(src, 'r', encoding='utf-8', errors='ignore') as f:
                        content = f.read()
                    for (t, n) in names:
                        key = (reldir, t, n)
                        if key in seen:
                            content = remove_named_element(content, t, n)
                        else:
                            seen[key] = lib
                    if re.search(r'<(%s)\s' % VALUE_TYPES, content):
                        dst = os.path.join(staged, reldir, fn)
                        os.makedirs(os.path.dirname(dst), exist_ok=True)
                        with open(dst, 'w', encoding='utf-8') as f:
                            f.write(content)
            else:
                key = (reldir, fn)
                if key in seen:
                    continue
                seen[key] = lib
                dst = os.path.join(staged, reldir, fn)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(src, dst)

print(f"Deduplicated AAR resources staged under {dedup_base}")
PYEOF
echo "Compiling real resources for ALL AAR libraries..."
UI_RES_FLATS=""
for libdir in $BUILD/aar-res-dedup/*/; do
  libname=$(basename "$libdir")
  if [ -d "$libdir/res" ]; then
    $BUILD_TOOLS/aapt2 compile --dir "$libdir/res" -o "$BUILD/res-ui-$libname.zip" 2>/dev/null || true
    if [ -f "$BUILD/res-ui-$libname.zip" ]; then
      rm -rf "$BUILD/res-ui-out-$libname"
      mkdir -p "$BUILD/res-ui-out-$libname"
      unzip -q -o "$BUILD/res-ui-$libname.zip" -d "$BUILD/res-ui-out-$libname" 2>/dev/null || true
      UI_RES_FLATS="$UI_RES_FLATS $BUILD/res-ui-out-$libname/*.flat"
    fi
  fi
done
echo "UI resource flats: $(echo $UI_RES_FLATS | wc -w)"

# Generate R classes for AAR libraries from their R.txt files.
# (Library classes.jar files don't include R classes, causing
#  ClassNotFoundException at runtime like the poolingcontainer crash.)
# NOTE: UI libs with real resources (above) are excluded; aapt2 generates their R.
echo "Generating library R classes..."
mkdir -p $BUILD/r-lib-src
python3 << 'PYEOF'
import os, re, glob

aar_extract = os.environ.get('AAR_EXTRACT_DIR', '/home/hatch/workspace/airvia/build-manual/aar-extract')
out_base = '/home/hatch/workspace/airvia/build-manual/r-lib-src'

# All libraries now get real compiled resources above, so NONE need synthetic R.
# Their R classes are generated with real IDs from aapt2's link output below.
SKIP_LIBS = set()
import glob as _glob
for _d in _glob.glob(os.path.join(aar_extract, '*')):
    _m = os.path.join(_d, 'AndroidManifest.xml')
    _r = os.path.join(_d, 'R.txt')
    if os.path.exists(_m) and os.path.exists(_r):
        SKIP_LIBS.add(os.path.basename(_d.rstrip('/')))

# Synthetic ID counter (unique across all libs)
next_id = 0x7f000001

for aardir in glob.glob(os.path.join(aar_extract, '*')):
    libname = os.path.basename(aardir.rstrip('/'))
    if libname in SKIP_LIBS:
        continue
    r_txt = os.path.join(aardir, 'R.txt')
    manifest = os.path.join(aardir, 'AndroidManifest.xml')
    if not os.path.exists(r_txt) or not os.path.exists(manifest):
        continue
    
    # Get package from manifest
    with open(manifest) as f:
        m = re.search(r'package="([^"]+)"', f.read())
        if not m:
            continue
        pkg = m.group(1)
    
    # Parse R.txt: format is "int <type> <name> <id>"
    resources = {}  # type -> [(name, id)]
    with open(r_txt) as f:
        for line in f:
            parts = line.strip().split()
            if len(parts) == 4 and parts[0] == 'int':
                rtype, name = parts[1], parts[2]
                # Use synthetic ID (R.txt has 0x0 placeholders)
                resources.setdefault(rtype, []).append(name)
    
    if not resources:
        continue
    
    # Generate R.java
    pkg_dir = os.path.join(out_base, pkg.replace('.', '/'))
    os.makedirs(pkg_dir, exist_ok=True)
    with open(os.path.join(pkg_dir, 'R.java'), 'w') as f:
        f.write(f'package {pkg};\npublic final class R {{\n')
        for rtype, names in resources.items():
            f.write(f'  public static final class {rtype} {{\n')
            for name in names:
                # Sanitize name (replace invalid chars)
                safe_name = re.sub(r'[^a-zA-Z0-9_]', '_', name)
                f.write(f'    public static final int {safe_name} = {next_id};\n')
                next_id += 1
            f.write('  }\n')
        f.write('}\n')

print(f"Generated R classes, next_id={hex(next_id)}")
PYEOF
echo "Library R classes generated."

echo "=== Step 5: Link resources and generate R.java ==="
mkdir -p $BUILD/r-src
$BUILD_TOOLS/aapt2 link \
  -o $BUILD/apk/base-unaligned.apk \
  -I $PLATFORM \
  --manifest $PROJECT/app/src/main/AndroidManifest.xml \
  --java $BUILD/r-src \
  --rename-manifest-package com.opus.airvia \
  --min-sdk-version 29 \
  --target-sdk-version 34 \
  --version-code 7 \
  --version-name "1.2.4" \
  $BUILD/res-out/*.flat $UI_RES_FLATS

# Fix: Generate material3 R with REAL IDs from aapt2's output.
# The synthetic R used fake IDs; the APK has real IDs from the link.
echo "Generating material3 R with real IDs..."
python3 << 'PYEOF'
import os, re

# Parse app's R.java for real IDs
app_r = '/home/hatch/workspace/airvia/build-manual/r-src/com/opus/airvia/R.java'
id_map = {}
with open(app_r) as f:
    content = f.read()
    # Match: public static final int name=0x12345678;
    for m in re.finditer(r'public static final int (\w+)=(0x[0-9a-fA-F]+);', content):
        id_map[m.group(1)] = m.group(2)

# Parse ALL libraries' R.txt files, generate their R classes with real IDs
# from the app's R (aapt2 link output). Package name comes from each AAR's manifest.
import re as _re
import glob as _glob2
for _aardir in _glob2.glob('/home/hatch/workspace/airvia/build-manual/aar-extract/*'):
    _libname = os.path.basename(_aardir.rstrip('/'))
    _manifest = os.path.join(_aardir, 'AndroidManifest.xml')
    _r_txt = os.path.join(_aardir, 'R.txt')
    if not (os.path.exists(_manifest) and os.path.exists(_r_txt)):
        continue
    try:
        with open(_manifest) as _mf:
            _mm = _re.search(r'package="([^"]+)"', _mf.read())
            if not _mm:
                continue
            _pkg = _mm.group(1)
    except Exception:
        continue
    _resources = {}
    try:
        with open(_r_txt) as f:
            for line in f:
                parts = line.strip().split()
                if len(parts) >= 4 and parts[0] == 'int':
                    _rtype, _name = parts[1], parts[2]
                    _real_id = id_map.get(_name, '0x0')
                    _resources.setdefault(_rtype, []).append((_name, _real_id))
    except FileNotFoundError:
        continue
    if not _resources:
        continue
    _out_dir = '/home/hatch/workspace/airvia/build-manual/r-lib-src/' + _pkg.replace('.', '/')
    os.makedirs(_out_dir, exist_ok=True)
    with open(os.path.join(_out_dir, 'R.java'), 'w') as f:
        f.write(f'package {_pkg};\n\npublic final class R {{\n')
        for _rtype, _items in sorted(_resources.items()):
            f.write(f'  public static final class {_rtype} {{\n')
            for _name, _rid in _items:
                _jname = _name
                if _re.match(r'^[0-9]', _jname):
                    _jname = '_' + _jname
                _jname = _re.sub(r'[^a-zA-Z0-9_]', '_', _jname)
                f.write(f'    public static final int {_jname}={_rid};\n')
            f.write('  }\n')
        f.write('}\n')
print(f"Generated real-ID R classes for all AAR libraries")
# Legacy single-lib block below kept for reference; replaced by loop above.
if False:
    r_txt = '/home/hatch/workspace/airvia/build-manual/aar-extract/material3-android-1.3.0/R.txt'
    resources = {}
    with open(r_txt) as f:
        for line in f:
            parts = line.strip().split()
            if len(parts) >= 4 and parts[0] == 'int':
                rtype, name = parts[1], parts[2]
                real_id = id_map.get(name, '0x0')
                resources.setdefault(rtype, []).append((name, real_id))
    out_dir = '/home/hatch/workspace/airvia/build-manual/r-lib-src/androidx/compose/material3'
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, 'R.java'), 'w') as f:
        f.write('package androidx.compose.material3;\n\npublic final class R {\n')
        for rtype, items in sorted(resources.items()):
            f.write(f'  public static final class {rtype} {{\n')
            for name, rid in items:
                f.write(f'    public static final int {name}={rid};\n')
            f.write('  }\n')
        f.write('}\n')
    print(f"Generated material3 R with {sum(len(v) for v in resources.values())} resources")
PYEOF

# Compile R.java (app + library R classes). aapt2 emits R.java as UTF-8
# (resource comments can contain non-ASCII chars), so javac must read it
# as UTF-8 rather than the platform default.
find $BUILD/r-src -name "*.java" > $BUILD/r-java.txt
find $BUILD/r-lib-src -name "*.java" >> $BUILD/r-java.txt 2>/dev/null || true
$JAVA_HOME/bin/javac -encoding UTF-8 -cp "$CP" -d $BUILD/classes @$BUILD/r-java.txt 2>&1 | head -20 || true

echo "=== Step 6: Dex with d8 ==="
# Jar up our compiled classes first (d8 cannot take a raw classes dir)
$JAVA_HOME/bin/jar cf $BUILD/app-classes.jar -C $BUILD/classes .
# Dex program inputs come from the resolved artifact list.
# NOTE: lifecycle-livedata-core 2.8.3 was excluded (D8 8.2.2 NPE on LiveData$1.class);
# downgraded to 2.7.0 which D8 handles. It IS needed (Navigation Compose uses MutableLiveData).
DEX_LIST=$BUILD/dex-inputs.txt
: > "$DEX_LIST"
while IFS=: read -r g a v ext; do
  if [ "$ext" = "aar" ]; then
    cj="$BUILD/aar-extract/$a-$v/classes.jar"
    [ -f "$cj" ] && echo "$cj" >> "$DEX_LIST"
  else
    jar="$LIBS/$g/$a/$v/$a-$v.jar"
    [ -f "$jar" ] && echo "$jar" >> "$DEX_LIST"
  fi
done < "$RESOLVED"
echo "dex program inputs: $(wc -l < $DEX_LIST)"
DEX_INPUTS="$BUILD/app-classes.jar $(tr '\n' ' ' < $DEX_LIST)"

# Main dex list: startup classes MUST be in primary classes.dex
# or Android can't find them at launch (instant ClassNotFoundException crash).
# NOTE: Disabled - using --min-api 29 without main-dex-list (like working bare test)
# cat > $BUILD/main-dex-list.txt << 'EOF'
# com.opus.airvia/MainActivity.class
# com.opus.airvia/CrashReportActivity.class
# EOF

$BUILD_TOOLS/d8 \
  --min-api 29 \
  --lib $PLATFORM \
  --output $BUILD/dex \
  $DEX_INPUTS > $BUILD/d8.log 2>&1 || { echo "D8 FAILED:"; tail -30 $BUILD/d8.log; exit 1; }
echo "D8 OK."

echo "=== Step 7: Add dex to APK ==="
cp $BUILD/apk/base-unaligned.apk $BUILD/apk/airvia-unsigned.apk
cd $BUILD/dex
for dex in *.dex; do
  $JAVA_HOME/bin/jar uf $BUILD/apk/airvia-unsigned.apk $dex
done
cd $PROJECT

echo "=== Step 8: Zipalign ==="
$BUILD_TOOLS/zipalign -f 4 $BUILD/apk/airvia-unsigned.apk $BUILD/apk/airvia-aligned.apk

echo "=== Step 9: Sign with debug key ==="
KEYSTORE=~/workspace/jazzy/build-manual/debug.keystore
if [ ! -f "$KEYSTORE" ]; then
  $JAVA_HOME/bin/keytool -genkeypair -keystore $KEYSTORE -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Android Debug,O=Android,C=US" 2>&1 | tail -2
fi
$BUILD_TOOLS/apksigner sign \
  --ks $KEYSTORE --ks-pass pass:android --key-pass pass:android \
  --out $BUILD/apk/airvia.apk \
  $BUILD/apk/airvia-aligned.apk

echo "=== Build complete ==="
ls -lh $BUILD/apk/airvia.apk
$BUILD_TOOLS/apksigner verify --print-certs $BUILD/apk/airvia.apk | head -5
