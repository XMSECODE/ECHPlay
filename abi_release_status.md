# ECHPlay ABI 发布状态

本文档记录 SDK 发版时各 ABI 的真实状态，避免把“目录存在”误解为“可运行支持”。

## 当前结论

| ABI | 状态 | 发版动作 | 说明 |
| --- | --- | --- | --- |
| `arm64-v8a` | 已支持 | 进入 AAR | 当前仓库包含完整 FFmpeg so，`smoke_check.sh` 已检查 Release AAR 条目 |
| `armeabi-v7a` | 待补齐 | 不进入 AAR | 目录已预留，但缺少完整 `libavcodec.so`、`libavformat.so`、`libavutil.so`、`libswresample.so`、`libswscale.so` |
| `x86_64` | 待预研 | 不进入 AAR | 需要补齐 x86_64 FFmpeg so、CMake 验证和模拟器播放回归 |
| `x86` | 暂不建议 | 不进入 AAR | Android 生态占比低，优先级低于 `x86_64` |

## Gradle 策略

`android/echplayer/build.gradle` 中维护候选 ABI 和必需 FFmpeg 动态库列表。只有某个 ABI 下同时存在以下库时，Gradle 才会把该 ABI 加入 `abiFilters`：

1. `libavcodec.so`
2. `libavformat.so`
3. `libavutil.so`
4. `libswresample.so`
5. `libswscale.so`

这样做的好处是简单、可解释，也能避免 AAR 里出现 `libechplayer.so` 存在但 FFmpeg 依赖缺失的半成品 ABI。

## 补齐新 ABI 的步骤

1. 编译或引入目标 ABI 的 FFmpeg so。
2. 把 so 放到 `android/echplayer/src/main/jniLibs/<abi>/`。
3. 确认 CMake 能为该 ABI 编译 `libechplayer.so`。
4. 执行 `cd android && ./tools/smoke_check.sh`。
5. 用 `unzip -l android/echplayer/build/outputs/aar/echplayer-release.aar` 检查 AAR 条目。
6. 使用真机或模拟器验证本地 MP4、HTTP、HLS、RTSP TCP/UDP。
7. 更新本文件和 `compatibility_matrix.md`。

## v2.7 发版状态

v2.7 只声明 `arm64-v8a` 已支持。`armeabi-v7a` 和 `x86_64` 被列为后续版本目标，不在 v2.7 中伪装完成。
