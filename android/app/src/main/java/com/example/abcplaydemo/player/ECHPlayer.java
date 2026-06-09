package com.example.abcplaydemo.player;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.view.Surface;

/**
 * Java 层播放器封装，负责桥接 UI 与 NativePlayer。
 */
public class ECHPlayer implements AutoCloseable {

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

    static {
        System.loadLibrary("abcplaydemo");
    }

    /** NativePlayer 指针句柄。 */
    private long nativeHandle = 0;
    /** 当前对象是否已经释放。 */
    private boolean released = false;
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

    /** Java 音频输出实例。 */
    private AudioTrack audioTrack;

    /** 创建播放器并初始化 native 实例。 */
    public ECHPlayer() {
        nativeHandle = nativeInit();
    }

    /** 设置播放数据源。 */
    public synchronized void setDataSource(String dataSource) {
        checkReleased();

        if (dataSource == null || dataSource.length() == 0) {
            throw new IllegalArgumentException("dataSource is empty");
        }

        nativeSetDataSource(nativeHandle, dataSource);
        prepared = false;
        playing = false;
        paused = false;
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

        return false;
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

        return false;
    }

    /** 打开数据源并读取流信息。 */
    public synchronized String prepare() {
        checkReleased();
        lastPrepareResult = nativePrepare(nativeHandle);
        prepared = lastPrepareResult != null && lastPrepareResult.startsWith("prepare success");
        playing = false;
        paused = false;
        return lastPrepareResult;
    }

    /** 异步打开数据源并读取流信息。 */
    public synchronized void prepareAsync() {
        checkReleased();

        Thread prepareThread = new Thread(() -> {
            synchronized (ECHPlayer.this) {
                if (!released && nativeHandle != 0) {
                    prepare();
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
        }
        return lastStartResult;
    }

    /** 暂停播放。 */
    public synchronized void pause() {
        if (!released && nativeHandle != 0) {
            nativePause(nativeHandle);

            if (audioTrack != null) {
                audioTrack.pause();
            }

            playing = false;
            paused = true;
        }
    }

    /** 恢复播放。 */
    public synchronized void resume() {
        if (!released && nativeHandle != 0) {
            if (audioTrack != null) {
                audioTrack.play();
            }

            nativeResume(nativeHandle);
            playing = true;
            paused = false;
        }
    }

    /** 停止播放。 */
    public synchronized void stop() {
        if (!released && nativeHandle != 0) {
            nativeStop(nativeHandle);
            releaseAudioTrack();
            prepared = false;
            playing = false;
            paused = false;
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
    }

    /** 跳转到指定毫秒位置。 */
    public synchronized String seekToMs(long positionMs) {
        return seekTo(positionMs);
    }

    /** 按标准播放器命名跳转到指定毫秒位置。 */
    public synchronized String seekTo(long positionMs) {
        checkReleased();
        return nativeSeekToMs(nativeHandle, positionMs);
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
        return nativeGetCurrentPositionMs(nativeHandle);
    }

    /** 返回当前是否处于播放中。 */
    public synchronized boolean isPlaying() {
        return !released && playing;
    }

    /** 返回当前是否已经 prepare 成功。 */
    public synchronized boolean isPrepared() {
        return !released && prepared;
    }

    /** 返回当前播放器是否已经释放。 */
    public synchronized boolean isReleased() {
        return released;
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
        return nativeStartRecording(nativeHandle, outputPath);
    }

    /** 停止录制当前播放中的流。 */
    public synchronized String stopRecording() {
        checkReleased();
        return nativeStopRecording(nativeHandle);
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
