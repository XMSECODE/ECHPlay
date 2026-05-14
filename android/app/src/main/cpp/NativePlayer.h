#ifndef ECHPLAY_NATIVE_PLAYER_H
#define ECHPLAY_NATIVE_PLAYER_H

#include <string>

struct AVFormatContext;
struct ANativeWindow;

class NativePlayer {
public:
    NativePlayer();

    ~NativePlayer();

    void setDataSource(const std::string &dataSource);

    std::string prepare();

    std::string decodeFirstVideoFrame();

    std::string renderFirstVideoFrame(ANativeWindow *nativeWindow);

    std::string getFFmpegVersion();

private:
    std::string dataSource;
    AVFormatContext *formatContext;

    int videoStreamIndex;
    int audioStreamIndex;

    bool prepared;
    bool released;

private:
    void releaseFormatContext();

    std::string decodeFirstVideoFrameInternal(ANativeWindow *nativeWindow);

    std::string makeErrorString(int ret);
};

#endif // ECHPLAY_NATIVE_PLAYER_H
