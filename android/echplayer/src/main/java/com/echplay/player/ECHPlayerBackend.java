package com.echplay.player;

import android.view.Surface;

import java.io.IOException;

/**
 * ECHPlayer 后端适配器，把现有播放器包装成 PlayerBackend。
 */
public class ECHPlayerBackend implements PlayerBackend {
    /** 被包装的 ECHPlayer 实例。 */
    private final ECHPlayer player;

    /** 创建默认 ECHPlayer 后端。 */
    public ECHPlayerBackend() {
        this(new ECHPlayer());
    }

    /** 使用外部传入的 ECHPlayer 创建后端，方便测试和高级接入。 */
    public ECHPlayerBackend(ECHPlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("player is null");
        }
        this.player = player;
    }

    /** 返回内部 ECHPlayer，便于业务侧继续访问截图、录制、统计等扩展能力。 */
    public ECHPlayer getPlayer() {
        return player;
    }

    /** 返回后端名称。 */
    @Override
    public String getBackendName() {
        return "ECHPlayer";
    }

    /** 设置显示 Surface。 */
    @Override
    public void setSurface(Surface surface) {
        player.setSurface(surface);
    }

    /** 设置播放地址。 */
    @Override
    public void setDataSource(String dataSource) throws IOException {
        try {
            player.setDataSource(dataSource);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** 同步准备数据源。 */
    @Override
    public void prepare() throws IOException {
        String result = player.prepare();
        if (player.getState() == ECHPlayer.State.ERROR) {
            throw new IOException(result);
        }
    }

    /** 开始或恢复播放。 */
    @Override
    public void start() {
        player.start();
    }

    /** 暂停播放。 */
    @Override
    public void pause() {
        player.pause();
    }

    /** 停止播放。 */
    @Override
    public void stop() {
        player.stop();
    }

    /** 重置播放器。 */
    @Override
    public void reset() {
        player.reset();
    }

    /** 释放播放器。 */
    @Override
    public void release() {
        player.release();
    }

    /** 跳转播放位置。 */
    @Override
    public void seekTo(long positionMs) {
        player.seekTo(positionMs);
    }

    /** 返回媒体总时长。 */
    @Override
    public long getDuration() {
        return player.getDuration();
    }

    /** 返回当前播放位置。 */
    @Override
    public long getCurrentPosition() {
        return player.getCurrentPosition();
    }

    /** 返回是否正在播放。 */
    @Override
    public boolean isPlaying() {
        return player.isPlaying();
    }

    /** 设置是否循环播放。 */
    @Override
    public void setLooping(boolean looping) {
        player.setLooping(looping);
    }

    /** 返回是否循环播放。 */
    @Override
    public boolean isLooping() {
        return player.isLooping();
    }

    /** 设置左右声道音量。 */
    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        player.setVolume(leftVolume, rightVolume);
    }

    /** 返回当前后端状态文本。 */
    @Override
    public String getStateText() {
        return player.getState().name();
    }

    /** 返回是否已经释放。 */
    @Override
    public boolean isReleased() {
        return player.isReleased();
    }
}
