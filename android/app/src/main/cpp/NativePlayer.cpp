#include "NativePlayer.h"

#include <android/log.h>
#include <iomanip>
#include <sstream>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/error.h>
#include <libavutil/rational.h>
}

#define ECH_LOG_TAG "ECHPlayer"
#define ECH_LOGI(...) __android_log_print(ANDROID_LOG_INFO, ECH_LOG_TAG, __VA_ARGS__)
#define ECH_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ECH_LOG_TAG, __VA_ARGS__)

NativePlayer::NativePlayer()
        : formatContext(nullptr),
          videoStreamIndex(-1),
          audioStreamIndex(-1),
          prepared(false),
          released(false) {
    ECH_LOGI("NativePlayer create");
    ECH_LOGI("NativePlayer init");
}

NativePlayer::~NativePlayer() {
    if (!released) {
        released = true;
        ECH_LOGI("NativePlayer release");
    }

    releaseFormatContext();

    ECH_LOGI("NativePlayer destroy");
}

void NativePlayer::setDataSource(const std::string &source) {
    dataSource = source;
    ECH_LOGI("setDataSource: %s", dataSource.c_str());
}

std::string NativePlayer::prepare() {
    if (dataSource.empty()) {
        return "prepare failed: dataSource is empty";
    }

    releaseFormatContext();

    ECH_LOGI("prepare start: %s", dataSource.c_str());

    int ret = avformat_open_input(&formatContext, dataSource.c_str(), nullptr, nullptr);
    if (ret < 0) {
        std::string error = makeErrorString(ret);
        ECH_LOGE("avformat_open_input failed: %s", error.c_str());

        return "prepare failed\n"
               "step: avformat_open_input\n"
               "error: " + error + "\n"
               "source: " + dataSource;
    }

    ret = avformat_find_stream_info(formatContext, nullptr);
    if (ret < 0) {
        std::string error = makeErrorString(ret);
        ECH_LOGE("avformat_find_stream_info failed: %s", error.c_str());

        releaseFormatContext();

        return "prepare failed\n"
               "step: avformat_find_stream_info\n"
               "error: " + error + "\n"
               "source: " + dataSource;
    }

    videoStreamIndex = av_find_best_stream(
            formatContext,
            AVMEDIA_TYPE_VIDEO,
            -1,
            -1,
            nullptr,
            0
    );

    audioStreamIndex = av_find_best_stream(
            formatContext,
            AVMEDIA_TYPE_AUDIO,
            -1,
            -1,
            nullptr,
            0
    );

    if (videoStreamIndex < 0 && audioStreamIndex < 0) {
        releaseFormatContext();
        return "prepare failed: no audio or video stream found";
    }

    prepared = true;

    std::ostringstream oss;

    oss << "prepare success\n";
    oss << "source: " << dataSource << "\n";

    if (formatContext->iformat && formatContext->iformat->name) {
        oss << "format: " << formatContext->iformat->name << "\n";
    }

    if (formatContext->duration != AV_NOPTS_VALUE) {
        double durationSeconds = formatContext->duration / 1000000.0;
        oss << "duration: " << std::fixed << std::setprecision(2) << durationSeconds << "s\n";
    } else {
        oss << "duration: unknown\n";
    }

    oss << "bitrate: " << formatContext->bit_rate << "\n";

    if (videoStreamIndex >= 0) {
        AVStream *videoStream = formatContext->streams[videoStreamIndex];
        AVCodecParameters *videoCodecPar = videoStream->codecpar;

        oss << "\n";
        oss << "video stream index: " << videoStreamIndex << "\n";
        oss << "video codec: " << avcodec_get_name(videoCodecPar->codec_id) << "\n";
        oss << "video size: " << videoCodecPar->width << "x" << videoCodecPar->height << "\n";

        if (videoStream->avg_frame_rate.num > 0 && videoStream->avg_frame_rate.den > 0) {
            double fps = av_q2d(videoStream->avg_frame_rate);
            oss << "video fps: " << std::fixed << std::setprecision(2) << fps << "\n";
        }
    } else {
        oss << "\nvideo stream: not found\n";
    }

    if (audioStreamIndex >= 0) {
        AVStream *audioStream = formatContext->streams[audioStreamIndex];
        AVCodecParameters *audioCodecPar = audioStream->codecpar;

        oss << "\n";
        oss << "audio stream index: " << audioStreamIndex << "\n";
        oss << "audio codec: " << avcodec_get_name(audioCodecPar->codec_id) << "\n";
        oss << "audio sample rate: " << audioCodecPar->sample_rate << "\n";
        oss << "audio channels: " << audioCodecPar->ch_layout.nb_channels << "\n";
    } else {
        oss << "\naudio stream: not found\n";
    }

    ECH_LOGI("prepare success");

    return oss.str();
}

std::string NativePlayer::getFFmpegVersion() {
    return std::string(av_version_info());
}

void NativePlayer::releaseFormatContext() {
    if (formatContext != nullptr) {
        avformat_close_input(&formatContext);
        formatContext = nullptr;
    }

    videoStreamIndex = -1;
    audioStreamIndex = -1;
    prepared = false;
}

std::string NativePlayer::makeErrorString(int ret) {
    char errorBuffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(ret, errorBuffer, sizeof(errorBuffer));
    return std::string(errorBuffer);
}
