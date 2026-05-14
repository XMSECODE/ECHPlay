package com.example.abcplaydemo.player;

import android.view.Surface;

public class ECHPlayer implements AutoCloseable {

    static {
        System.loadLibrary("abcplaydemo");
    }

    private long nativeHandle = 0;
    private boolean released = false;

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

    public synchronized void stop() {
        if (!released && nativeHandle != 0) {
            nativeStop(nativeHandle);
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
        }
    }

    @Override
    public void close() {
        release();
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

    private native void nativeStop(long nativeHandle);

    private native String nativeGetFFmpegVersion(long nativeHandle);
}
