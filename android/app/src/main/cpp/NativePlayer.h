#ifndef ECHPLAY_NATIVE_PLAYER_H
#define ECHPLAY_NATIVE_PLAYER_H

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <jni.h>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct AVFormatContext;
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

    /** 设置 RTSP 传输方式，0 为 TCP，1 为 UDP。 */
    void setRtspTransport(int transport);

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

    /** JavaVM 指针，用于子线程回调 Java。 */
    JavaVM *javaVm;
    /** Java 播放器对象全局引用。 */
    jobject javaPlayerObject;
    /** 音频参数回调方法。 */
    jmethodID onNativeAudioInfoMethod;
    /** PCM 数据回调方法。 */
    jmethodID onNativeAudioDataMethod;

    /** 录制输出上下文。 */
    AVFormatContext *recordFormatContext;
    /** 当前是否正在录制。 */
    bool recording;
    /** 录制状态互斥锁。 */
    std::mutex recordMutex;
    /** 输入流到录制输出流的索引映射。 */
    std::vector<int> recordStreamMapping;
    /** 每个流录制起始 PTS，用于把录制时间归零。 */
    std::vector<int64_t> recordStartPts;
    /** 每个流录制起始 DTS，用于把录制时间归零。 */
    std::vector<int64_t> recordStartDts;
    /** 当前录制文件路径。 */
    std::string recordingOutputPath;
    /** 当前录制输出是否已成功写入文件头。 */
    bool recordHeaderWritten;

private:
    /** 解封装线程循环。 */
    void demuxLoop();

    /** 视频解码线程循环。 */
    void decodeLoop();

    /** 音频解码线程循环。 */
    void audioDecodeLoop();

    /** 把解码后的视频帧渲染到 Surface。 */
    bool renderFrameToSurface(AVFrame *frame);

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

    /** 生成打开输入流失败的中文提示。 */
    std::string makeOpenInputHint(const std::string &error);

    /** 把 FFmpeg 错误码转成字符串。 */
    std::string makeErrorString(int ret);
};

#endif // ECHPLAY_NATIVE_PLAYER_H
