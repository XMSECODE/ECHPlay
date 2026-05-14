#ifndef ECHPLAY_NATIVE_PLAYER_H
#define ECHPLAY_NATIVE_PLAYER_H

#include <atomic>
#include <jni.h>
#include <mutex>
#include <string>
#include <thread>

struct AVFormatContext;
struct ANativeWindow;
struct AVFrame;

class NativePlayer {
public:
    NativePlayer(JavaVM *vm, JNIEnv *env, jobject javaPlayer);

    ~NativePlayer();

    void setDataSource(const std::string &dataSource);

    void setSurface(ANativeWindow *window);

    std::string prepare();

    std::string play();

    void pause();

    void resume();

    void stop();

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

    std::thread playThread;
    std::thread audioThread;

    JavaVM *javaVm;
    jobject javaPlayerObject;
    jmethodID onNativeAudioInfoMethod;
    jmethodID onNativeAudioDataMethod;

private:
    void decodeLoop();

    void audioDecodeLoop();

    bool renderFrameToSurface(AVFrame *frame);

    void releaseFormatContext();

    void releaseSurface();

    void releaseJavaCallback();

    JNIEnv *getJNIEnv(bool *needDetach);

    void releaseJNIEnv(bool needDetach);

    void notifyAudioInfo(int sampleRate, int channels);

    void notifyAudioData(uint8_t *data, int size);

    std::string makeErrorString(int ret);
};

#endif // ECHPLAY_NATIVE_PLAYER_H
