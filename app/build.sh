#!/usr/bin/env bash
# Build hoya-runner.apk with the plain SDK tools: aapt2 + javac + d8 + apksigner.
# No Gradle, no Android Studio project, no dependencies.
#
# Payload: set GHOSTPW=/path/to/ghostpw to use a prebuilt binary, or let
# exploit/build.sh produce one into exploit/build/ghostpw first.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)

SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}
BT=${BUILD_TOOLS:-$SDK/build-tools/34.0.0}
JAR=${ANDROID_JAR:-$SDK/platforms/android-30/android.jar}
out=$here/build
ks=${KS:-$HOME/.hoya-runner/runner.jks}
kspass=${KS_PASS:-android}
r8=${R8_JAR:-$HOME/.hoya-runner/r8.jar}
R8_VERSION=${R8_VERSION:-9.5.32}
version_code=${VERSION_CODE:-1}
version_name=${VERSION_NAME:-0.1}

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$JAR"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done
export PATH="$BT:$PATH"

echo "==> payload"
payload_src=${GHOSTPW:-$root/exploit/build/ghostpw}
if [ ! -e "$payload_src" ]; then
  echo "no payload at $payload_src; run exploit/build.sh first or set GHOSTPW=" >&2
  exit 1
fi
mkdir -p "$here/assets"
cp "$payload_src" "$here/assets/ghostpw"
chmod 644 "$here/assets/ghostpw"
echo "    using $payload_src ($(shasum -a 256 "$payload_src" | cut -c1-16)…)"

rm -rf "$out"
mkdir -p "$out/gen" "$out/classes" "$out/dex"

echo "==> aapt2 compile"
aapt2 compile --dir "$here/res" -o "$out/res.zip"

echo "==> aapt2 link"
aapt2 link \
  -o "$out/base.apk" \
  -I "$JAR" \
  --manifest "$here/AndroidManifest.xml" \
  -A "$here/assets" \
  --java "$out/gen" \
  --min-sdk-version 28 \
  --target-sdk-version 28 \
  --version-code "$version_code" \
  --version-name "$version_name" \
  "$out/res.zip"

echo "==> javac"
find "$here/src" "$out/gen" -name '*.java' > "$out/sources.txt"
javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 \
  -classpath "$JAR" -d "$out/classes" @"$out/sources.txt"

echo "==> d8"
find "$out/classes" -name '*.class' > "$out/classes.txt"

# Build-tools 34's bundled d8 (R8 8.2.2-dev) crashes on anonymous inner classes on this
# machine, so probe it once and fall back to a stock r8.jar from Maven.
mkdir -p "$out/probe/src" "$out/probe/cls" "$out/probe/dex"
cat > "$out/probe/src/P.java" <<'EOF'
public class P { Runnable r = new Runnable() { public void run() {} }; }
EOF
javac -source 8 -target 8 -nowarn -d "$out/probe/cls" "$out/probe/src/P.java" 2>/dev/null
d8_ok=1
d8 --release --min-api 28 --lib "$JAR" --output "$out/probe/dex" "$out/probe/cls/P\$1.class" \
  >/dev/null 2>&1 || d8_ok=0

if [ "$d8_ok" = 1 ]; then
  d8 --release --min-api 28 --lib "$JAR" --output "$out/dex" @"$out/classes.txt"
else
  echo "    bundled d8 is broken here, using r8 ${R8_VERSION} from \$HOME/.hoya-runner"
  if [ ! -e "$r8" ]; then
    mkdir -p "$(dirname "$r8")"
    curl -fsSL -o "$r8" \
      "https://dl.google.com/dl/android/maven2/com/android/tools/r8/${R8_VERSION}/r8-${R8_VERSION}.jar"
  fi
  java -cp "$r8" com.android.tools.r8.D8 --release --min-api 28 --lib "$JAR" \
    --output "$out/dex" @"$out/classes.txt"
fi

echo "==> package"
(cd "$out/dex" && zip -q -j "$out/base.apk" classes.dex)
zipalign -f 4 "$out/base.apk" "$out/aligned.apk"

if [ ! -e "$ks" ]; then
  echo "==> keystore (first run)"
  mkdir -p "$(dirname "$ks")"
  keytool -genkeypair -keystore "$ks" -alias runner -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$kspass" -keypass "$kspass" \
    -dname "CN=GhostLock Runner, O=local, C=US" >/dev/null
fi

echo "==> sign"
apksigner sign --ks "$ks" --ks-key-alias runner \
  --ks-pass "pass:$kspass" --key-pass "pass:$kspass" \
  --out "$here/hoya-runner.apk" "$out/aligned.apk"
apksigner verify --print-certs "$here/hoya-runner.apk" | sed -n '1,3p'

ls -lh "$here/hoya-runner.apk"
echo "install: upload $here/hoya-runner.apk to LAT"
