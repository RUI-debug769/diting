#!/usr/bin/env bash
# Clean 的纯 JVM 自测：只编译 Clean + CleanCheck，不碰 Android 框架。
# 全项目编译仍由 build_apk.sh 的 javac 段负责。
set -euo pipefail
cd "$(dirname "$0")"

JDK="${JAVA_HOME:-$HOME/tools/jdk-17.0.21+9}"
JAVAC="$JDK/bin/javac"
JAVA="$JDK/bin/java"
SRC="app/src/main/java/com/diting/app"
TEST="test/java/com/diting/app"
OUT="build/clean-check"

if [ ! -x "$JAVAC" ]; then
  echo "找不到 javac：$JAVAC（设 JAVA_HOME 指到 JDK 17）" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"
"$JAVAC" -encoding UTF-8 -d "$OUT" \
  "$SRC/Clean.java" "$TEST/CleanCheck.java"
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT" com.diting.app.CleanCheck
