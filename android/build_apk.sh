#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-$(ls -d ~/tools/jdk-17* | head -1)}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/tools/android-sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
AJ="$ANDROID_HOME/platforms/android-34/android.jar"
PATH="$JAVA_HOME/bin:$BT:$PATH"

OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/7] aapt2 compile（只编译图标资源）"
# 全量编译 res/ 会在 themes.xml 上翻车：Theme.Material3.DayNight.NoActionBar 来自
# Material 库，而这个脚本不引 AAR。所以只把图标相关的文件编成 .flat，其它 res 不进包。
RES=app/src/main/res
rm -rf "$OUT/res"; mkdir -p "$OUT/res"
find "$RES" -type f \( -path '*/mipmap-*/*' -o -name 'ic_launcher_colors.xml' \) -print0 |
  while IFS= read -r -d '' f; do aapt2 compile -o "$OUT/res" "$f"; done

echo "[2/7] aapt2 link"
mapfile -t FLATS < <(find "$OUT/res" -name '*.flat' | sort)
aapt2 link -o "$OUT/base.apk" -I "$AJ" \
  --manifest app/src/main/AndroidManifest.xml \
  "${FLATS[@]}" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 4 --version-name 0.4

echo "[3/7] javac"
find app/src/main/java -name '*.java' > "$OUT/srcs.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$AJ" -classpath "$AJ" \
  -d "$OUT/classes" @"$OUT/srcs.txt" 2>&1 | { grep -v 'bootstrap class path' || true; } | tee "$OUT/javac.log"
# grep 的退出码在这里不说明问题，javac 自己写下的 error 才算；吞掉它就会发出缺类的 APK。
if grep -q ' error: ' "$OUT/javac.log"; then
  echo '编译失败，停下。' >&2
  exit 1
fi

echo "[4/7] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
d8 --lib "$AJ" --min-api 26 --output "$OUT/dex" @"$OUT/classes.txt"

echo "[5/7] 打包 dex"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/unsigned.apk" <<'PYEOF'
import sys, zipfile, shutil
src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
print("   dex 已打入")
PYEOF

echo "[6/7] zipalign"
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[7/7] 签名"
KS="debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/diting.apk" "$OUT/aligned.apk"
apksigner verify --print-certs "$OUT/diting.apk" | head -4
echo "APK: $(pwd)/$OUT/diting.apk  $(stat -c%s "$OUT/diting.apk") bytes"
