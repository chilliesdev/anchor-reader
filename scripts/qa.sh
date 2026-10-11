#!/usr/bin/env bash
set -euo pipefail

# Test only the selected Android device. Build output stays in the project.
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
if [[ -z "${JAVA_HOME:-}" && -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]]; then
  export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
fi
adb_bin="$ANDROID_HOME/platform-tools/adb"
mode="${1:-all}"
evidence_dir="${ANCHOR_QA_OUTPUT:-$project_dir/.tmp/qa}"
mkdir -p "$evidence_dir"

case "$mode" in
  unit)
    ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug
    ;;
  device|all)
    : "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a task-owned emulator or approved test device.}"
    "$adb_bin" -s "$ANDROID_SERIAL" get-state >/dev/null
    if [[ "$mode" == all ]]; then
      ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
    else
      ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
    fi
    "$adb_bin" -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
    "$adb_bin" -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    "$adb_bin" -s "$ANDROID_SERIAL" shell am instrument -w -r \
      com.anchorreader.test/androidx.test.runner.AndroidJUnitRunner | tee "$evidence_dir/instrumentation.log"
    # Android's shell may return zero even when a test fails. Check the runner summary.
    if ! awk '/^OK \([0-9]+ tests?\)/ { passed=1 } /FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/ { failed=1 } END { exit !(passed && !failed) }' "$evidence_dir/instrumentation.log"; then
      echo "Device tests failed. Read $evidence_dir/instrumentation.log" >&2
      exit 1
    fi
    ;;
  screenshot)
    : "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the test device.}"
    name="${2:-current}"
    if [[ ! "$name" =~ ^[a-zA-Z0-9_-]+$ ]]; then
      echo 'Screenshot name must use letters, numbers, underscores, or hyphens.' >&2
      exit 2
    fi
    "$adb_bin" -s "$ANDROID_SERIAL" exec-out screencap -p > "$evidence_dir/$name.png"
    echo "$evidence_dir/$name.png"
    ;;
  *)
    echo 'Usage: scripts/qa.sh [all|unit|device|screenshot [name]]' >&2
    exit 2
    ;;
esac
