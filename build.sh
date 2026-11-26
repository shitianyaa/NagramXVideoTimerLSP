#!/usr/bin/env bash
# Use Gradle so the development APK includes transitive dependencies and native libraries.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then
  export MSYS2_ARG_CONV_EXCL='*'
  "$ROOT/gradlew.bat" -p "$(cygpath -w "$ROOT")" :app:assembleDebug
else
  bash "$ROOT/gradlew" -p "$ROOT" :app:assembleDebug
fi
VERSION="$(sed -n 's/^MODULE_VERSION_NAME=//p' "$ROOT/gradle.properties" | tr -d '\r')"
DESTINATION="${1:-$ROOT/build/NagramTimer-v$VERSION-debug.apk}"
mkdir -p "$(dirname "$DESTINATION")"
cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$DESTINATION"
printf '已输出 Gradle Debug 安装包：%s\n' "$DESTINATION"
