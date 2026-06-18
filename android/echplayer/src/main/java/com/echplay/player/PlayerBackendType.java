package com.echplay.player;

/**
 * 播放器后端类型，用于让业务方明确选择主播放器或系统 fallback。
 */
public enum PlayerBackendType {
    /** ECHPlayer 主后端，使用 FFmpeg、OpenGL、MediaCodec 和项目自有能力。 */
    ECH_PLAYER,
    /** Android 原生 MediaPlayer fallback 后端，适合系统可播的简单文件和网络源。 */
    ANDROID_MEDIA_PLAYER
}
