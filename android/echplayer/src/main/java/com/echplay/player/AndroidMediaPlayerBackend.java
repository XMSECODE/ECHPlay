package com.echplay.player;

import android.media.MediaPlayer;
import android.view.Surface;

import java.io.IOException;

/**
 * Android 原生 MediaPlayer fallback 后端，用于系统播放器可直接处理的简单源。
 */
public class AndroidMediaPlayerBackend implements PlayerBackend {
    /** 原生 MediaPlayer 实例。 */
    private MediaPlayer mediaPlayer;
    /** 当前后端状态文本。 */
    private String stateText = "IDLE";
    /** 当前是否已释放。 */
    private boolean released = false;
    /** 当前是否循环播放。 */
    private boolean looping = false;
    /** 左声道音量。 */
    private float leftVolume = 1.0f;
    /** 右声道音量。 */
    private float rightVolume = 1.0f;

    /** 创建 Android MediaPlayer fallback 后端。 */
    public AndroidMediaPlayerBackend() {
        mediaPlayer = new MediaPlayer();
    }

    /** 返回后端名称。 */
    @Override
    public String getBackendName() {
        return "AndroidMediaPlayer";
    }

    /** 设置显示 Surface。 */
    @Override
    public void setSurface(Surface surface) {
        checkReleased();
        mediaPlayer.setSurface(surface);
    }

    /** 设置播放地址。 */
    @Override
    public void setDataSource(String dataSource) throws IOException {
        checkReleased();
        if (dataSource == null || dataSource.trim().isEmpty()) {
            throw new IOException("dataSource is empty");
        }
        mediaPlayer.setDataSource(dataSource.trim());
        stateText = "INITIALIZED";
    }

    /** 同步准备数据源。 */
    @Override
    public void prepare() throws IOException {
        checkReleased();
        mediaPlayer.prepare();
        mediaPlayer.setLooping(looping);
        mediaPlayer.setVolume(leftVolume, rightVolume);
        stateText = "PREPARED";
    }

    /** 开始或恢复播放。 */
    @Override
    public void start() {
        checkReleased();
        mediaPlayer.start();
        stateText = "STARTED";
    }

    /** 暂停播放。 */
    @Override
    public void pause() {
        checkReleased();
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
        stateText = "PAUSED";
    }

    /** 停止播放。 */
    @Override
    public void stop() {
        if (released) {
            return;
        }
        mediaPlayer.stop();
        stateText = "STOPPED";
    }

    /** 重置播放器。 */
    @Override
    public void reset() {
        checkReleased();
        mediaPlayer.reset();
        stateText = "IDLE";
    }

    /** 释放播放器。 */
    @Override
    public void release() {
        if (released) {
            return;
        }
        mediaPlayer.release();
        released = true;
        stateText = "RELEASED";
    }

    /** 跳转播放位置。 */
    @Override
    public void seekTo(long positionMs) {
        checkReleased();
        mediaPlayer.seekTo((int) Math.max(0L, positionMs));
    }

    /** 返回媒体总时长。 */
    @Override
    public long getDuration() {
        checkReleased();
        return mediaPlayer.getDuration();
    }

    /** 返回当前播放位置。 */
    @Override
    public long getCurrentPosition() {
        checkReleased();
        return mediaPlayer.getCurrentPosition();
    }

    /** 返回是否正在播放。 */
    @Override
    public boolean isPlaying() {
        return !released && mediaPlayer.isPlaying();
    }

    /** 设置是否循环播放。 */
    @Override
    public void setLooping(boolean looping) {
        checkReleased();
        this.looping = looping;
        mediaPlayer.setLooping(looping);
    }

    /** 返回是否循环播放。 */
    @Override
    public boolean isLooping() {
        return looping;
    }

    /** 设置左右声道音量。 */
    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        checkReleased();
        this.leftVolume = normalizeVolume(leftVolume);
        this.rightVolume = normalizeVolume(rightVolume);
        mediaPlayer.setVolume(this.leftVolume, this.rightVolume);
    }

    /** 返回当前后端状态文本。 */
    @Override
    public String getStateText() {
        return stateText;
    }

    /** 返回是否已经释放。 */
    @Override
    public boolean isReleased() {
        return released;
    }

    /** 检查后端是否已经释放。 */
    private void checkReleased() {
        if (released) {
            throw new IllegalStateException("backend is released");
        }
    }

    /** 把音量限制在 0 到 1 之间。 */
    private float normalizeVolume(float volume) {
        if (Float.isNaN(volume) || Float.isInfinite(volume)) {
            return 1.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, volume));
    }
}
