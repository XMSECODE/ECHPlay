package com.echplay.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Java 层播放器封装，负责桥接 UI 与 NativePlayer。
 */
public class ECHPlayer implements IECHMediaPlayer, AutoCloseable {

    /**
     * 播放器状态机，命名对齐 Android MediaPlayer 的常见生命周期。
     */
    public enum State {
        /** 初始状态，可设置数据源。 */
        IDLE,
        /** 已设置数据源。 */
        INITIALIZED,
        /** 正在 prepare。 */
        PREPARING,
        /** 已 prepare 成功。 */
        PREPARED,
        /** 播放中。 */
        STARTED,
        /** 暂停中。 */
        PAUSED,
        /** 已停止。 */
        STOPPED,
        /** 正在 seek。 */
        SEEKING,
        /** 播放完成。 */
        COMPLETED,
        /** 出错。 */
        ERROR,
        /** 已释放。 */
        RELEASED
    }

    /**
     * 录制状态机，用于让外部页面明确知道当前录制阶段。
     */
    public enum RecordingState {
        /** 当前没有录制任务。 */
        IDLE,
        /** 正在录制。 */
        RECORDING,
        /** 正在停止录制。 */
        STOPPING,
        /** 录制失败。 */
        FAILED
    }

    /**
     * 截图结果，保存 PNG 路径、尺寸和截图时间戳。
     */
    public static class CaptureResult {
        /** PNG 文件绝对路径。 */
        public final String filePath;
        /** 截图宽度。 */
        public final int width;
        /** 截图高度。 */
        public final int height;
        /** 截图创建时间戳，单位毫秒。 */
        public final long timestampMs;

        /** 创建截图结果对象。 */
        public CaptureResult(String filePath, int width, int height, long timestampMs) {
            this.filePath = filePath;
            this.width = width;
            this.height = height;
            this.timestampMs = timestampMs;
        }
    }

    /**
     * 播放统计快照，用于 Demo 或业务侧观察网络读取、缓冲队列和解码速度。
     */
    public static class PlaybackStats {
        /** 累计读取字节数。 */
        public final long readBytes;
        /** 当前读取速度，单位字节/秒。 */
        public final long readSpeedBytesPerSecond;
        /** 视频 packet 队列长度。 */
        public final int videoPacketQueueSize;
        /** 音频 packet 队列长度。 */
        public final int audioPacketQueueSize;
        /** 缓冲百分比估算值。 */
        public final int bufferedPercent;
        /** 平均视频解码帧率。 */
        public final double decodeFps;
        /** 平均视频渲染帧率。 */
        public final double renderFps;
        /** 累计解码视频帧数。 */
        public final long decodedFrameCount;
        /** 累计渲染视频帧数。 */
        public final long renderedFrameCount;
        /** 累计主动丢弃视频帧数。 */
        public final long droppedFrameCount;
        /** 最近一次 prepare 耗时，单位毫秒。 */
        public final long prepareCostMs;
        /** 最近一次 start 到首帧耗时，单位毫秒。 */
        public final long firstFrameCostMs;
        /** 统计采样时间戳，单位毫秒。 */
        public final long timestampMs;

        /** 创建播放统计快照。 */
        public PlaybackStats(
                long readBytes,
                long readSpeedBytesPerSecond,
                int videoPacketQueueSize,
                int audioPacketQueueSize,
                int bufferedPercent,
                double decodeFps,
                double renderFps,
                long decodedFrameCount,
                long renderedFrameCount,
                long droppedFrameCount,
                long prepareCostMs,
                long firstFrameCostMs,
                long timestampMs) {

            this.readBytes = readBytes;
            this.readSpeedBytesPerSecond = readSpeedBytesPerSecond;
            this.videoPacketQueueSize = videoPacketQueueSize;
            this.audioPacketQueueSize = audioPacketQueueSize;
            this.bufferedPercent = bufferedPercent;
            this.decodeFps = decodeFps;
            this.renderFps = renderFps;
            this.decodedFrameCount = decodedFrameCount;
            this.renderedFrameCount = renderedFrameCount;
            this.droppedFrameCount = droppedFrameCount;
            this.prepareCostMs = prepareCostMs;
            this.firstFrameCostMs = firstFrameCostMs;
            this.timestampMs = timestampMs;
        }
    }

    /**
     * 媒体信息快照，用于展示容器、时长、码率和最佳音视频流摘要。
     */
    public static class MediaInfo {
        /** 封装格式名称。 */
        public final String format;
        /** 媒体总时长，单位毫秒。 */
        public final long durationMs;
        /** 总码率，单位 bit/s。 */
        public final long bitRate;
        /** 视频流索引。 */
        public final int videoStreamIndex;
        /** 音频流索引。 */
        public final int audioStreamIndex;
        /** 视频编码名。 */
        public final String videoCodec;
        /** 视频宽度。 */
        public final int videoWidth;
        /** 视频高度。 */
        public final int videoHeight;
        /** 音频编码名。 */
        public final String audioCodec;
        /** 音频采样率。 */
        public final int audioSampleRate;
        /** 音频声道数。 */
        public final int audioChannels;

        /** 创建媒体信息快照。 */
        public MediaInfo(
                String format,
                long durationMs,
                long bitRate,
                int videoStreamIndex,
                int audioStreamIndex,
                String videoCodec,
                int videoWidth,
                int videoHeight,
                String audioCodec,
                int audioSampleRate,
                int audioChannels) {

            this.format = format;
            this.durationMs = durationMs;
            this.bitRate = bitRate;
            this.videoStreamIndex = videoStreamIndex;
            this.audioStreamIndex = audioStreamIndex;
            this.videoCodec = videoCodec;
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
            this.audioCodec = audioCodec;
            this.audioSampleRate = audioSampleRate;
            this.audioChannels = audioChannels;
        }
    }

    /**
     * 媒体元信息快照，用于对齐 ijkplayer 的 media meta 概念。
     */
    public static class MediaMeta {
        /** 封装格式名称。 */
        public final String format;
        /** 媒体总时长，单位毫秒。 */
        public final long durationMs;
        /** 总码率，单位 bit/s。 */
        public final long bitRate;
        /** 视频流索引。 */
        public final int videoStreamIndex;
        /** 音频流索引。 */
        public final int audioStreamIndex;
        /** 视频编码名。 */
        public final String videoCodec;
        /** 视频宽度。 */
        public final int videoWidth;
        /** 视频高度。 */
        public final int videoHeight;
        /** 音频编码名。 */
        public final String audioCodec;
        /** 音频采样率。 */
        public final int audioSampleRate;
        /** 音频声道数。 */
        public final int audioChannels;
        /** 当前轨道数量。 */
        public final int trackCount;
        /** 展平后的元信息键值表。 */
        public final Map<String, String> metadata;

        /** 创建媒体元信息快照。 */
        public MediaMeta(
                String format,
                long durationMs,
                long bitRate,
                int videoStreamIndex,
                int audioStreamIndex,
                String videoCodec,
                int videoWidth,
                int videoHeight,
                String audioCodec,
                int audioSampleRate,
                int audioChannels,
                int trackCount,
                Map<String, String> metadata) {

            this.format = format;
            this.durationMs = durationMs;
            this.bitRate = bitRate;
            this.videoStreamIndex = videoStreamIndex;
            this.audioStreamIndex = audioStreamIndex;
            this.videoCodec = videoCodec;
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
            this.audioCodec = audioCodec;
            this.audioSampleRate = audioSampleRate;
            this.audioChannels = audioChannels;
            this.trackCount = trackCount;
            this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
    }

    /**
     * 轨道信息快照，用于展示 FFmpeg 读取到的每条 stream。
     */
    public static class TrackInfo {
        /** 轨道类型：video、audio、subtitle 或 other。 */
        public final String type;
        /** FFmpeg stream index。 */
        public final int streamIndex;
        /** 编码名。 */
        public final String codec;
        /** 语言标签，可能为空。 */
        public final String language;
        /** 视频宽度。 */
        public final int width;
        /** 视频高度。 */
        public final int height;
        /** 音频采样率。 */
        public final int sampleRate;
        /** 音频声道数。 */
        public final int channels;

        /** 创建轨道信息快照。 */
        public TrackInfo(
                String type,
                int streamIndex,
                String codec,
                String language,
                int width,
                int height,
                int sampleRate,
                int channels) {

            this.type = type;
            this.streamIndex = streamIndex;
            this.codec = codec;
            this.language = language;
            this.width = width;
            this.height = height;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    /** 外挂字幕片段。 */
    private static class SubtitleCue {
        /** 开始显示时间，单位毫秒。 */
        final long startMs;
        /** 结束显示时间，单位毫秒。 */
        final long endMs;
        /** 字幕文本。 */
        final String text;

        /** 创建外挂字幕片段。 */
        SubtitleCue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text == null ? "" : text;
        }
    }

    /** 打开输入失败。 */
    public static final int ERROR_OPEN_INPUT_FAILED = 1001;
    /** 读取流信息失败。 */
    public static final int ERROR_STREAM_INFO_FAILED = 1002;
    /** 没有视频流。 */
    public static final int ERROR_NO_VIDEO_STREAM = 1003;
    /** 解码器打开失败。 */
    public static final int ERROR_DECODER_OPEN_FAILED = 1004;
    /** 网络超时。 */
    public static final int ERROR_NETWORK_TIMEOUT = 1005;
    /** RTSP 鉴权失败。 */
    public static final int ERROR_RTSP_AUTH_FAILED = 1006;
    /** 渲染 Surface 无效。 */
    public static final int ERROR_RENDER_SURFACE_INVALID = 1007;
    /** 录制失败。 */
    public static final int ERROR_RECORD_FAILED = 1008;
    /** 非法状态。 */
    public static final int ERROR_INVALID_STATE = 1009;
    /** 当前媒体流不支持 seek。 */
    public static final int ERROR_STREAM_NOT_SEEKABLE = 1010;
    /** 未知错误。 */
    public static final int ERROR_UNKNOWN = 1999;

    /** prepare 开始。 */
    public static final int INFO_PREPARE_STARTED = 2001;
    /** prepare 成功。 */
    public static final int INFO_PREPARED = 2002;
    /** 播放开始。 */
    public static final int INFO_PLAY_STARTED = 2003;
    /** seek 完成。 */
    public static final int INFO_SEEK_COMPLETE = 2004;
    /** 录制开始。 */
    public static final int INFO_RECORDING_START = 2005;
    /** 录制结束。 */
    public static final int INFO_RECORDING_END = 2006;
    /** 暂停。 */
    public static final int INFO_PAUSED = 2007;
    /** 停止。 */
    public static final int INFO_STOPPED = 2008;
    /** 首帧视频开始渲染。 */
    public static final int INFO_VIDEO_RENDERING_START = 2009;
    /** 音频开始输出。 */
    public static final int INFO_AUDIO_RENDERING_START = 2010;
    /** 缓冲开始。 */
    public static final int INFO_BUFFERING_START = 2011;
    /** 缓冲结束。 */
    public static final int INFO_BUFFERING_END = 2012;
    /** 当前实际解码方式发生变化。 */
    public static final int INFO_DECODE_MODE_CHANGED = 2013;
    /** MediaCodec 硬解打开成功。 */
    public static final int INFO_MEDIACODEC_OPENED = 2014;
    /** MediaCodec 硬解失败并回退软解。 */
    public static final int INFO_MEDIACODEC_FALLBACK = 2015;
    /** 当前流或设备不支持 MediaCodec 硬解。 */
    public static final int INFO_MEDIACODEC_UNSUPPORTED = 2016;
    /** 正在自动重连。 */
    public static final int INFO_RECONNECTING = 2017;
    /** 自动重连成功。 */
    public static final int INFO_RECONNECTED = 2018;
    /** 自动重连失败。 */
    public static final int INFO_RECONNECT_FAILED = 2019;
    /** MediaCodec 输出格式发生变化。 */
    public static final int INFO_MEDIACODEC_FORMAT_CHANGED = 2020;

    /** prepare 完成监听器。 */
    public interface OnPreparedListener {
        /** prepare 成功时回调。 */
        void onPrepared(ECHPlayer player);
    }

    /** 播放完成监听器。 */
    public interface OnCompletionListener {
        /** 播放完成时回调。 */
        void onCompletion(ECHPlayer player);
    }

    /** 错误监听器。 */
    public interface OnErrorListener {
        /** 出错时回调，返回 true 表示错误已处理。 */
        boolean onError(ECHPlayer player, int errorCode, String message);
    }

    /** 信息监听器。 */
    public interface OnInfoListener {
        /** 播放器信息回调，返回 true 表示信息已处理。 */
        boolean onInfo(ECHPlayer player, int infoCode, String message);
    }

    /** 缓冲进度监听器。 */
    public interface OnBufferingUpdateListener {
        /** 缓冲进度更新时回调。 */
        void onBufferingUpdate(ECHPlayer player, int percent);
    }

    /** 视频尺寸变化监听器。 */
    public interface OnVideoSizeChangedListener {
        /** 视频宽高首次可用或发生变化时回调。 */
        void onVideoSizeChanged(ECHPlayer player, int width, int height);
    }

    /** seek 完成监听器。 */
    public interface OnSeekCompleteListener {
        /** seek 成功完成时回调。 */
        void onSeekComplete(ECHPlayer player);
    }

    /** 字幕文本监听器，v1.9 先提供接口占位，真实字幕解码在后续版本补齐。 */
    public interface OnTimedTextListener {
        /** 收到字幕文本时回调。 */
        void onTimedText(ECHPlayer player, ECHTimedText text);
    }

    /** MediaCodec 选择监听器，用于允许业务屏蔽具体硬解路径。 */
    public interface OnMediaCodecSelectListener {
        /** 返回 true 表示允许该 codec 走 MediaCodec，false 表示强制回退软解。 */
        boolean onMediaCodecSelect(ECHPlayer player, String mimeType, String codecName);
    }

    /** RTSP 走 TCP 传输。 */
    public static final int RTSP_TRANSPORT_TCP = 0;
    /** RTSP 走 UDP 传输。 */
    public static final int RTSP_TRANSPORT_UDP = 1;
    /** 解码模式：自动选择，后续优先硬解，失败回退软解。 */
    public static final int DECODE_MODE_AUTO = 0;
    /** 解码模式：强制使用 FFmpeg 软件解码。 */
    public static final int DECODE_MODE_SOFTWARE = 1;
    /** 解码模式：优先使用 MediaCodec 硬解，失败回退软解。 */
    public static final int DECODE_MODE_MEDIACODEC = 2;
    /** Surface 渲染保持比例居中。 */
    public static final int SURFACE_SCALE_TYPE_FIT_CENTER = 0;
    /** Surface 渲染拉伸填满。 */
    public static final int SURFACE_SCALE_TYPE_FILL = 1;
    /** 渲染模式：自动选择，优先 OpenGL，失败回退 NativeWindow。 */
    public static final int RENDER_MODE_AUTO = 0;
    /** 渲染模式：强制使用 OpenGL。 */
    public static final int RENDER_MODE_OPENGL = 1;
    /** 渲染模式：强制使用 NativeWindow。 */
    public static final int RENDER_MODE_NATIVE_WINDOW = 2;
    /** FFmpeg format 层 option 分类。 */
    public static final int OPTION_CATEGORY_FORMAT = 1;
    /** FFmpeg codec 层 option 分类。 */
    public static final int OPTION_CATEGORY_CODEC = 2;
    /** FFmpeg swscale 层 option 分类。 */
    public static final int OPTION_CATEGORY_SWS = 3;
    /** 播放器自身 option 分类。 */
    public static final int OPTION_CATEGORY_PLAYER = 4;
    /** 平均视频解码 FPS 属性。 */
    public static final int PROP_FLOAT_VIDEO_DECODE_FRAMES_PER_SECOND = 10001;
    /** 平均视频输出 FPS 属性。 */
    public static final int PROP_FLOAT_VIDEO_OUTPUT_FRAMES_PER_SECOND = 10002;
    /** 播放速率属性，v2.0 暂固定为 1。 */
    public static final int PROP_FLOAT_PLAYBACK_RATE = 10003;
    /** 丢帧率属性。 */
    public static final int PROP_FLOAT_DROP_FRAME_RATE = 10007;
    /** 音频延迟属性，单位毫秒。 */
    public static final int PROP_FLOAT_AUDIO_DELAY = 10008;
    /** 当前选中视频流属性。 */
    public static final int PROP_INT64_SELECTED_VIDEO_STREAM = 20001;
    /** 当前选中音频流属性。 */
    public static final int PROP_INT64_SELECTED_AUDIO_STREAM = 20002;
    /** 当前视频解码器类型属性。 */
    public static final int PROP_INT64_VIDEO_DECODER = 20003;
    /** 当前音频解码器类型属性。 */
    public static final int PROP_INT64_AUDIO_DECODER = 20004;
    /** 视频缓存时长属性，单位毫秒。 */
    public static final int PROP_INT64_VIDEO_CACHED_DURATION = 20005;
    /** 音频缓存时长属性，单位毫秒。 */
    public static final int PROP_INT64_AUDIO_CACHED_DURATION = 20006;
    /** 视频缓存字节数属性，v2.0 暂按 0 返回。 */
    public static final int PROP_INT64_VIDEO_CACHED_BYTES = 20007;
    /** 音频缓存字节数属性，v2.0 暂按 0 返回。 */
    public static final int PROP_INT64_AUDIO_CACHED_BYTES = 20008;
    /** 视频缓存 packet 数属性。 */
    public static final int PROP_INT64_VIDEO_CACHED_PACKETS = 20009;
    /** 音频缓存 packet 数属性。 */
    public static final int PROP_INT64_AUDIO_CACHED_PACKETS = 20010;
    /** 当前选中字幕流属性，v2.0 暂返回 -1。 */
    public static final int PROP_INT64_SELECTED_TIMEDTEXT_STREAM = 20011;
    /** 总码率属性，单位 bit/s。 */
    public static final int PROP_INT64_BIT_RATE = 20100;
    /** TCP / 网络读取速度属性，单位字节/秒。 */
    public static final int PROP_INT64_TCP_SPEED = 20200;
    /** 异步缓存向后统计属性，v2.0 暂返回默认值。 */
    public static final int PROP_INT64_ASYNC_STATISTIC_BUF_BACKWARDS = 20201;
    /** 异步缓存向前统计属性，v2.0 暂返回默认值。 */
    public static final int PROP_INT64_ASYNC_STATISTIC_BUF_FORWARDS = 20202;
    /** 异步缓存容量属性，v2.0 暂返回默认值。 */
    public static final int PROP_INT64_ASYNC_STATISTIC_BUF_CAPACITY = 20203;
    /** 累计网络读取字节数属性。 */
    public static final int PROP_INT64_TRAFFIC_STATISTIC_BYTE_COUNT = 20204;
    /** 最近一次 seek 耗时属性，单位毫秒。 */
    public static final int PROP_INT64_LATEST_SEEK_LOAD_DURATION = 20300;
    /** 解码器未知。 */
    public static final int PROP_DECODER_UNKNOWN = 0;
    /** FFmpeg 软件解码器。 */
    public static final int PROP_DECODER_AVCODEC = 1;
    /** Android MediaCodec 硬解码器。 */
    public static final int PROP_DECODER_MEDIACODEC = 2;
    /** RTSP 传输方式 option 名称。 */
    public static final String OPTION_RTSP_TRANSPORT = "rtsp_transport";
    /** HTTP headers option 名称。 */
    public static final String OPTION_HEADERS = "headers";
    /** 协议白名单 option 名称。 */
    public static final String OPTION_PROTOCOL_WHITELIST = "protocol_whitelist";
    /** User-Agent option 名称。 */
    public static final String OPTION_USER_AGENT = "user_agent";
    /** 渲染模式 option 名称。 */
    public static final String OPTION_RENDER_MODE = "render_mode";
    /** 解码模式 option 名称。 */
    public static final String OPTION_DECODE_MODE = "decode_mode";
    /** ijkplayer 风格 MediaCodec 开关 option 名称。 */
    public static final String OPTION_MEDIACODEC = "mediacodec";
    /** MediaCodec 自动旋转 option 名称。 */
    public static final String OPTION_MEDIACODEC_AUTO_ROTATE = "mediacodec-auto-rotate";
    /** MediaCodec 分辨率变化处理 option 名称。 */
    public static final String OPTION_MEDIACODEC_HANDLE_RESOLUTION_CHANGE =
            "mediacodec-handle-resolution-change";
    /** H.264 MediaCodec 开关 option 名称。 */
    public static final String OPTION_MEDIACODEC_AVC = "mediacodec-avc";
    /** H.265 MediaCodec 开关 option 名称。 */
    public static final String OPTION_MEDIACODEC_HEVC = "mediacodec-hevc";
    /** MPEG4 MediaCodec 预留开关 option 名称。 */
    public static final String OPTION_MEDIACODEC_MPEG4 = "mediacodec-mpeg4";
    /** MPEG2 MediaCodec 预留开关 option 名称。 */
    public static final String OPTION_MEDIACODEC_MPEG2 = "mediacodec-mpeg2";
    /** RTSP TCP option 值。 */
    public static final String OPTION_VALUE_TCP = "tcp";
    /** RTSP UDP option 值。 */
    public static final String OPTION_VALUE_UDP = "udp";
    /** 渲染模式 AUTO option 值。 */
    public static final String OPTION_VALUE_RENDER_AUTO = "auto";
    /** 渲染模式 OPENGL option 值。 */
    public static final String OPTION_VALUE_RENDER_OPENGL = "opengl";
    /** 渲染模式 NativeWindow option 值。 */
    public static final String OPTION_VALUE_RENDER_NATIVE_WINDOW = "native_window";
    /** 解码模式 AUTO option 值。 */
    public static final String OPTION_VALUE_DECODE_AUTO = "auto";
    /** 解码模式 SOFTWARE option 值。 */
    public static final String OPTION_VALUE_DECODE_SOFTWARE = "software";
    /** 解码模式 MEDIACODEC option 值。 */
    public static final String OPTION_VALUE_DECODE_MEDIACODEC = "mediacodec";
    /** 打开输入超时时间 option 名称，单位微秒。 */
    public static final String OPTION_TIMEOUT = "timeout";
    /** RTSP 建连超时时间 option 名称，单位微秒，兼容部分 FFmpeg 构建。 */
    public static final String OPTION_STIMEOUT = "stimeout";
    /** 网络读取超时时间 option 名称，单位微秒。 */
    public static final String OPTION_RW_TIMEOUT = "rw_timeout";
    /** 网络输入缓冲大小 option 名称，单位字节。 */
    public static final String OPTION_BUFFER_SIZE = "buffer_size";
    /** RTSP 最大延迟 option 名称，单位微秒。 */
    public static final String OPTION_MAX_DELAY = "max_delay";
    /** 自动重连开关 option 名称，1 开启，0 关闭。 */
    public static final String OPTION_RECONNECT = "reconnect";
    /** 最大重连次数 option 名称。 */
    public static final String OPTION_RECONNECT_MAX_COUNT = "reconnect_max_count";
    /** 重连间隔 option 名称，单位毫秒。 */
    public static final String OPTION_RECONNECT_INTERVAL_MS = "reconnect_interval_ms";
    /** 播放速度 option 名称。 */
    public static final String OPTION_PLAYBACK_SPEED = "playback_speed";
    /** 精确 seek 开关 option 名称，1 开启，0 关闭。 */
    public static final String OPTION_ACCURATE_SEEK = "accurate_seek";
    /** 缓冲开始水位 option 名称，单位百分比。 */
    public static final String OPTION_BUFFERING_START_PERCENT = "buffering_start_percent";
    /** 缓冲结束水位 option 名称，单位百分比。 */
    public static final String OPTION_BUFFERING_END_PERCENT = "buffering_end_percent";

    static {
        System.loadLibrary("echplayer");
    }

    /** NativePlayer 指针句柄。 */
    private long nativeHandle = 0;
    /** 当前对象是否已经释放。 */
    private boolean released = false;
    /** 当前播放器状态。 */
    private State state = State.IDLE;
    /** 当前是否已经 prepare 成功。 */
    private boolean prepared = false;
    /** 当前是否处于播放中。 */
    private boolean playing = false;
    /** 当前是否处于暂停状态。 */
    private boolean paused = false;
    /** 最近一次 prepare 返回信息。 */
    private String lastPrepareResult = "";
    /** 最近一次 start 返回信息。 */
    private String lastStartResult = "";
    /** 当前 RTSP 传输方式。 */
    private int rtspTransport = RTSP_TRANSPORT_TCP;
    /** 当前渲染模式。 */
    private int renderMode = RENDER_MODE_AUTO;
    /** 当前期望解码模式。 */
    private int decodeMode = DECODE_MODE_AUTO;
    /** 当前实际解码方式。 */
    private String currentDecodeType = "software";
    /** 当前实际解码器名称。 */
    private String currentDecoderName = "ffmpeg";
    /** 最近一次硬解回退原因。 */
    private String lastDecodeFallbackReason = "";
    /** prepare 完成监听器。 */
    private OnPreparedListener onPreparedListener;
    /** 播放完成监听器。 */
    private OnCompletionListener onCompletionListener;
    /** 错误监听器。 */
    private OnErrorListener onErrorListener;
    /** 信息监听器。 */
    private OnInfoListener onInfoListener;
    /** 缓冲进度监听器。 */
    private OnBufferingUpdateListener onBufferingUpdateListener;
    /** 视频尺寸变化监听器。 */
    private OnVideoSizeChangedListener onVideoSizeChangedListener;
    /** seek 完成监听器。 */
    private OnSeekCompleteListener onSeekCompleteListener;
    /** 字幕文本监听器。 */
    private OnTimedTextListener onTimedTextListener;
    /** MediaCodec 选择监听器。 */
    private OnMediaCodecSelectListener onMediaCodecSelectListener;
    /** 当前显示目标 SurfaceHolder。 */
    private SurfaceHolder displayHolder;
    /** 当前视频宽度。 */
    private int videoWidth = 0;
    /** 当前视频高度。 */
    private int videoHeight = 0;
    /** 首帧视频渲染事件是否已经发出。 */
    private boolean videoRenderingStarted = false;
    /** 音频输出事件是否已经发出。 */
    private boolean audioRenderingStarted = false;
    /** 播放完成事件是否已经发出。 */
    private boolean completionDispatched = false;
    /** 当前是否正在执行 seek。 */
    private boolean seeking = false;
    /** 当前录制状态。 */
    private RecordingState recordingState = RecordingState.IDLE;
    /** 最近一次录制文件路径。 */
    private String lastRecordingPath = "";
    /** 最近一次设置的数据源。 */
    private String currentDataSource = "";
    /** 当前 content Uri 打开的文件描述符，需要保持到 reset/release。 */
    private ParcelFileDescriptor currentParcelFileDescriptor;
    /** 最近一次设置数据源时附带的 headers。 */
    private final Map<String, String> currentDataSourceHeaders = new HashMap<>();
    /** 已成功设置的 long option 快照。 */
    private final Map<String, Long> longOptions = new LinkedHashMap<>();
    /** 已成功设置的 String option 快照。 */
    private final Map<String, String> stringOptions = new LinkedHashMap<>();
    /** 左声道音量，范围 0 到 1。 */
    private float leftVolume = 1.0f;
    /** 右声道音量，范围 0 到 1。 */
    private float rightVolume = 1.0f;
    /** 当前是否静音。 */
    private boolean muted = false;
    /** 当前期望播放速度。 */
    private float playbackSpeed = 1.0f;
    /** 最近一次估算音频延迟，单位毫秒。 */
    private float audioDelayMs = 0.0f;
    /** 当前是否已经获得音频焦点。 */
    private boolean audioFocusGranted = false;
    /** 当前选中视频轨道 stream index。 */
    private int selectedVideoTrack = -1;
    /** 当前选中音频轨道 stream index。 */
    private int selectedAudioTrack = -1;
    /** 当前选中字幕轨道 stream index。 */
    private int selectedTimedTextTrack = -1;
    /** 外挂字幕片段列表。 */
    private final List<SubtitleCue> externalSubtitleCues = new ArrayList<>();
    /** 最近一次分发的字幕片段索引。 */
    private int lastDispatchedSubtitleCueIndex = -1;
    /** Android O 及以上使用的音频焦点请求对象。 */
    private AudioFocusRequest audioFocusRequest;
    /** 音频焦点变化监听器。 */
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener =
            focusChange -> {
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                        || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    setMutedInternal(true);
                } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                    setMutedInternal(false);
                }
            };
    /** 是否循环播放。 */
    private boolean looping = false;
    /** 是否开启自动重连。 */
    private boolean reconnectEnabled = false;
    /** 自动重连最大次数。 */
    private int reconnectMaxCount = 3;
    /** 自动重连间隔，单位毫秒。 */
    private long reconnectIntervalMs = 2000L;
    /** 当前播放生命周期内已经重连的次数。 */
    private int reconnectCount = 0;
    /** 当前是否正在重连。 */
    private boolean reconnecting = false;
    /** 重连代次，用于 stop / release 后让旧线程失效。 */
    private int reconnectGeneration = 0;
    /** 最近一次 prepare 开始时间，单位毫秒。 */
    private long prepareStartTimeMs = 0L;
    /** 最近一次 prepare 耗时，单位毫秒。 */
    private long lastPrepareCostMs = -1L;
    /** 最近一次 start 调用时间，单位毫秒。 */
    private long startCallTimeMs = 0L;
    /** 最近一次首帧耗时，单位毫秒。 */
    private long firstFrameCostMs = -1L;
    /** 最近一次 seek 耗时，单位毫秒。 */
    private long latestSeekLoadDurationMs = -1L;
    /** 是否开启精确 seek。 */
    private boolean accurateSeekEnabled = false;
    /** 是否允许 H.264 走 MediaCodec。 */
    private boolean mediaCodecAvcEnabled = true;
    /** 是否允许 H.265 走 MediaCodec。 */
    private boolean mediaCodecHevcEnabled = true;
    /** 是否允许 MediaCodec 自动旋转。 */
    private boolean mediaCodecAutoRotateEnabled = true;
    /** 是否允许 MediaCodec 处理分辨率变化。 */
    private boolean mediaCodecHandleResolutionChangeEnabled = true;
    /** 缓冲开始水位，单位百分比。 */
    private int bufferingStartPercent = 5;
    /** 缓冲结束水位，单位百分比。 */
    private int bufferingEndPercent = 95;
    /** 最近一次分发给业务层的缓冲百分比。 */
    private int lastBufferingPercent = 100;

    /** Java 音频输出实例。 */
    private AudioTrack audioTrack;

    /** 创建播放器并初始化 native 实例。 */
    public ECHPlayer() {
        nativeHandle = nativeInit();
    }

    /** 设置 prepare 成功监听器。 */
    public synchronized void setOnPreparedListener(OnPreparedListener listener) {
        onPreparedListener = listener;
    }

    /** 设置播放完成监听器。 */
    public synchronized void setOnCompletionListener(OnCompletionListener listener) {
        onCompletionListener = listener;
    }

    /** 设置错误监听器。 */
    public synchronized void setOnErrorListener(OnErrorListener listener) {
        onErrorListener = listener;
    }

    /** 设置信息监听器。 */
    public synchronized void setOnInfoListener(OnInfoListener listener) {
        onInfoListener = listener;
    }

    /** 设置缓冲进度监听器。 */
    public synchronized void setOnBufferingUpdateListener(OnBufferingUpdateListener listener) {
        onBufferingUpdateListener = listener;
    }

    /** 设置视频尺寸变化监听器。 */
    public synchronized void setOnVideoSizeChangedListener(OnVideoSizeChangedListener listener) {
        onVideoSizeChangedListener = listener;
    }

    /** 设置 seek 完成监听器。 */
    public synchronized void setOnSeekCompleteListener(OnSeekCompleteListener listener) {
        onSeekCompleteListener = listener;
    }

    /** 设置字幕文本监听器。 */
    public synchronized void setOnTimedTextListener(OnTimedTextListener listener) {
        onTimedTextListener = listener;
    }

    /** 设置 MediaCodec 选择监听器。 */
    public synchronized void setOnMediaCodecSelectListener(OnMediaCodecSelectListener listener) {
        onMediaCodecSelectListener = listener;
    }

    /** 设置播放数据源。 */
    public synchronized void setDataSource(String dataSource) {
        setDataSource(dataSource, null);
    }

    /** 设置带 headers 的播放数据源。 */
    public synchronized void setDataSource(String dataSource, Map<String, String> headers) {
        setResolvedDataSource(dataSource, headers, null);
    }

    /** 设置已经解析完成的数据源，并按需持有文件描述符。 */
    private void setResolvedDataSource(
            String dataSource,
            Map<String, String> headers,
            ParcelFileDescriptor parcelFileDescriptor) {
        checkReleased();
        requireState(State.IDLE, State.STOPPED, State.ERROR);

        if (dataSource == null || dataSource.length() == 0) {
            throw new IllegalArgumentException("dataSource is empty");
        }

        closeCurrentParcelFileDescriptor();
        currentParcelFileDescriptor = parcelFileDescriptor;
        nativeSetDataSource(nativeHandle, dataSource);
        applyDataSourceHeaders(headers);
        currentDataSource = dataSource;
        state = State.INITIALIZED;
        prepared = false;
        playing = false;
        paused = false;
        reconnectCount = 0;
        reconnecting = false;
        resetPlaybackTiming();
        resetVideoSize();
        resetPlaybackEventFlags();
    }

    /** 设置 Uri 播放数据源。 */
    public synchronized void setDataSource(Context context, Uri uri) {
        setDataSource(context, uri, null);
    }

    /** 设置带 headers 的 Uri 播放数据源。 */
    public synchronized void setDataSource(Context context, Uri uri, Map<String, String> headers) {
        if (uri == null) {
            throw new IllegalArgumentException("uri is null");
        }

        String scheme = uri.getScheme();
        if ("content".equalsIgnoreCase(scheme)) {
            setContentUriDataSource(context, uri, headers);
            return;
        }

        String resolvedSource = resolveUriDataSource(context, uri);
        setDataSource(resolvedSource, headers);
    }

    /** 设置文件描述符播放数据源，调用方需要保证 fd 在播放期间保持打开。 */
    public synchronized void setDataSource(FileDescriptor fd) {
        if (fd == null) {
            throw new IllegalArgumentException("fd is null");
        }
        setResolvedDataSource(resolveFileDescriptorDataSource(fd), null, null);
    }

    /** 设置 ParcelFileDescriptor 播放数据源，播放器会在 reset/release 时关闭它。 */
    public synchronized void setDataSource(ParcelFileDescriptor parcelFileDescriptor) {
        if (parcelFileDescriptor == null) {
            throw new IllegalArgumentException("parcelFileDescriptor is null");
        }
        setResolvedDataSource(resolveParcelFileDescriptorDataSource(parcelFileDescriptor), null,
                parcelFileDescriptor);
    }

    /** 设置自定义数据源，并把数据顺序写入临时文件后交给 FFmpeg 播放。 */
    @Override
    public synchronized void setDataSource(ECHMediaDataSource dataSource, File cacheFile)
            throws IOException {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource is null");
        }
        if (cacheFile == null) {
            throw new IllegalArgumentException("cacheFile is null");
        }

        copyCustomDataSourceToFile(dataSource, cacheFile);
        setDataSource(cacheFile.getAbsolutePath());
    }

    /** 返回最近一次设置的数据源。 */
    public synchronized String getDataSource() {
        return currentDataSource;
    }

    /** 使用 SurfaceHolder 设置视频输出目标。 */
    public synchronized void setDisplay(SurfaceHolder holder) {
        checkReleased();
        displayHolder = holder;
        setSurface(holder == null ? null : holder.getSurface());
    }

    /** 设置视频输出 Surface。 */
    public synchronized void setSurface(Surface surface) {
        checkReleased();
        nativeSetSurface(nativeHandle, surface);
    }

    /** 设置 NativeWindow 兼容渲染路径的 Surface 缩放方式。 */
    public synchronized void setSurfaceScaleType(int scaleType) {
        checkReleased();
        int nativeScaleType = scaleType == SURFACE_SCALE_TYPE_FILL
                ? SURFACE_SCALE_TYPE_FILL
                : SURFACE_SCALE_TYPE_FIT_CENTER;
        nativeSetSurfaceScaleType(nativeHandle, nativeScaleType);
    }

    /** 设置渲染模式。 */
    public synchronized void setRenderMode(int renderMode) {
        checkReleased();
        this.renderMode = normalizeRenderMode(renderMode);
        nativeSetRenderMode(nativeHandle, this.renderMode);
    }

    /** 返回当前渲染模式。 */
    public synchronized int getRenderMode() {
        return renderMode;
    }

    /** 设置解码模式，建议在 prepare 前调用。 */
    public synchronized void setDecodeMode(int decodeMode) {
        checkReleased();
        this.decodeMode = normalizeDecodeMode(decodeMode);
        nativeSetDecodeMode(nativeHandle, this.decodeMode);
        applyMediaCodecOptions();
    }

    /** 返回当前期望解码模式。 */
    public synchronized int getDecodeMode() {
        return decodeMode;
    }

    /** 返回当前实际解码方式，例如 software 或 mediacodec。 */
    public synchronized String getCurrentDecodeType() {
        updateDecodeInfoFromNative();
        return currentDecodeType;
    }

    /** 返回当前实际解码器名称，例如 ffmpeg-h264 或 MediaCodec 名称。 */
    public synchronized String getCurrentDecoderName() {
        updateDecodeInfoFromNative();
        return currentDecoderName;
    }

    /** 返回最近一次硬解失败回退原因。 */
    public synchronized String getLastDecodeFallbackReason() {
        updateDecodeInfoFromNative();
        return lastDecodeFallbackReason;
    }

    /** 设置 RTSP 传输方式。 */
    public synchronized void setRtspTransport(int transport) {
        checkReleased();
        rtspTransport = transport == RTSP_TRANSPORT_UDP
                ? RTSP_TRANSPORT_UDP
                : RTSP_TRANSPORT_TCP;
        nativeSetRtspTransport(nativeHandle, rtspTransport);
    }

    /** 设置是否开启自动重连。 */
    public synchronized void setReconnectEnabled(boolean enabled) {
        checkReleased();
        reconnectEnabled = enabled;
        if (!enabled) {
            cancelReconnectLocked();
        }
    }

    /** 设置自动重连最大次数和重连间隔。 */
    public synchronized void setReconnectConfig(int maxRetryCount, long retryIntervalMs) {
        checkReleased();
        reconnectMaxCount = Math.max(0, maxRetryCount);
        reconnectIntervalMs = Math.max(0L, retryIntervalMs);
    }

    /** 返回当前播放生命周期内已经尝试的重连次数。 */
    public synchronized int getReconnectCount() {
        return reconnectCount;
    }

    /** 获取当前播放统计快照。 */
    public synchronized PlaybackStats getPlaybackStats() {
        checkReleased();
        dispatchVideoRenderingStartIfReady();
        return new PlaybackStats(
                nativeGetReadBytes(nativeHandle),
                nativeGetReadSpeedBytesPerSecond(nativeHandle),
                nativeGetVideoPacketQueueSize(nativeHandle),
                nativeGetAudioPacketQueueSize(nativeHandle),
                nativeGetBufferedPercent(nativeHandle),
                nativeGetDecodeFps(nativeHandle),
                nativeGetRenderFps(nativeHandle),
                nativeGetDecodedFrameCount(nativeHandle),
                nativeGetRenderedFrameCount(nativeHandle),
                nativeGetDroppedFrameCount(nativeHandle),
                lastPrepareCostMs,
                firstFrameCostMs,
                System.currentTimeMillis()
        );
    }

    /** 获取当前媒体信息快照。 */
    public synchronized MediaInfo getMediaInfo() {
        checkReleased();
        return parseMediaInfo(nativeGetMediaInfoText(nativeHandle));
    }

    /** 获取当前轨道信息快照列表。 */
    public synchronized List<TrackInfo> getTrackInfo() {
        checkReleased();
        return parseTrackInfo(nativeGetTrackInfoText(nativeHandle));
    }

    /** 选择指定轨道。 */
    @Override
    public synchronized void selectTrack(int streamIndex) {
        checkReleased();
        TrackInfo track = findTrackByIndex(streamIndex);
        if (track == null) {
            throw new IllegalArgumentException("track not found: " + streamIndex);
        }

        if ("video".equals(track.type)) {
            selectedVideoTrack = streamIndex;
        } else if ("audio".equals(track.type)) {
            selectedAudioTrack = streamIndex;
        } else if ("subtitle".equals(track.type)) {
            selectedTimedTextTrack = streamIndex;
        } else {
            throw new IllegalArgumentException("track type is not selectable: " + track.type);
        }
    }

    /** 取消选择指定轨道。 */
    @Override
    public synchronized void deselectTrack(int streamIndex) {
        checkReleased();
        if (selectedVideoTrack == streamIndex) {
            selectedVideoTrack = -1;
        }
        if (selectedAudioTrack == streamIndex) {
            selectedAudioTrack = -1;
        }
        if (selectedTimedTextTrack == streamIndex) {
            selectedTimedTextTrack = -1;
        }
    }

    /** 加载外部 SRT 字幕文件。 */
    public synchronized void loadExternalSubtitle(File subtitleFile) throws IOException {
        checkReleased();
        if (subtitleFile == null) {
            throw new IllegalArgumentException("subtitleFile is null");
        }
        byte[] data = Files.readAllBytes(subtitleFile.toPath());
        loadExternalSubtitleText(new String(data, StandardCharsets.UTF_8));
    }

    /** 加载外部 SRT 字幕文本。 */
    public synchronized void loadExternalSubtitleText(String subtitleText) {
        checkReleased();
        externalSubtitleCues.clear();
        externalSubtitleCues.addAll(parseSrtCues(subtitleText));
        lastDispatchedSubtitleCueIndex = -1;
        selectedTimedTextTrack = externalSubtitleCues.isEmpty() ? -1 : -2;
    }

    /** 获取当前媒体元信息快照。 */
    public synchronized MediaMeta getMediaMeta() {
        checkReleased();
        MediaInfo info = getMediaInfo();
        List<TrackInfo> tracks = getTrackInfo();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("format", info.format);
        metadata.put("durationMs", String.valueOf(info.durationMs));
        metadata.put("bitRate", String.valueOf(info.bitRate));
        metadata.put("videoStreamIndex", String.valueOf(info.videoStreamIndex));
        metadata.put("audioStreamIndex", String.valueOf(info.audioStreamIndex));
        metadata.put("videoCodec", info.videoCodec);
        metadata.put("videoWidth", String.valueOf(info.videoWidth));
        metadata.put("videoHeight", String.valueOf(info.videoHeight));
        metadata.put("audioCodec", info.audioCodec);
        metadata.put("audioSampleRate", String.valueOf(info.audioSampleRate));
        metadata.put("audioChannels", String.valueOf(info.audioChannels));
        metadata.put("trackCount", String.valueOf(tracks.size()));

        for (TrackInfo track : tracks) {
            String prefix = "track." + track.streamIndex + ".";
            metadata.put(prefix + "type", track.type);
            metadata.put(prefix + "codec", track.codec);
            metadata.put(prefix + "language", track.language);
        }

        return new MediaMeta(
                info.format,
                info.durationMs,
                info.bitRate,
                info.videoStreamIndex,
                info.audioStreamIndex,
                info.videoCodec,
                info.videoWidth,
                info.videoHeight,
                info.audioCodec,
                info.audioSampleRate,
                info.audioChannels,
                tracks.size(),
                metadata
        );
    }

    /** 返回当前视频解码器名称。 */
    public synchronized String getVideoDecoder() {
        return getCurrentDecoderName();
    }

    /** 返回当前音频解码器名称。 */
    public synchronized String getAudioDecoder() {
        MediaInfo info = getMediaInfo();
        if (info.audioCodec == null || info.audioCodec.length() == 0) {
            return "";
        }
        return "ffmpeg-" + info.audioCodec;
    }

    /** 按 ijkplayer 风格读取 long 属性。 */
    public synchronized long getPropertyLong(int property, long defaultValue) {
        checkReleased();
        switch (property) {
            case PROP_INT64_SELECTED_VIDEO_STREAM:
                return selectedVideoTrack >= 0 ? selectedVideoTrack : getMediaInfo().videoStreamIndex;
            case PROP_INT64_SELECTED_AUDIO_STREAM:
                return selectedAudioTrack >= 0 ? selectedAudioTrack : getMediaInfo().audioStreamIndex;
            case PROP_INT64_SELECTED_TIMEDTEXT_STREAM:
                return selectedTimedTextTrack;
            case PROP_INT64_VIDEO_DECODER:
                return decoderTypeToPropertyValue(getCurrentDecodeType());
            case PROP_INT64_AUDIO_DECODER:
                return getMediaInfo().audioStreamIndex >= 0
                        ? PROP_DECODER_AVCODEC
                        : PROP_DECODER_UNKNOWN;
            case PROP_INT64_VIDEO_CACHED_DURATION:
            case PROP_INT64_AUDIO_CACHED_DURATION:
                return defaultValue;
            case PROP_INT64_VIDEO_CACHED_BYTES:
            case PROP_INT64_AUDIO_CACHED_BYTES:
                return 0L;
            case PROP_INT64_VIDEO_CACHED_PACKETS:
                return nativeGetVideoPacketQueueSize(nativeHandle);
            case PROP_INT64_AUDIO_CACHED_PACKETS:
                return nativeGetAudioPacketQueueSize(nativeHandle);
            case PROP_INT64_BIT_RATE:
                return getMediaInfo().bitRate;
            case PROP_INT64_TCP_SPEED:
                return nativeGetReadSpeedBytesPerSecond(nativeHandle);
            case PROP_INT64_TRAFFIC_STATISTIC_BYTE_COUNT:
                return nativeGetReadBytes(nativeHandle);
            case PROP_INT64_LATEST_SEEK_LOAD_DURATION:
                return latestSeekLoadDurationMs >= 0L ? latestSeekLoadDurationMs : defaultValue;
            case PROP_INT64_ASYNC_STATISTIC_BUF_BACKWARDS:
            case PROP_INT64_ASYNC_STATISTIC_BUF_FORWARDS:
            case PROP_INT64_ASYNC_STATISTIC_BUF_CAPACITY:
                return defaultValue;
            default:
                return defaultValue;
        }
    }

    /** 按 ijkplayer 风格读取 float 属性。 */
    public synchronized float getPropertyFloat(int property, float defaultValue) {
        checkReleased();
        switch (property) {
            case PROP_FLOAT_VIDEO_DECODE_FRAMES_PER_SECOND:
                return (float) nativeGetDecodeFps(nativeHandle);
            case PROP_FLOAT_VIDEO_OUTPUT_FRAMES_PER_SECOND:
                return (float) nativeGetRenderFps(nativeHandle);
            case PROP_FLOAT_PLAYBACK_RATE:
                return playbackSpeed;
            case PROP_FLOAT_DROP_FRAME_RATE:
                long decoded = nativeGetDecodedFrameCount(nativeHandle);
                long dropped = nativeGetDroppedFrameCount(nativeHandle);
                long total = decoded + dropped;
                return total <= 0 ? 0.0f : (float) dropped / (float) total;
            case PROP_FLOAT_AUDIO_DELAY:
                return audioDelayMs;
            default:
                return defaultValue;
        }
    }

    /** 设置 long 类型播放器选项。 */
    public synchronized boolean setOption(int category, String name, long value) {
        checkReleased();

        if (OPTION_RTSP_TRANSPORT.equals(name)) {
            setRtspTransport(value == RTSP_TRANSPORT_UDP ? RTSP_TRANSPORT_UDP : RTSP_TRANSPORT_TCP);
            rememberLongOption(category, name, value);
            return true;
        }
        if (OPTION_RENDER_MODE.equals(name)) {
            setRenderMode((int) value);
            rememberLongOption(category, name, value);
            return true;
        }
        if (OPTION_DECODE_MODE.equals(name)) {
            setDecodeMode((int) value);
            rememberLongOption(category, name, value);
            return true;
        }
        if (OPTION_MEDIACODEC.equals(name)) {
            setDecodeMode(value == 0 ? DECODE_MODE_SOFTWARE : DECODE_MODE_MEDIACODEC);
            rememberLongOption(category, name, value);
            return true;
        }
        if (OPTION_MEDIACODEC_AUTO_ROTATE.equals(name)) {
            mediaCodecAutoRotateEnabled = value != 0;
            return rememberNativeLongOption(category, name, mediaCodecAutoRotateEnabled ? 1L : 0L);
        }
        if (OPTION_MEDIACODEC_HANDLE_RESOLUTION_CHANGE.equals(name)) {
            mediaCodecHandleResolutionChangeEnabled = value != 0;
            return rememberNativeLongOption(category, name,
                    mediaCodecHandleResolutionChangeEnabled ? 1L : 0L);
        }
        if (OPTION_MEDIACODEC_AVC.equals(name)) {
            mediaCodecAvcEnabled = value != 0;
            return rememberNativeLongOption(category, name, mediaCodecAvcEnabled ? 1L : 0L);
        }
        if (OPTION_MEDIACODEC_HEVC.equals(name)) {
            mediaCodecHevcEnabled = value != 0;
            return rememberNativeLongOption(category, name, mediaCodecHevcEnabled ? 1L : 0L);
        }
        if (OPTION_MEDIACODEC_MPEG4.equals(name) || OPTION_MEDIACODEC_MPEG2.equals(name)) {
            return rememberNativeLongOption(category, name, 0L);
        }
        if (OPTION_RECONNECT.equals(name)) {
            setReconnectEnabled(value != 0);
            rememberLongOption(category, name, value);
            return true;
        }
        if (OPTION_RECONNECT_MAX_COUNT.equals(name)) {
            setReconnectConfig((int) value, reconnectIntervalMs);
            rememberLongOption(category, name, value);
            return true;
        }
        if (OPTION_RECONNECT_INTERVAL_MS.equals(name)) {
            setReconnectConfig(reconnectMaxCount, value);
            rememberLongOption(category, name, value);
            return true;
        }
        if (OPTION_PLAYBACK_SPEED.equals(name)) {
            setSpeed(value / 1000.0f);
            rememberLongOption(category, name, value);
            return true;
        }
        if (OPTION_ACCURATE_SEEK.equals(name)) {
            accurateSeekEnabled = value != 0;
            rememberLongOption(category, name, value);
            return true;
        }
        if (OPTION_BUFFERING_START_PERCENT.equals(name)) {
            bufferingStartPercent = clampPercent((int) value);
            if (bufferingStartPercent > bufferingEndPercent) {
                bufferingEndPercent = bufferingStartPercent;
            }
            rememberLongOption(category, name, bufferingStartPercent);
            return true;
        }
        if (OPTION_BUFFERING_END_PERCENT.equals(name)) {
            bufferingEndPercent = clampPercent((int) value);
            if (bufferingEndPercent < bufferingStartPercent) {
                bufferingStartPercent = bufferingEndPercent;
            }
            rememberLongOption(category, name, bufferingEndPercent);
            return true;
        }

        boolean handled = nativeSetLongOption(nativeHandle, category, name, value);
        if (handled) {
            rememberLongOption(category, name, value);
        }
        return handled;
    }

    /** 设置 String 类型播放器选项。 */
    public synchronized boolean setOption(int category, String name, String value) {
        checkReleased();

        if (OPTION_RTSP_TRANSPORT.equals(name)) {
            if (OPTION_VALUE_UDP.equalsIgnoreCase(value)) {
                setRtspTransport(RTSP_TRANSPORT_UDP);
            } else {
                setRtspTransport(RTSP_TRANSPORT_TCP);
            }
            rememberStringOption(category, name, value);
            return true;
        }
        if (OPTION_RENDER_MODE.equals(name)) {
            if (OPTION_VALUE_RENDER_OPENGL.equalsIgnoreCase(value)) {
                setRenderMode(RENDER_MODE_OPENGL);
            } else if (OPTION_VALUE_RENDER_NATIVE_WINDOW.equalsIgnoreCase(value)
                    || "nativewindow".equalsIgnoreCase(value)) {
                setRenderMode(RENDER_MODE_NATIVE_WINDOW);
            } else {
                setRenderMode(RENDER_MODE_AUTO);
            }
            rememberStringOption(category, name, value);
            return true;
        }
        if (OPTION_DECODE_MODE.equals(name)) {
            setDecodeMode(decodeModeFromText(value));
            rememberStringOption(category, name, value);
            return true;
        }
        if (OPTION_MEDIACODEC.equals(name)) {
            boolean enable = "1".equals(value)
                    || "true".equalsIgnoreCase(value)
                    || OPTION_VALUE_DECODE_MEDIACODEC.equalsIgnoreCase(value);
            setDecodeMode(enable ? DECODE_MODE_MEDIACODEC : DECODE_MODE_SOFTWARE);
            rememberStringOption(category, name, value);
            return true;
        }
        if (OPTION_MEDIACODEC_AUTO_ROTATE.equals(name)
                || OPTION_MEDIACODEC_HANDLE_RESOLUTION_CHANGE.equals(name)
                || OPTION_MEDIACODEC_AVC.equals(name)
                || OPTION_MEDIACODEC_HEVC.equals(name)
                || OPTION_MEDIACODEC_MPEG4.equals(name)
                || OPTION_MEDIACODEC_MPEG2.equals(name)) {
            return setOption(category, name, parseBooleanOption(value) ? 1L : 0L);
        }
        if (OPTION_RECONNECT.equals(name)) {
            boolean enable = "1".equals(value) || "true".equalsIgnoreCase(value);
            setReconnectEnabled(enable);
            rememberStringOption(category, name, value);
            return true;
        }
        if (OPTION_PLAYBACK_SPEED.equals(name)) {
            try {
                setSpeed(Float.parseFloat(value));
                rememberStringOption(category, name, value);
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        if (OPTION_ACCURATE_SEEK.equals(name)) {
            boolean enable = "1".equals(value) || "true".equalsIgnoreCase(value);
            accurateSeekEnabled = enable;
            rememberStringOption(category, name, value);
            return true;
        }
        if (OPTION_HEADERS.equals(name)
                || OPTION_USER_AGENT.equals(name)
                || OPTION_PROTOCOL_WHITELIST.equals(name)) {
            boolean handled = nativeSetStringOption(nativeHandle, category, name, value == null ? "" : value);
            if (handled) {
                rememberStringOption(category, name, value);
            }
            return handled;
        }

        try {
            return setOption(category, name, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            // 字符串 option 目前仅 RTSP 传输方式需要，其他网络参数使用 long 更清晰。
        }

        return false;
    }

    /** 返回 long option 快照。 */
    public synchronized Map<String, Long> getLongOptionsSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(longOptions));
    }

    /** 返回 String option 快照。 */
    public synchronized Map<String, String> getStringOptionsSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(stringOptions));
    }

    /** 判断指定 option 当前是否已支持。 */
    public synchronized boolean isOptionSupported(int category, String name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        if (category != OPTION_CATEGORY_FORMAT
                && category != OPTION_CATEGORY_CODEC
                && category != OPTION_CATEGORY_SWS
                && category != OPTION_CATEGORY_PLAYER) {
            return false;
        }
        return OPTION_RTSP_TRANSPORT.equals(name)
                || OPTION_RENDER_MODE.equals(name)
                || OPTION_DECODE_MODE.equals(name)
                || OPTION_MEDIACODEC.equals(name)
                || OPTION_MEDIACODEC_AUTO_ROTATE.equals(name)
                || OPTION_MEDIACODEC_HANDLE_RESOLUTION_CHANGE.equals(name)
                || OPTION_MEDIACODEC_AVC.equals(name)
                || OPTION_MEDIACODEC_HEVC.equals(name)
                || OPTION_MEDIACODEC_MPEG4.equals(name)
                || OPTION_MEDIACODEC_MPEG2.equals(name)
                || OPTION_RECONNECT.equals(name)
                || OPTION_RECONNECT_MAX_COUNT.equals(name)
                || OPTION_RECONNECT_INTERVAL_MS.equals(name)
                || OPTION_PLAYBACK_SPEED.equals(name)
                || OPTION_ACCURATE_SEEK.equals(name)
                || OPTION_BUFFERING_START_PERCENT.equals(name)
                || OPTION_BUFFERING_END_PERCENT.equals(name)
                || OPTION_TIMEOUT.equals(name)
                || OPTION_STIMEOUT.equals(name)
                || OPTION_RW_TIMEOUT.equals(name)
                || OPTION_BUFFER_SIZE.equals(name)
                || OPTION_MAX_DELAY.equals(name)
                || OPTION_HEADERS.equals(name)
                || OPTION_USER_AGENT.equals(name)
                || OPTION_PROTOCOL_WHITELIST.equals(name);
    }

    /** 打开数据源并读取流信息。 */
    public synchronized String prepare() {
        checkReleased();
        requireState(State.INITIALIZED, State.STOPPED);
        state = State.PREPARING;
        markPrepareStarted();
        dispatchInfo(INFO_PREPARE_STARTED, "prepare started");
        lastPrepareResult = nativePrepare(nativeHandle);
        markPrepareFinished();
        updateDecodeInfoFromNative();
        prepared = lastPrepareResult != null && lastPrepareResult.startsWith("prepare success");
        playing = false;
        paused = false;
        state = prepared ? State.PREPARED : State.ERROR;
        if (prepared) {
            updateVideoSizeFromNative();
            dispatchInfo(INFO_PREPARED, lastPrepareResult);
            dispatchBufferingUpdate(100);
            if (onPreparedListener != null) {
                onPreparedListener.onPrepared(this);
            }
        } else {
            int errorCode = mapPrepareErrorCode(lastPrepareResult);
            if (!startReconnectIfNeededLocked(errorCode, lastPrepareResult)) {
                dispatchError(errorCode, lastPrepareResult);
            }
        }
        return lastPrepareResult;
    }

    /** 异步打开数据源并读取流信息。 */
    public synchronized void prepareAsync() {
        checkReleased();
        requireState(State.INITIALIZED, State.STOPPED);
        state = State.PREPARING;
        markPrepareStarted();
        dispatchInfo(INFO_PREPARE_STARTED, "prepare started");

        Thread prepareThread = new Thread(() -> {
            synchronized (ECHPlayer.this) {
                if (!released && nativeHandle != 0) {
                    lastPrepareResult = nativePrepare(nativeHandle);
                    markPrepareFinished();
                    updateDecodeInfoFromNative();
                    prepared = lastPrepareResult != null
                            && lastPrepareResult.startsWith("prepare success");
                    playing = false;
                    paused = false;
                    state = prepared ? State.PREPARED : State.ERROR;
                    if (prepared) {
                        updateVideoSizeFromNative();
                        dispatchInfo(INFO_PREPARED, lastPrepareResult);
                        dispatchBufferingUpdate(100);
                        if (onPreparedListener != null) {
                            onPreparedListener.onPrepared(this);
                        }
                    } else {
                        int errorCode = mapPrepareErrorCode(lastPrepareResult);
                        if (!startReconnectIfNeededLocked(errorCode, lastPrepareResult)) {
                            dispatchError(errorCode, lastPrepareResult);
                        }
                    }
                }
            }
        }, "ECHPlayer-Prepare");
        prepareThread.start();
    }

    /** 启动播放。 */
    public synchronized String play() {
        return start();
    }

    /** 按标准播放器命名启动或恢复播放。 */
    public synchronized String start() {
        checkReleased();
        requireState(State.PREPARED, State.STARTED, State.PAUSED);

        if (paused) {
            resume();
            lastStartResult = "play resumed";
            return lastStartResult;
        }

        if (!playing) {
            markStartRequested();
        }
        lastStartResult = nativePlay(nativeHandle);
        if (lastStartResult != null
                && (lastStartResult.startsWith("play started")
                || lastStartResult.startsWith("play ignored"))) {
            playing = true;
            paused = false;
            state = State.STARTED;
            completionDispatched = false;
            dispatchInfo(INFO_PLAY_STARTED, lastStartResult);
        } else {
            state = State.ERROR;
            dispatchError(mapPlaybackErrorCode(lastStartResult), lastStartResult);
        }
        return lastStartResult;
    }

    /** 暂停播放。 */
    public synchronized void pause() {
        if (!released && nativeHandle != 0) {
            requireState(State.STARTED, State.PAUSED);
            nativePause(nativeHandle);

            if (audioTrack != null) {
                audioTrack.pause();
            }

            playing = false;
            paused = true;
            state = State.PAUSED;
            dispatchInfo(INFO_PAUSED, "pause");
        }
    }

    /** 恢复播放。 */
    public synchronized void resume() {
        if (!released && nativeHandle != 0) {
            requireState(State.PAUSED, State.STARTED);
            if (audioTrack != null) {
                audioTrack.play();
            }

            nativeResume(nativeHandle);
            playing = true;
            paused = false;
            state = State.STARTED;
            completionDispatched = false;
            dispatchInfo(INFO_PLAY_STARTED, "resume");
        }
    }

    /** 停止播放。 */
    public synchronized void stop() {
        if (!released && nativeHandle != 0) {
            if (state == State.IDLE || state == State.RELEASED) {
                return;
            }
            cancelReconnectLocked();
            stopRecordingIfNeeded();
            nativeStop(nativeHandle);
            releaseAudioTrack();
            prepared = false;
            playing = false;
            paused = false;
            resetPlaybackTiming();
            resetPlaybackEventFlags();
            state = State.STOPPED;
            dispatchInfo(INFO_STOPPED, "stop");
        }
    }

    /** 重置播放器，回到可重新设置数据源的初始状态。 */
    public synchronized void reset() {
        checkReleased();

        cancelReconnectLocked();
        stopRecordingIfNeeded();
        nativeStop(nativeHandle);
        releaseAudioTrack();
        nativeRelease(nativeHandle);
        nativeHandle = nativeInit();
        closeCurrentParcelFileDescriptor();
        prepared = false;
        playing = false;
        paused = false;
        lastPrepareResult = "";
        lastStartResult = "";
        rtspTransport = RTSP_TRANSPORT_TCP;
        renderMode = RENDER_MODE_AUTO;
        decodeMode = DECODE_MODE_AUTO;
        nativeSetDecodeMode(nativeHandle, decodeMode);
        nativeSetRenderMode(nativeHandle, renderMode);
        nativeSetRtspTransport(nativeHandle, rtspTransport);
        currentDecodeType = "software";
        currentDecoderName = "ffmpeg";
        lastDecodeFallbackReason = "";
        mediaCodecAvcEnabled = true;
        mediaCodecHevcEnabled = true;
        mediaCodecAutoRotateEnabled = true;
        mediaCodecHandleResolutionChangeEnabled = true;
        recordingState = RecordingState.IDLE;
        lastRecordingPath = "";
        currentDataSource = "";
        currentDataSourceHeaders.clear();
        longOptions.clear();
        stringOptions.clear();
        displayHolder = null;
        leftVolume = 1.0f;
        rightVolume = 1.0f;
        muted = false;
        playbackSpeed = 1.0f;
        audioDelayMs = 0.0f;
        audioFocusGranted = false;
        audioFocusRequest = null;
        selectedVideoTrack = -1;
        selectedAudioTrack = -1;
        selectedTimedTextTrack = -1;
        externalSubtitleCues.clear();
        lastDispatchedSubtitleCueIndex = -1;
        looping = false;
        latestSeekLoadDurationMs = -1L;
        accurateSeekEnabled = false;
        applyMediaCodecOptions();
        bufferingStartPercent = 5;
        bufferingEndPercent = 95;
        lastBufferingPercent = 100;
        reconnectCount = 0;
        reconnecting = false;
        resetPlaybackTiming();
        resetVideoSize();
        resetPlaybackEventFlags();
        state = State.IDLE;
    }

    /** 跳转到指定毫秒位置。 */
    public synchronized String seekToMs(long positionMs) {
        return seekTo(positionMs);
    }

    /** 按标准播放器命名跳转到指定毫秒位置。 */
    public synchronized String seekTo(long positionMs) {
        checkReleased();
        requireState(State.PREPARED, State.STARTED, State.PAUSED, State.COMPLETED);

        if (seeking) {
            dispatchError(ERROR_INVALID_STATE, "seek ignored: already seeking");
            return "seek ignored: already seeking";
        }

        State stateBeforeSeek = state;
        boolean wasPlaying = playing;
        boolean wasPaused = paused;
        int bufferingPercentBeforeSeek = lastBufferingPercent;
        long seekStartTimeMs = System.currentTimeMillis();
        seeking = true;
        state = State.SEEKING;
        dispatchBufferingUpdate(bufferingStartPercent);
        String result = nativeSeekToMs(nativeHandle, positionMs);
        latestSeekLoadDurationMs = Math.max(0L, System.currentTimeMillis() - seekStartTimeMs);
        seeking = false;

        if (result != null && result.startsWith("seek success")) {
            playing = wasPlaying;
            paused = wasPaused;
            state = wasPlaying ? State.STARTED : (wasPaused ? State.PAUSED : State.PREPARED);
            completionDispatched = false;
            dispatchBufferingUpdate(bufferingEndPercent);
            dispatchInfo(
                    INFO_SEEK_COMPLETE,
                    result
                            + "\naccurateSeek: " + accurateSeekEnabled
                            + "\nseekLoadDurationMs: " + latestSeekLoadDurationMs
            );
            dispatchSeekComplete();
        } else {
            dispatchBufferingUpdate(bufferingPercentBeforeSeek);
            playing = wasPlaying;
            paused = wasPaused;
            state = stateBeforeSeek;
            if (result != null && result.contains("not seekable")) {
                dispatchError(ERROR_STREAM_NOT_SEEKABLE, result);
            } else {
                dispatchError(ERROR_UNKNOWN, result);
            }
        }
        return result;
    }

    /** 获取总时长。 */
    public synchronized long getDurationMs() {
        return getDuration();
    }

    /** 按标准播放器命名获取总时长。 */
    public synchronized long getDuration() {
        checkReleased();
        return nativeGetDurationMs(nativeHandle);
    }

    /** 获取当前播放位置。 */
    public synchronized long getCurrentPositionMs() {
        return getCurrentPosition();
    }

    /** 按标准播放器命名获取当前播放位置。 */
    public synchronized long getCurrentPosition() {
        checkReleased();
        long currentPosition = nativeGetCurrentPositionMs(nativeHandle);
        long duration = nativeGetDurationMs(nativeHandle);
        dispatchVideoRenderingStartIfReady();
        if (state == State.STARTED
                && duration > 0
                && currentPosition >= Math.max(0, duration - 500)) {
            dispatchCompletion();
        }
        dispatchExternalSubtitleForPosition(currentPosition);
        return currentPosition;
    }

    /** 返回当前是否处于播放中。 */
    public synchronized boolean isPlaying() {
        return !released && playing;
    }

    /** 返回当前是否已经 prepare 成功。 */
    public synchronized boolean isPrepared() {
        return !released && prepared;
    }

    /** 返回当前媒体是否支持 seek。 */
    public synchronized boolean isSeekable() {
        return !released && nativeIsSeekable(nativeHandle);
    }

    /** 设置左右声道音量，范围建议为 0 到 1。 */
    public synchronized void setVolume(float leftVolume, float rightVolume) {
        checkReleased();
        this.leftVolume = normalizeVolume(leftVolume);
        this.rightVolume = normalizeVolume(rightVolume);
        applyAudioTrackVolume();
    }

    /** 设置是否静音。 */
    @Override
    public synchronized void setMuted(boolean muted) {
        checkReleased();
        this.muted = muted;
        applyAudioTrackVolume();
    }

    /** 返回当前是否静音。 */
    @Override
    public synchronized boolean isMuted() {
        return muted;
    }

    /** 设置播放速度，当前版本先记录期望值。 */
    @Override
    public synchronized void setSpeed(float speed) {
        checkReleased();
        if (Float.isNaN(speed) || Float.isInfinite(speed)) {
            throw new IllegalArgumentException("speed is invalid");
        }
        playbackSpeed = Math.max(0.25f, Math.min(4.0f, speed));
        applyAudioTrackSpeed();
    }

    /** 返回当前期望播放速度。 */
    @Override
    public synchronized float getSpeed() {
        return playbackSpeed;
    }

    /** 请求音频焦点，适合 Activity 在 start 前调用。 */
    public synchronized boolean requestAudioFocus(Context context) {
        checkReleased();
        if (context == null) {
            throw new IllegalArgumentException("context is null");
        }
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            audioFocusGranted = false;
            return false;
        }

        int result;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build();
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
        }

        audioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return audioFocusGranted;
    }

    /** 释放音频焦点，适合 Activity 停止播放或退出时调用。 */
    public synchronized void abandonAudioFocus(Context context) {
        if (context == null) {
            return;
        }
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            audioFocusGranted = false;
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
        audioFocusGranted = false;
        audioFocusRequest = null;
    }

    /** 返回当前是否已获得音频焦点。 */
    public synchronized boolean isAudioFocusGranted() {
        return audioFocusGranted;
    }

    /** 设置是否循环播放。 */
    public synchronized void setLooping(boolean looping) {
        checkReleased();
        this.looping = looping;
    }

    /** 返回是否循环播放。 */
    public synchronized boolean isLooping() {
        return looping;
    }

    /** 获取当前视频宽度。 */
    public synchronized int getVideoWidth() {
        checkReleased();
        updateVideoSizeFromNative();
        return videoWidth;
    }

    /** 获取当前视频高度。 */
    public synchronized int getVideoHeight() {
        checkReleased();
        updateVideoSizeFromNative();
        return videoHeight;
    }

    /** 返回当前播放器是否已经释放。 */
    public synchronized boolean isReleased() {
        return released;
    }

    /** 返回当前播放器状态。 */
    public synchronized State getState() {
        return state;
    }

    /** 获取最近一次 prepare 返回信息。 */
    public synchronized String getLastPrepareResult() {
        return lastPrepareResult;
    }

    /** 获取最近一次 start 返回信息。 */
    public synchronized String getLastStartResult() {
        return lastStartResult;
    }

    /** 获取最近一帧的原始 RGBA 数据。 */
    public synchronized byte[] getCurrentFrameRgba() {
        checkReleased();
        return nativeGetCurrentFrameRgba(nativeHandle);
    }

    /** 获取最近一帧宽高信息。返回 [width, height]。 */
    public synchronized int[] getCurrentFrameSize() {
        checkReleased();
        return nativeGetCurrentFrameSize(nativeHandle);
    }

    /** 把当前解码后的原始帧保存为 PNG，并返回截图结果。 */
    public synchronized CaptureResult captureCurrentFramePng(String outputPath) throws IOException {
        checkReleased();
        if (outputPath == null || outputPath.length() == 0) {
            throw new IOException("outputPath is empty");
        }

        int[] frameSize = nativeGetCurrentFrameSize(nativeHandle);
        byte[] rgbaData = nativeGetCurrentFrameRgba(nativeHandle);
        int frameWidth = frameSize != null && frameSize.length >= 2 ? frameSize[0] : 0;
        int frameHeight = frameSize != null && frameSize.length >= 2 ? frameSize[1] : 0;

        if (frameWidth <= 0 || frameHeight <= 0 || rgbaData == null || rgbaData.length == 0) {
            throw new IOException("current decoded frame is unavailable");
        }

        int expectedSize = frameWidth * frameHeight * 4;
        if (rgbaData.length < expectedSize) {
            throw new IOException("rgba data size is invalid");
        }

        File outputFile = new File(outputPath);
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("cannot create output dir: " + parentDir.getAbsolutePath());
        }

        Bitmap bitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgbaData, 0, expectedSize));

        try (FileOutputStream outputStream = new FileOutputStream(outputFile, false)) {
            boolean compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
            outputStream.flush();
            if (!compressed) {
                throw new IOException("bitmap compress returned false");
            }
        } finally {
            bitmap.recycle();
        }

        return new CaptureResult(
                outputFile.getAbsolutePath(),
                frameWidth,
                frameHeight,
                System.currentTimeMillis()
        );
    }

    /** 开始录制当前播放中的流。 */
    public synchronized String startRecording(String outputPath) {
        checkReleased();
        if (recordingState == RecordingState.RECORDING) {
            return "recording ignored: already recording\nfile: " + lastRecordingPath;
        }

        String result = nativeStartRecording(nativeHandle, outputPath);
        if (result != null && result.startsWith("recording started")) {
            recordingState = RecordingState.RECORDING;
            lastRecordingPath = outputPath == null ? "" : outputPath;
            dispatchInfo(INFO_RECORDING_START, result);
        } else {
            recordingState = RecordingState.FAILED;
            dispatchError(ERROR_RECORD_FAILED, result);
        }
        return result;
    }

    /** 停止录制当前播放中的流。 */
    public synchronized String stopRecording() {
        checkReleased();
        if (recordingState != RecordingState.RECORDING && !nativeIsRecording(nativeHandle)) {
            recordingState = RecordingState.IDLE;
            return "recording not running";
        }

        recordingState = RecordingState.STOPPING;
        String result = nativeStopRecording(nativeHandle);
        if (result != null && result.startsWith("recording stopped")) {
            recordingState = RecordingState.IDLE;
            dispatchInfo(INFO_RECORDING_END, result);
        } else if (result != null && result.startsWith("recording not running")) {
            recordingState = RecordingState.IDLE;
        } else {
            recordingState = RecordingState.FAILED;
            dispatchError(ERROR_RECORD_FAILED, result);
        }
        return result;
    }

    /** 返回当前是否正在录制。 */
    public synchronized boolean isRecording() {
        checkReleased();
        return nativeIsRecording(nativeHandle);
    }

    /** 返回当前录制状态。 */
    public synchronized RecordingState getRecordingState() {
        return recordingState;
    }

    /** 返回最近一次录制文件路径。 */
    public synchronized String getLastRecordingPath() {
        return lastRecordingPath;
    }

    /** 获取 FFmpeg 版本号。 */
    public synchronized String getFFmpegVersion() {
        checkReleased();
        return nativeGetFFmpegVersion(nativeHandle);
    }

    /** 释放播放器实例。 */
    public synchronized void release() {
        if (!released) {
            cancelReconnectLocked();
            stopRecordingIfNeeded();
            nativeRelease(nativeHandle);
            nativeHandle = 0;
            closeCurrentParcelFileDescriptor();
            released = true;
            prepared = false;
            playing = false;
            paused = false;
            recordingState = RecordingState.IDLE;
            audioFocusGranted = false;
            audioFocusRequest = null;
            resetPlaybackTiming();
            resetVideoSize();
            latestSeekLoadDurationMs = -1L;
            lastBufferingPercent = 100;
            state = State.RELEASED;

            releaseAudioTrack();
        }
    }

    @Override
    public void close() {
        release();
    }

    /** Native 回调：初始化 AudioTrack 参数。 */
    private synchronized void onNativeAudioInfo(int sampleRate, int channels) {
        releaseAudioTrack();

        int channelConfig = channels == 1
                ? AudioFormat.CHANNEL_OUT_MONO
                : AudioFormat.CHANNEL_OUT_STEREO;

        int minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
        );

        int bufferSize = Math.max(minBufferSize, sampleRate * channels * 2);
        audioDelayMs = sampleRate <= 0 || channels <= 0
                ? 0.0f
                : (bufferSize * 1000.0f) / (sampleRate * channels * 2.0f);

        audioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
        );

        applyAudioTrackVolume();
        applyAudioTrackSpeed();
        audioTrack.play();
        if (!audioRenderingStarted) {
            audioRenderingStarted = true;
            dispatchInfo(INFO_AUDIO_RENDERING_START, "audio rendering started");
        }
    }

    /** Native 回调：写入一段 PCM 数据到 AudioTrack。 */
    private void onNativeAudioData(byte[] data, int size) {
        AudioTrack track;

        synchronized (this) {
            track = audioTrack;
        }

        if (track != null && data != null && size > 0) {
            track.write(data, 0, size);
        }
    }

    /** Native 回调：播放器信息事件。 */
    private synchronized void onNativeInfo(int infoCode, String message) {
        if (infoCode == INFO_BUFFERING_START) {
            dispatchBufferingUpdate(bufferingStartPercent);
        } else if (infoCode == INFO_BUFFERING_END) {
            dispatchBufferingUpdate(bufferingEndPercent);
        } else if (infoCode == INFO_MEDIACODEC_FORMAT_CHANGED) {
            updateVideoSizeFromNative();
        }
        dispatchInfo(infoCode, message);
    }

    /** Native 回调：询问 Java 层是否允许指定 MediaCodec。 */
    @SuppressWarnings("unused")
    private synchronized boolean onNativeMediaCodecSelect(String mimeType, String codecName) {
        if (onMediaCodecSelectListener == null) {
            return true;
        }
        return onMediaCodecSelectListener.onMediaCodecSelect(
                this,
                mimeType == null ? "" : mimeType,
                codecName == null ? "" : codecName
        );
    }

    /** Native 回调：播放器错误事件。 */
    private synchronized void onNativeError(int errorCode, String message) {
        if (startReconnectIfNeededLocked(errorCode, message)) {
            return;
        }
        if (state != State.RELEASED) {
            state = State.ERROR;
            playing = false;
            paused = false;
        }
        dispatchError(errorCode, message);
    }

    /** Native 回调：视频尺寸发生变化。 */
    private synchronized void onNativeVideoSizeChanged(int width, int height) {
        updateVideoSize(width, height);
    }

    /** 释放当前 AudioTrack。 */
    private synchronized void releaseAudioTrack() {
        if (audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
            } catch (Exception ignored) {
            }

            audioTrack.release();
            audioTrack = null;
        }
    }

    /** 检查播放器是否已经释放。 */
    private void checkReleased() {
        if (released || nativeHandle == 0) {
            throw new IllegalStateException("ECHPlayer has been released");
        }
    }

    /** 如果正在录制则安全停止。 */
    private void stopRecordingIfNeeded() {
        if (nativeHandle != 0 && nativeIsRecording(nativeHandle)) {
            recordingState = RecordingState.STOPPING;
            nativeStopRecording(nativeHandle);
            recordingState = RecordingState.IDLE;
        }
    }

    /** 检查当前状态是否允许执行操作。 */
    private void requireState(State... allowedStates) {
        for (State allowedState : allowedStates) {
            if (state == allowedState) {
                return;
            }
        }

        String message = "Invalid player state: " + state;
        dispatchError(ERROR_INVALID_STATE, message);
        throw new IllegalStateException(message);
    }

    /** 分发错误回调。 */
    private void dispatchError(int errorCode, String message) {
        if (onErrorListener != null) {
            onErrorListener.onError(this, errorCode, message == null ? "" : message);
        }
    }

    /** 分发信息回调。 */
    private void dispatchInfo(int infoCode, String message) {
        if (onInfoListener != null) {
            onInfoListener.onInfo(this, infoCode, message == null ? "" : message);
        }
    }

    /** 分发播放完成回调。 */
    private void dispatchCompletion() {
        if (completionDispatched) {
            return;
        }

        if (looping && tryRestartLoopingPlayback()) {
            return;
        }

        completionDispatched = true;
        state = State.COMPLETED;
        playing = false;
        paused = false;
        if (onCompletionListener != null) {
            onCompletionListener.onCompletion(this);
        }
    }

    /** 分发 seek 完成回调。 */
    private void dispatchSeekComplete() {
        if (onSeekCompleteListener != null) {
            onSeekCompleteListener.onSeekComplete(this);
        }
    }

    /** 预留字幕文本分发入口，真实字幕解码在 v2.5 接入。 */
    @SuppressWarnings("unused")
    private void dispatchTimedText(ECHTimedText text) {
        if (onTimedTextListener != null) {
            onTimedTextListener.onTimedText(this, text);
        }
    }

    /** 按 stream index 查找轨道。 */
    private TrackInfo findTrackByIndex(int streamIndex) {
        List<TrackInfo> tracks = getTrackInfo();
        for (TrackInfo track : tracks) {
            if (track.streamIndex == streamIndex) {
                return track;
            }
        }
        return null;
    }

    /** 根据当前播放位置分发外挂字幕。 */
    private void dispatchExternalSubtitleForPosition(long positionMs) {
        if (externalSubtitleCues.isEmpty() || selectedTimedTextTrack == -1) {
            return;
        }

        int cueIndex = findSubtitleCueIndex(positionMs);
        if (cueIndex == lastDispatchedSubtitleCueIndex) {
            return;
        }

        lastDispatchedSubtitleCueIndex = cueIndex;
        if (cueIndex < 0) {
            dispatchTimedText(new ECHTimedText(null, ""));
            return;
        }

        SubtitleCue cue = externalSubtitleCues.get(cueIndex);
        dispatchTimedText(new ECHTimedText(null, cue.text));
    }

    /** 查找当前时间命中的外挂字幕片段。 */
    private int findSubtitleCueIndex(long positionMs) {
        for (int index = 0; index < externalSubtitleCues.size(); index++) {
            SubtitleCue cue = externalSubtitleCues.get(index);
            if (positionMs >= cue.startMs && positionMs <= cue.endMs) {
                return index;
            }
        }
        return -1;
    }

    /** 循环播放时尝试回到开头并重新启动播放。 */
    private boolean tryRestartLoopingPlayback() {
        if (!nativeIsSeekable(nativeHandle)) {
            return false;
        }

        String seekResult = nativeSeekToMs(nativeHandle, 0L);
        if (seekResult == null || !seekResult.startsWith("seek success")) {
            return false;
        }

        dispatchInfo(INFO_SEEK_COMPLETE, seekResult);
        dispatchSeekComplete();
        markStartRequested();
        String startResult = nativePlay(nativeHandle);
        if (startResult != null
                && (startResult.startsWith("play started")
                || startResult.startsWith("play ignored"))) {
            playing = true;
            paused = false;
            state = State.STARTED;
            completionDispatched = false;
            dispatchInfo(INFO_PLAY_STARTED, "loop restart\n" + startResult);
            return true;
        }
        return false;
    }

    /** 分发缓冲进度回调。 */
    private void dispatchBufferingUpdate(int percent) {
        lastBufferingPercent = clampPercent(percent);
        if (onBufferingUpdateListener != null) {
            onBufferingUpdateListener.onBufferingUpdate(this, lastBufferingPercent);
        }
    }

    /** 把百分比限制到 0 到 100。 */
    private int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    /** 取消当前正在等待或执行的重连任务。 */
    private void cancelReconnectLocked() {
        reconnecting = false;
        reconnectGeneration++;
    }

    /** 判断当前错误是否应该触发自动重连。 */
    private boolean startReconnectIfNeededLocked(int errorCode, String message) {
        if (!shouldReconnectLocked(errorCode)) {
            return false;
        }
        if (reconnecting) {
            return true;
        }

        reconnecting = true;
        prepared = false;
        playing = false;
        paused = false;
        state = State.PREPARING;
        int generation = ++reconnectGeneration;
        String dataSource = currentDataSource;
        String reason = message == null ? "" : message;

        Thread reconnectThread = new Thread(
                () -> runReconnectLoop(generation, dataSource, errorCode, reason),
                "ECHPlayer-Reconnect"
        );
        reconnectThread.start();
        return true;
    }

    /** 判断当前状态和错误码是否允许自动重连。 */
    private boolean shouldReconnectLocked(int errorCode) {
        if (!reconnectEnabled || released || nativeHandle == 0 || reconnectMaxCount <= 0) {
            return false;
        }
        if (!isRtspDataSourceLocked()) {
            return false;
        }
        if (state == State.STOPPED || state == State.RELEASED || state == State.IDLE) {
            return false;
        }
        return errorCode == ERROR_NETWORK_TIMEOUT
                || errorCode == ERROR_OPEN_INPUT_FAILED
                || errorCode == ERROR_STREAM_INFO_FAILED
                || errorCode == ERROR_UNKNOWN;
    }

    /** 判断当前数据源是否是 RTSP 地址。 */
    private boolean isRtspDataSourceLocked() {
        String lowerSource = currentDataSource == null
                ? ""
                : currentDataSource.toLowerCase(Locale.US);
        return lowerSource.startsWith("rtsp://");
    }

    /** 执行自动重连循环，直到成功、取消或达到最大次数。 */
    private void runReconnectLoop(
            int generation,
            String dataSource,
            int originalErrorCode,
            String originalReason) {

        while (true) {
            int attempt;
            long delayMs;
            synchronized (this) {
                if (!isReconnectActiveLocked(generation)) {
                    return;
                }
                if (reconnectCount >= reconnectMaxCount) {
                    finishReconnectFailedLocked(originalErrorCode, originalReason);
                    return;
                }
                reconnectCount++;
                attempt = reconnectCount;
                delayMs = reconnectIntervalMs;
                dispatchInfo(
                        INFO_RECONNECTING,
                        "reconnecting"
                                + "\nattempt: " + attempt + "/" + reconnectMaxCount
                                + "\nreason: " + originalReason
                );
            }

            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    synchronized (this) {
                        finishReconnectFailedLocked(originalErrorCode, "reconnect interrupted");
                    }
                    return;
                }
            }

            synchronized (this) {
                if (!isReconnectActiveLocked(generation)) {
                    return;
                }

                nativeStop(nativeHandle);
                releaseAudioTrack();
                resetPlaybackEventFlags();
                prepared = false;
                playing = false;
                paused = false;
                state = State.PREPARING;

                nativeSetDataSource(nativeHandle, dataSource);
                nativeSetRtspTransport(nativeHandle, rtspTransport);
                nativeSetRenderMode(nativeHandle, renderMode);
                nativeSetDecodeMode(nativeHandle, decodeMode);

                markPrepareStarted();
                lastPrepareResult = nativePrepare(nativeHandle);
                markPrepareFinished();
                updateDecodeInfoFromNative();
                prepared = lastPrepareResult != null
                        && lastPrepareResult.startsWith("prepare success");
                if (!prepared) {
                    dispatchInfo(
                            INFO_RECONNECTING,
                            "reconnect attempt failed"
                                    + "\nattempt: " + attempt + "/" + reconnectMaxCount
                                    + "\nresult: " + lastPrepareResult
                    );
                    continue;
                }

                updateVideoSizeFromNative();
                dispatchInfo(INFO_PREPARED, lastPrepareResult);
                dispatchBufferingUpdate(100);

                markStartRequested();
                lastStartResult = nativePlay(nativeHandle);
                if (lastStartResult != null
                        && (lastStartResult.startsWith("play started")
                        || lastStartResult.startsWith("play ignored"))) {
                    playing = true;
                    paused = false;
                    state = State.STARTED;
                    reconnecting = false;
                    completionDispatched = false;
                    dispatchInfo(
                            INFO_RECONNECTED,
                            "reconnected"
                                    + "\nattempt: " + attempt + "/" + reconnectMaxCount
                                    + "\nsource: " + dataSource
                    );
                    dispatchInfo(INFO_PLAY_STARTED, lastStartResult);
                    return;
                }

                dispatchInfo(
                        INFO_RECONNECTING,
                        "reconnect start failed"
                                + "\nattempt: " + attempt + "/" + reconnectMaxCount
                                + "\nresult: " + lastStartResult
                );
            }
        }
    }

    /** 判断指定重连代次是否仍然有效。 */
    private boolean isReconnectActiveLocked(int generation) {
        return reconnecting
                && !released
                && nativeHandle != 0
                && reconnectGeneration == generation;
    }

    /** 结束自动重连并分发最终错误。 */
    private void finishReconnectFailedLocked(int errorCode, String reason) {
        reconnecting = false;
        prepared = false;
        playing = false;
        paused = false;
        state = State.ERROR;
        dispatchInfo(
                INFO_RECONNECT_FAILED,
                "reconnect failed"
                        + "\nattempts: " + reconnectCount + "/" + reconnectMaxCount
                        + "\nreason: " + reason
        );
        dispatchError(errorCode, reason);
    }

    /** 分发视频尺寸变化回调。 */
    private void dispatchVideoSizeChanged(int width, int height) {
        if (onVideoSizeChangedListener != null) {
            onVideoSizeChangedListener.onVideoSizeChanged(this, width, height);
        }
    }

    /** 从 NativePlayer 刷新当前视频尺寸。 */
    private void updateVideoSizeFromNative() {
        if (nativeHandle == 0) {
            return;
        }

        int width = nativeGetVideoWidth(nativeHandle);
        int height = nativeGetVideoHeight(nativeHandle);
        updateVideoSize(width, height);
    }

    /** 更新当前视频尺寸并在变化时通知外部。 */
    private void updateVideoSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }

        if (videoWidth == width && videoHeight == height) {
            return;
        }

        videoWidth = width;
        videoHeight = height;
        dispatchVideoSizeChanged(width, height);
    }

    /** 重置当前视频尺寸。 */
    private void resetVideoSize() {
        videoWidth = 0;
        videoHeight = 0;
    }

    /** 规范化外部传入的渲染模式。 */
    private int normalizeRenderMode(int requestedRenderMode) {
        if (requestedRenderMode == RENDER_MODE_OPENGL
                || requestedRenderMode == RENDER_MODE_NATIVE_WINDOW) {
            return requestedRenderMode;
        }
        return RENDER_MODE_AUTO;
    }

    /** 规范化外部传入的解码模式。 */
    private int normalizeDecodeMode(int requestedDecodeMode) {
        if (requestedDecodeMode == DECODE_MODE_SOFTWARE
                || requestedDecodeMode == DECODE_MODE_MEDIACODEC) {
            return requestedDecodeMode;
        }
        return DECODE_MODE_AUTO;
    }

    /** 根据字符串解析解码模式。 */
    private int decodeModeFromText(String value) {
        if (value == null) {
            return DECODE_MODE_AUTO;
        }
        if (OPTION_VALUE_DECODE_SOFTWARE.equalsIgnoreCase(value)) {
            return DECODE_MODE_SOFTWARE;
        }
        if (OPTION_VALUE_DECODE_MEDIACODEC.equalsIgnoreCase(value)
                || "hardware".equalsIgnoreCase(value)
                || "hard".equalsIgnoreCase(value)) {
            return DECODE_MODE_MEDIACODEC;
        }
        return DECODE_MODE_AUTO;
    }

    /** 解析常见布尔 option 文本。 */
    private boolean parseBooleanOption(String value) {
        return "1".equals(value)
                || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value)
                || "enable".equalsIgnoreCase(value)
                || "enabled".equalsIgnoreCase(value);
    }

    /** 把 Java 层 MediaCodec 配置同步给 native。 */
    private void applyMediaCodecOptions() {
        if (nativeHandle == 0) {
            return;
        }
        nativeSetLongOption(nativeHandle, OPTION_CATEGORY_PLAYER,
                OPTION_MEDIACODEC_AVC, mediaCodecAvcEnabled ? 1L : 0L);
        nativeSetLongOption(nativeHandle, OPTION_CATEGORY_PLAYER,
                OPTION_MEDIACODEC_HEVC, mediaCodecHevcEnabled ? 1L : 0L);
        nativeSetLongOption(nativeHandle, OPTION_CATEGORY_PLAYER,
                OPTION_MEDIACODEC_AUTO_ROTATE, mediaCodecAutoRotateEnabled ? 1L : 0L);
        nativeSetLongOption(nativeHandle, OPTION_CATEGORY_PLAYER,
                OPTION_MEDIACODEC_HANDLE_RESOLUTION_CHANGE,
                mediaCodecHandleResolutionChangeEnabled ? 1L : 0L);
    }

    /** 记录 long option 快照。 */
    private void rememberLongOption(int category, String name, long value) {
        if (name == null || name.length() == 0) {
            return;
        }
        longOptions.put(buildOptionKey(category, name), value);
    }

    /** 记录已经成功下发到 native 的 long option。 */
    private boolean rememberNativeLongOption(int category, String name, long value) {
        boolean handled = nativeSetLongOption(nativeHandle, category, name, value);
        if (handled) {
            rememberLongOption(category, name, value);
        }
        return handled;
    }

    /** 记录 String option 快照。 */
    private void rememberStringOption(int category, String name, String value) {
        if (name == null || name.length() == 0) {
            return;
        }
        stringOptions.put(buildOptionKey(category, name), value == null ? "" : value);
    }

    /** 构造稳定的 option 快照 key。 */
    private String buildOptionKey(int category, String name) {
        return category + ":" + name;
    }

    /** 将当前解码类型映射为 property 值。 */
    private long decoderTypeToPropertyValue(String decodeType) {
        if ("mediacodec".equalsIgnoreCase(decodeType)) {
            return PROP_DECODER_MEDIACODEC;
        }
        if ("software".equalsIgnoreCase(decodeType)) {
            return PROP_DECODER_AVCODEC;
        }
        return PROP_DECODER_UNKNOWN;
    }

    /** 根据 Uri 解析可直接交给 FFmpeg 的数据源。 */
    private String resolveUriDataSource(Context context, Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null || scheme.length() == 0) {
            String path = uri.getPath();
            if (path == null || path.length() == 0) {
                throw new IllegalArgumentException("uri path is empty");
            }
            return path;
        }

        if ("file".equalsIgnoreCase(scheme)) {
            String path = uri.getPath();
            if (path == null || path.length() == 0) {
                throw new IllegalArgumentException("file uri path is empty");
            }
            return path;
        }

        if ("http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme)
                || "rtsp".equalsIgnoreCase(scheme)) {
            return uri.toString();
        }

        return uri.toString();
    }

    /** 打开 content Uri 并转成 FFmpeg 可访问的 fd 路径。 */
    private void setContentUriDataSource(Context context, Uri uri, Map<String, String> headers) {
        if (context == null) {
            throw new IllegalArgumentException("context is null for content uri");
        }

        ParcelFileDescriptor parcelFileDescriptor = null;
        try {
            parcelFileDescriptor = context.getContentResolver().openFileDescriptor(uri, "r");
            if (parcelFileDescriptor == null) {
                throw new IllegalArgumentException("cannot open content uri: " + uri);
            }
            setResolvedDataSource(resolveParcelFileDescriptorDataSource(parcelFileDescriptor),
                    headers, parcelFileDescriptor);
            parcelFileDescriptor = null;
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot open content uri: " + uri, e);
        } finally {
            if (parcelFileDescriptor != null) {
                try {
                    parcelFileDescriptor.close();
                } catch (IOException ignored) {
                    // 打开失败时尽量释放临时 fd，避免泄漏。
                }
            }
        }
    }

    /** 把 ParcelFileDescriptor 转为 /proc/self/fd 路径。 */
    private String resolveParcelFileDescriptorDataSource(ParcelFileDescriptor parcelFileDescriptor) {
        int rawFd = parcelFileDescriptor.getFd();
        if (rawFd < 0) {
            throw new IllegalArgumentException("parcel file descriptor is invalid");
        }
        return "/proc/self/fd/" + rawFd;
    }

    /** 把普通 FileDescriptor 转为 /proc/self/fd 路径。 */
    private String resolveFileDescriptorDataSource(FileDescriptor fd) {
        int rawFd = readRawFileDescriptor(fd);
        if (rawFd < 0) {
            throw new IllegalArgumentException("file descriptor is invalid");
        }
        return "/proc/self/fd/" + rawFd;
    }

    /** 通过 Android/Java 常见字段读取原始 fd 编号。 */
    private int readRawFileDescriptor(FileDescriptor fd) {
        try {
            Method getIntMethod = FileDescriptor.class.getDeclaredMethod("getInt$");
            getIntMethod.setAccessible(true);
            Object value = getIntMethod.invoke(fd);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Exception ignored) {
            // 不同 Android/Java 版本可能没有 getInt$，继续尝试 descriptor 字段。
        }

        try {
            Field descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            descriptorField.setAccessible(true);
            return descriptorField.getInt(fd);
        } catch (Exception e) {
            throw new UnsupportedOperationException("cannot read raw file descriptor", e);
        }
    }

    /** 将自定义数据源复制到文件，作为当前阶段的简单可维护实现。 */
    private void copyCustomDataSourceToFile(ECHMediaDataSource dataSource, File cacheFile)
            throws IOException {
        File parentDir = cacheFile.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("cannot create cache dir: " + parentDir.getAbsolutePath());
        }

        try {
            long position = 0L;
            long size = dataSource.getSize();
            byte[] buffer = new byte[64 * 1024];
            try (FileOutputStream outputStream = new FileOutputStream(cacheFile, false)) {
                while (size < 0 || position < size) {
                    int maxRead = size < 0
                            ? buffer.length
                            : (int) Math.min(buffer.length, size - position);
                    int readSize = dataSource.readAt(position, buffer, 0, maxRead);
                    if (readSize <= 0) {
                        break;
                    }
                    outputStream.write(buffer, 0, readSize);
                    position += readSize;
                }
                outputStream.flush();
            }
        } finally {
            dataSource.close();
        }
    }

    /** 关闭当前播放器持有的 content Uri fd。 */
    private void closeCurrentParcelFileDescriptor() {
        if (currentParcelFileDescriptor == null) {
            return;
        }
        try {
            currentParcelFileDescriptor.close();
        } catch (IOException ignored) {
            // reset/release 阶段忽略关闭失败，避免掩盖主流程状态。
        } finally {
            currentParcelFileDescriptor = null;
        }
    }

    /** 保存并下发当前数据源 headers。 */
    private void applyDataSourceHeaders(Map<String, String> headers) {
        currentDataSourceHeaders.clear();
        if (headers == null || headers.isEmpty()) {
            nativeSetStringOption(nativeHandle, OPTION_CATEGORY_FORMAT, OPTION_HEADERS, "");
            stringOptions.remove(buildOptionKey(OPTION_CATEGORY_FORMAT, OPTION_HEADERS));
            return;
        }

        currentDataSourceHeaders.putAll(headers);
        String headerText = buildFfmpegHeaders(headers);
        nativeSetStringOption(nativeHandle, OPTION_CATEGORY_FORMAT, OPTION_HEADERS, headerText);
        rememberStringOption(OPTION_CATEGORY_FORMAT, OPTION_HEADERS, headerText);
    }

    /** 把 headers Map 转为 FFmpeg 需要的多行 Header 文本。 */
    private String buildFfmpegHeaders(Map<String, String> headers) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.length() == 0 || value == null) {
                continue;
            }
            builder.append(key).append(": ").append(value).append("\r\n");
        }
        return builder.toString();
    }

    /** 把外部音量归一化到 AudioTrack 可接受的范围。 */
    private float normalizeVolume(float volume) {
        if (Float.isNaN(volume)) {
            return 1.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, volume));
    }

    /** 把当前音量应用到 AudioTrack。 */
    private void applyAudioTrackVolume() {
        if (audioTrack == null) {
            return;
        }
        float appliedLeftVolume = muted ? 0.0f : leftVolume;
        float appliedRightVolume = muted ? 0.0f : rightVolume;
        audioTrack.setStereoVolume(appliedLeftVolume, appliedRightVolume);
    }

    /** 安全设置静音状态，允许音频焦点回调在任意生命周期进入。 */
    private synchronized void setMutedInternal(boolean muted) {
        if (released || nativeHandle == 0) {
            return;
        }
        this.muted = muted;
        applyAudioTrackVolume();
    }

    /** 把当前速度设置应用到 AudioTrack。 */
    private void applyAudioTrackSpeed() {
        if (audioTrack == null || audioTrack.getPlaybackRate() <= 0) {
            return;
        }
        int targetRate = Math.max(1, Math.round(audioTrack.getSampleRate() * playbackSpeed));
        try {
            audioTrack.setPlaybackRate(targetRate);
        } catch (IllegalArgumentException ignored) {
            // 部分设备不接受过高或过低采样率，保留期望速度，后续 native pipeline 再深接。
        }
    }

    /** 解析 SRT 字幕文本。 */
    private List<SubtitleCue> parseSrtCues(String subtitleText) {
        List<SubtitleCue> cues = new ArrayList<>();
        if (subtitleText == null || subtitleText.length() == 0) {
            return cues;
        }

        String normalizedText = subtitleText.replace("\r\n", "\n").replace('\r', '\n');
        String[] blocks = normalizedText.split("\n\n+");
        for (String block : blocks) {
            SubtitleCue cue = parseSrtCueBlock(block);
            if (cue != null) {
                cues.add(cue);
            }
        }
        return cues;
    }

    /** 解析单个 SRT 字幕块。 */
    private SubtitleCue parseSrtCueBlock(String block) {
        if (block == null) {
            return null;
        }
        String[] lines = block.trim().split("\n");
        if (lines.length < 2) {
            return null;
        }

        int timingLineIndex = lines[0].contains("-->") ? 0 : 1;
        if (timingLineIndex >= lines.length || !lines[timingLineIndex].contains("-->")) {
            return null;
        }

        String[] range = lines[timingLineIndex].split("-->");
        if (range.length != 2) {
            return null;
        }

        long startMs = parseSrtTimeMs(range[0].trim());
        long endMs = parseSrtTimeMs(range[1].trim());
        if (startMs < 0L || endMs <= startMs) {
            return null;
        }

        StringBuilder textBuilder = new StringBuilder();
        for (int index = timingLineIndex + 1; index < lines.length; index++) {
            if (textBuilder.length() > 0) {
                textBuilder.append('\n');
            }
            textBuilder.append(lines[index]);
        }
        return new SubtitleCue(startMs, endMs, textBuilder.toString());
    }

    /** 解析 SRT 时间戳为毫秒。 */
    private long parseSrtTimeMs(String value) {
        String normalized = value.replace(',', '.');
        String[] parts = normalized.split(":");
        if (parts.length != 3) {
            return -1L;
        }
        try {
            long hours = Long.parseLong(parts[0].trim());
            long minutes = Long.parseLong(parts[1].trim());
            String[] secondParts = parts[2].split("\\.");
            long seconds = Long.parseLong(secondParts[0].trim());
            long millis = secondParts.length > 1
                    ? parseMillisPart(secondParts[1].trim())
                    : 0L;
            return hours * 3600000L + minutes * 60000L + seconds * 1000L + millis;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    /** 把 SRT 毫秒片段补齐到三位。 */
    private long parseMillisPart(String value) {
        if (value.length() == 0) {
            return 0L;
        }
        String millisText = value.length() >= 3
                ? value.substring(0, 3)
                : (value + "000").substring(0, 3);
        return Long.parseLong(millisText);
    }

    /** 从 native 刷新当前实际解码信息。 */
    private void updateDecodeInfoFromNative() {
        if (nativeHandle == 0) {
            return;
        }

        String decodeType = nativeGetCurrentDecodeType(nativeHandle);
        String decoderName = nativeGetCurrentDecoderName(nativeHandle);
        String fallbackReason = nativeGetLastDecodeFallbackReason(nativeHandle);
        currentDecodeType = decodeType == null || decodeType.length() == 0 ? "software" : decodeType;
        currentDecoderName = decoderName == null || decoderName.length() == 0 ? "ffmpeg" : decoderName;
        lastDecodeFallbackReason = fallbackReason == null ? "" : fallbackReason;
    }

    /** 在首帧解码后分发视频渲染开始事件。 */
    private void dispatchVideoRenderingStartIfReady() {
        if (videoRenderingStarted || state != State.STARTED) {
            return;
        }

        int[] frameSize = nativeGetCurrentFrameSize(nativeHandle);
        int width = frameSize != null && frameSize.length >= 2 ? frameSize[0] : 0;
        int height = frameSize != null && frameSize.length >= 2 ? frameSize[1] : 0;
        if (width > 0 && height > 0) {
            videoRenderingStarted = true;
            if (startCallTimeMs > 0 && firstFrameCostMs < 0L) {
                firstFrameCostMs = Math.max(0L, System.currentTimeMillis() - startCallTimeMs);
            }
            dispatchInfo(
                    INFO_VIDEO_RENDERING_START,
                    "video rendering started\nfirstFrameCostMs: " + firstFrameCostMs
            );
        }
    }

    /** 重置 Java 层播放耗时统计。 */
    private void resetPlaybackTiming() {
        prepareStartTimeMs = 0L;
        lastPrepareCostMs = -1L;
        startCallTimeMs = 0L;
        firstFrameCostMs = -1L;
    }

    /** 记录 prepare 开始时间。 */
    private void markPrepareStarted() {
        prepareStartTimeMs = System.currentTimeMillis();
        lastPrepareCostMs = -1L;
    }

    /** 记录 prepare 完成耗时。 */
    private void markPrepareFinished() {
        if (prepareStartTimeMs > 0L) {
            lastPrepareCostMs = Math.max(0L, System.currentTimeMillis() - prepareStartTimeMs);
        } else {
            lastPrepareCostMs = -1L;
        }
        prepareStartTimeMs = 0L;
    }

    /** 记录 start 发起时间，用于计算首帧耗时。 */
    private void markStartRequested() {
        startCallTimeMs = System.currentTimeMillis();
        firstFrameCostMs = -1L;
    }

    /** 解析 Native 返回的媒体信息文本。 */
    private MediaInfo parseMediaInfo(String text) {
        Map<String, String> values = parseKeyValueLines(text);
        return new MediaInfo(
                valueOrEmpty(values, "format"),
                parseLong(values.get("durationMs"), 0L),
                parseLong(values.get("bitRate"), 0L),
                parseInt(values.get("videoStreamIndex"), -1),
                parseInt(values.get("audioStreamIndex"), -1),
                valueOrEmpty(values, "videoCodec"),
                parseInt(values.get("videoWidth"), 0),
                parseInt(values.get("videoHeight"), 0),
                valueOrEmpty(values, "audioCodec"),
                parseInt(values.get("audioSampleRate"), 0),
                parseInt(values.get("audioChannels"), 0)
        );
    }

    /** 解析 Native 返回的轨道信息文本。 */
    private List<TrackInfo> parseTrackInfo(String text) {
        List<TrackInfo> tracks = new ArrayList<>();
        if (text == null || text.length() == 0) {
            return tracks;
        }

        Map<String, String> current = null;
        String[] lines = text.split("\\n");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.length() == 0) {
                continue;
            }
            if ("track".equals(line)) {
                addTrackInfoFromMap(tracks, current);
                current = new HashMap<>();
                continue;
            }
            if (current == null) {
                current = new HashMap<>();
            }
            putKeyValueLine(current, line);
        }
        addTrackInfoFromMap(tracks, current);
        return tracks;
    }

    /** 从键值表创建并加入一条轨道信息。 */
    private void addTrackInfoFromMap(List<TrackInfo> tracks, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        tracks.add(new TrackInfo(
                valueOrEmpty(values, "type"),
                parseInt(values.get("index"), -1),
                valueOrEmpty(values, "codec"),
                valueOrEmpty(values, "language"),
                parseInt(values.get("width"), 0),
                parseInt(values.get("height"), 0),
                parseInt(values.get("sampleRate"), 0),
                parseInt(values.get("channels"), 0)
        ));
    }

    /** 解析多行 key=value 文本为 Map。 */
    private Map<String, String> parseKeyValueLines(String text) {
        Map<String, String> values = new HashMap<>();
        if (text == null || text.length() == 0) {
            return values;
        }

        String[] lines = text.split("\\n");
        for (String rawLine : lines) {
            putKeyValueLine(values, rawLine == null ? "" : rawLine.trim());
        }
        return values;
    }

    /** 把单行 key=value 放入 Map。 */
    private void putKeyValueLine(Map<String, String> values, String line) {
        int separatorIndex = line.indexOf('=');
        if (separatorIndex <= 0) {
            return;
        }

        String key = line.substring(0, separatorIndex).trim();
        String value = line.substring(separatorIndex + 1).trim();
        if (key.length() > 0) {
            values.put(key, value);
        }
    }

    /** 从 Map 读取字符串，空值统一返回空字符串。 */
    private String valueOrEmpty(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null ? "" : value;
    }

    /** 安全解析 long，失败时返回默认值。 */
    private long parseLong(String value, long defaultValue) {
        if (value == null || value.length() == 0) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /** 安全解析 int，失败时返回默认值。 */
    private int parseInt(String value, int defaultValue) {
        if (value == null || value.length() == 0) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /** 重置单次播放过程里的事件标记。 */
    private void resetPlaybackEventFlags() {
        videoRenderingStarted = false;
        audioRenderingStarted = false;
        completionDispatched = false;
        seeking = false;
    }

    /** 根据 prepare 结果映射错误码。 */
    private int mapPrepareErrorCode(String result) {
        if (result == null) {
            return ERROR_UNKNOWN;
        }
        if (result.contains("avformat_open_input")) {
            String lowerResult = result.toLowerCase();
            if (result.contains("401 Unauthorized")
                    || lowerResult.contains("unauthorized")
                    || result.contains("鉴权失败")) {
                return ERROR_RTSP_AUTH_FAILED;
            }
            if (result.contains("Connection timed out")
                    || lowerResult.contains("timed out")
                    || lowerResult.contains("timeout")) {
                return ERROR_NETWORK_TIMEOUT;
            }
            return ERROR_OPEN_INPUT_FAILED;
        }
        if (result.contains("avformat_find_stream_info")) {
            return ERROR_STREAM_INFO_FAILED;
        }
        if (result.contains("no video stream")) {
            return ERROR_NO_VIDEO_STREAM;
        }
        return ERROR_UNKNOWN;
    }

    /** 根据播放结果映射错误码。 */
    private int mapPlaybackErrorCode(String result) {
        if (result == null) {
            return ERROR_UNKNOWN;
        }
        if (result.contains("surface is null")) {
            return ERROR_RENDER_SURFACE_INVALID;
        }
        return ERROR_UNKNOWN;
    }

    /** 创建 NativePlayer 并返回指针。 */
    private native long nativeInit();

    /** 销毁 NativePlayer。 */
    private native void nativeRelease(long nativeHandle);

    /** 设置 NativePlayer 数据源。 */
    private native void nativeSetDataSource(long nativeHandle, String dataSource);

    /** 设置 NativePlayer Surface。 */
    private native void nativeSetSurface(long nativeHandle, Surface surface);

    /** 设置 NativePlayer 的 Surface 缩放方式。 */
    private native void nativeSetSurfaceScaleType(long nativeHandle, int scaleType);

    /** 设置 NativePlayer 的渲染模式。 */
    private native void nativeSetRenderMode(long nativeHandle, int renderMode);

    /** 设置 NativePlayer 的解码模式。 */
    private native void nativeSetDecodeMode(long nativeHandle, int decodeMode);

    /** 设置 NativePlayer 的 RTSP 传输方式。 */
    private native void nativeSetRtspTransport(long nativeHandle, int transport);

    /** 设置 NativePlayer 的 long 类型 option。 */
    private native boolean nativeSetLongOption(long nativeHandle, int category, String name, long value);

    /** 设置 NativePlayer 的 String 类型 option。 */
    private native boolean nativeSetStringOption(
            long nativeHandle,
            int category,
            String name,
            String value
    );

    /** 调用 NativePlayer.prepare。 */
    private native String nativePrepare(long nativeHandle);

    /** 调用 NativePlayer.play。 */
    private native String nativePlay(long nativeHandle);

    /** 调用 NativePlayer.pause。 */
    private native void nativePause(long nativeHandle);

    /** 调用 NativePlayer.resume。 */
    private native void nativeResume(long nativeHandle);

    /** 调用 NativePlayer.stop。 */
    private native void nativeStop(long nativeHandle);

    /** 调用 NativePlayer.seekToMs。 */
    private native String nativeSeekToMs(long nativeHandle, long positionMs);

    /** 调用 NativePlayer.getDurationMs。 */
    private native long nativeGetDurationMs(long nativeHandle);

    /** 调用 NativePlayer.getCurrentPositionMs。 */
    private native long nativeGetCurrentPositionMs(long nativeHandle);

    /** 调用 NativePlayer.getReadBytes。 */
    private native long nativeGetReadBytes(long nativeHandle);

    /** 调用 NativePlayer.getReadSpeedBytesPerSecond。 */
    private native long nativeGetReadSpeedBytesPerSecond(long nativeHandle);

    /** 调用 NativePlayer.getVideoPacketQueueSize。 */
    private native int nativeGetVideoPacketQueueSize(long nativeHandle);

    /** 调用 NativePlayer.getAudioPacketQueueSize。 */
    private native int nativeGetAudioPacketQueueSize(long nativeHandle);

    /** 调用 NativePlayer.getBufferedPercent。 */
    private native int nativeGetBufferedPercent(long nativeHandle);

    /** 调用 NativePlayer.getDecodeFps。 */
    private native double nativeGetDecodeFps(long nativeHandle);

    /** 调用 NativePlayer.getRenderFps。 */
    private native double nativeGetRenderFps(long nativeHandle);

    /** 调用 NativePlayer.getDecodedFrameCount。 */
    private native long nativeGetDecodedFrameCount(long nativeHandle);

    /** 调用 NativePlayer.getRenderedFrameCount。 */
    private native long nativeGetRenderedFrameCount(long nativeHandle);

    /** 调用 NativePlayer.getDroppedFrameCount。 */
    private native long nativeGetDroppedFrameCount(long nativeHandle);

    /** 调用 NativePlayer.getMediaInfoText。 */
    private native String nativeGetMediaInfoText(long nativeHandle);

    /** 调用 NativePlayer.getTrackInfoText。 */
    private native String nativeGetTrackInfoText(long nativeHandle);

    /** 调用 NativePlayer.isSeekable。 */
    private native boolean nativeIsSeekable(long nativeHandle);

    /** 调用 NativePlayer.getVideoWidth。 */
    private native int nativeGetVideoWidth(long nativeHandle);

    /** 调用 NativePlayer.getVideoHeight。 */
    private native int nativeGetVideoHeight(long nativeHandle);

    /** 调用 NativePlayer.copyCurrentFrameRgba。 */
    private native byte[] nativeGetCurrentFrameRgba(long nativeHandle);

    /** 调用 NativePlayer.copyCurrentFrameSnapshot 的宽高部分。 */
    private native int[] nativeGetCurrentFrameSize(long nativeHandle);

    /** 调用 NativePlayer.startRecording。 */
    private native String nativeStartRecording(long nativeHandle, String outputPath);

    /** 调用 NativePlayer.stopRecording。 */
    private native String nativeStopRecording(long nativeHandle);

    /** 调用 NativePlayer.isRecording。 */
    private native boolean nativeIsRecording(long nativeHandle);

    /** 调用 NativePlayer.getCurrentDecodeType。 */
    private native String nativeGetCurrentDecodeType(long nativeHandle);

    /** 调用 NativePlayer.getCurrentDecoderName。 */
    private native String nativeGetCurrentDecoderName(long nativeHandle);

    /** 调用 NativePlayer.getLastDecodeFallbackReason。 */
    private native String nativeGetLastDecodeFallbackReason(long nativeHandle);

    /** 调用 NativePlayer.getFFmpegVersion。 */
    private native String nativeGetFFmpegVersion(long nativeHandle);
}
