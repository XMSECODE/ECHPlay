#ifndef ECHPLAY_NATIVE_PLAYER_H
#define ECHPLAY_NATIVE_PLAYER_H

#include <atomic>
#include <mutex>
#include <string>
#include <thread>

struct AVFormatContext;
struct ANativeWindow;
struct AVFrame;

class NativePlayer {
public:
    NativePlayer();

    ~NativePlayer();

    void setDataSource(const std::string &dataSource);

    void setSurface(ANativeWindow *window);

    std::string prepare();

    std::string play();

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
    std::thread playThread;

private:
    void decodeLoop();

    bool renderFrameToSurface(AVFrame *frame);

    void releaseFormatContext();

    void releaseSurface();

    std::string makeErrorString(int ret);
};

#endif // ECHPLAY_NATIVE_PLAYER_H
