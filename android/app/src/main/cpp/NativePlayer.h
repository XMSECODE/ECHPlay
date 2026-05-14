#ifndef ECHPLAY_NATIVE_PLAYER_H
#define ECHPLAY_NATIVE_PLAYER_H

#include <string>

class NativePlayer {
public:
    NativePlayer();

    ~NativePlayer();

    std::string getFFmpegVersion();

private:
    bool released;
};

#endif // ECHPLAY_NATIVE_PLAYER_H
