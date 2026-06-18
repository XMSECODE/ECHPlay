# ECHPlay

ECHPlay 是一个基于 FFmpeg 和 Android MediaCodec 的 Android 播放器项目。v1.4 已补齐硬解能力：在 v1.3 OpenGL ES、YUV420P 三纹理、渲染模式切换、视频尺寸回调和 `ECHPlayerView` 画面比例控制基础上，新增 H.264 / H.265 MediaCodec 硬解、软硬解切换、失败回退和当前解码方式展示。v1.5 进入网络播放稳定性和可观测性阶段，重点推进 HTTP / HTTPS / HLS / RTSP 验证、RTSP 自动重连、播放统计和 PlayerView 基础状态。v1.6 进一步补齐媒体信息、轨道信息、prepare 耗时、首帧耗时、render fps、渲染帧数和丢帧统计，让 Demo 与业务侧都能更快定位播放问题。

## v1.4 能力

1. 独立播放器库：`android/echplayer` 使用 `com.android.library`，namespace 为 `com.echplay.player`。
2. 纯 Demo app：`android/app` 只保留页面、布局、图标、测试资源和示例交互。
3. Java API：`ECHPlayer`、`ECHPlayerView` 已迁移到 `com.echplay.player`。
4. native 核心：JNI、`NativePlayer`、CMake、FFmpeg include 已迁移到 `echplayer`。
5. AAR 输出：支持生成 `echplayer-debug.aar` 和 `echplayer-release.aar`。
6. native so 自包含：Release AAR 内包含 `libechplayer.so` 和 FFmpeg so。
7. ABI 策略：当前 arm64-v8a 可打包；armeabi-v7a 已预留目录，补齐 FFmpeg so 后自动启用。
8. OpenGL ES 渲染：YUV420P 帧优先通过 Y、U、V 三纹理上传到 GPU 渲染。
9. 兼容渲染：保留 NativeWindow + RGBA 软渲染路径，OpenGL 失败时可回退。
10. 渲染模式：支持 `AUTO`、`OPENGL`、`NATIVE_WINDOW`。
11. 画面比例：`ECHPlayerView` 支持 `fitCenter`、`centerCrop`、`fill`、`original`。
12. 视频尺寸：Java 层支持视频宽高变化回调和宽高读取。
13. 截图录制：截图仍保存当前解码帧 PNG，录制仍走 FFmpeg 封装输出，不依赖 Surface 截屏。
14. 解码模式：支持 `AUTO`、`SOFTWARE`、`MEDIACODEC` 三种模式。
15. MediaCodec：支持 H.264 / H.265 的硬解基础路径。
16. 失败回退：硬解不可用、输出格式不支持或送取帧失败时会回退 FFmpeg 软解。
17. 状态展示：Java API 和 Demo 可查看目标解码模式、当前实际解码方式、解码器名称和回退原因。

## v1.5 目标（已完成本轮规划）

v1.5 的目标不是一次性追平 ijkplayer 的全部协议和弱网经验，而是先建立“可播放、可诊断、可恢复、可验证”的网络播放闭环。

核心目标：

1. 验证 HTTP / HTTPS MP4 的播放、暂停、恢复、seek 和错误提示。
2. 验证 HLS / m3u8 的基础播放能力，并记录直播流 seek 边界。
3. 使用真实 RTSP 摄像头或稳定 RTSP server 验证 TCP / UDP 两种传输方式。
4. 增加 RTSP 断流自动重连开关、重试次数、重试间隔和重连 info 回调。
5. 增加网速、累计读取字节数、缓冲比例、队列长度、首开耗时、首帧耗时和 decode fps 等基础统计。
6. Demo 展示协议类型、网络状态、重连次数、统计信息和错误原因。
7. `ECHPlayerView` 增加 loading、buffering、error、retry 基础状态。
8. 维护协议能力表和 v1.5 验证报告，避免把“代码路径存在”误标成“实测通过”。

协议能力表：

| 协议 | 当前代码路径 | v1.5 目标 | 当前验证状态 | 说明 |
| --- | --- | --- | --- | --- |
| 本地文件 | 已支持 | 回归验证 | v1.4 已验证 | v1.5 需要确保不退化 |
| RTSP TCP | 已支持入口 | 真实源验证、断流重连 | 已执行，Android 侧未通过 | mediamtx + 宿主机 FFmpeg 拉流通过，Android FFmpeg 打开失败 |
| RTSP UDP | 已支持入口 | 真实源验证、断流重连 | 已执行，Android 侧未通过 | mediamtx 源可用，Android 真机 UDP 拉流超时 |
| HTTP MP4 | 已支持 | 播放、seek、错误诊断 | 播放已通过，seek 待复测 | faststart MP4 已播放通过，普通 MP4 建议 HTTP server 支持 Range |
| HTTPS MP4 | FFmpeg 可能支持 | 播放、seek、错误诊断 | 待验证 | 需要记录证书和 FFmpeg TLS 兼容性 |
| HLS VOD | 已支持 | m3u8 基础播放 | 已通过 | v1.5 不承诺加密、多码率切换和字幕 |
| HLS Live | FFmpeg 可能支持 | 播放或明确错误 | 待验证 | 直播流 seek 预期可能不支持 |

v1.5 文档：

1. `v1.5_requirements_goals.md`：v1.5 目标拆解和验收标准。
2. `v1.5_validation_report.md`：v1.5 协议、构建、统计和回归验证记录。

## v1.6 能力

v1.6 聚焦“媒体信息和性能统计”，主要新增：

1. `ECHPlayer.MediaInfo`：提供封装格式、总时长、总码率、视频编码、视频宽高、音频编码、采样率和声道数。
2. `ECHPlayer.TrackInfo`：提供每条 FFmpeg stream 的 index、type、codec、language、width、height、sampleRate 和 channels。
3. `ECHPlayer.getMediaInfo()`：prepare 成功后读取当前媒体摘要。
4. `ECHPlayer.getTrackInfo()`：prepare 成功后读取轨道列表。
5. `ECHPlayer.PlaybackStats` 扩展：新增 render fps、解码帧数、渲染帧数、主动丢帧数、prepare 耗时和首帧耗时。
6. 主 Demo：prepare 后直接打印媒体信息和轨道信息，状态栏展示 decode/render fps、D/R/Drop 帧数、prepare 和首帧耗时。
7. `ECHPlayerView`：`INFO_PREPARED` 后通过事件日志输出媒体摘要和轨道数量。

v1.6 文档：

1. `v1.6_requirements_goals.md`：v1.6 目标拆解和验收标准。
2. `v1.6_validation_report.md`：v1.6 构建和功能验收记录。

v1.7 文档：

1. `v1.7_requirements_goals.md`：v1.7 PlayerView 体验增强目标拆解。
2. `v1.7_validation_report.md`：v1.7 构建和功能验收记录。

v1.8 测试和发版文档：

1. `v1.8_requirements_goals.md`：v1.8 测试体系和稳定性文档目标拆解。
2. `v1.8_validation_report.md`：v1.8 smoke 检查和版本汇总报告。
3. `compatibility_matrix.md`：ABI、协议、编码、解码和核心功能兼容性矩阵。
4. `release_checklist.md`：发版前可勾选检查清单。
5. `android/tools/smoke_check.sh`：构建、产物和可选 adb 安装启动 smoke 脚本。

## 快速运行 Demo

```bash
cd android
./gradlew :app:assembleDebug -x test
```

Demo APK：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

当前仓库可直接运行的 ABI 是 `arm64-v8a`，请优先使用 arm64 真机或模拟器。

## Smoke 检查

发版前建议执行：

```bash
cd android
./tools/smoke_check.sh
```

脚本会构建 Debug / Release AAR 和 Demo Debug APK，检查 AAR / APK 产物与 arm64-v8a native so。若本机存在 adb 设备，脚本还会安装并启动 Demo；没有设备时会跳过安装启动，不影响基础 smoke 结果。

## 作为 Module 集成

如果业务项目和 ECHPlay 在同一个 Gradle 工程里，可以直接依赖 library module：

```gradle
dependencies {
    implementation project(':echplayer')
}
```

settings 示例：

```gradle
include ':app'
include ':echplayer'
```

Java import：

```java
import com.echplay.player.ECHPlayer;
import com.echplay.player.ECHPlayerView;
```

## 作为 AAR 集成

先生成 Release AAR：

```bash
cd android
./gradlew :echplayer:assembleRelease
```

AAR 路径：

```text
android/echplayer/build/outputs/aar/echplayer-release.aar
```

业务项目可以把 AAR 放到 `app/libs/echplayer-release.aar`，再添加依赖：

```gradle
dependencies {
    implementation files('libs/echplayer-release.aar')
}
```

注意：当前 AAR 已包含 `arm64-v8a` 的播放器 native so 和 FFmpeg so。若要运行 `armeabi-v7a` 设备，需要先补齐对应 ABI 的 FFmpeg so 并重新打 AAR。

## ECHPlayer 基础用法

```java
ECHPlayer player = new ECHPlayer();
player.setSurface(surface);
player.setRenderMode(ECHPlayer.RENDER_MODE_AUTO);
player.setDecodeMode(ECHPlayer.DECODE_MODE_AUTO);
player.setDataSource("/sdcard/Movies/test.mp4");
player.prepare();
player.start();
```

RTSP 示例：

```java
ECHPlayer player = new ECHPlayer();
player.setSurface(surface);
player.setRenderMode(ECHPlayer.RENDER_MODE_AUTO);
player.setDecodeMode(ECHPlayer.DECODE_MODE_AUTO);
player.setDataSource("rtsp://120.24.161.118:8554/live");
player.setRtspTransport(ECHPlayer.RTSP_TRANSPORT_TCP);
player.setReconnectEnabled(true);
player.setReconnectConfig(3, 2_000L);
player.setOption(ECHPlayer.OPTION_CATEGORY_FORMAT, ECHPlayer.OPTION_TIMEOUT, 5_000_000L);
player.setOption(ECHPlayer.OPTION_CATEGORY_FORMAT, ECHPlayer.OPTION_RW_TIMEOUT, 5_000_000L);
player.prepare();
player.start();
```

回调示例：

```java
player.setOnErrorListener((targetPlayer, errorCode, message) -> {
    // errorCode 可区分网络超时、鉴权失败、无视频流、录制失败等场景。
    return true;
});

player.setOnInfoListener((targetPlayer, infoCode, message) -> {
    // infoCode 可监听 prepare、播放开始、缓冲、seek、录制、自动重连等事件。
    return true;
});
```

视频尺寸回调：

```java
player.setOnVideoSizeChangedListener((targetPlayer, width, height) -> {
    // width 和 height 是当前视频帧尺寸，可用于布局和日志展示。
});

int videoWidth = player.getVideoWidth();
int videoHeight = player.getVideoHeight();
```

渲染模式示例：

```java
// 默认推荐：优先 OpenGL，失败时回退 NativeWindow。
player.setRenderMode(ECHPlayer.RENDER_MODE_AUTO);

// 强制优先走 OpenGL YUV 三纹理路径。
player.setRenderMode(ECHPlayer.RENDER_MODE_OPENGL);

// 强制走兼容路径：NativeWindow + RGBA。
player.setRenderMode(ECHPlayer.RENDER_MODE_NATIVE_WINDOW);

// 也可以通过 option 设置。
player.setOption(
        ECHPlayer.OPTION_CATEGORY_PLAYER,
        ECHPlayer.OPTION_RENDER_MODE,
        ECHPlayer.OPTION_VALUE_RENDER_OPENGL
);
```

解码模式示例：

```java
// 默认推荐：H.264 / H.265 优先尝试 MediaCodec，失败回退 FFmpeg 软解。
player.setDecodeMode(ECHPlayer.DECODE_MODE_AUTO);

// 强制软解：完全不创建 MediaCodec。
player.setDecodeMode(ECHPlayer.DECODE_MODE_SOFTWARE);

// 硬解优先：优先 MediaCodec，失败时回退软解并通过 info 回调说明原因。
player.setDecodeMode(ECHPlayer.DECODE_MODE_MEDIACODEC);

// 也可以使用 option，方便对齐 ijkplayer 风格配置。
player.setOption(
        ECHPlayer.OPTION_CATEGORY_PLAYER,
        ECHPlayer.OPTION_DECODE_MODE,
        ECHPlayer.OPTION_VALUE_DECODE_MEDIACODEC
);
player.setOption(ECHPlayer.OPTION_CATEGORY_PLAYER, ECHPlayer.OPTION_MEDIACODEC, 1L);
```

读取当前解码状态：

```java
String currentDecodeType = player.getCurrentDecodeType();       // software 或 mediacodec
String decoderName = player.getCurrentDecoderName();            // ffmpeg-h264、video/avc 等
String fallbackReason = player.getLastDecodeFallbackReason();   // 硬解失败回退原因
```

读取播放统计：

```java
ECHPlayer.PlaybackStats stats = player.getPlaybackStats();
long readBytes = stats.readBytes;                               // 累计读取字节数
long speed = stats.readSpeedBytesPerSecond;                     // 当前读取速度，单位字节/秒
int videoQueueSize = stats.videoPacketQueueSize;                // 视频 packet 队列长度
int audioQueueSize = stats.audioPacketQueueSize;                // 音频 packet 队列长度
int bufferedPercent = stats.bufferedPercent;                    // 缓冲百分比估算值
double decodeFps = stats.decodeFps;                             // 平均视频解码 FPS
double renderFps = stats.renderFps;                             // 平均视频渲染 FPS
long decodedFrames = stats.decodedFrameCount;                   // 累计解码视频帧数
long renderedFrames = stats.renderedFrameCount;                 // 累计渲染视频帧数
long droppedFrames = stats.droppedFrameCount;                   // 主动丢弃视频帧数
long prepareCostMs = stats.prepareCostMs;                       // 最近一次 prepare 耗时
long firstFrameCostMs = stats.firstFrameCostMs;                 // 最近一次 start 到首帧耗时
```

读取媒体信息和轨道信息：

```java
ECHPlayer.MediaInfo mediaInfo = player.getMediaInfo();
String format = mediaInfo.format;                               // 封装格式
String videoCodec = mediaInfo.videoCodec;                       // 视频编码名
int videoWidth = mediaInfo.videoWidth;                          // 视频宽度
int videoHeight = mediaInfo.videoHeight;                        // 视频高度

List<ECHPlayer.TrackInfo> tracks = player.getTrackInfo();
for (ECHPlayer.TrackInfo track : tracks) {
    // track.type 可能是 video、audio、subtitle 或 other。
}
```

解码状态 info：

1. `INFO_DECODE_MODE_CHANGED`：当前实际解码方式变化。
2. `INFO_MEDIACODEC_OPENED`：MediaCodec 创建并启动成功。
3. `INFO_MEDIACODEC_FALLBACK`：硬解失败，已回退软解。
4. `INFO_MEDIACODEC_UNSUPPORTED`：当前编码或设备不支持硬解。
5. `INFO_RECONNECTING`：RTSP 正在按配置自动重连。
6. `INFO_RECONNECTED`：RTSP 自动重连成功并恢复播放。
7. `INFO_RECONNECT_FAILED`：RTSP 自动重连达到上限后失败。

截图示例：

```java
ECHPlayer.CaptureResult result = player.captureCurrentFramePng(outputPath);
String filePath = result.filePath;
int width = result.width;
int height = result.height;
long timestampMs = result.timestampMs;
```

截图说明：`captureCurrentFramePng(...)` 保存的是播放器缓存的当前解码帧 RGBA 数据，不是 `SurfaceView` 屏幕截图。因此在 OpenGL 和 NativeWindow 渲染模式下，截图行为保持一致。

录制示例：

```java
player.startRecording(outputPath);
ECHPlayer.RecordingState state = player.getRecordingState();
player.stopRecording();
```

录制说明：录制保存的是当前播放中的 demux packet 码流封装结果，不是屏幕录制。因此软解和硬解模式下录制行为一致。

页面退出时释放：

```java
player.release();
```

## ECHPlayerView 基础用法

XML：

```xml
<com.echplay.player.ECHPlayerView
    android:id="@+id/playerView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

Java：

```java
ECHPlayerView playerView = findViewById(R.id.playerView);
playerView.setVideoPath("rtsp://120.24.161.118:8554/live");
playerView.setRtspTransport(ECHPlayer.RTSP_TRANSPORT_TCP);
playerView.setRenderMode(ECHPlayer.RENDER_MODE_AUTO);
playerView.setDecodeMode(ECHPlayer.DECODE_MODE_AUTO);
playerView.setScaleType(ECHPlayerView.SCALE_TYPE_FIT_CENTER);
playerView.start();
```

`ECHPlayerView` 内置基础状态覆盖层：

1. 播放准备阶段显示 `loading` 文案。
2. 缓冲开始时显示 `buffering` 文案，缓冲结束后自动隐藏。
3. 播放错误时显示错误码和错误信息。
4. 错误状态提供 `重试` 按钮，会复用最近一次 `videoPath`、渲染模式、解码模式和 RTSP 传输方式重新播放。
5. 底部内置进度条、当前时间和总时长。
6. 本地 MP4、HTTP MP4 等可 seek 媒体支持拖动进度条跳转。
7. RTSP / HLS Live 等不可 seek 媒体会禁用进度条，并在拖动时输出明确日志。
8. 播放中 3 秒无操作会自动隐藏控制条，点击视频区域可重新显示。
9. 暂停、停止、loading、buffering 和 error 状态下控制条保持可见，便于继续操作。

画面比例模式：

```java
playerView.setScaleType(ECHPlayerView.SCALE_TYPE_FIT_CENTER);
playerView.setScaleType(ECHPlayerView.SCALE_TYPE_CENTER_CROP);
playerView.setScaleType(ECHPlayerView.SCALE_TYPE_FILL);
playerView.setScaleType(ECHPlayerView.SCALE_TYPE_ORIGINAL);
```

比例模式说明：

1. `SCALE_TYPE_FIT_CENTER`：保持比例完整显示，默认模式。
2. `SCALE_TYPE_CENTER_CROP`：保持比例填满容器，允许裁剪边缘。
3. `SCALE_TYPE_FILL`：拉伸填满容器，允许变形。
4. `SCALE_TYPE_ORIGINAL`：尽量按视频原始尺寸显示，超出容器时等比缩小。

页面退出时：

```java
playerView.release();
```

## Demo 入口

主页面 `MainActivity` 保留两种入口：

1. 网络 URL：输入 `rtsp://...`、`http://...`、`https://...` 或 `.m3u8`，RTSP 可选择 `RTSP TCP` 或 `RTSP UDP`，点击“播放”。
2. 本地文件：切到“本地文件”，输入路径或通过“选择文件”选择视频。
3. PlayerView Demo：点击 `PlayerView` 按钮打开 `PlayerViewDemoActivity`。
4. 渲染模式：主页面和 PlayerView Demo 均可切换 `AUTO`、`OpenGL`、`NativeWindow`。
5. 解码模式：主页面和 PlayerView Demo 均可切换 `解码AUTO`、`软解`、`硬解`。
6. 画面比例：PlayerView Demo 可切换 `fit`、`crop`、`fill`、`original`。

主页面会展示播放器状态、协议类型、错误码、info 回调、缓冲事件、视频尺寸、媒体信息、轨道信息、渲染模式、目标解码模式、当前实际解码方式、解码器名称、硬解回退原因、读取速度、累计读取字节、音视频队列长度、缓冲百分比、decode/render fps、D/R/Drop 帧数、prepare 耗时、首帧耗时、重连次数、截图路径、录制状态和录制文件路径。

## ABI 状态

当前 ABI：

1. `arm64-v8a`：已提供 FFmpeg so，可直接构建 AAR 和 Demo APK。
2. `armeabi-v7a`：已预留目录和 Gradle 候选配置，当前缺少 FFmpeg so，Gradle 会自动跳过。

启用 `armeabi-v7a` 需要补齐：

1. `android/echplayer/src/main/jniLibs/armeabi-v7a/libavcodec.so`
2. `android/echplayer/src/main/jniLibs/armeabi-v7a/libavformat.so`
3. `android/echplayer/src/main/jniLibs/armeabi-v7a/libavutil.so`
4. `android/echplayer/src/main/jniLibs/armeabi-v7a/libswresample.so`
5. `android/echplayer/src/main/jniLibs/armeabi-v7a/libswscale.so`

补齐后重新执行：

```bash
cd android
./gradlew :echplayer:assembleRelease
```

## FFmpeg 重新编译

arm64-v8a FFmpeg 构建脚本：

```bash
cd android
ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/你的NDK版本 ./tools/build_ffmpeg_android_arm64.sh
```

脚本输出位置：

```text
android/echplayer/src/main/cpp/ffmpeg/include
android/echplayer/src/main/jniLibs/arm64-v8a
```

## v1.4 验证清单

构建验证：

1. `./gradlew :echplayer:assembleDebug`
2. `./gradlew :echplayer:assembleRelease`
3. `./gradlew :app:assembleDebug -x test`

产物验证：

1. `android/echplayer/build/outputs/aar/echplayer-release.aar` 存在。
2. Release AAR 内包含 `jni/arm64-v8a/libechplayer.so`。
3. Release AAR 内包含 `jni/arm64-v8a/libavcodec.so`、`libavformat.so`、`libavutil.so`、`libswresample.so`、`libswscale.so`。
4. Debug APK 内包含 `lib/arm64-v8a/libechplayer.so` 和 FFmpeg so。
5. `android/app/src/main` 不再包含 `cpp` 或 `jniLibs`。

功能回归：

1. 本地 H.264 MP4 在 `DECODE_MODE_AUTO` 播放，确认 Demo 显示 `mediacodec` 或明确回退 `software`。
2. 本地 H.264 MP4 在 `DECODE_MODE_SOFTWARE` 播放，确认不会创建 MediaCodec，Demo 显示 `software`。
3. 本地 H.264 MP4 在 `DECODE_MODE_MEDIACODEC` 播放，确认硬解成功或显示清晰回退原因。
4. 本地 H.265 MP4 在支持 HEVC 的设备上播放，确认 Demo 显示硬解或设备限制。
5. 本地 H.265 MP4 在不支持 HEVC 的设备上播放，确认自动回退软解，不崩溃。
6. 本地 MP4 在 `OpenGL` 模式播放，确认 YUV420P 三纹理路径可显示或能明确回退。
7. 本地 MP4 在 `NativeWindow` 模式播放，确认兼容路径正常。
8. 本地 MP4 控制：测试暂停、继续、停止、重新播放。
9. seek：对本地 MP4 连续拖动，确认不死锁，seek 后音视频能恢复。
10. RTSP TCP：输入 RTSP URL，选择 TCP，确认能播放或返回明确错误。
11. RTSP UDP：输入同一 RTSP URL，选择 UDP，确认兼容性和错误提示。
12. RTSP H.264：在 `DECODE_MODE_AUTO` 下播放，确认可硬解或回退软解。
13. 直播流 seek：RTSP 直播流拖动时提示不支持 seek。
14. 视频尺寸：prepare 后 Demo 日志能显示视频宽高变化。
15. 解码状态：Demo 固定状态栏能显示目标解码、当前解码、解码器和回退原因。
16. PlayerView 比例：切换 `fit`、`crop`、`fill`、`original`，横屏和竖屏视频布局正常。
17. 截图：软解播放中点击截图，确认保存的是当前解码帧 PNG。
18. 截图：硬解播放中点击截图，确认保存的是当前解码帧 PNG，不是 SurfaceView 画面。
19. 录制：软解播放中开始录制后停止录制，确认文件能生成。
20. 录制：硬解播放中开始录制后停止录制，确认文件能生成，录制来源是 demux packet 码流。
21. 录制中停止播放：开始录制后点停止或退出页面，确认录制能安全停止。
22. PlayerView Demo：只通过组件按钮完成播放、暂停、停止、截图、录制。
23. 资源释放：连续进入和退出 PlayerView Demo，多次播放、停止、重新播放，不崩溃。

## 后续方向

1. v1.7：增强 `ECHPlayerView` 进度条、时间显示、seek、控制栏显隐和更完整的组件状态体验。
2. v1.8：补齐 smoke check 脚本、兼容性矩阵、发布检查清单和回归文档。
3. 后续版本：字幕、多音轨、更多像素格式渲染兼容和更完整的媒体信息面板。
4. 后续版本：SurfaceTexture / Surface 零拷贝硬解渲染路径。
5. 后续版本：更完整的机型黑名单 / 白名单和性能统计面板。
