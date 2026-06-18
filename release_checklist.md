# ECHPlay 发版检查清单

适用范围：v1.8 及后续版本发版前检查。

============================================================
一、构建与产物
============================================================

- [ ] 执行 `cd android && ./tools/smoke_check.sh`。
- [ ] `echplayer-debug.aar` 已生成。
- [ ] `echplayer-release.aar` 已生成。
- [ ] `app-debug.apk` 已生成。
- [ ] Release AAR 包含 `jni/arm64-v8a/libechplayer.so`。
- [ ] Release AAR 包含 FFmpeg so：`libavcodec.so`、`libavformat.so`、`libavutil.so`、`libswresample.so`、`libswscale.so`。
- [ ] 如补齐 `armeabi-v7a`，确认对应 ABI so 已进入 AAR。
- [ ] 执行 `cd android && ./tools/publish_maven_local.sh`。
- [ ] Maven Local 产物 `com.echplay:echplayer:<version>` 可被外部工程依赖。
- [ ] `abi_release_status.md` 已更新，未验证 ABI 不标为已支持。

============================================================
二、基础播放回归
============================================================

- [ ] 本地 MP4 可播放。
- [ ] 本地 MP4 可暂停、恢复、停止、重新播放。
- [ ] 本地 MP4 可 seek。
- [ ] HTTP MP4 可播放。
- [ ] HTTP MP4 在支持 Range 的服务端可 seek。
- [ ] HLS VOD 可播放。
- [ ] HLS Live 如无稳定源，可标记待验证并记录原因。
- [ ] HTTPS MP4 如无稳定源，可标记待验证并记录原因。

============================================================
三、RTSP 回归
============================================================

- [ ] RTSP TCP 入口可设置。
- [ ] RTSP UDP 入口可设置。
- [ ] RTSP 自动重连开关、次数和间隔可配置。
- [ ] 如 Android 侧 RTSP 仍失败，记录源地址、传输方式、错误信息和 logcat 摘要。
- [ ] RTSP 直播流 seek 禁用或提示清楚。

============================================================
四、解码与渲染
============================================================

- [ ] `DECODE_MODE_AUTO` 可播放并展示当前解码方式。
- [ ] `DECODE_MODE_SOFTWARE` 可播放。
- [ ] `DECODE_MODE_MEDIACODEC` 可硬解或给出回退原因。
- [ ] `RENDER_MODE_AUTO` 可播放。
- [ ] `RENDER_MODE_OPENGL` 可播放或明确回退。
- [ ] `RENDER_MODE_NATIVE_WINDOW` 可播放。
- [ ] 视频尺寸回调正常。

============================================================
五、截图、录制和统计
============================================================

- [ ] 截图保存 PNG，来源是当前解码帧，不是 SurfaceView 截屏。
- [ ] 录制开始和停止成功。
- [ ] 录制文件可生成并可被播放器识别。
- [ ] `getMediaInfo()` 返回媒体摘要。
- [ ] `getTrackInfo()` 返回轨道列表。
- [ ] `getPlaybackStats()` 返回读取速度、队列、缓冲、decode/render fps、帧数、prepare 和首帧耗时。

============================================================
六、PlayerView
============================================================

- [ ] PlayerView 可设置地址并播放。
- [ ] PlayerView loading、buffering、error、retry 状态可用。
- [ ] PlayerView 进度条、当前时间和总时长显示正常。
- [ ] PlayerView 对可 seek 媒体拖动跳转正常。
- [ ] PlayerView 对不可 seek 流禁用进度条或输出明确提示。
- [ ] PlayerView 播放中控制条自动隐藏。
- [ ] 点击视频区域可重新显示控制条。
- [ ] PlayerView 截图和录制入口可用。

============================================================
七、文档、标签和推送
============================================================

- [ ] README 已更新新版本能力。
- [ ] 版本目标文档已更新。
- [ ] 验证报告已更新。
- [ ] 兼容性矩阵已更新。
- [ ] release checklist 如有新增能力已更新。
- [ ] `github_release_template.md` 已按当前版本填写或复用。
- [ ] FFmpeg、ijkplayer 参考源码和第三方库 License 说明已检查。
- [ ] 所有代码已提交，提交信息包含中文和英文。
- [ ] 当前分支已推送到服务器。
- [ ] tag 已创建并推送。
