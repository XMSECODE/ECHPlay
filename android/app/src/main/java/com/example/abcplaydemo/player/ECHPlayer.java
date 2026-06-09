package com.example.abcplaydemo.player;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.view.Surface;

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
        /** 播放完成。 */
        COMPLETED,
        /** 出错。 */
        ERROR,
        /** 已释放。 */
        RELEASED
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

    /** RTSP 走 TCP 传输。 */
    public static final int RTSP_TRANSPORT_TCP = 0;
    /** RTSP 走 UDP 传输。 */
    public static final int RTSP_TRANSPORT_UDP = 1;
    /** FFmpeg format 层 option 分类。 */
    public static final int OPTION_CATEGORY_FORMAT = 1;
    /** 播放器自身 option 分类。 */
    public static final int OPTION_CATEGORY_PLAYER = 2;
    /** RTSP 传输方式 option 名称。 */
    public static final String OPTION_RTSP_TRANSPORT = "rtsp_transport";
    /** RTSP TCP option 值。 */
    public static final String OPTION_VALUE_TCP = "tcp";
    /** RTSP UDP option 值。 */
    public static final String OPTION_VALUE_UDP = "udp";
    /** 打开输入超时时间 option 名称，单位微秒。 */
    public static final String OPTION_TIMEOUT = "timeout";
    /** 网络读取超时时间 option 名称，单位微秒。 */
    public static final String OPTION_RW_TIMEOUT = "rw_timeout";
    /** 网络输入缓冲大小 option 名称，单位字节。 */
    public static final String OPTION_BUFFER_SIZE = "buffer_size";
    /** RTSP 最大延迟 option 名称，单位微秒。 */
    public static final String OPTION_MAX_DELAY = "max_delay";

    static {
        System.loadLibrary("abcplaydemo");
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
    /** 首帧视频渲染事件是否已经发出。 */
    private boolean videoRenderingStarted = false;
    /** 音频输出事件是否已经发出。 */
    private boolean audioRenderingStarted = false;
    /** 播放完成事件是否已经发出。 */
    private boolean completionDispatched = false;

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
        resetPlaybackEventFlags();
    }

    /** 设置视频输出 Surface。 */
    public synchronized void setSurface(Surface surface) {
        checkReleased();
        nativeSetSurface(nativeHandle, surface);
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
        String result = nativeSeekToMs(nativeHandle, positionMs);
        if (result != null && result.startsWith("seek success")) {
            dispatchInfo(INFO_SEEK_COMPLETE, result);
        } else if (result != null && result.contains("not seekable")) {
            dispatchError(ERROR_STREAM_NOT_SEEKABLE, result);
        } else {
            dispatchError(ERROR_UNKNOWN, result);
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

    /** 开始录制当前播放中的流。 */
    public synchronized String startRecording(String outputPath) {
        checkReleased();
        String result = nativeStartRecording(nativeHandle, outputPath);
        if (result != null && result.startsWith("recording started")) {
            dispatchInfo(INFO_RECORDING_START, result);
        } else {
            dispatchError(ERROR_RECORD_FAILED, result);
        }
        return result;
    }

    /** 停止录制当前播放中的流。 */
    public synchronized String stopRecording() {
        checkReleased();
        String result = nativeStopRecording(nativeHandle);
        if (result != null && result.startsWith("recording stopped")) {
            dispatchInfo(INFO_RECORDING_END, result);
        }
        return result;
    }

    /** 返回当前是否正在录制。 */
    public synchronized boolean isRecording() {
        checkReleased();
        return nativeIsRecording(nativeHandle);
    }

    /** 获取 FFmpeg 版本号。 */
    public synchronized String getFFmpegVersion() {
        checkReleased();
        return nativeGetFFmpegVersion(nativeHandle);
    }

    /** 释放播放器实例。 */
    public synchronized void release() {
        if (!released) {
            nativeRelease(nativeHandle);
            nativeHandle = 0;
            released = true;
            prepared = false;
            playing = false;
            paused = false;
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
