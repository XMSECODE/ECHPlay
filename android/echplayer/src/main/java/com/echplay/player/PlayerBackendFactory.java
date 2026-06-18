package com.echplay.player;

/**
 * 播放器后端工厂，集中创建不同后端，避免业务侧直接依赖实现类。
 */
public final class PlayerBackendFactory {
    /** 工具类不需要实例化。 */
    private PlayerBackendFactory() {
    }

    /** 按类型创建播放器后端。 */
    public static PlayerBackend create(PlayerBackendType type) {
        if (type == PlayerBackendType.ANDROID_MEDIA_PLAYER) {
            return new AndroidMediaPlayerBackend();
        }
        return new ECHPlayerBackend();
    }

    /** 创建默认主后端。 */
    public static PlayerBackend createDefault() {
        return create(PlayerBackendType.ECH_PLAYER);
    }
}
