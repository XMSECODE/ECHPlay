#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

# 输出 Maven Local 发布日志，方便发版时定位当前步骤。
log() {
  printf '[publish-local] %s\n' "$1"
}

log "publish echplayer release AAR to Maven Local"
./gradlew :echplayer:publishReleasePublicationToMavenLocal --console=plain
log "published com.echplay:echplayer:2.8.0"
