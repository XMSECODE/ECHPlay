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

    static {
        System.loadLibrary("abcplaydemo");
    }

    /** NativePlayer 指针句柄。 */
    private long nativeHandle = 0;
    /** 当前对象是否已经释放。 */
    private boolean released = false;

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
    }

    /** 设置视频输出 Surface。 */
    public synchronized void setSurface(Surface surface) {
        checkReleased();
        nativeSetSurface(nativeHandle, surface);
    }

    /** 设置 RTSP 传输方式。 */
    public synchronized void setRtspTransport(int transport) {
        checkReleased();
        nativeSetRtspTransport(nativeHandle, transport);
    }

    /** 打开数据源并读取流信息。 */
    public synchronized String prepare() {
        checkReleased();
        return nativePrepare(nativeHandle);
    }

    /** 启动播放。 */
    public synchronized String play() {
        checkReleased();
        return nativePlay(nativeHandle);
    }

    /** 暂停播放。 */
    public synchronized void pause() {
        if (!released && nativeHandle != 0) {
            nativePause(nativeHandle);

            if (audioTrack != null) {
                audioTrack.pause();
            }
        }
    }

    /** 恢复播放。 */
    public synchronized void resume() {
        if (!released && nativeHandle != 0) {
            if (audioTrack != null) {
                audioTrack.play();
            }

            nativeResume(nativeHandle);
        }
    }

    /** 停止播放。 */
    public synchronized void stop() {
        if (!released && nativeHandle != 0) {
            nativeStop(nativeHandle);
            releaseAudioTrack();
        }
    }

    /** 跳转到指定毫秒位置。 */
    public synchronized String seekToMs(long positionMs) {
        checkReleased();
        return nativeSeekToMs(nativeHandle, positionMs);
    }

    /** 获取总时长。 */
    public synchronized long getDurationMs() {
        checkReleased();
        return nativeGetDurationMs(nativeHandle);
    }

    /** 获取当前播放位置。 */
    public synchronized long getCurrentPositionMs() {
        checkReleased();
        return nativeGetCurrentPositionMs(nativeHandle);
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
