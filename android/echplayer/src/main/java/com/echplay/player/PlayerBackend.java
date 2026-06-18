package com.echplay.player;

import android.view.Surface;

import java.io.IOException;

/**
 * 播放器后端最小接口，用于预研 ECHPlayer、Android MediaPlayer 和后续 ExoPlayer 的统一边界。
 */
public interface PlayerBackend {
    /** 返回后端名称，方便日志和调试面板展示。 */
    String getBackendName();

    /** 设置视频显示 Surface。 */
    void setSurface(Surface surface);

    /** 设置字符串数据源。 */
    void setDataSource(String dataSource) throws IOException;

    /** 同步准备数据源。 */
    void prepare() throws IOException;

    /** 开始或恢复播放。 */
    void start();

    /** 暂停播放。 */
    void pause();

    /** 停止播放。 */
    void stop();

    /** 重置后端到可重新设置数据源的状态。 */
    void reset();

    /** 释放后端资源。 */
    void release();

    /** 跳转到指定毫秒位置。 */
    void seekTo(long positionMs);

    /** 返回媒体总时长，单位毫秒。 */
    long getDuration();

    /** 返回当前播放位置，单位毫秒。 */
    long getCurrentPosition();

    /** 返回当前是否正在播放。 */
    boolean isPlaying();

    /** 设置是否循环播放。 */
    void setLooping(boolean looping);

    /** 返回是否循环播放。 */
    boolean isLooping();

    /** 设置左右声道音量，范围建议为 0 到 1。 */
    void setVolume(float leftVolume, float rightVolume);

    /** 返回当前后端状态文本。 */
    String getStateText();

    /** 返回后端是否已经释放。 */
    boolean isReleased();
}
