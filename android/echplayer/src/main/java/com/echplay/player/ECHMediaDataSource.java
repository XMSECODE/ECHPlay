package com.echplay.player;

import java.io.Closeable;
import java.io.IOException;

/**
 * 自定义媒体数据源接口，用于对齐 ijkplayer/MediaPlayer 风格的外部数据读取入口。
 */
public interface ECHMediaDataSource extends Closeable {
    /**
     * 返回媒体数据总长度，未知长度返回 -1。
     */
    long getSize() throws IOException;

    /**
     * 从指定位置读取数据，返回实际读取字节数，读到结尾返回 -1。
     */
    int readAt(long position, byte[] buffer, int offset, int size) throws IOException;

    /**
     * 关闭数据源，默认实现方便只读内存数据源按需覆写。
     */
    @Override
    default void close() throws IOException {
        // 默认无资源需要关闭。
    }
}
