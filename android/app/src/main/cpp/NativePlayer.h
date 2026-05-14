#ifndef ECHPLAY_NATIVE_PLAYER_H
#define ECHPLAY_NATIVE_PLAYER_H

#include <string>

struct AVFormatContext;

class NativePlayer {
public:
    NativePlayer();

    ~NativePlayer();

    void setDataSource(const std::string &dataSource);

    std::string prepare();

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

    std::string makeErrorString(int ret);
};

#endif // ECHPLAY_NATIVE_PLAYER_H
