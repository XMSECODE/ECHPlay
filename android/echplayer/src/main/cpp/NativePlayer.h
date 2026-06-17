#ifndef ECHPLAY_NATIVE_PLAYER_H
#define ECHPLAY_NATIVE_PLAYER_H

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <jni.h>
#include <mutex>
#include <map>
#include <string>
#include <thread>
#include <vector>

#include "GlVideoRenderer.h"
#include "MediaCodecVideoDecoder.h"

struct AVFormatContext;
struct AVCodecParameters;
struct AVStream;
struct ANativeWindow;
struct AVFrame;
struct SwsContext;

/**
 * Native 播放器核心，负责解封装、解码、渲染、截图缓存、音频回调与录制。
 */
class NativePlayer {
public:
    /** 创建 Native 播放器实例。 */
    NativePlayer(JavaVM *vm, JNIEnv *env, jobject javaPlayer);

    /** 销毁 Native 播放器实例。 */
    ~NativePlayer();

    /** 设置数据源路径或网络地址。 */
    void setDataSource(const std::string &dataSource);

    /** 设置视频渲染目标 Surface。 */
    void setSurface(ANativeWindow *window);

    /** 设置 Surface 渲染缩放方式，0 保持比例居中，1 拉伸填满。 */
    void setSurfaceScaleType(int scaleType);

    /** 设置渲染模式，0 自动，1 OpenGL，2 NativeWindow。 */
    void setRenderMode(int renderMode);

    /** 设置解码模式，0 自动，1 软解，2 硬解优先。 */
    void setDecodeMode(int decodeMode);

    /** 设置 RTSP 传输方式，0 为 TCP，1 为 UDP。 */
    void setRtspTransport(int transport);

    /** 设置 long 类型播放器选项。 */
    bool setLongOption(int category, const std::string &name, int64_t value);

    /** 设置 String 类型播放器选项。 */
    bool setStringOption(int category, const std::string &name, const std::string &value);

    /** 打开数据源并读取流信息。 */
    std::string prepare();

    /** 启动播放线程。 */
    std::string play();

    /** 暂停播放。 */
    void pause();

    /** 恢复播放。 */
    void resume();

    /** 停止播放并回收线程。 */
    void stop();

    /** 跳转到指定毫秒位置。 */
    std::string seekToMs(int64_t positionMs);

    /** 获取媒体总时长，单位毫秒。 */
    int64_t getDurationMs();

    /** 获取当前播放位置，单位毫秒。 */
    int64_t getCurrentPositionMs();

    /** 获取累计读取字节数。 */
    int64_t getReadBytes();

    /** 获取最近一次计算出的读取速度，单位字节/秒。 */
    int64_t getReadSpeedBytesPerSecond();

    /** 获取视频 packet 队列长度。 */
    int getVideoPacketQueueSize();

    /** 获取音频 packet 队列长度。 */
    int getAudioPacketQueueSize();

    /** 获取当前缓冲百分比估算值。 */
    int getBufferedPercent();

    /** 获取平均视频解码帧率。 */
    double getDecodeFps();

    /** 获取平均视频渲染帧率。 */
    double getRenderFps();

    /** 获取累计解码视频帧数。 */
    int64_t getDecodedFrameCount();

    /** 获取累计渲染视频帧数。 */
    int64_t getRenderedFrameCount();

    /** 获取累计主动丢弃视频帧数。 */
    int64_t getDroppedFrameCount();

    /** 获取当前媒体信息，使用简单键值行返回给 Java 解析。 */
    std::string getMediaInfoText();

    /** 获取当前轨道信息，使用简单键值行返回给 Java 解析。 */
    std::string getTrackInfoText();

    /** 原子复制最近一帧解码快照。 */
    bool copyCurrentFrameSnapshot(
            std::vector<uint8_t> &rgbaData,
            int &frameWidth,
            int &frameHeight
    );

    /** 开始把当前播放中的码流录制为文件。 */
    std::string startRecording(const std::string &outputPath);

    /** 停止录制并落盘文件尾。 */
    std::string stopRecording();

    /** 返回当前是否正在录制。 */
    bool isRecording();

    /** 返回当前媒体是否支持 seek。 */
    bool isSeekable();

    /** 获取当前视频宽度。 */
    int getVideoWidth();

    /** 获取当前视频高度。 */
    int getVideoHeight();

    /** 获取当前实际解码方式。 */
    std::string getCurrentDecodeType();

    /** 获取当前实际解码器名称。 */
    std::string getCurrentDecoderName();

    /** 获取最近一次硬解回退原因。 */
    std::string getLastDecodeFallbackReason();

    /** 获取 FFmpeg 版本字符串。 */
    std::string getFFmpegVersion();

private:
    /** 当前数据源路径或 URL。 */
    std::string dataSource;
    /** 输入格式上下文。 */
    AVFormatContext *formatContext;

    /** 当前渲染窗口。 */
    ANativeWindow *nativeWindow;
    /** 渲染窗口互斥锁。 */
    std::mutex windowMutex;

    /** 视频流索引。 */
    int videoStreamIndex;
    /** 音频流索引。 */
    int audioStreamIndex;

    /** 是否已完成 prepare。 */
    bool prepared;
    /** 是否已开始释放资源。 */
    bool released;

    /** 当前是否处于播放中。 */
    std::atomic<bool> playing;
    /** 是否请求停止播放线程。 */
    std::atomic<bool> stopRequested;
    /** 当前是否处于暂停状态。 */
    std::atomic<bool> paused;
    /** demux 线程是否已读到结尾或退出。 */
    std::atomic<bool> demuxFinished;
    /** 当前仍在运行的播放工作线程数量。 */
    std::atomic<int> activePlaybackWorkers;
    /** 当前音频主时钟，单位微秒。 */
    std::atomic<int64_t> audioClockUs;
    /** 累计从 FFmpeg 读取到的 packet 字节数。 */
    std::atomic<int64_t> totalReadBytes;
    /** 当前测速窗口内累计的 packet 字节数。 */
    std::atomic<int64_t> speedWindowBytes;
    /** 最近一次计算出的读取速度，单位字节/秒。 */
    std::atomic<int64_t> readSpeedBytesPerSecond;
    /** 读取速度统计窗口的起始时间。 */
    std::chrono::steady_clock::time_point lastSpeedTime;
    /** 读取速度统计互斥锁，避免多线程同时更新窗口时间。 */
    std::mutex speedMutex;
    /** 累计解码出的视频帧数。 */
    std::atomic<int64_t> decodedFrameTotal;
    /** 累计渲染成功的视频帧数。 */
    std::atomic<int64_t> renderedFrameTotal;
    /** 累计主动丢弃的视频帧数。 */
    std::atomic<int64_t> droppedFrameTotal;
    /** 解码 FPS 统计起始时间。 */
    std::chrono::steady_clock::time_point decodeFpsStartTime;
    /** Surface 渲染缩放方式，0 保持比例居中，1 拉伸填满。 */
    std::atomic<int> surfaceScaleType;
    /** 当前渲染模式，0 自动，1 OpenGL，2 NativeWindow。 */
    std::atomic<int> renderMode;
    /** 当前解码模式，0 自动，1 软解，2 硬解优先。 */
    std::atomic<int> decodeMode;
    /** OpenGL ES 视频渲染器。 */
    GlVideoRenderer glVideoRenderer;
    /** OpenGL 渲染器互斥锁，避免 Surface 释放和渲染并发访问 EGL。 */
    std::mutex glRendererMutex;
    /** OpenGL 渲染是否已经失败，失败后回退 NativeWindow。 */
    std::atomic<bool> glRenderFailed;

    /** 解封装线程。 */
    std::thread demuxThread;
    /** 视频解码线程。 */
    std::thread playThread;
    /** 音频解码线程。 */
    std::thread audioThread;

    /** 音视频包队列互斥锁。 */
    std::mutex packetQueueMutex;
    /** 音视频包队列条件变量。 */
    std::condition_variable packetQueueCond;
    /** seek 操作互斥锁，避免连续 seek 并发改动解封装状态。 */
    std::mutex seekMutex;
    /** 视频包队列。 */
    std::deque<struct AVPacket *> videoPacketQueue;
    /** 音频包队列。 */
    std::deque<struct AVPacket *> audioPacketQueue;

    /** 视频缩放上下文缓存。 */
    SwsContext *swsContextCache;
    /** 截图用缩放上下文缓存。 */
    SwsContext *captureSwsContextCache;
    /** RGBA 帧缓存。 */
    AVFrame *rgbaFrameCache;
    /** 截图用原始尺寸 RGBA 帧缓存。 */
    AVFrame *captureFrameCache;
    /** RGBA 原始缓冲区缓存。 */
    std::vector<uint8_t> rgbaBufferCache;
    /** 截图用原始尺寸 RGBA 原始缓冲区缓存。 */
    std::vector<uint8_t> captureFrameBufferCache;
    /** 最近一帧截图缓存互斥锁。 */
    std::mutex captureFrameMutex;
    /** 最近一帧原始 RGBA 数据缓存。 */
    std::vector<uint8_t> captureFrameRgba;
    /** 最近一帧宽度。 */
    int captureFrameWidth;
    /** 最近一帧高度。 */
    int captureFrameHeight;
    /** 渲染缓存源宽度。 */
    int renderSrcWidth;
    /** 渲染缓存源高度。 */
    int renderSrcHeight;
    /** 渲染缓存源像素格式。 */
    int renderSrcFormat;
    /** 渲染缓存目标宽度。 */
    int renderDstWidth;
    /** 渲染缓存目标高度。 */
    int renderDstHeight;
    /** 截图缓存源宽度。 */
    int captureSrcWidth;
    /** 截图缓存源高度。 */
    int captureSrcHeight;
    /** 截图缓存源像素格式。 */
    int captureSrcFormat;
    /** RTSP 传输方式，0 为 TCP，1 为 UDP。 */
    int rtspTransport;
    /** 打开输入超时时间，单位微秒。 */
    int64_t openTimeoutUs;
    /** 网络读取超时时间，单位微秒。 */
    int64_t readWriteTimeoutUs;
    /** 网络输入缓冲大小，单位字节。 */
    int64_t inputBufferSize;
    /** RTSP 最大延迟，单位微秒。 */
    int64_t maxDelayUs;
    /** 字符串输入 option，例如 headers、user_agent、protocol_whitelist。 */
    std::map<std::string, std::string> stringOptions;
    /** 字符串输入 option 互斥锁。 */
    std::mutex optionMutex;
    /** 当前媒体是否支持 seek。 */
    bool seekable;
    /** 视频尺寸互斥锁。 */
    std::mutex videoSizeMutex;
    /** 当前视频宽度。 */
    int videoWidth;
    /** 当前视频高度。 */
    int videoHeight;
    /** 当前是否处于缓冲状态。 */
    std::atomic<bool> buffering;
    /** 解码信息互斥锁。 */
    std::mutex decodeInfoMutex;
    /** 当前实际解码方式，software 或 mediacodec。 */
    std::string currentDecodeType;
    /** 当前实际解码器名称。 */
    std::string currentDecoderName;
    /** 最近一次硬解失败回退原因。 */
    std::string lastDecodeFallbackReason;

    /** JavaVM 指针，用于子线程回调 Java。 */
    JavaVM *javaVm;
    /** Java 播放器对象全局引用。 */
    jobject javaPlayerObject;
    /** 音频参数回调方法。 */
    jmethodID onNativeAudioInfoMethod;
    /** PCM 数据回调方法。 */
    jmethodID onNativeAudioDataMethod;
    /** Native 信息事件回调方法。 */
    jmethodID onNativeInfoMethod;
    /** Native 错误事件回调方法。 */
    jmethodID onNativeErrorMethod;
    /** Native 视频尺寸变化回调方法。 */
    jmethodID onNativeVideoSizeChangedMethod;

    /** 录制输出上下文。 */
    AVFormatContext *recordFormatContext;
    /** 当前是否正在录制。 */
    bool recording;
    /** 录制状态互斥锁。 */
    std::mutex recordMutex;
    /** 输入流到录制输出流的索引映射。 */
    std::vector<int> recordStreamMapping;
    /** 每个流录制起始 DTS，用于把录制时间归零。 */
    std::vector<int64_t> recordStartDts;
    /** 每个输入流是否已经允许写入录制文件。 */
    std::vector<bool> recordStreamReady;
    /** 当前录制文件路径。 */
    std::string recordingOutputPath;
    /** 当前录制输出是否已成功写入文件头。 */
    bool recordHeaderWritten;

private:
    /** 解封装线程循环。 */
    void demuxLoop();

    /** 视频解码线程循环。 */
    void decodeLoop();

    /** 尝试使用 MediaCodec 硬解视频，失败时返回 false 让上层回退软解。 */
    bool tryMediaCodecDecodeLoop(
            AVStream *videoStream,
            const AVCodecParameters *codecParameters
    );

    /** 将 MediaCodec 输出帧包装成 AVFrame 并走现有渲染链路。 */
    bool renderMediaCodecFrame(const MediaCodecVideoDecoder::DecodedVideoFrame &decodedFrame);

    /** 音频解码线程循环。 */
    void audioDecodeLoop();

    /** 把解码后的视频帧渲染到 Surface。 */
    bool renderFrameToSurface(AVFrame *frame);

    /** 尝试用 OpenGL ES 三纹理渲染 YUV420P 视频帧。 */
    bool tryRenderFrameWithOpenGL(ANativeWindow *window, AVFrame *frame);

    /** 用 NativeWindow RGBA 兼容路径渲染视频帧。 */
    bool renderFrameWithNativeWindow(ANativeWindow *window, AVFrame *frame);

    /** 更新最近一帧截图缓存。 */
    bool updateCaptureFrameSnapshot(AVFrame *frame);

    /** 确保渲染缓存与当前帧尺寸匹配。 */
    bool ensureRenderCache(
            int srcWidth,
            int srcHeight,
            int srcFormat,
            int dstWidth,
            int dstHeight
    );

    /** 确保截图缓存与当前原始帧尺寸匹配。 */
    bool ensureCaptureCache(
            int srcWidth,
            int srcHeight,
            int srcFormat
    );

    /** 清空渲染缓存。 */
    void clearRenderCache();

    /** 从视频包队列取出一个包。 */
    bool dequeueVideoPacket(struct AVPacket *outPacket);

    /** 从音频包队列取出一个包。 */
    bool dequeueAudioPacket(struct AVPacket *outPacket);

    /** 向对应音视频队列压入一个包。 */
    void enqueuePacket(struct AVPacket *packet);

    /** 清空所有音视频包队列。 */
    void clearPacketQueues();

    /** 重置本轮播放统计数据。 */
    void resetPlaybackStats();

    /** 记录一次成功读取的 packet 字节数。 */
    void recordReadBytes(int packetSize);

    /** 记录一帧已经解码出的视频帧。 */
    void recordDecodedVideoFrame();

    /** 记录一帧已经渲染成功的视频帧。 */
    void recordRenderedVideoFrame();

    /** 记录一帧主动丢弃的视频帧。 */
    void recordDroppedVideoFrame();

    /** 记录工作线程结束并更新播放状态。 */
    void markPlaybackWorkerFinished();

    /** 写入一个录制输出包。 */
    void writeRecordingPacket(struct AVPacket *packet);

    /** 在持锁状态下停止录制并回收输出上下文。 */
    void stopRecordingLocked();

    /** 释放输入格式上下文。 */
    void releaseFormatContext();

    /** 释放 Surface 引用。 */
    void releaseSurface();

    /** 释放 Java 回调对象引用。 */
    void releaseJavaCallback();

    /** 获取当前线程的 JNIEnv。 */
    JNIEnv *getJNIEnv(bool *needDetach);

    /** 释放当前线程的 JNIEnv 绑定。 */
    void releaseJNIEnv(bool needDetach);

    /** 回调 Java 创建音频输出参数。 */
    void notifyAudioInfo(int sampleRate, int channels);

    /** 回调 Java 写入 PCM 音频数据。 */
    void notifyAudioData(uint8_t *data, int size);

    /** 回调 Java 播放器信息事件。 */
    void notifyInfo(int infoCode, const std::string &message);

    /** 更新当前解码信息并通知 Java。 */
    void updateDecodeInfo(
            const std::string &decodeType,
            const std::string &decoderName,
            const std::string &fallbackReason
    );

    /** 回调 Java 播放器错误事件。 */
    void notifyError(int errorCode, const std::string &message);

    /** 回调 Java 视频尺寸变化事件。 */
    void notifyVideoSizeChanged(int width, int height);

    /** 更新视频尺寸并在变化时回调 Java。 */
    void updateVideoSize(int width, int height);

    /** 清空已记录的视频尺寸。 */
    void clearVideoSize();

    /** 更新缓冲状态并按需发出事件。 */
    void updateBufferingState(bool isBuffering, const std::string &message);

    /** 生成打开输入流失败的中文提示。 */
    std::string makeOpenInputHint(const std::string &error);

    /** 把 FFmpeg 错误码转成字符串。 */
    std::string makeErrorString(int ret);

    /** 在 prepare 阶段探测 MediaCodec 是否可用。 */
    std::string probeMediaCodecDecoder(const AVCodecParameters *codecParameters);
};

#endif // ECHPLAY_NATIVE_PLAYER_H
