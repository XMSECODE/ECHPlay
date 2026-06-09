# ECHPlay

ECHPlay 是一个 Android FFmpeg 软解播放器 Demo。v1.1 的重点是把 v1.0 已经跑通的本地播放、RTSP、截图、录制能力，整理成更接近播放器 SDK 的使用方式。

## v1.1 能力

1. 标准播放器 API：`setDataSource`、`prepare`、`prepareAsync`、`start`、`pause`、`stop`、`reset`、`release`。
2. 播放器状态机：`IDLE`、`INITIALIZED`、`PREPARING`、`PREPARED`、`STARTED`、`PAUSED`、`STOPPED`、`SEEKING`、`COMPLETED`、`ERROR`、`RELEASED`。
3. 回调体系：`OnPreparedListener`、`OnCompletionListener`、`OnErrorListener`、`OnInfoListener`、`OnBufferingUpdateListener`。
4. RTSP 支持：TCP / UDP 切换，`timeout`、`rw_timeout`、`buffer_size`、`max_delay` option 配置。
5. seek 稳定性：不可 seek 的直播流会被拦截，连续 seek 有状态保护。
6. 截图产品化：保存当前解码后的 RGBA 数据为 PNG，不保存 `SurfaceView` 屏幕画面。
7. 录制产品化：支持录制状态 `IDLE`、`RECORDING`、`STOPPING`、`FAILED`。
8. PlayerView 雏形：`ECHPlayerView` 内部管理 `SurfaceView`、播放器生命周期和基础控制条。

## 快速运行

```bash
cd android
./gradlew :app:assembleDebug -x test
```

构建产物：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

当前工程只配置了 `arm64-v8a`，请优先使用 arm64 真机或模拟器。

## Demo 入口

主页面 `MainActivity` 保留两种入口：

1. RTSP 流：输入 `rtsp://...`，选择 `RTSP TCP` 或 `RTSP UDP`，点击“播放”。
2. 本地文件：切到“本地文件”，输入路径或通过“选择文件”选择视频。

主页面会展示：

1. 播放器状态。
2. 错误码和中文错误信息。
3. info 回调。
4. 缓冲事件。
5. 截图文件路径、尺寸、时间戳。
6. 录制状态和录制文件路径。

点击 `PlayerView` 按钮可以打开 `PlayerViewDemoActivity`，这个页面只通过 `ECHPlayerView` 完成播放、暂停、停止、截图和录制。

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

## ECHPlayerView 基础用法

XML：

```xml
<com.example.abcplaydemo.player.ECHPlayerView
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

## v1.1 测试清单

1. 本地 MP4 播放：选择本地视频，点击播放，确认画面和声音正常。
2. 本地 MP4 控制：测试暂停、继续、停止、重新播放。
3. seek：对本地 MP4 连续拖动 10 次，确认不死锁，seek 后音视频能恢复。
4. RTSP TCP：输入 RTSP URL，选择 TCP，确认能播放或 5 秒左右返回明确错误。
5. RTSP UDP：输入同一 RTSP URL，选择 UDP，确认兼容性和错误提示。
6. RTSP 错误：测试错误地址、错误账号密码，确认 UI 展示错误码和中文提示。
7. 直播流 seek：RTSP 直播流进度条不可拖动，或拖动时提示不支持 seek。
8. 截图：播放中连续点击截图 5 次，确认日志显示 PNG 路径、宽、高、时间戳。
9. 录制：开始录制后停止录制，确认日志显示录制路径，文件能正常生成。
10. 录制中停止播放：开始录制后点停止或退出页面，确认录制能安全停止。
11. Surface 生命周期：退出页面或切换到 `PlayerViewDemoActivity` 后返回，确认不崩溃。
12. PlayerView Demo：打开 `PlayerView` 页面，只通过组件按钮完成播放、暂停、停止、截图、录制。

## 后续方向

1. 独立 Android library module。
2. 更精确的缓冲百分比和网速统计。
3. 自动重连。
4. OpenGL YUV 渲染。
5. MediaCodec 硬解。
6. HTTP / HTTPS / HLS 兼容测试。
