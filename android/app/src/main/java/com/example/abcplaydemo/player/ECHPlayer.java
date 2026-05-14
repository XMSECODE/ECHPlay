package com.example.abcplaydemo.player;

public class ECHPlayer implements AutoCloseable {

    static {
        System.loadLibrary("abcplaydemo");
    }

    private long nativeHandle = 0;
    private boolean released = false;

    public ECHPlayer() {
        nativeHandle = nativeInit();
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

    private native String nativeGetFFmpegVersion(long nativeHandle);
}
