# ECHPlay

ECHPlay 是一个基于 FFmpeg 的 Android 软解播放器项目。v1.3 的重点是渲染链路升级：播放器能力已经从 Demo app 拆成独立 `echplayer` Android library module，并新增 OpenGL ES、YUV420P 三纹理、渲染模式切换、视频尺寸回调和 `ECHPlayerView` 画面比例控制。

## v1.3 能力

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
player.setDataSource("/sdcard/Movies/test.mp4");
player.prepare();
player.start();
```

RTSP 示例：

```java
ECHPlayer player = new ECHPlayer();
player.setSurface(surface);
player.setRenderMode(ECHPlayer.RENDER_MODE_AUTO);
player.setDataSource("rtsp://192.168.1.1:554/live");
player.setRtspTransport(ECHPlayer.RTSP_TRANSPORT_TCP);
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
    // infoCode 可监听 prepare、播放开始、缓冲、seek、录制等事件。
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
playerView.setVideoPath("rtsp://192.168.1.1:554/live");
playerView.setRtspTransport(ECHPlayer.RTSP_TRANSPORT_TCP);
playerView.setRenderMode(ECHPlayer.RENDER_MODE_AUTO);
playerView.setScaleType(ECHPlayerView.SCALE_TYPE_FIT_CENTER);
playerView.start();
```

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

1. RTSP 流：输入 `rtsp://...`，选择 `RTSP TCP` 或 `RTSP UDP`，点击“播放”。
2. 本地文件：切到“本地文件”，输入路径或通过“选择文件”选择视频。
3. PlayerView Demo：点击 `PlayerView` 按钮打开 `PlayerViewDemoActivity`。
4. 渲染模式：主页面和 PlayerView Demo 均可切换 `AUTO`、`OpenGL`、`NativeWindow`。
5. 画面比例：PlayerView Demo 可切换 `fit`、`crop`、`fill`、`original`。

主页面会展示播放器状态、错误码、info 回调、缓冲事件、视频尺寸、渲染模式、截图路径、录制状态和录制文件路径。

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

## v1.3 验证清单

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

1. 本地 MP4 在 `AUTO` 模式播放，确认画面和声音正常。
2. 本地 MP4 在 `OpenGL` 模式播放，确认 YUV420P 三纹理路径可显示或能明确回退。
3. 本地 MP4 在 `NativeWindow` 模式播放，确认兼容路径正常。
4. 本地 MP4 控制：测试暂停、继续、停止、重新播放。
5. seek：对本地 MP4 连续拖动，确认不死锁，seek 后音视频能恢复。
6. RTSP TCP：输入 RTSP URL，选择 TCP，确认能播放或返回明确错误。
7. RTSP UDP：输入同一 RTSP URL，选择 UDP，确认兼容性和错误提示。
8. 直播流 seek：RTSP 直播流拖动时提示不支持 seek。
9. 视频尺寸：prepare 后 Demo 日志能显示视频宽高变化。
10. PlayerView 比例：切换 `fit`、`crop`、`fill`、`original`，横屏和竖屏视频布局正常。
11. 截图：OpenGL 模式播放中点击截图，确认保存的是当前解码帧 PNG。
12. 截图：NativeWindow 模式播放中点击截图，确认保存的是当前解码帧 PNG。
13. 录制：OpenGL 模式播放中开始录制后停止录制，确认文件能生成。
14. 录制：NativeWindow 模式播放中开始录制后停止录制，确认文件能生成。
15. 录制中停止播放：开始录制后点停止或退出页面，确认录制能安全停止。
16. PlayerView Demo：只通过组件按钮完成播放、暂停、停止、截图、录制。
17. 资源释放：连续进入和退出 PlayerView Demo，多次播放、停止、重新播放，不崩溃。

## 后续方向

1. v1.4：MediaCodec H.264 / H.265 硬解。
2. v1.4：软硬解切换和失败回退。
3. v1.4：当前解码方式展示。
4. 后续版本：自动重连和更完整的网络流协议兼容。
5. 后续版本：字幕、多音轨和更多像素格式渲染兼容。
