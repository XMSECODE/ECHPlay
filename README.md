# ECHPlay

ECHPlay 是一个基于 FFmpeg 的 Android 软解播放器项目。v1.2 的重点是工程化：播放器能力已经从 Demo app 拆成独立 `echplayer` Android library module，业务项目可以通过 module 或 AAR 复用 `ECHPlayer` / `ECHPlayerView`。

## v1.2 能力

1. 独立播放器库：`android/echplayer` 使用 `com.android.library`，namespace 为 `com.echplay.player`。
2. 纯 Demo app：`android/app` 只保留页面、布局、图标、测试资源和示例交互。
3. Java API：`ECHPlayer`、`ECHPlayerView` 已迁移到 `com.echplay.player`。
4. native 核心：JNI、`NativePlayer`、CMake、FFmpeg include 已迁移到 `echplayer`。
5. AAR 输出：支持生成 `echplayer-debug.aar` 和 `echplayer-release.aar`。
6. native so 自包含：Release AAR 内包含 `libechplayer.so` 和 FFmpeg so。
7. ABI 策略：当前 arm64-v8a 可打包；armeabi-v7a 已预留目录，补齐 FFmpeg so 后自动启用。

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
player.setDataSource("/sdcard/Movies/test.mp4");
player.prepare();
player.start();
```

RTSP 示例：

```java
ECHPlayer player = new ECHPlayer();
player.setSurface(surface);
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

截图示例：

```java
ECHPlayer.CaptureResult result = player.captureCurrentFramePng(outputPath);
String filePath = result.filePath;
int width = result.width;
int height = result.height;
long timestampMs = result.timestampMs;
```

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
playerView.start();
```

页面退出时：

```java
playerView.release();
```

## Demo 入口

主页面 `MainActivity` 保留两种入口：

1. RTSP 流：输入 `rtsp://...`，选择 `RTSP TCP` 或 `RTSP UDP`，点击“播放”。
2. 本地文件：切到“本地文件”，输入路径或通过“选择文件”选择视频。
3. PlayerView Demo：点击 `PlayerView` 按钮打开 `PlayerViewDemoActivity`。

主页面会展示播放器状态、错误码、info 回调、缓冲事件、截图路径、录制状态和录制文件路径。

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

## v1.2 验证清单

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

1. 本地 MP4 播放：选择本地视频，点击播放，确认画面和声音正常。
2. 本地 MP4 控制：测试暂停、继续、停止、重新播放。
3. seek：对本地 MP4 连续拖动，确认不死锁，seek 后音视频能恢复。
4. RTSP TCP：输入 RTSP URL，选择 TCP，确认能播放或返回明确错误。
5. RTSP UDP：输入同一 RTSP URL，选择 UDP，确认兼容性和错误提示。
6. 直播流 seek：RTSP 直播流拖动时提示不支持 seek。
7. 截图：播放中点击截图，确认保存的是当前解码帧 PNG。
8. 录制：开始录制后停止录制，确认文件能正常生成。
9. 录制中停止播放：开始录制后点停止或退出页面，确认录制能安全停止。
10. PlayerView Demo：只通过组件按钮完成播放、暂停、停止、截图、录制。

## 后续方向

1. v1.3：OpenGL ES 渲染和 YUV 三纹理。
2. v1.3：PlayerView 画面比例模式。
3. v1.3：视频尺寸变化回调。
4. 后续版本：MediaCodec 硬解。
5. 后续版本：自动重连和更完整的网络流协议兼容。
