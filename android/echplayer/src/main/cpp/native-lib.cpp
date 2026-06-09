#include <jni.h>
#include <string>

#include <android/native_window_jni.h>

#include "NativePlayer.h"

/** 根据 nativeHandle 还原 NativePlayer 指针。 */
static NativePlayer *getPlayer(jlong nativeHandle) {
    return reinterpret_cast<NativePlayer *>(nativeHandle);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_echplay_player_ECHPlayer_nativeInit(
        JNIEnv *env,
        jobject thiz) {

    JavaVM *javaVm = nullptr;
    env->GetJavaVM(&javaVm);

    NativePlayer *player = new NativePlayer(javaVm, env, thiz);
    return reinterpret_cast<jlong>(player);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_echplay_player_ECHPlayer_nativeRelease(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player != nullptr) {
        delete player;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_echplay_player_ECHPlayer_nativeSetDataSource(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jstring dataSource) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return;
    }

    if (dataSource == nullptr) {
        player->setDataSource("");
        return;
    }

    const char *sourceChars = env->GetStringUTFChars(dataSource, nullptr);
    if (sourceChars == nullptr) {
        return;
    }

    player->setDataSource(sourceChars);

    env->ReleaseStringUTFChars(dataSource, sourceChars);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_echplay_player_ECHPlayer_nativeSetSurface(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jobject surface) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return;
    }

    if (surface == nullptr) {
        player->setSurface(nullptr);
        return;
    }

    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    player->setSurface(window);

    if (window != nullptr) {
        ANativeWindow_release(window);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_echplay_player_ECHPlayer_nativeSetRtspTransport(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jint transport) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player != nullptr) {
        player->setRtspTransport(static_cast<int>(transport));
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_echplay_player_ECHPlayer_nativeSetLongOption(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jint category,
        jstring name,
        jlong value) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr || name == nullptr) {
        return JNI_FALSE;
    }

    const char *nameChars = env->GetStringUTFChars(name, nullptr);
    if (nameChars == nullptr) {
        return JNI_FALSE;
    }

    bool handled = player->setLongOption(
            static_cast<int>(category),
            nameChars,
            static_cast<int64_t>(value)
    );

    env->ReleaseStringUTFChars(name, nameChars);
    return handled ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativePrepare(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("prepare failed: NativePlayer is null");
    }

    std::string result = player->prepare();
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativePlay(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("play failed: NativePlayer is null");
    }

    std::string result = player->play();
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_echplay_player_ECHPlayer_nativePause(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player != nullptr) {
        player->pause();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_echplay_player_ECHPlayer_nativeResume(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player != nullptr) {
        player->resume();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_echplay_player_ECHPlayer_nativeStop(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player != nullptr) {
        player->stop();
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativeSeekToMs(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jlong positionMs) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("seek failed: NativePlayer is null");
    }

    std::string result = player->seekToMs(static_cast<int64_t>(positionMs));
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetDurationMs(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return static_cast<jlong>(-1);
    }

    return static_cast<jlong>(player->getDurationMs());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetCurrentPositionMs(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return static_cast<jlong>(-1);
    }

    return static_cast<jlong>(player->getCurrentPositionMs());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_echplay_player_ECHPlayer_nativeIsSeekable(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return JNI_FALSE;
    }

    return player->isSeekable() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetCurrentFrameRgba(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return nullptr;
    }

    std::vector<uint8_t> rgbaData;
    int frameWidth = 0;
    int frameHeight = 0;
    if (!player->copyCurrentFrameSnapshot(rgbaData, frameWidth, frameHeight) || rgbaData.empty()) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(rgbaData.size()));
    if (result == nullptr) {
        return nullptr;
    }

    env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(rgbaData.size()),
            reinterpret_cast<const jbyte *>(rgbaData.data())
    );
    return result;
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetCurrentFrameSize(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return nullptr;
    }

    std::vector<uint8_t> rgbaData;
    int frameWidth = 0;
    int frameHeight = 0;
    if (!player->copyCurrentFrameSnapshot(rgbaData, frameWidth, frameHeight)) {
        return nullptr;
    }

    jintArray result = env->NewIntArray(2);
    if (result == nullptr) {
        return nullptr;
    }

    jint sizeArray[2] = {
            static_cast<jint>(frameWidth),
            static_cast<jint>(frameHeight)
    };
    env->SetIntArrayRegion(result, 0, 2, sizeArray);
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativeStartRecording(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle,
        jstring outputPath) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("start recording failed: NativePlayer is null");
    }

    if (outputPath == nullptr) {
        return env->NewStringUTF("start recording failed: output path is null");
    }

    const char *pathChars = env->GetStringUTFChars(outputPath, nullptr);
    if (pathChars == nullptr) {
        return env->NewStringUTF("start recording failed: cannot read output path");
    }

    std::string result = player->startRecording(pathChars);
    env->ReleaseStringUTFChars(outputPath, pathChars);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativeStopRecording(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return env->NewStringUTF("stop recording failed: NativePlayer is null");
    }

    std::string result = player->stopRecording();
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_echplay_player_ECHPlayer_nativeIsRecording(
        JNIEnv *env,
        jobject thiz,
        jlong nativeHandle) {

    NativePlayer *player = getPlayer(nativeHandle);
    if (player == nullptr) {
        return JNI_FALSE;
    }

    return player->isRecording() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_echplay_player_ECHPlayer_nativeGetFFmpegVersion(
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
