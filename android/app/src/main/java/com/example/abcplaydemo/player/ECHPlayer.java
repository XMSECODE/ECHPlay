package com.example.abcplaydemo.player;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.view.Surface;

public class ECHPlayer implements AutoCloseable {

    static {
        System.loadLibrary("abcplaydemo");
    }

    private long nativeHandle = 0;
    private boolean released = false;

    private AudioTrack audioTrack;

    public ECHPlayer() {
        nativeHandle = nativeInit();
    }

    public synchronized void setDataSource(String dataSource) {
        checkReleased();

        if (dataSource == null || dataSource.length() == 0) {
            throw new IllegalArgumentException("dataSource is empty");
        }

        nativeSetDataSource(nativeHandle, dataSource);
    }

    public synchronized void setSurface(Surface surface) {
        checkReleased();
        nativeSetSurface(nativeHandle, surface);
    }

    public synchronized String prepare() {
        checkReleased();
        return nativePrepare(nativeHandle);
    }

    public synchronized String play() {
        checkReleased();
        return nativePlay(nativeHandle);
    }

    public synchronized void pause() {
        if (!released && nativeHandle != 0) {
            nativePause(nativeHandle);

            if (audioTrack != null) {
                audioTrack.pause();
            }
        }
    }

    public synchronized void resume() {
        if (!released && nativeHandle != 0) {
            if (audioTrack != null) {
                audioTrack.play();
            }

            nativeResume(nativeHandle);
        }
    }

    public synchronized void stop() {
        if (!released && nativeHandle != 0) {
            nativeStop(nativeHandle);
            releaseAudioTrack();
        }
    }

    public synchronized String getFFmpegVersion() {
        checkReleased();
        return nativeGetFFmpegVersion(nativeHandle);
    }

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

    private void onNativeAudioData(byte[] data, int size) {
        AudioTrack track;

        synchronized (this) {
            track = audioTrack;
        }

        if (track != null && data != null && size > 0) {
            track.write(data, 0, size);
        }
    }

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

    private void checkReleased() {
        if (released || nativeHandle == 0) {
            throw new IllegalStateException("ECHPlayer has been released");
        }
    }

    private native long nativeInit();

    private native void nativeRelease(long nativeHandle);

    private native void nativeSetDataSource(long nativeHandle, String dataSource);

    private native void nativeSetSurface(long nativeHandle, Surface surface);

    private native String nativePrepare(long nativeHandle);

    private native String nativePlay(long nativeHandle);

    private native void nativePause(long nativeHandle);

    private native void nativeResume(long nativeHandle);

    private native void nativeStop(long nativeHandle);

    private native String nativeGetFFmpegVersion(long nativeHandle);
}
