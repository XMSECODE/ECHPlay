package com.echplay.player;

import android.content.Context;
import android.net.Uri;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * MediaPlayer 风格播放器接口，用于让 ECHPlayer 的接入方式接近 ijkplayer。
 */
public interface IECHMediaPlayer {
    /** 使用 SurfaceHolder 设置视频显示目标。 */
    void setDisplay(SurfaceHolder holder);

    /** 使用 Surface 设置视频显示目标。 */
    void setSurface(Surface surface);

    /** 设置字符串数据源。 */
    void setDataSource(String dataSource);

    /** 设置带 headers 的字符串数据源。 */
    void setDataSource(String dataSource, Map<String, String> headers);

    /** 设置 Uri 数据源。 */
    void setDataSource(Context context, Uri uri);

    /** 设置带 headers 的 Uri 数据源。 */
    void setDataSource(Context context, Uri uri, Map<String, String> headers);

    /** 设置文件描述符数据源。 */
    void setDataSource(FileDescriptor fd);

    /** 设置自定义数据源，并把数据落到临时文件后播放。 */
    void setDataSource(ECHMediaDataSource dataSource, File cacheFile) throws IOException;

    /** 返回最近设置的数据源。 */
    String getDataSource();

    /** 同步准备播放器。 */
    String prepare();

    /** 异步准备播放器。 */
    void prepareAsync();

    /** 开始或恢复播放。 */
    String start();

    /** 暂停播放。 */
    void pause();

    /** 停止播放。 */
    void stop();

    /** 重置播放器。 */
    void reset();

    /** 释放播放器。 */
    void release();

    /** 跳转到指定毫秒位置。 */
    String seekTo(long positionMs);

    /** 返回总时长，单位毫秒。 */
    long getDuration();

    /** 返回当前播放位置，单位毫秒。 */
    long getCurrentPosition();

    /** 返回当前是否正在播放。 */
    boolean isPlaying();

    /** 设置左右声道音量，范围建议为 0 到 1。 */
    void setVolume(float leftVolume, float rightVolume);

    /** 设置是否静音。 */
    void setMuted(boolean muted);

    /** 返回当前是否静音。 */
    boolean isMuted();

    /** 设置播放速度，当前版本记录期望值，后续 native 音视频时钟继续接入。 */
    void setSpeed(float speed);

    /** 返回当前期望播放速度。 */
    float getSpeed();

    /** 设置是否循环播放。 */
    void setLooping(boolean looping);

    /** 返回是否循环播放。 */
    boolean isLooping();

    /** 返回视频宽度。 */
    int getVideoWidth();

    /** 返回视频高度。 */
    int getVideoHeight();

    /** 返回媒体信息。 */
    ECHPlayer.MediaInfo getMediaInfo();

    /** 返回媒体元信息。 */
    ECHPlayer.MediaMeta getMediaMeta();

    /** 返回轨道信息列表。 */
    List<ECHPlayer.TrackInfo> getTrackInfo();

    /** 选择指定轨道。 */
    void selectTrack(int streamIndex);

    /** 取消选择指定轨道。 */
    void deselectTrack(int streamIndex);

    /** 返回当前视频解码器名称。 */
    String getVideoDecoder();

    /** 返回当前音频解码器名称。 */
    String getAudioDecoder();

    /** 读取 long 类型播放器属性。 */
    long getPropertyLong(int property, long defaultValue);

    /** 读取 float 类型播放器属性。 */
    float getPropertyFloat(int property, float defaultValue);

    /** 设置准备完成监听器。 */
    void setOnPreparedListener(ECHPlayer.OnPreparedListener listener);

    /** 设置播放完成监听器。 */
    void setOnCompletionListener(ECHPlayer.OnCompletionListener listener);

    /** 设置错误监听器。 */
    void setOnErrorListener(ECHPlayer.OnErrorListener listener);

    /** 设置信息监听器。 */
    void setOnInfoListener(ECHPlayer.OnInfoListener listener);

    /** 设置缓冲进度监听器。 */
    void setOnBufferingUpdateListener(ECHPlayer.OnBufferingUpdateListener listener);

    /** 设置视频尺寸变化监听器。 */
    void setOnVideoSizeChangedListener(ECHPlayer.OnVideoSizeChangedListener listener);

    /** 设置 seek 完成监听器。 */
    void setOnSeekCompleteListener(ECHPlayer.OnSeekCompleteListener listener);

    /** 设置字幕文本监听器。 */
    void setOnTimedTextListener(ECHPlayer.OnTimedTextListener listener);
}
