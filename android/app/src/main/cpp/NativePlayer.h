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

class NativePlayer {
public:
    NativePlayer(JavaVM *vm, JNIEnv *env, jobject javaPlayer);

    ~NativePlayer();

    void setDataSource(const std::string &dataSource);

    void setSurface(ANativeWindow *window);

    void setRtspTransport(int transport);

    std::string prepare();

    std::string play();

    void pause();

    void resume();

    void stop();

    std::string seekToMs(int64_t positionMs);

    int64_t getDurationMs();

    int64_t getCurrentPositionMs();

    std::string getFFmpegVersion();

private:
    std::string dataSource;
    AVFormatContext *formatContext;

    ANativeWindow *nativeWindow;
    std::mutex windowMutex;

    int videoStreamIndex;
    int audioStreamIndex;

    bool prepared;
    bool released;

    std::atomic<bool> playing;
    std::atomic<bool> stopRequested;
    std::atomic<bool> paused;
    std::atomic<bool> demuxFinished;
    std::atomic<int> activePlaybackWorkers;
    std::atomic<int64_t> audioClockUs;

    std::thread demuxThread;
    std::thread playThread;
    std::thread audioThread;

    std::mutex packetQueueMutex;
    std::condition_variable packetQueueCond;
    std::deque<struct AVPacket *> videoPacketQueue;
    std::deque<struct AVPacket *> audioPacketQueue;

    SwsContext *swsContextCache;
    AVFrame *rgbaFrameCache;
    std::vector<uint8_t> rgbaBufferCache;
    int renderSrcWidth;
    int renderSrcHeight;
    int renderSrcFormat;
    int renderDstWidth;
    int renderDstHeight;
    int rtspTransport;

    JavaVM *javaVm;
    jobject javaPlayerObject;
    jmethodID onNativeAudioInfoMethod;
    jmethodID onNativeAudioDataMethod;

private:
    void demuxLoop();

    void decodeLoop();

    void audioDecodeLoop();

    bool renderFrameToSurface(AVFrame *frame);

    bool ensureRenderCache(
            int srcWidth,
            int srcHeight,
            int srcFormat,
            int dstWidth,
            int dstHeight
    );

    void clearRenderCache();

    bool dequeueVideoPacket(struct AVPacket *outPacket);

    bool dequeueAudioPacket(struct AVPacket *outPacket);

    void enqueuePacket(struct AVPacket *packet);

    void clearPacketQueues();

    void markPlaybackWorkerFinished();

    void releaseFormatContext();

    void releaseSurface();

    void releaseJavaCallback();

    JNIEnv *getJNIEnv(bool *needDetach);

    void releaseJNIEnv(bool needDetach);

    void notifyAudioInfo(int sampleRate, int channels);

    void notifyAudioData(uint8_t *data, int size);

    std::string makeOpenInputHint(const std::string &error);

    std::string makeErrorString(int ret);
};

#endif // ECHPLAY_NATIVE_PLAYER_H
