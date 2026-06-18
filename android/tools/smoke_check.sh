#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

# 输出 smoke 检查日志。
log() {
  printf '[smoke] %s\n' "$1"
}

# 检查指定文件必须存在。
require_file() {
  local file_path="$1"
  if [[ ! -f "$file_path" ]]; then
    printf '[smoke] missing file: %s\n' "$file_path" >&2
    exit 1
  fi
  log "found $file_path"
}

# 检查 AAR 内必须包含指定条目。
require_aar_entry() {
  local aar_path="$1"
  local entry_name="$2"
  if ! unzip -l "$aar_path" | awk -v entry="$entry_name" '$NF == entry { found = 1 } END { exit found ? 0 : 1 }'; then
    printf '[smoke] missing aar entry: %s in %s\n' "$entry_name" "$aar_path" >&2
    exit 1
  fi
  log "aar contains $entry_name"
}

# 检查文本文件必须包含指定内容。
require_text() {
  local file_path="$1"
  local pattern="$2"
  if ! grep -Fq "$pattern" "$file_path"; then
    printf '[smoke] missing text: %s in %s\n' "$pattern" "$file_path" >&2
    exit 1
  fi
  log "text found in $file_path: $pattern"
}

log "build Debug / Release AAR and Demo APK"
./gradlew :echplayer:assembleDebug :echplayer:assembleRelease :app:assembleDebug -x test --console=plain

DEBUG_AAR="echplayer/build/outputs/aar/echplayer-debug.aar"
RELEASE_AAR="echplayer/build/outputs/aar/echplayer-release.aar"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
PLAYER_API="echplayer/src/main/java/com/echplay/player/ECHPlayer.java"
DEMO_ACTIVITY="app/src/main/java/com/example/abcplaydemo/MainActivity.java"
BACKEND_API="echplayer/src/main/java/com/echplay/player/PlayerBackend.java"
BACKEND_FACTORY="echplayer/src/main/java/com/echplay/player/PlayerBackendFactory.java"

# 检查构建产物是否生成。
require_file "$DEBUG_AAR"
require_file "$RELEASE_AAR"
require_file "$DEBUG_APK"

# 检查 Release AAR 是否包含播放器和 FFmpeg native so。
require_aar_entry "$RELEASE_AAR" "jni/arm64-v8a/libechplayer.so"
require_aar_entry "$RELEASE_AAR" "jni/arm64-v8a/libavcodec.so"
require_aar_entry "$RELEASE_AAR" "jni/arm64-v8a/libavformat.so"
require_aar_entry "$RELEASE_AAR" "jni/arm64-v8a/libavutil.so"
require_aar_entry "$RELEASE_AAR" "jni/arm64-v8a/libswresample.so"
require_aar_entry "$RELEASE_AAR" "jni/arm64-v8a/libswscale.so"

# 检查关键 API 和自动回归入口，避免发布时误删核心能力。
require_text "$PLAYER_API" "captureCurrentFramePng"
require_text "$PLAYER_API" "startRecording"
require_text "$PLAYER_API" "setRtspTransport"
require_text "$PLAYER_API" "getPropertyLong"
require_text "$DEMO_ACTIVITY" "EXTRA_AUTO_PLAY"
require_text "$DEMO_ACTIVITY" "scheduleAutomationActions"
require_text "$BACKEND_API" "interface PlayerBackend"
require_text "$BACKEND_FACTORY" "AndroidMediaPlayerBackend"

# 检查发版文档是否存在，保证代码和流程一起交付。
require_file "../abi_release_status.md"
require_file "../github_release_template.md"
require_file "../v2.8_test_matrix.md"
require_file "../v2.8_validation_report.md"
require_file "../v2.9_backend_decision.md"
require_file "../v2.9_validation_report.md"

if command -v adb >/dev/null 2>&1; then
  # 有 adb 设备时做安装和启动验证，没有设备则跳过。
  DEVICE_COUNT="$(adb devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
  if [[ "$DEVICE_COUNT" -gt 0 ]]; then
    log "adb device found, install and launch Demo"
    adb install -r "$DEBUG_APK"
    adb shell am start -n com.example.abcplaydemo/.MainActivity
  else
    log "no adb device, skip install and launch"
  fi
else
  log "adb not found, skip install and launch"
fi

log "smoke check passed"
