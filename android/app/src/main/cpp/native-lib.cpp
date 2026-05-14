#include <jni.h>
#include <string>

#include "NativePlayer.h"

static NativePlayer *getPlayer(jlong nativeHandle) {
    return reinterpret_cast<NativePlayer *>(nativeHandle);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_abcplaydemo_player_ECHPlayer_nativeInit(
        JNIEnv *env,
        jobject thiz) {

    NativePlayer *player = new NativePlayer();
    return reinterpret_cast<jlong>(player);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_abcplaydemo_player_ECHPlayer_nativeRelease(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player != nullptr) {
        delete player;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_abcplaydemo_player_ECHPlayer_nativeGetFFmpegVersion(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("NativePlayer is null");
    }

    std::string version = player->getFFmpegVersion();
    return env->NewStringUTF(version.c_str());
}
