#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MOD="$ROOT/module"
OUT="$MOD/build/test-classes"

if command -v cygpath >/dev/null 2>&1; then
  export MSYS2_ARG_CONV_EXCL='*'
  native_path() { cygpath -w "$1"; }
  SEP=';'
  AJ_W="${AJ_W:-D:/AndroidSDK/platforms/android-34/android.jar}"
  JDK_W="${JDK_W:-D:/JAVA/bin}"
  JAVAC="$JDK_W/javac.exe"
  JAVA="$JDK_W/java.exe"
else
  native_path() { printf '%s' "$1"; }
  SEP=':'
  : "${AJ_W:?Set AJ_W to the Android SDK android.jar path}"
  JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
  JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
fi
API_W="$(native_path "$MOD/lib/api-102.jar")"
if command -v cygpath >/dev/null 2>&1; then
  "$ROOT/gradlew.bat" -p "$(native_path "$ROOT")" :app:prepareHookTestLibraries --quiet
else
  bash "$ROOT/gradlew" -p "$ROOT" :app:prepareHookTestLibraries --quiet
fi
LIBS_W="$(native_path "$MOD/build/test-libs")/*"

mkdir -p "$OUT"
SRCS=()
while IFS= read -r -d '' f; do SRCS+=("$(native_path "$f")"); done < <(find "$MOD/src" "$MOD/test" -name '*.java' -print0)

"$JAVAC" -J-Duser.language=en -J-Duser.country=US \
  -encoding UTF-8 -source 8 -target 8 -nowarn \
  -cp "$AJ_W$SEP$API_W$SEP$LIBS_W" -d "$(native_path "$OUT")" "${SRCS[@]}"

"$JAVA" -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
  -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "-Dtest.output=$(native_path "$MOD/build")" \
  -cp "$(native_path "$OUT")$SEP$AJ_W$SEP$API_W$SEP$LIBS_W" com.shitianyaa.nagramx.videotimer.TestMain
