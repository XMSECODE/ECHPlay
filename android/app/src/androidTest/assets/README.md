# 数据源回归样本

`data_source_sample.mp4` 是生成的 3 秒 H.264 视频，160x96、10 FPS、无音频，约 26 KB。仅用于验证 FFmpeg 解析和 Demo 播放，不依赖主 App 中未跟踪的 `assets/test.mp4` 或网络。

重建命令（在仓库根目录执行；已有文件时 FFmpeg 会要求确认覆盖）：

```bash
ffmpeg -f lavfi -i 'testsrc2=size=160x96:rate=10:duration=3' \
  -an -c:v libx264 -pix_fmt yuv420p -movflags +faststart \
  android/app/src/androidTest/assets/data_source_sample.mp4
```

样本和测试提供方仅供回归使用，Release APK 不包含这些测试资源。
