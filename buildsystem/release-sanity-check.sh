#!/usr/bin/env bash
set -euo pipefail

default_gradle="/Users/damanmehta/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin/gradle"

if [[ -n "${GRADLE_BIN:-}" ]]; then
  gradle_bin="$GRADLE_BIN"
elif command -v gradle >/dev/null 2>&1; then
  gradle_bin="gradle"
elif [[ -x "$default_gradle" ]]; then
  gradle_bin="$default_gradle"
else
  echo "Set GRADLE_BIN to a Gradle executable or install gradle on PATH." >&2
  exit 1
fi

"$gradle_bin" \
  :application:vlc-android:packageDebugResources \
  :application:vlc-android:compileDebugKotlin \
  :application:television:compileDebugKotlin
