#!/usr/bin/env bash
set -euo pipefail

# 使用宿主机 FFmpeg 验证 I/O 中断，不替代 Android JNI/设备验收。
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TASK_BUILD="$(mktemp -d "${TMPDIR:-/tmp}/echplay-io-test.XXXXXX")"
trap 'rm -rf "$TASK_BUILD"' EXIT

for tool in c++ pkg-config ffmpeg; do
    command -v "$tool" >/dev/null || { printf 'missing tool: %s\n' "$tool"; exit 1; }
done
pkg-config --exists libavformat libavcodec libavutil

# 生成只用于本次测试的 TS 码流，服务端发送完后保持连接模拟断流。
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=160x96:rate=10:duration=4' \
    -an -c:v libx264 -pix_fmt yuv420p -f mpegts "$TASK_BUILD/sample.ts"

# pkg-config 输出有意按编译器参数拆分，不作为 shell 命令执行。
c++ -std=c++17 -pthread -Wall -Wextra -Werror \
    -I"$TASK_ROOT/echplayer/src/main/cpp" \
    $(pkg-config --cflags libavformat libavcodec libavutil) \
    "$TASK_ROOT/tools/tests/io_interrupt_integration.cpp" \
    $(pkg-config --libs libavformat libavcodec libavutil) \
    -o "$TASK_BUILD/io_interrupt_test"

"$TASK_BUILD/io_interrupt_test" "$TASK_BUILD/sample.ts" \
    "$TASK_ROOT/app/src/androidTest/assets/data_source_sample.mp4"

# 使用真实 RTSP 响应验证错误分类，覆盖旧日志文本匹配容易误判的地址。
c++ -std=c++17 -pthread -Wall -Wextra -Werror \
    -I"$TASK_ROOT/echplayer/src/main/cpp" \
    $(pkg-config --cflags libavformat libavcodec libavutil) \
    "$TASK_ROOT/tools/tests/player_error_integration.cpp" \
    $(pkg-config --libs libavformat libavcodec libavutil) \
    -o "$TASK_BUILD/player_error_test"
"$TASK_BUILD/player_error_test"
