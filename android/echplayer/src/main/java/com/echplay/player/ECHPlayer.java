package com.echplay.player;

import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Java 层播放器封装，负责桥接 UI 与 NativePlayer。
 */
public class ECHPlayer implements AutoCloseable {

    /**
     * 播放器状态机，命名对齐 Android MediaPlayer 的常见生命周期。
     */
    public enum State {
        /** 初始状态，可设置数据源。 */
        IDLE,
        /** 已设置数据源。 */
        INITIALIZED,
        /** 正在 prepare。 */
        PREPARING,
        /** 已 prepare 成功。 */
        PREPARED,
        /** 播放中。 */
        STARTED,
        /** 暂停中。 */
        PAUSED,
        /** 已停止。 */
        STOPPED,
        /** 正在 seek。 */
        SEEKING,
        /** 播放完成。 */
        COMPLETED,
        /** 出错。 */
        ERROR,
        /** 已释放。 */
        RELEASED
    }

    /**
     * 录制状态机，用于让外部页面明确知道当前录制阶段。
     */
    public enum RecordingState {
        /** 当前没有录制任务。 */
        IDLE,
        /** 正在录制。 */
        RECORDING,
        /** 正在停止录制。 */
        STOPPING,
        /** 录制失败。 */
        FAILED
    }

    /**
     * 截图结果，保存 PNG 路径、尺寸和截图时间戳。
     */
    public static class CaptureResult {
        /** PNG 文件绝对路径。 */
        public final String filePath;
        /** 截图宽度。 */
        public final int width;
        /** 截图高度。 */
        public final int height;
        /** 截图创建时间戳，单位毫秒。 */
        public final long timestampMs;

        /** 创建截图结果对象。 */
        public CaptureResult(String filePath, int width, int height, long timestampMs) {
            this.filePath = filePath;
            this.width = width;
            this.height = height;
            this.timestampMs = timestampMs;
        }
    }

    /** 打开输入失败。 */
    public static final int ERROR_OPEN_INPUT_FAILED = 1001;
    /** 读取流信息失败。 */
    public static final int ERROR_STREAM_INFO_FAILED = 1002;
    /** 没有视频流。 */
    public static final int ERROR_NO_VIDEO_STREAM = 1003;
    /** 解码器打开失败。 */
    public static final int ERROR_DECODER_OPEN_FAILED = 1004;
    /** 网络超时。 */
    public static final int ERROR_NETWORK_TIMEOUT = 1005;
    /** RTSP 鉴权失败。 */
    public static final int ERROR_RTSP_AUTH_FAILED = 1006;
    /** 渲染 Surface 无效。 */
    public static final int ERROR_RENDER_SURFACE_INVALID = 1007;
    /** 录制失败。 */
    public static final int ERROR_RECORD_FAILED = 1008;
    /** 非法状态。 */
    public static final int ERROR_INVALID_STATE = 1009;
    /** 当前媒体流不支持 seek。 */
    public static final int ERROR_STREAM_NOT_SEEKABLE = 1010;
    /** 未知错误。 */
    public static final int ERROR_UNKNOWN = 1999;

    /** prepare 开始。 */
    public static final int INFO_PREPARE_STARTED = 2001;
    /** prepare 成功。 */
    public static final int INFO_PREPARED = 2002;
    /** 播放开始。 */
    public static final int INFO_PLAY_STARTED = 2003;
    /** seek 完成。 */
    public static final int INFO_SEEK_COMPLETE = 2004;
    /** 录制开始。 */
    public static final int INFO_RECORDING_START = 2005;
    /** 录制结束。 */
    public static final int INFO_RECORDING_END = 2006;
    /** 暂停。 */
    public static final int INFO_PAUSED = 2007;
    /** 停止。 */
    public static final int INFO_STOPPED = 2008;
    /** 首帧视频开始渲染。 */
    public static final int INFO_VIDEO_RENDERING_START = 2009;
    /** 音频开始输出。 */
    public static final int INFO_AUDIO_RENDERING_START = 2010;
    /** 缓冲开始。 */
    public static final int INFO_BUFFERING_START = 2011;
    /** 缓冲结束。 */
    public static final int INFO_BUFFERING_END = 2012;

    /** prepare 完成监听器。 */
    public interface OnPreparedListener {
        /** prepare 成功时回调。 */
        void onPrepared(ECHPlayer player);
    }

    /** 播放完成监听器。 */
    public interface OnCompletionListener {
        /** 播放完成时回调。 */
        void onCompletion(ECHPlayer player);
    }

    /** 错误监听器。 */
    public interface OnErrorListener {
        /** 出错时回调，返回 true 表示错误已处理。 */
        boolean onError(ECHPlayer player, int errorCode, String message);
    }

    /** 信息监听器。 */
    public interface OnInfoListener {
        /** 播放器信息回调，返回 true 表示信息已处理。 */
        boolean onInfo(ECHPlayer player, int infoCode, String message);
    }

    /** 缓冲进度监听器。 */
    public interface OnBufferingUpdateListener {
        /** 缓冲进度更新时回调。 */
        void onBufferingUpdate(ECHPlayer player, int percent);
    }

    /** 视频尺寸变化监听器。 */
    public interface OnVideoSizeChangedListener {
        /** 视频宽高首次可用或发生变化时回调。 */
        void onVideoSizeChanged(ECHPlayer player, int width, int height);
    }

    /** RTSP 走 TCP 传输。 */
    public static final int RTSP_TRANSPORT_TCP = 0;
    /** RTSP 走 UDP 传输。 */
    public static final int RTSP_TRANSPORT_UDP = 1;
    /** Surface 渲染保持比例居中。 */
    public static final int SURFACE_SCALE_TYPE_FIT_CENTER = 0;
    /** Surface 渲染拉伸填满。 */
    public static final int SURFACE_SCALE_TYPE_FILL = 1;
    /** 渲染模式：自动选择，优先 OpenGL，失败回退 NativeWindow。 */
    public static final int RENDER_MODE_AUTO = 0;
    /** 渲染模式：强制使用 OpenGL。 */
    public static final int RENDER_MODE_OPENGL = 1;
    /** 渲染模式：强制使用 NativeWindow。 */
    public static final int RENDER_MODE_NATIVE_WINDOW = 2;
    /** FFmpeg format 层 option 分类。 */
    public static final int OPTION_CATEGORY_FORMAT = 1;
    /** 播放器自身 option 分类。 */
    public static final int OPTION_CATEGORY_PLAYER = 2;
    /** RTSP 传输方式 option 名称。 */
    public static final String OPTION_RTSP_TRANSPORT = "rtsp_transport";
    /** 渲染模式 option 名称。 */
    public static final String OPTION_RENDER_MODE = "render_mode";
    /** RTSP TCP option 值。 */
    public static final String OPTION_VALUE_TCP = "tcp";
    /** RTSP UDP option 值。 */
    public static final String OPTION_VALUE_UDP = "udp";
    /** 渲染模式 AUTO option 值。 */
    public static final String OPTION_VALUE_RENDER_AUTO = "auto";
    /** 渲染模式 OPENGL option 值。 */
    public static final String OPTION_VALUE_RENDER_OPENGL = "opengl";
    /** 渲染模式 NativeWindow option 值。 */
    public static final String OPTION_VALUE_RENDER_NATIVE_WINDOW = "native_window";
    /** 打开输入超时时间 option 名称，单位微秒。 */
    public static final String OPTION_TIMEOUT = "timeout";
    /** 网络读取超时时间 option 名称，单位微秒。 */
    public static final String OPTION_RW_TIMEOUT = "rw_timeout";
    /** 网络输入缓冲大小 option 名称，单位字节。 */
    public static final String OPTION_BUFFER_SIZE = "buffer_size";
    /** RTSP 最大延迟 option 名称，单位微秒。 */
    public static final String OPTION_MAX_DELAY = "max_delay";

    static {
        System.loadLibrary("echplayer");
    }

    /** NativePlayer 指针句柄。 */
    private long nativeHandle = 0;
    /** 当前对象是否已经释放。 */
    private boolean released = false;
    /** 当前播放器状态。 */
    private State state = State.IDLE;
    /** 当前是否已经 prepare 成功。 */
    private boolean prepared = false;
    /** 当前是否处于播放中。 */
    private boolean playing = false;
    /** 当前是否处于暂停状态。 */
    private boolean paused = false;
    /** 最近一次 prepare 返回信息。 */
    private String lastPrepareResult = "";
    /** 最近一次 start 返回信息。 */
    private String lastStartResult = "";
    /** 当前 RTSP 传输方式。 */
    private int rtspTransport = RTSP_TRANSPORT_TCP;
    /** 当前渲染模式。 */
    private int renderMode = RENDER_MODE_AUTO;
    /** prepare 完成监听器。 */
    private OnPreparedListener onPreparedListener;
    /** 播放完成监听器。 */
    private OnCompletionListener onCompletionListener;
    /** 错误监听器。 */
    private OnErrorListener onErrorListener;
    /** 信息监听器。 */
    private OnInfoListener onInfoListener;
    /** 缓冲进度监听器。 */
    private OnBufferingUpdateListener onBufferingUpdateListener;
    /** 视频尺寸变化监听器。 */
    private OnVideoSizeChangedListener onVideoSizeChangedListener;
    /** 当前视频宽度。 */
    private int videoWidth = 0;
    /** 当前视频高度。 */
    private int videoHeight = 0;
    /** 首帧视频渲染事件是否已经发出。 */
    private boolean videoRenderingStarted = false;
    /** 音频输出事件是否已经发出。 */
    private boolean audioRenderingStarted = false;
    /** 播放完成事件是否已经发出。 */
    private boolean completionDispatched = false;
    /** 当前是否正在执行 seek。 */
    private boolean seeking = false;
    /** 当前录制状态。 */
    private RecordingState recordingState = RecordingState.IDLE;
    /** 最近一次录制文件路径。 */
    private String lastRecordingPath = "";

    /** Java 音频输出实例。 */
    private AudioTrack audioTrack;

    /** 创建播放器并初始化 native 实例。 */
    public ECHPlayer() {
        nativeHandle = nativeInit();
    }

    /** 设置 prepare 成功监听器。 */
    public synchronized void setOnPreparedListener(OnPreparedListener listener) {
        onPreparedListener = listener;
    }

    /** 设置播放完成监听器。 */
    public synchronized void setOnCompletionListener(OnCompletionListener listener) {
        onCompletionListener = listener;
    }

    /** 设置错误监听器。 */
    public synchronized void setOnErrorListener(OnErrorListener listener) {
        onErrorListener = listener;
    }

    /** 设置信息监听器。 */
    public synchronized void setOnInfoListener(OnInfoListener listener) {
        onInfoListener = listener;
    }

    /** 设置缓冲进度监听器。 */
    public synchronized void setOnBufferingUpdateListener(OnBufferingUpdateListener listener) {
        onBufferingUpdateListener = listener;
    }

    /** 设置视频尺寸变化监听器。 */
    public synchronized void setOnVideoSizeChangedListener(OnVideoSizeChangedListener listener) {
        onVideoSizeChangedListener = listener;
    }

    /** 设置播放数据源。 */
    public synchronized void setDataSource(String dataSource) {
        checkReleased();
        requireState(State.IDLE, State.STOPPED, State.ERROR);

        if (dataSource == null || dataSource.length() == 0) {
            throw new IllegalArgumentException("dataSource is empty");
        }

        nativeSetDataSource(nativeHandle, dataSource);
        state = State.INITIALIZED;
        prepared = false;
        playing = false;
        paused = false;
        resetVideoSize();
        resetPlaybackEventFlags();
    }

    /** 设置视频输出 Surface。 */
    public synchronized void setSurface(Surface surface) {
        checkReleased();
        nativeSetSurface(nativeHandle, surface);
    }

    /** 设置 NativeWindow 兼容渲染路径的 Surface 缩放方式。 */
    public synchronized void setSurfaceScaleType(int scaleType) {
        checkReleased();
        int nativeScaleType = scaleType == SURFACE_SCALE_TYPE_FILL
                ? SURFACE_SCALE_TYPE_FILL
                : SURFACE_SCALE_TYPE_FIT_CENTER;
        nativeSetSurfaceScaleType(nativeHandle, nativeScaleType);
    }

    /** 设置渲染模式。 */
    public synchronized void setRenderMode(int renderMode) {
        checkReleased();
        this.renderMode = normalizeRenderMode(renderMode);
        nativeSetRenderMode(nativeHandle, this.renderMode);
    }

    /** 返回当前渲染模式。 */
    public synchronized int getRenderMode() {
        return renderMode;
    }

    /** 设置 RTSP 传输方式。 */
    public synchronized void setRtspTransport(int transport) {
        checkReleased();
        rtspTransport = transport == RTSP_TRANSPORT_UDP
                ? RTSP_TRANSPORT_UDP
                : RTSP_TRANSPORT_TCP;
        nativeSetRtspTransport(nativeHandle, rtspTransport);
    }

    /** 设置 long 类型播放器选项。 */
    public synchronized boolean setOption(int category, String name, long value) {
        checkReleased();

        if (OPTION_RTSP_TRANSPORT.equals(name)) {
            setRtspTransport(value == RTSP_TRANSPORT_UDP ? RTSP_TRANSPORT_UDP : RTSP_TRANSPORT_TCP);
            return true;
        }
        if (OPTION_RENDER_MODE.equals(name)) {
            setRenderMode((int) value);
            return true;
        }

        return nativeSetLongOption(nativeHandle, category, name, value);
    }

    /** 设置 String 类型播放器选项。 */
    public synchronized boolean setOption(int category, String name, String value) {
        checkReleased();

        if (OPTION_RTSP_TRANSPORT.equals(name)) {
            if (OPTION_VALUE_UDP.equalsIgnoreCase(value)) {
                setRtspTransport(RTSP_TRANSPORT_UDP);
            } else {
                setRtspTransport(RTSP_TRANSPORT_TCP);
            }
            return true;
        }
        if (OPTION_RENDER_MODE.equals(name)) {
            if (OPTION_VALUE_RENDER_OPENGL.equalsIgnoreCase(value)) {
                setRenderMode(RENDER_MODE_OPENGL);
            } else if (OPTION_VALUE_RENDER_NATIVE_WINDOW.equalsIgnoreCase(value)
                    || "nativewindow".equalsIgnoreCase(value)) {
                setRenderMode(RENDER_MODE_NATIVE_WINDOW);
            } else {
                setRenderMode(RENDER_MODE_AUTO);
            }
            return true;
        }

        try {
            return setOption(category, name, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            // 字符串 option 目前仅 RTSP 传输方式需要，其他网络参数使用 long 更清晰。
        }

        return false;
    }

    /** 打开数据源并读取流信息。 */
    public synchronized String prepare() {
        checkReleased();
        requireState(State.INITIALIZED, State.STOPPED);
        state = State.PREPARING;
        dispatchInfo(INFO_PREPARE_STARTED, "prepare started");
        lastPrepareResult = nativePrepare(nativeHandle);
        prepared = lastPrepareResult != null && lastPrepareResult.startsWith("prepare success");
        playing = false;
        paused = false;
        state = prepared ? State.PREPARED : State.ERROR;
        if (prepared) {
            updateVideoSizeFromNative();
            dispatchInfo(INFO_PREPARED, lastPrepareResult);
            dispatchBufferingUpdate(100);
            if (onPreparedListener != null) {
                onPreparedListener.onPrepared(this);
            }
        } else {
            dispatchError(mapPrepareErrorCode(lastPrepareResult), lastPrepareResult);
        }
        return lastPrepareResult;
    }

    /** 异步打开数据源并读取流信息。 */
    public synchronized void prepareAsync() {
        checkReleased();
        requireState(State.INITIALIZED, State.STOPPED);
        state = State.PREPARING;
        dispatchInfo(INFO_PREPARE_STARTED, "prepare started");

        Thread prepareThread = new Thread(() -> {
            synchronized (ECHPlayer.this) {
                if (!released && nativeHandle != 0) {
                    lastPrepareResult = nativePrepare(nativeHandle);
                    prepared = lastPrepareResult != null
                            && lastPrepareResult.startsWith("prepare success");
                    playing = false;
                    paused = false;
                    state = prepared ? State.PREPARED : State.ERROR;
                    if (prepared) {
                        updateVideoSizeFromNative();
                        dispatchInfo(INFO_PREPARED, lastPrepareResult);
                        dispatchBufferingUpdate(100);
                        if (onPreparedListener != null) {
                            onPreparedListener.onPrepared(this);
                        }
                    } else {
                        dispatchError(mapPrepareErrorCode(lastPrepareResult), lastPrepareResult);
                    }
                }
            }
        }, "ECHPlayer-Prepare");
        prepareThread.start();
    }

    /** 启动播放。 */
    public synchronized String play() {
        return start();
    }

    /** 按标准播放器命名启动或恢复播放。 */
    public synchronized String start() {
        checkReleased();
        requireState(State.PREPARED, State.STARTED, State.PAUSED);

        if (paused) {
            resume();
            lastStartResult = "play resumed";
            return lastStartResult;
        }

        lastStartResult = nativePlay(nativeHandle);
        if (lastStartResult != null
                && (lastStartResult.startsWith("play started")
                || lastStartResult.startsWith("play ignored"))) {
            playing = true;
            paused = false;
            state = State.STARTED;
            completionDispatched = false;
            dispatchInfo(INFO_PLAY_STARTED, lastStartResult);
        } else {
            state = State.ERROR;
            dispatchError(mapPlaybackErrorCode(lastStartResult), lastStartResult);
        }
        return lastStartResult;
    }

    /** 暂停播放。 */
    public synchronized void pause() {
        if (!released && nativeHandle != 0) {
            requireState(State.STARTED, State.PAUSED);
            nativePause(nativeHandle);

            if (audioTrack != null) {
                audioTrack.pause();
            }

            playing = false;
            paused = true;
            state = State.PAUSED;
            dispatchInfo(INFO_PAUSED, "pause");
        }
    }

    /** 恢复播放。 */
    public synchronized void resume() {
        if (!released && nativeHandle != 0) {
            requireState(State.PAUSED, State.STARTED);
            if (audioTrack != null) {
                audioTrack.play();
            }

            nativeResume(nativeHandle);
            playing = true;
            paused = false;
            state = State.STARTED;
            completionDispatched = false;
            dispatchInfo(INFO_PLAY_STARTED, "resume");
        }
    }

    /** 停止播放。 */
    public synchronized void stop() {
        if (!released && nativeHandle != 0) {
            if (state == State.IDLE || state == State.RELEASED) {
                return;
            }
            stopRecordingIfNeeded();
            nativeStop(nativeHandle);
            releaseAudioTrack();
            prepared = false;
            playing = false;
            paused = false;
            state = State.STOPPED;
            dispatchInfo(INFO_STOPPED, "stop");
        }
    }

    /** 重置播放器，回到可重新设置数据源的初始状态。 */
    public synchronized void reset() {
        checkReleased();

        stopRecordingIfNeeded();
        nativeStop(nativeHandle);
        releaseAudioTrack();
        nativeRelease(nativeHandle);
        nativeHandle = nativeInit();
        prepared = false;
        playing = false;
        paused = false;
        lastPrepareResult = "";
        lastStartResult = "";
        rtspTransport = RTSP_TRANSPORT_TCP;
        renderMode = RENDER_MODE_AUTO;
        recordingState = RecordingState.IDLE;
        lastRecordingPath = "";
        resetVideoSize();
        resetPlaybackEventFlags();
        state = State.IDLE;
    }

    /** 跳转到指定毫秒位置。 */
    public synchronized String seekToMs(long positionMs) {
        return seekTo(positionMs);
    }

    /** 按标准播放器命名跳转到指定毫秒位置。 */
    public synchronized String seekTo(long positionMs) {
        checkReleased();
        requireState(State.PREPARED, State.STARTED, State.PAUSED, State.COMPLETED);

        if (seeking) {
            dispatchError(ERROR_INVALID_STATE, "seek ignored: already seeking");
            return "seek ignored: already seeking";
        }

        State stateBeforeSeek = state;
        boolean wasPlaying = playing;
        boolean wasPaused = paused;
        seeking = true;
        state = State.SEEKING;
        String result = nativeSeekToMs(nativeHandle, positionMs);
        seeking = false;

        if (result != null && result.startsWith("seek success")) {
            playing = wasPlaying;
            paused = wasPaused;
            state = wasPlaying ? State.STARTED : (wasPaused ? State.PAUSED : State.PREPARED);
            completionDispatched = false;
            dispatchInfo(INFO_SEEK_COMPLETE, result);
        } else {
            playing = wasPlaying;
            paused = wasPaused;
            state = stateBeforeSeek;
            if (result != null && result.contains("not seekable")) {
                dispatchError(ERROR_STREAM_NOT_SEEKABLE, result);
            } else {
                dispatchError(ERROR_UNKNOWN, result);
            }
        }
        return result;
    }

    /** 获取总时长。 */
    public synchronized long getDurationMs() {
        return getDuration();
    }

    /** 按标准播放器命名获取总时长。 */
    public synchronized long getDuration() {
        checkReleased();
        return nativeGetDurationMs(nativeHandle);
    }

    /** 获取当前播放位置。 */
    public synchronized long getCurrentPositionMs() {
        return getCurrentPosition();
    }

    /** 按标准播放器命名获取当前播放位置。 */
    public synchronized long getCurrentPosition() {
        checkReleased();
        long currentPosition = nativeGetCurrentPositionMs(nativeHandle);
        long duration = nativeGetDurationMs(nativeHandle);
        dispatchVideoRenderingStartIfReady();
        if (state == State.STARTED
                && duration > 0
                && currentPosition >= Math.max(0, duration - 500)) {
            dispatchCompletion();
        }
        return currentPosition;
    }

    /** 返回当前是否处于播放中。 */
    public synchronized boolean isPlaying() {
        return !released && playing;
    }

    /** 返回当前是否已经 prepare 成功。 */
    public synchronized boolean isPrepared() {
        return !released && prepared;
    }

    /** 返回当前媒体是否支持 seek。 */
    public synchronized boolean isSeekable() {
        return !released && nativeIsSeekable(nativeHandle);
    }

    /** 获取当前视频宽度。 */
    public synchronized int getVideoWidth() {
        checkReleased();
        updateVideoSizeFromNative();
        return videoWidth;
    }

    /** 获取当前视频高度。 */
    public synchronized int getVideoHeight() {
        checkReleased();
        updateVideoSizeFromNative();
        return videoHeight;
    }

    /** 返回当前播放器是否已经释放。 */
    public synchronized boolean isReleased() {
        return released;
    }

    /** 返回当前播放器状态。 */
    public synchronized State getState() {
        return state;
    }

    /** 获取最近一次 prepare 返回信息。 */
    public synchronized String getLastPrepareResult() {
        return lastPrepareResult;
    }

    /** 获取最近一次 start 返回信息。 */
    public synchronized String getLastStartResult() {
        return lastStartResult;
    }

    /** 获取最近一帧的原始 RGBA 数据。 */
    public synchronized byte[] getCurrentFrameRgba() {
        checkReleased();
        return nativeGetCurrentFrameRgba(nativeHandle);
    }

    /** 获取最近一帧宽高信息。返回 [width, height]。 */
    public synchronized int[] getCurrentFrameSize() {
        checkReleased();
        return nativeGetCurrentFrameSize(nativeHandle);
    }

    /** 把当前解码后的原始帧保存为 PNG，并返回截图结果。 */
    public synchronized CaptureResult captureCurrentFramePng(String outputPath) throws IOException {
        checkReleased();
        if (outputPath == null || outputPath.length() == 0) {
            throw new IOException("outputPath is empty");
        }

        int[] frameSize = nativeGetCurrentFrameSize(nativeHandle);
        byte[] rgbaData = nativeGetCurrentFrameRgba(nativeHandle);
        int frameWidth = frameSize != null && frameSize.length >= 2 ? frameSize[0] : 0;
        int frameHeight = frameSize != null && frameSize.length >= 2 ? frameSize[1] : 0;

        if (frameWidth <= 0 || frameHeight <= 0 || rgbaData == null || rgbaData.length == 0) {
            throw new IOException("current decoded frame is unavailable");
        }

        int expectedSize = frameWidth * frameHeight * 4;
        if (rgbaData.length < expectedSize) {
            throw new IOException("rgba data size is invalid");
        }

        File outputFile = new File(outputPath);
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("cannot create output dir: " + parentDir.getAbsolutePath());
        }

        Bitmap bitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgbaData, 0, expectedSize));

        try (FileOutputStream outputStream = new FileOutputStream(outputFile, false)) {
            boolean compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
            outputStream.flush();
            if (!compressed) {
                throw new IOException("bitmap compress returned false");
            }
        } finally {
            bitmap.recycle();
        }

        return new CaptureResult(
                outputFile.getAbsolutePath(),
                frameWidth,
                frameHeight,
                System.currentTimeMillis()
        );
    }

    /** 开始录制当前播放中的流。 */
    public synchronized String startRecording(String outputPath) {
        checkReleased();
        if (recordingState == RecordingState.RECORDING) {
            return "recording ignored: already recording\nfile: " + lastRecordingPath;
        }

        String result = nativeStartRecording(nativeHandle, outputPath);
        if (result != null && result.startsWith("recording started")) {
            recordingState = RecordingState.RECORDING;
            lastRecordingPath = outputPath == null ? "" : outputPath;
            dispatchInfo(INFO_RECORDING_START, result);
        } else {
            recordingState = RecordingState.FAILED;
            dispatchError(ERROR_RECORD_FAILED, result);
        }
        return result;
    }

    /** 停止录制当前播放中的流。 */
    public synchronized String stopRecording() {
        checkReleased();
        if (recordingState != RecordingState.RECORDING && !nativeIsRecording(nativeHandle)) {
            recordingState = RecordingState.IDLE;
            return "recording not running";
        }

        recordingState = RecordingState.STOPPING;
        String result = nativeStopRecording(nativeHandle);
        if (result != null && result.startsWith("recording stopped")) {
            recordingState = RecordingState.IDLE;
            dispatchInfo(INFO_RECORDING_END, result);
        } else if (result != null && result.startsWith("recording not running")) {
            recordingState = RecordingState.IDLE;
        } else {
            recordingState = RecordingState.FAILED;
            dispatchError(ERROR_RECORD_FAILED, result);
        }
        return result;
    }

    /** 返回当前是否正在录制。 */
    public synchronized boolean isRecording() {
        checkReleased();
        return nativeIsRecording(nativeHandle);
    }

    /** 返回当前录制状态。 */
    public synchronized RecordingState getRecordingState() {
        return recordingState;
    }

    /** 返回最近一次录制文件路径。 */
    public synchronized String getLastRecordingPath() {
        return lastRecordingPath;
    }

    /** 获取 FFmpeg 版本号。 */
    public synchronized String getFFmpegVersion() {
        checkReleased();
        return nativeGetFFmpegVersion(nativeHandle);
    }

    /** 释放播放器实例。 */
    public synchronized void release() {
        if (!released) {
            stopRecordingIfNeeded();
            nativeRelease(nativeHandle);
            nativeHandle = 0;
            released = true;
            prepared = false;
            playing = false;
            paused = false;
            recordingState = RecordingState.IDLE;
            resetVideoSize();
            state = State.RELEASED;

            releaseAudioTrack();
        }
    }

    @Override
    public void close() {
        release();
    }

    /** Native 回调：初始化 AudioTrack 参数。 */
    private synchronized void onNativeAudioInfo(int sampleRate, int channels) {
        releaseAudioTrack();

        int channelConfig = channels == 1
                ? AudioFormat.CHANNEL_OUT_MONO
                : AudioFormat.CHANNEL_OUT_STEREO;

        int minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
        );

        int bufferSize = Math.max(minBufferSize, sampleRate * channels * 2);

        audioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
        );

        audioTrack.play();
        if (!audioRenderingStarted) {
            audioRenderingStarted = true;
            dispatchInfo(INFO_AUDIO_RENDERING_START, "audio rendering started");
        }
    }

    /** Native 回调：写入一段 PCM 数据到 AudioTrack。 */
    private void onNativeAudioData(byte[] data, int size) {
        AudioTrack track;

        synchronized (this) {
            track = audioTrack;
        }

        if (track != null && data != null && size > 0) {
            track.write(data, 0, size);
        }
    }

    /** Native 回调：播放器信息事件。 */
    private synchronized void onNativeInfo(int infoCode, String message) {
        if (infoCode == INFO_BUFFERING_START) {
            dispatchBufferingUpdate(0);
        } else if (infoCode == INFO_BUFFERING_END) {
            dispatchBufferingUpdate(100);
        }
        dispatchInfo(infoCode, message);
    }

    /** Native 回调：播放器错误事件。 */
    private synchronized void onNativeError(int errorCode, String message) {
        if (state != State.RELEASED) {
            state = State.ERROR;
            playing = false;
            paused = false;
        }
        dispatchError(errorCode, message);
    }

    /** Native 回调：视频尺寸发生变化。 */
    private synchronized void onNativeVideoSizeChanged(int width, int height) {
        updateVideoSize(width, height);
    }

    /** 释放当前 AudioTrack。 */
    private synchronized void releaseAudioTrack() {
        if (audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
            } catch (Exception ignored) {
            }

            audioTrack.release();
            audioTrack = null;
        }
    }

    /** 检查播放器是否已经释放。 */
    private void checkReleased() {
        if (released || nativeHandle == 0) {
            throw new IllegalStateException("ECHPlayer has been released");
        }
    }

    /** 如果正在录制则安全停止。 */
    private void stopRecordingIfNeeded() {
        if (nativeHandle != 0 && nativeIsRecording(nativeHandle)) {
            recordingState = RecordingState.STOPPING;
            nativeStopRecording(nativeHandle);
            recordingState = RecordingState.IDLE;
        }
    }

    /** 检查当前状态是否允许执行操作。 */
    private void requireState(State... allowedStates) {
        for (State allowedState : allowedStates) {
            if (state == allowedState) {
                return;
            }
        }

        String message = "Invalid player state: " + state;
        dispatchError(ERROR_INVALID_STATE, message);
        throw new IllegalStateException(message);
    }

    /** 分发错误回调。 */
    private void dispatchError(int errorCode, String message) {
        if (onErrorListener != null) {
            onErrorListener.onError(this, errorCode, message == null ? "" : message);
        }
    }

    /** 分发信息回调。 */
    private void dispatchInfo(int infoCode, String message) {
        if (onInfoListener != null) {
            onInfoListener.onInfo(this, infoCode, message == null ? "" : message);
        }
    }

    /** 分发播放完成回调。 */
    private void dispatchCompletion() {
        if (completionDispatched) {
            return;
        }

        completionDispatched = true;
        state = State.COMPLETED;
        playing = false;
        paused = false;
        if (onCompletionListener != null) {
            onCompletionListener.onCompletion(this);
        }
    }

    /** 分发缓冲进度回调。 */
    private void dispatchBufferingUpdate(int percent) {
        if (onBufferingUpdateListener != null) {
            onBufferingUpdateListener.onBufferingUpdate(this, percent);
        }
    }

    /** 分发视频尺寸变化回调。 */
    private void dispatchVideoSizeChanged(int width, int height) {
        if (onVideoSizeChangedListener != null) {
            onVideoSizeChangedListener.onVideoSizeChanged(this, width, height);
        }
    }

    /** 从 NativePlayer 刷新当前视频尺寸。 */
    private void updateVideoSizeFromNative() {
        if (nativeHandle == 0) {
            return;
        }

        int width = nativeGetVideoWidth(nativeHandle);
        int height = nativeGetVideoHeight(nativeHandle);
        updateVideoSize(width, height);
    }

    /** 更新当前视频尺寸并在变化时通知外部。 */
    private void updateVideoSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }

        if (videoWidth == width && videoHeight == height) {
            return;
        }

        videoWidth = width;
        videoHeight = height;
        dispatchVideoSizeChanged(width, height);
    }

    /** 重置当前视频尺寸。 */
    private void resetVideoSize() {
        videoWidth = 0;
        videoHeight = 0;
    }

    /** 规范化外部传入的渲染模式。 */
    private int normalizeRenderMode(int requestedRenderMode) {
        if (requestedRenderMode == RENDER_MODE_OPENGL
                || requestedRenderMode == RENDER_MODE_NATIVE_WINDOW) {
            return requestedRenderMode;
        }
        return RENDER_MODE_AUTO;
    }

    /** 在首帧解码后分发视频渲染开始事件。 */
    private void dispatchVideoRenderingStartIfReady() {
        if (videoRenderingStarted || state != State.STARTED) {
            return;
        }

        int[] frameSize = nativeGetCurrentFrameSize(nativeHandle);
        int width = frameSize != null && frameSize.length >= 2 ? frameSize[0] : 0;
        int height = frameSize != null && frameSize.length >= 2 ? frameSize[1] : 0;
        if (width > 0 && height > 0) {
            videoRenderingStarted = true;
            dispatchInfo(INFO_VIDEO_RENDERING_START, "video rendering started");
        }
    }

    /** 重置单次播放过程里的事件标记。 */
    private void resetPlaybackEventFlags() {
        videoRenderingStarted = false;
        audioRenderingStarted = false;
        completionDispatched = false;
        seeking = false;
    }

    /** 根据 prepare 结果映射错误码。 */
    private int mapPrepareErrorCode(String result) {
        if (result == null) {
            return ERROR_UNKNOWN;
        }
        if (result.contains("avformat_open_input")) {
            String lowerResult = result.toLowerCase();
            if (result.contains("401 Unauthorized")
                    || lowerResult.contains("unauthorized")
                    || result.contains("鉴权失败")) {
                return ERROR_RTSP_AUTH_FAILED;
            }
            if (result.contains("Connection timed out")
                    || lowerResult.contains("timed out")
                    || lowerResult.contains("timeout")) {
                return ERROR_NETWORK_TIMEOUT;
            }
            return ERROR_OPEN_INPUT_FAILED;
        }
        if (result.contains("avformat_find_stream_info")) {
            return ERROR_STREAM_INFO_FAILED;
        }
        if (result.contains("no video stream")) {
            return ERROR_NO_VIDEO_STREAM;
        }
        return ERROR_UNKNOWN;
    }

    /** 根据播放结果映射错误码。 */
    private int mapPlaybackErrorCode(String result) {
        if (result == null) {
            return ERROR_UNKNOWN;
        }
        if (result.contains("surface is null")) {
            return ERROR_RENDER_SURFACE_INVALID;
        }
        return ERROR_UNKNOWN;
    }

    /** 创建 NativePlayer 并返回指针。 */
    private native long nativeInit();

    /** 销毁 NativePlayer。 */
    private native void nativeRelease(long nativeHandle);

    /** 设置 NativePlayer 数据源。 */
    private native void nativeSetDataSource(long nativeHandle, String dataSource);

    /** 设置 NativePlayer Surface。 */
    private native void nativeSetSurface(long nativeHandle, Surface surface);

    /** 设置 NativePlayer 的 Surface 缩放方式。 */
    private native void nativeSetSurfaceScaleType(long nativeHandle, int scaleType);

    /** 设置 NativePlayer 的渲染模式。 */
    private native void nativeSetRenderMode(long nativeHandle, int renderMode);

    /** 设置 NativePlayer 的 RTSP 传输方式。 */
    private native void nativeSetRtspTransport(long nativeHandle, int transport);

    /** 设置 NativePlayer 的 long 类型 option。 */
    private native boolean nativeSetLongOption(long nativeHandle, int category, String name, long value);

    /** 调用 NativePlayer.prepare。 */
    private native String nativePrepare(long nativeHandle);

    /** 调用 NativePlayer.play。 */
    private native String nativePlay(long nativeHandle);

    /** 调用 NativePlayer.pause。 */
    private native void nativePause(long nativeHandle);

    /** 调用 NativePlayer.resume。 */
    private native void nativeResume(long nativeHandle);

    /** 调用 NativePlayer.stop。 */
    private native void nativeStop(long nativeHandle);

    /** 调用 NativePlayer.seekToMs。 */
    private native String nativeSeekToMs(long nativeHandle, long positionMs);

    /** 调用 NativePlayer.getDurationMs。 */
    private native long nativeGetDurationMs(long nativeHandle);

    /** 调用 NativePlayer.getCurrentPositionMs。 */
    private native long nativeGetCurrentPositionMs(long nativeHandle);

    /** 调用 NativePlayer.isSeekable。 */
    private native boolean nativeIsSeekable(long nativeHandle);

    /** 调用 NativePlayer.getVideoWidth。 */
    private native int nativeGetVideoWidth(long nativeHandle);

    /** 调用 NativePlayer.getVideoHeight。 */
    private native int nativeGetVideoHeight(long nativeHandle);

    /** 调用 NativePlayer.copyCurrentFrameRgba。 */
    private native byte[] nativeGetCurrentFrameRgba(long nativeHandle);

    /** 调用 NativePlayer.copyCurrentFrameSnapshot 的宽高部分。 */
    private native int[] nativeGetCurrentFrameSize(long nativeHandle);

    /** 调用 NativePlayer.startRecording。 */
    private native String nativeStartRecording(long nativeHandle, String outputPath);

    /** 调用 NativePlayer.stopRecording。 */
    private native String nativeStopRecording(long nativeHandle);

    /** 调用 NativePlayer.isRecording。 */
    private native boolean nativeIsRecording(long nativeHandle);

    /** 调用 NativePlayer.getFFmpegVersion。 */
    private native String nativeGetFFmpegVersion(long nativeHandle);
}
