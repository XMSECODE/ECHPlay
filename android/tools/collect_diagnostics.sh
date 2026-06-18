#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

PACKAGE_NAME="${PACKAGE_NAME:-com.example.abcplaydemo}"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/build/diagnostics/$(date +%Y%m%d_%H%M%S)}"

# 输出诊断脚本日志。
log() {
  printf '[diagnostics] %s\n' "$1"
}

# 安全执行 adb 命令，失败时写入提示但不中断其他收集项。
run_adb_capture() {
  local output_file="$1"
  shift
  if "$@" >"$output_file" 2>&1; then
    log "saved $output_file"
  else
    printf 'command failed: %s\n' "$*" >>"$output_file"
    log "saved failed command output $output_file"
  fi
}

if ! command -v adb >/dev/null 2>&1; then
  log "adb not found, skip diagnostics"
  exit 0
fi

if ! DEVICE_LIST="$(adb devices 2>&1)"; then
  # 某些沙箱或 CI 环境不允许启动 adb daemon，此时跳过而不是阻塞发版。
  log "adb unavailable, skip diagnostics"
  printf '%s\n' "$DEVICE_LIST"
  exit 0
fi

DEVICE_COUNT="$(printf '%s\n' "$DEVICE_LIST" | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
if [[ "$DEVICE_COUNT" -le 0 ]]; then
  log "no adb device, skip diagnostics"
  exit 0
fi

mkdir -p "$OUTPUT_DIR"
log "collect diagnostics to $OUTPUT_DIR"

run_adb_capture "$OUTPUT_DIR/devices.txt" adb devices -l
run_adb_capture "$OUTPUT_DIR/logcat_tail.txt" adb logcat -d -t 2000
run_adb_capture "$OUTPUT_DIR/player_pid.txt" adb shell pidof "$PACKAGE_NAME"
run_adb_capture "$OUTPUT_DIR/meminfo.txt" adb shell dumpsys meminfo "$PACKAGE_NAME"
run_adb_capture "$OUTPUT_DIR/gfxinfo.txt" adb shell dumpsys gfxinfo "$PACKAGE_NAME"
run_adb_capture "$OUTPUT_DIR/activity.txt" adb shell dumpsys activity top
run_adb_capture "$OUTPUT_DIR/tombstones.txt" adb shell ls -lt /data/tombstones

log "diagnostics complete"
