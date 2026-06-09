# ECHPlay 与 ijkplayer 差距及后续版本迭代目标

本文档用于把 ECHPlay v1.1 与 ijkplayer 的能力差距，拆成后续版本可执行、可验收的迭代目标。

参考对象：

1. ijkplayer 官方工程：Android / iOS video player based on FFmpeg。
2. ijkplayer Android 侧能力：MediaPlayer-like API、NativeWindow / OpenGL ES 输出、AudioTrack / OpenSL ES 音频输出、MediaCodec 硬解、ExoPlayer 后端、FFmpeg 多协议能力、多 ABI 包。
3. ECHPlay v1.1 当前能力：本地 MP4、RTSP、TCP / UDP 切换、基础状态机、回调错误码、seek 保护、解码帧截图、流录制、ECHPlayerView 雏形。

============================================================
一、总体判断
============================================================

ECHPlay v1.1 已经从“功能 Demo”进入“播放器组件雏形”阶段，但和 ijkplayer 相比，仍存在明显差距：

1. ijkplayer 是成熟播放器框架，ECHPlay 仍是 Android App Demo 工程。
2. ijkplayer 有跨平台和多 ABI 工程体系，ECHPlay 当前只覆盖 Android arm64-v8a。
3. ijkplayer 有 OpenGL ES、MediaCodec、OpenSL ES 等性能路径，ECHPlay 当前主要是 FFmpeg 软解 + RGBA + ANativeWindow + Java AudioTrack。
4. ijkplayer 的协议、格式、错误恢复、统计能力更完整，ECHPlay 当前重点验证本地 MP4 和 RTSP。
5. ijkplayer 的 API、配置项、播放器视图、日志和调试体系更成熟，ECHPlay v1.1 只是第一版 SDK 化。

结论：

后续版本不建议直接“复刻 ijkplayer”，而应该按工程化、渲染、硬解、网络协议、稳定性、发布能力逐步推进。

============================================================
二、能力差距清单
============================================================

差距 1：工程形态和发布能力

ijkplayer：

1. 拥有独立播放器库模块。
2. 支持 Gradle 依赖集成。
3. 按 ABI 提供 so 包。
4. 示例 App 和播放器库边界清晰。

ECHPlay v1.1：

1. 当前仍是单一 Android app module。
2. ECHPlayer、ECHPlayerView 和 Demo 在同一个 app 工程里。
3. 只配置 arm64-v8a。
4. 没有 AAR、Maven、本地发布脚本。

后续目标：

1. 拆出独立 `echplayer` Android library module。
2. app module 只保留 Demo。
3. 输出 Debug / Release AAR。
4. 支持 arm64-v8a、armeabi-v7a，后续再补 x86_64。

============================================================

差距 2：跨平台能力

ijkplayer：

1. 支持 Android。
2. 支持 iOS。

ECHPlay v1.1：

1. 只支持 Android。
2. NativePlayer 直接绑定 Android Surface、JNI、AudioTrack 回调。

后续目标：

1. 短期不做 iOS。
2. 先把 Native 核心拆成平台无关 core + Android adapter。
3. 为后续 iOS 或其他平台预留接口边界。

============================================================

差距 3：渲染链路

ijkplayer：

1. 支持 Android NativeWindow。
2. 支持 OpenGL ES 2.0。
3. 可以走更高效的纹理渲染路径。

ECHPlay v1.1：

1. FFmpeg 解码后通过 sws_scale 转 RGBA。
2. 使用 ANativeWindow lock / memcpy / unlockAndPost 渲染。
3. 每帧 RGBA 转换和拷贝成本较高。
4. 没有 TextureView。
5. 没有画面比例、旋转、镜像、填充模式等完整策略。

后续目标：

1. 新增 OpenGL ES 渲染器。
2. 支持 YUV420P 三纹理上传。
3. 支持 fitCenter、centerCrop、fill、原始比例。
4. ECHPlayerView 支持 SurfaceView / TextureView 可选。
5. 截图继续从解码帧获取，不依赖 Surface 截屏。

============================================================

差距 4：硬件解码

ijkplayer：

1. 支持 Android MediaCodec。
2. 可根据配置选择软解或硬解。
3. 有软硬解回退策略。

ECHPlay v1.1：

1. 当前只有 FFmpeg 软解。
2. 高分辨率、高码率视频容易 CPU 压力大。
3. 没有硬解失败回退。
4. 没有硬解相关错误码和诊断信息。

后续目标：

1. 增加 `setOption(..., "mediacodec", 1)` 风格开关。
2. 支持 H.264 MediaCodec 硬解。
3. 支持 H.265 MediaCodec 硬解。
4. 硬解失败自动回退软解。
5. UI 展示当前解码方式：software / mediacodec。

============================================================

差距 5：音频输出能力

ijkplayer：

1. 支持 AudioTrack。
2. 支持 OpenSL ES。
3. 音频输出链路更成熟。

ECHPlay v1.1：

1. 只通过 Java AudioTrack 输出 PCM。
2. 没有音量控制。
3. 没有静音。
4. 没有倍速。
5. 没有音频焦点管理。
6. 没有音轨选择。

后续目标：

1. 增加音量、静音、左右声道基础控制。
2. 增加 Android 音频焦点处理。
3. 评估 Native AudioTrack 或 Oboe / OpenSL ES 输出。
4. 后续支持倍速播放。

============================================================

差距 6：协议和格式兼容

ijkplayer：

1. 依托 FFmpeg 支持大量协议和封装格式。
2. 常见本地文件、HTTP、HTTPS、HLS、RTMP 等可以按 FFmpeg 配置扩展。

ECHPlay v1.1：

1. 已验证本地 MP4。
2. 已支持 RTSP。
3. 没有系统验证 HTTP / HTTPS。
4. 不承诺 HLS / m3u8。
5. 不支持 RTMP。
6. 没有协议能力探测和测试矩阵。

后续目标：

1. 增加 HTTP / HTTPS 播放验证。
2. 增加 HLS / m3u8 基础支持验证。
3. 增加协议能力表。
4. 增加测试样例清单。
5. RTMP 可作为 P2 或后续长期目标。

============================================================

差距 7：缓冲、弱网和重连

ijkplayer：

1. 网络播放经验更成熟。
2. 有更完整的缓冲和网络配置能力。

ECHPlay v1.1：

1. 有基础 BUFFERING_START / BUFFERING_END。
2. 有 RTSP timeout、rw_timeout、buffer_size、max_delay。
3. 缓冲百分比不准确。
4. 没有网速统计。
5. 没有自动重连。
6. 没有弱网恢复策略。

后续目标：

1. 增加下载/读取速度统计。
2. 增加缓冲百分比和队列长度回调。
3. 增加 RTSP 断流后自动重连。
4. 增加最大重连次数、重连间隔 option。
5. UI 展示网络状态和重连次数。

============================================================

差距 8：播放器 API 完整度

ijkplayer：

1. API 更接近成熟 MediaPlayer。
2. setOption 能覆盖更多类别和参数。
3. 支持更多状态、统计、元信息和控制能力。

ECHPlay v1.1：

1. 已有基础 MediaPlayer-like API。
2. setOption 当前主要支持 RTSP 和网络参数。
3. 缺少 metadata、track info、video size changed、seek mode、looping、playback speed。
4. prepareAsync 当前仍比较简单。

后续目标：

1. 增加 OnVideoSizeChangedListener。
2. 增加 OnSeekCompleteListener 独立回调。
3. 增加 getVideoWidth / getVideoHeight。
4. 增加 getMediaInfo / getTrackInfo。
5. 增加 setLooping / isLooping。
6. 增加 setPlaybackSpeed 的预留接口。
7. 完善 prepareAsync 线程模型。

============================================================

差距 9：字幕和多音轨

ijkplayer：

1. 可基于 FFmpeg 能力处理更多流类型。
2. 成熟播放器通常会支持字幕、音轨、码流信息展示。

ECHPlay v1.1：

1. 只处理一个最佳视频流。
2. 只处理一个最佳音频流。
3. 不处理字幕流。
4. 不支持音轨切换。

后续目标：

1. 先输出 track info。
2. 支持音轨列表展示。
3. 支持音轨切换。
4. 字幕作为后续版本目标，先支持外挂 SRT 或内嵌文本字幕。

============================================================

差距 10：性能统计和调试能力

ijkplayer：

1. 具备更成熟的日志和统计信息。
2. 能辅助定位首帧、卡顿、缓冲、解码耗时等问题。

ECHPlay v1.1：

1. UI 能显示错误码和 info。
2. 没有首开耗时统计。
3. 没有首帧耗时统计。
4. 没有 decode fps、render fps、drop frame 统计。
5. 没有 CPU / 内存层面的播放器诊断。

后续目标：

1. 增加首开耗时、prepare 耗时、首帧耗时。
2. 增加 decode fps、render fps、丢帧数。
3. 增加音视频同步偏差统计。
4. 增加 Debug 面板。
5. 增加日志级别 option。

============================================================

差距 11：PlayerView 成熟度

ijkplayer：

1. 有成熟 Demo 和播放器视图使用方式。
2. 可围绕播放器 API 构建完整控制体验。

ECHPlay v1.1：

1. ECHPlayerView 只是雏形。
2. 控制条只有播放、暂停、停止、截图、录制。
3. 没有进度条、全屏、加载、错误页、手势、封面、横竖屏适配。

后续目标：

1. PlayerView 增加进度条和时间显示。
2. 增加 loading / buffering / error UI。
3. 增加全屏切换。
4. 增加横竖屏生命周期验证。
5. 增加可隐藏控制条。

============================================================

差距 12：测试体系

ijkplayer：

1. 作为成熟项目，经过大量机型、协议、格式、网络环境验证。

ECHPlay v1.1：

1. 目前主要依赖手动测试清单。
2. 没有自动化单元测试。
3. 没有 Native 层测试。
4. 没有压力测试脚本。
5. 没有真机兼容性矩阵。

后续目标：

1. 增加 Java 层 API 单元测试。
2. 增加 NativePlayer 基础测试入口。
3. 增加 adb 自动化 smoke test。
4. 增加长时间 RTSP 播放测试。
5. 建立设备和协议兼容性表。

============================================================
三、后续版本迭代目标
============================================================

v1.2：工程化和 library module

目标：

1. 拆出独立 `echplayer` library module。
2. app module 只作为 Demo。
3. 输出 AAR。
4. 支持 arm64-v8a、armeabi-v7a。
5. README 增加 AAR 集成方式。

验收标准：

1. Demo app 能通过 library module 调用播放器。
2. `./gradlew :echplayer:assembleRelease` 能输出 AAR。
3. app 不直接依赖 native 实现细节。
4. arm64-v8a 和 armeabi-v7a 均能打包。

============================================================

v1.3：OpenGL 渲染和画面显示能力

目标：

1. 新增 OpenGL ES 渲染器。
2. 支持 YUV420P 三纹理渲染。
3. 保留 ANativeWindow RGBA 渲染作为 fallback。
4. PlayerView 支持画面比例模式。
5. 增加视频宽高变化回调。

验收标准：

1. 本地 MP4 可通过 OpenGL 渲染播放。
2. RTSP 可通过 OpenGL 渲染播放。
3. 截图仍保存解码帧 PNG。
4. UI 能切换 fitCenter / centerCrop。

============================================================

v1.4：MediaCodec 硬解

目标：

1. 增加 MediaCodec 硬解开关。
2. 支持 H.264 硬解。
3. 支持 H.265 硬解。
4. 硬解失败自动回退软解。
5. 增加当前解码方式回调。

验收标准：

1. H.264 MP4 可硬解播放。
2. H.265 MP4 可硬解播放。
3. 不支持硬解的流自动回退软解。
4. Demo 能显示 software / mediacodec。

============================================================

v1.5：网络协议、弱网和重连

目标：

1. 系统验证 HTTP / HTTPS 播放。
2. 验证 HLS / m3u8 基础播放。
3. 增加网速统计。
4. 增加自动重连。
5. 增加更完整的 buffering 百分比。

验收标准：

1. HTTP MP4 可播放、seek。
2. HTTPS MP4 可播放、seek。
3. HLS 可基础播放。
4. RTSP 断流后可按配置自动重连。
5. UI 能显示网速、缓冲、重连次数。

============================================================

v1.6：播放器 API 完整度和调试能力

目标：

1. 增加 getMediaInfo。
2. 增加 getTrackInfo。
3. 增加 getVideoWidth / getVideoHeight。
4. 增加 OnVideoSizeChangedListener。
5. 增加播放统计信息。
6. 增加 Debug 面板。

验收标准：

1. Demo 能展示媒体格式、视频尺寸、音频信息。
2. Demo 能展示首开耗时、首帧耗时、FPS、丢帧。
3. Debug 面板可开关。

============================================================

v1.7：PlayerView 体验增强

目标：

1. PlayerView 增加完整进度条。
2. 增加 loading、buffering、error UI。
3. 增加全屏切换。
4. 增加控制条自动隐藏。
5. 增加基础手势。

验收标准：

1. PlayerView 可作为普通业务页面播放器直接使用。
2. 本地、RTSP、HTTP 视频均能在 PlayerView 上操作。
3. 退出、旋转、全屏切换不崩溃。

============================================================

v1.8：测试体系和稳定性专项

目标：

1. 增加自动化 smoke test。
2. 增加连续 seek 压力测试。
3. 增加长时间 RTSP 播放测试。
4. 增加录制中停止、退出、断网测试。
5. 建立兼容性矩阵。

验收标准：

1. 每次发版前有固定测试报告。
2. 至少覆盖 3 台 Android 设备或模拟器。
3. 长时间播放 2 小时不崩溃。

============================================================
四、推荐优先级
============================================================

P0：下一阶段最应该先做

1. v1.2 library module。
2. 多 ABI。
3. OpenGL 渲染。
4. MediaCodec 硬解。

P1：播放器体验和稳定性关键项

1. HTTP / HTTPS / HLS。
2. 自动重连。
3. 网速和缓冲统计。
4. PlayerView 完整控制器。

P2：长期增强项

1. iOS。
2. 字幕。
3. 多音轨。
4. RTMP。
5. Maven 正式发布。

============================================================
五、当前最合理的下一步
============================================================

建议立即进入 v1.2：

1. 把播放器从 app module 拆成 library module。
2. 保证 Demo 通过 library module 使用播放器。
3. 输出 AAR。
4. 补 arm64-v8a 和 armeabi-v7a。

原因：

如果不先做工程化，后续 OpenGL、MediaCodec、协议扩展都会继续堆在 app Demo 里，越往后拆分成本越高。
