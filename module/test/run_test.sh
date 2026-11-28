#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$PROJECT_ROOT"

resolve_jdk_bin() {
  local cand
  for cand in "${JDK_W:-}" "${JAVA_HOME:+$JAVA_HOME/bin}" "/d/JAVA/bin" "/mnt/d/JAVA/bin"; do
    [[ -n "$cand" ]] || continue
    command -v cygpath >/dev/null 2>&1 && cand="$(cygpath -u "$cand")"
    if [[ -x "$cand/javac" || -x "$cand/javac.exe" ]]; then
      printf '%s\n' "$cand"
      return 0
    fi
  done
  if command -v javac >/dev/null 2>&1; then
    dirname "$(command -v javac)"
    return 0
  fi
  echo "Missing JDK javac (set JAVA_HOME or JDK_W)" >&2
  return 1
}

JDK_BIN="$(resolve_jdk_bin)"
EXT_EXE=""; [[ -x "$JDK_BIN/javac.exe" ]] && EXT_EXE=".exe"
TOOL_SRC="module/tools/ModuleBuild.java"
TOOLS_OUT="module/build/tools"
TOOL_CLASS="$TOOLS_OUT/tool/ModuleBuild.class"
if [[ ! -f "$TOOL_CLASS" || "$TOOL_SRC" -nt "$TOOL_CLASS" ]]; then
  mkdir -p "$TOOLS_OUT"
  "$JDK_BIN/javac$EXT_EXE" -J-Duser.language=en -encoding UTF-8 -d "$TOOLS_OUT" "$TOOL_SRC"
fi

exec "$JDK_BIN/java$EXT_EXE" -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
  -cp "$TOOLS_OUT" tool.ModuleBuild test
