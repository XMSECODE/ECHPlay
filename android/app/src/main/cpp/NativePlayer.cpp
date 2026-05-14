#include "NativePlayer.h"

#include <android/log.h>

extern "C" {
#include <libavutil/avutil.h>
}

#define ECH_LOG_TAG "ECHPlayer"
#define ECH_LOGI(...) __android_log_print(ANDROID_LOG_INFO, ECH_LOG_TAG, __VA_ARGS__)

NativePlayer::NativePlayer() : released(false) {
    ECH_LOGI("NativePlayer create");
    ECH_LOGI("NativePlayer init");
}

NativePlayer::~NativePlayer() {
    if (!released) {
        released = true;
        ECH_LOGI("NativePlayer release");
    }
    ECH_LOGI("NativePlayer destroy");
}

std::string NativePlayer::getFFmpegVersion() {
    return std::string(av_version_info());
}
