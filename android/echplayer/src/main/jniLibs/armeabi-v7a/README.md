armeabi-v7a ABI 目录预留说明

当前仓库还没有提供 armeabi-v7a 的 FFmpeg 动态库，因此 Gradle 会自动跳过该 ABI。

如需启用 armeabi-v7a，请补齐以下文件：

1. libavcodec.so
2. libavformat.so
3. libavutil.so
4. libswresample.so
5. libswscale.so

补齐后重新执行 `./gradlew :echplayer:assembleRelease`，`echplayer` 会自动把 armeabi-v7a 纳入 AAR 打包。
