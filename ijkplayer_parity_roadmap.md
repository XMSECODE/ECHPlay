# ECHPlay 对齐 ijkplayer 功能路线规划

本文档用于规划 ECHPlay 后续版本目标。目标是参考本地 `ijkplayer/` 源码，把 ECHPlay 逐步建设成接近 ijkplayer 功能覆盖的播放器 SDK。

重要原则：

1. 功能对齐，不直接复制 ijkplayer 源码。
2. 保持代码简单易懂，每个阶段都可独立验收。
3. 优先补 Android 播放器核心能力，再考虑 iOS 和更复杂后端。
4. 所有新增函数、方法、类型、属性继续加中文注释。
5. 每个版本完成后需要提交代码，并用中英文提交描述。

参考源码：

1. `ijkplayer/android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/IMediaPlayer.java`
2. `ijkplayer/android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/IjkMediaPlayer.java`
3. `ijkplayer/android/ijkplayer/ijkplayer-example/src/main/java/tv/danmaku/ijk/media/example/widget/media/IjkVideoView.java`
4. `ijkplayer/ijkmedia/ijkplayer/ff_ffplay.c`
5. `ijkplayer/ijkmedia/ijkplayer/ff_ffplay_options.h`
6. `ijkplayer/ijkmedia/ijkplayer/ijkavformat/`
7. `ijkplayer/ijkmedia/ijksdl/`

============================================================
一、总体目标
============================================================

最终目标：

ECHPlay 需要从当前轻量 Android 播放器，升级为功能接近 ijkplayer 的播放器 SDK，覆盖以下能力：

1. MediaPlayer-like Java API。
2. 完整数据源输入：String、Uri、headers、FileDescriptor、自定义数据源。
3. 丰富 option / property 系统。
4. 本地、HTTP、HTTPS、HLS、RTSP、常见直播流播放。
5. 弱网缓冲、自动重连、缓存统计。
6. 音视频同步、seek flush、准确 seek。
7. 软解、MediaCodec 硬解和自动回退。
8. OpenGL / NativeWindow 渲染，多像素格式适配。
9. 音量、静音、倍速、音频焦点、Native 音频输出。
10. 多音轨、字幕、TimedText。
11. 多 ABI AAR 发布和 Demo 示例。
12. 可重复测试、兼容性矩阵、长时间稳定性验证。

============================================================
二、与 ijkplayer 的核心差距
============================================================

差距 1：API 面不完整

ijkplayer 有 `IMediaPlayer`、`IjkMediaPlayer`、`AndroidMediaPlayer`、`MediaPlayerProxy` 等接口层。ECHPlay 当前 API 更轻，缺少 Uri、headers、FileDescriptor、自定义数据源、音量、循环、倍速、TimedText、轨道选择等接口。

差距 2：option / property 系统不完整

ijkplayer 支持 `OPT_CATEGORY_FORMAT`、`OPT_CATEGORY_CODEC`、`OPT_CATEGORY_SWS`、`OPT_CATEGORY_PLAYER`，并暴露大量播放统计属性。ECHPlay 当前 option 主要覆盖 RTSP、重连、渲染、硬解等少量配置。

差距 3：协议和缓存层不完整

ijkplayer 有 `ijkavformat`，包含 async、cache、hook、androidio、segment、livehook 等协议扩展。ECHPlay 目前主要依赖 FFmpeg 原生输入，缺少独立缓存和协议 hook 能力。

差距 4：FFplay 风格播放状态机不完整

ijkplayer 的 `ff_ffplay.c` 有读线程、packet queue、frame queue、audio/video/subtitle clock、seek flush、缓冲水位、丢帧控制。ECHPlay 已有基础队列和线程，但还没有完整 frame queue 和精细缓冲状态机。

差距 5：硬解和渲染能力不完整

ijkplayer 支持 MediaCodec 选择器、自动旋转、分辨率变化、多 codec 开关、多 overlay format。ECHPlay 已有 H264/H265 MediaCodec 基础路径和 OpenGL YUV420P 渲染，但还缺更多像素格式、旋转、分辨率变化处理。

差距 6：音频输出能力不完整

ijkplayer 有 SDL audio 抽象，支持 AudioTrack 和 OpenSL ES。ECHPlay 当前主要是 Java AudioTrack 回调输出，缺少 Native 音频输出、倍速、静音、音频焦点完整策略。

差距 7：字幕和多轨不完整

ijkplayer 支持 track info、selectTrack、deselectTrack、TimedText。ECHPlay 目前能展示 track info，但还没有真正切换音轨、字幕轨和字幕渲染。

差距 8：工程和发布能力不完整

ijkplayer 有多 ABI 模块、示例工程、Android/iOS 工程。ECHPlay 当前主要覆盖 Android arm64-v8a，iOS 暂无，多 ABI 和发布能力还需要补齐。

============================================================
三、版本路线图
============================================================

v1.9：MediaPlayer-like API 对齐

目标：先把 Java 层 API 形态向 ijkplayer 靠拢，让上层接入方式更接近成熟播放器。

需求：

1. 新增 `ECHMediaPlayer` 或 `IECHMediaPlayer` 接口，参考 ijkplayer 的 `IMediaPlayer`。
2. 增加 `setDataSource(Context, Uri)`。
3. 增加 `setDataSource(Context, Uri, Map<String, String> headers)`。
4. 增加 `setDataSource(FileDescriptor fd)`。
5. 增加 `setDisplay(SurfaceHolder holder)`。
6. 保留 `setSurface(Surface surface)`。
7. 增加 `setVolume(float left, float right)`。
8. 增加 `setLooping(boolean looping)` 和 `isLooping()`。
9. 增加 `OnSeekCompleteListener`。
10. 增加 `OnTimedTextListener` 空实现接口，先占位不渲染。

验收标准：

1. Demo 可以用新接口播放本地文件和网络地址。
2. 旧 `ECHPlayer` 用法保持兼容。
3. 新增 API 均有中文注释。
4. smoke check 通过。

建议提交：

`对齐MediaPlayer基础API / align basic MediaPlayer API`

------------------------------------------------------------

v2.0：option / property / metadata 系统

目标：建立类似 ijkplayer 的配置和统计入口，后续功能都能通过统一 option 和 property 管理。

需求：

1. 补齐 option category：format、codec、sws、player。
2. 统一保存 option，区分 prepare 前生效和运行时生效。
3. 增加 property 读取入口：`getPropertyLong`、`getPropertyFloat`。
4. 暴露视频解码 FPS、输出 FPS、丢帧数、读取字节数、码率、TCP 速度。
5. 增加 `getMediaMeta()`，返回格式、时长、码率、流信息。
6. 增加 `getVideoDecoder()` 和 `getAudioDecoder()`。
7. Demo 增加“统计信息”页面或弹窗。

验收标准：

1. 与 ijkplayer 常用 property 名称保持可映射。
2. 播放过程中统计值会更新。
3. option 未支持时明确返回 false 或错误信息。
4. 文档列出已支持和待支持 option。

建议提交：

`新增播放器配置和属性系统 / add player option and property system`

------------------------------------------------------------

v2.1：数据源和协议能力增强

目标：补齐 ijkplayer 常见数据源能力，并提升 HTTP / HTTPS / HLS / RTSP 兼容性。

需求：

1. 支持 headers 注入，映射到 FFmpeg `headers` option。
2. 支持 protocol whitelist 配置。
3. 支持 Android `content://` Uri，必要时转 FileDescriptor。
4. 支持 FileDescriptor 播放。
5. 增加自定义数据源接口，参考 ijkplayer `IMediaDataSource`。
6. 验证 HTTP Range、HTTPS、HLS VOD、HLS Live。
7. 增加 RTSP 常用参数：stimeout、rw_timeout、buffer_size、max_delay、user_agent。
8. 更新 `compatibility_matrix.md`。

验收标准：

1. 本地文件、HTTP、HTTPS、HLS、RTSP 都有测试记录。
2. headers 能通过 Demo 输入并生效。
3. `content://` 能播放系统文件选择器返回的视频。
4. 协议失败时错误信息可定位。

建议提交：

`增强数据源和协议兼容 / improve data source and protocol compatibility`

------------------------------------------------------------

v2.2：缓冲、队列和 seek 状态机

目标：向 ijkplayer 的 FFplay 播放内核靠拢，提升弱网、seek、长时间播放稳定性。

需求：

1. 引入更清晰的 read thread、audio decode thread、video decode thread 职责边界。
2. 增加 frame queue，避免解码和渲染强耦合。
3. 增加 audio clock、video clock、external clock 结构。
4. 完善 seek flush packet 机制。
5. 增加 accurate seek option。
6. 增加缓冲水位：最小帧数、最大队列大小、高低水位。
7. 增加 BUFFERING_START / BUFFERING_END 的准确触发。
8. 增加弱网自动恢复和重连后的状态恢复。

验收标准：

1. seek 后音视频能继续同步。
2. 弱网卡顿时能进入缓冲状态，恢复后退出缓冲状态。
3. 长时间播放不出现队列无限增长。
4. 本地和网络流都能通过基础回归。

建议提交：

`重构缓冲队列和seek状态机 / refactor buffering queue and seek state machine`

------------------------------------------------------------

v2.3：MediaCodec 和渲染能力对齐

目标：补齐 ijkplayer 常用硬解和渲染配置，提高性能和设备兼容性。

需求：

1. 增加 MediaCodec selector，允许选择或屏蔽具体 decoder。
2. 支持 `mediacodec-auto-rotate`。
3. 支持 `mediacodec-handle-resolution-change`。
4. 补齐 H264、H265、MPEG4、MPEG2 的硬解开关。
5. 增加 YUV420SP、NV12、NV21、RGB 等像素格式适配。
6. OpenGL 渲染支持更多 shader 路径。
7. 增加 SurfaceView / TextureView 可选渲染组件。
8. 截图继续使用解码后数据，不使用 Surface 截屏。

验收标准：

1. 常见 H264 / H265 文件能硬解播放。
2. 硬解失败能自动回退软解。
3. 分辨率变化流不崩溃。
4. 旋转 metadata 能正确显示。
5. Demo 能显示当前渲染模式和解码器名称。

建议提交：

`增强MediaCodec和渲染兼容 / improve MediaCodec and rendering compatibility`

------------------------------------------------------------

v2.4：音频系统对齐

目标：补齐 ijkplayer 音频输出能力，支持音量、静音、倍速和更稳定的 Native 输出。

需求：

1. 增加左/右声道音量控制。
2. 增加静音开关。
3. 增加 Android 音频焦点管理。
4. 增加 Native AudioTrack 输出路径。
5. 评估并实现 OpenSL ES 或 AAudio 输出路径。
6. 增加倍速播放接口 `setSpeed(float speed)`。
7. 引入 SoundTouch 或等价方案处理变速不变调。
8. 增加音频延迟统计。

验收标准：

1. 音量、静音、焦点抢占恢复可验证。
2. 0.5x、1.0x、1.5x、2.0x 倍速可播放。
3. 倍速播放时音视频同步可接受。
4. Native 输出和 Java AudioTrack 至少保留一种稳定路径。

建议提交：

`完善音频输出和倍速能力 / improve audio output and playback speed`

------------------------------------------------------------

v2.5：多轨、字幕和 TimedText

目标：补齐 ijkplayer 的 track 和 timed text 能力。

需求：

1. 完善 `getTrackInfo()`，区分 video、audio、subtitle、metadata。
2. 实现 `selectTrack(int track)`。
3. 实现 `deselectTrack(int track)`。
4. 支持多音轨切换。
5. 支持内嵌字幕轨解码。
6. 支持外挂 SRT 字幕。
7. 增加 `OnTimedTextListener` 回调真实字幕内容。
8. `ECHPlayerView` 增加字幕显示层。

验收标准：

1. 多音轨文件可以切换音轨。
2. 内嵌字幕可以显示。
3. 外挂 SRT 可以显示。
4. 字幕开关和 track 切换不会导致播放崩溃。

建议提交：

`新增多轨和字幕能力 / add multi-track and subtitle support`

------------------------------------------------------------

v2.6：缓存、统计和 Native invoke

目标：补齐 ijkplayer 网络缓存、统计和 Native 事件扩展能力。

需求：

1. 增加 HTTP 本地缓存策略。
2. 增加 cache file forwards、file pos、physical pos、count bytes 等统计。
3. 增加 TCP speed 和 traffic byte count 更准确计算。
4. 增加 latest seek load duration。
5. 增加 Native invoke listener，用于协议解析、segment 切换、重连事件。
6. 增加 immediate reconnect 能力。
7. Demo 展示缓存和网络统计。

验收标准：

1. HTTP VOD 第二次播放可复用缓存或明确命中缓存。
2. 缓存统计值在播放时变化合理。
3. Native invoke 事件能在 Java 层收到。
4. 重连事件可观察、可配置。

建议提交：

`新增缓存统计和Native事件 / add cache statistics and native events`

------------------------------------------------------------

v2.7：多 ABI、发布和示例工程对齐

目标：把 ECHPlay 做成更完整的 Android SDK 发布形态。

需求：

1. 补齐 `armeabi-v7a`。
2. 补齐 `x86_64`，方便模拟器测试。
3. 评估是否需要 `x86`。
4. AAR 包含正确 ABI so。
5. 增加 Maven Local 发布脚本。
6. 增加 GitHub Release 发版说明模板。
7. Demo 增加 ijkplayer-like 设置页。
8. README 增加完整接入文档。
9. 明确 FFmpeg、ijkplayer 参考源码、第三方库 license。

验收标准：

1. arm64-v8a、armeabi-v7a、x86_64 都能构建产物。
2. Demo 在真机和模拟器都能安装启动。
3. AAR 可被外部空项目依赖。
4. release checklist 完整覆盖发版流程。

建议提交：

`完善多ABI和SDK发布 / complete multi ABI and SDK release`

------------------------------------------------------------

v2.8：测试矩阵和稳定性追平

目标：建立接近成熟播放器项目的自动化验证体系。

需求：

1. 扩展 smoke check，覆盖 API、AAR、APK、ABI、基础播放入口。
2. 增加本地 MP4 自动播放测试。
3. 增加 HTTP / HLS / RTSP 自动或半自动测试入口。
4. 增加截图和录制自动验证。
5. 增加 seek、pause、resume、stop、reset 压力测试。
6. 增加 2 小时长播测试记录模板。
7. 增加崩溃日志、native tombstone、logcat 收集脚本。
8. 更新兼容性矩阵为版本化表格。

验收标准：

1. 发版前可以一键执行大部分回归。
2. 每个协议都有明确测试结果。
3. 长播和弱网测试有记录。
4. 崩溃问题可以快速定位到日志文件。

建议提交：

`完善播放器测试矩阵 / complete player test matrix`

------------------------------------------------------------

v2.9：可选后端和跨平台预研

目标：补齐 ijkplayer 工程层面的可选后端和跨平台方向，但不阻塞 Android 主线。

需求：

1. 评估 Android MediaPlayer fallback。
2. 评估 ExoPlayer backend，参考 ijkplayer 的 `ijkplayer-exo` 模块。
3. 抽象 PlayerBackend 接口。
4. 抽象平台无关 core 和 Android adapter 边界。
5. 预研 iOS 可行性，不直接承诺完整实现。
6. 输出 iOS / ExoPlayer / Android MediaPlayer 后端决策文档。

验收标准：

1. Android 主播放器不被后端抽象破坏。
2. 至少完成后端接口设计和一个 fallback 原型。
3. iOS 是否进入后续版本有明确结论。

建议提交：

`预研可选后端和跨平台 / research optional backends and cross platform`

============================================================
四、优先级建议
============================================================

必须优先完成：

1. v1.9 API 对齐。
2. v2.0 option / property 系统。
3. v2.1 数据源和协议增强。
4. v2.2 缓冲、队列和 seek 状态机。
5. v2.3 MediaCodec 和渲染能力。

原因：这些能力决定播放器是否真正接近 ijkplayer 的核心使用体验。

中优先级：

1. v2.4 音频系统。
2. v2.5 多轨和字幕。
3. v2.6 缓存统计和 Native 事件。

原因：这些能力会明显提升专业播放器能力，但实现复杂度较高，可以在核心稳定后推进。

长期优先级：

1. v2.7 多 ABI 和 SDK 发布。
2. v2.8 测试矩阵和稳定性追平。
3. v2.9 可选后端和跨平台预研。

原因：这些决定工程成熟度和长期可维护性，不建议在核心播放链路未稳定前过早铺开。

============================================================
五、完成定义
============================================================

当 ECHPlay 可以认为“功能接近 ijkplayer”时，需要满足：

1. Java API 覆盖 ijkplayer 常用 `IMediaPlayer` 使用方式。
2. option / property 覆盖 ijkplayer 常用配置和统计。
3. 本地、HTTP、HTTPS、HLS、RTSP 播放均有验证。
4. 支持软解、MediaCodec 硬解、硬解失败回退。
5. 支持 OpenGL 渲染、NativeWindow 兼容渲染、多像素格式。
6. 支持音量、静音、倍速、音频焦点。
7. 支持多音轨、字幕和 TimedText。
8. 支持缓存、弱网缓冲、自动重连和网络统计。
9. 支持 arm64-v8a、armeabi-v7a、x86_64。
10. 有 Demo、README、release checklist、compatibility matrix 和自动化测试脚本。

============================================================
六、风险和边界
============================================================

1. ijkplayer 是 LGPL 项目，后续只能做功能参考，不能无脑复制源码。
2. “完全一样”成本很高，尤其是 iOS、ExoPlayer 后端、协议 hook、缓存体系。
3. ECHPlay 应保持简单可懂，不能为了追平 ijkplayer 把代码一次性复杂化。
4. 每个版本都必须能编译、能运行、能验收。
5. 如果某项功能依赖第三方库，例如 SoundTouch、OpenSL ES、ExoPlayer，需要单独评估 license 和包体积。

