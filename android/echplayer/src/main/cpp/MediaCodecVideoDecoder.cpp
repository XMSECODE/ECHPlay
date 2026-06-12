#include "MediaCodecVideoDecoder.h"

#include <android/log.h>

extern "C" {
#include <libavcodec/codec_id.h>
#include <libavcodec/codec_par.h>
}

#define ECH_LOG_TAG "ECHPlayer"
#define ECH_LOGI(...) __android_log_print(ANDROID_LOG_INFO, ECH_LOG_TAG, __VA_ARGS__)
#define ECH_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ECH_LOG_TAG, __VA_ARGS__)

/** 创建硬解封装。 */
MediaCodecVideoDecoder::MediaCodecVideoDecoder()
        : codec(nullptr),
          format(nullptr),
          mime(),
          codecName(),
          started(false) {
}

/** 释放硬解资源。 */
MediaCodecVideoDecoder::~MediaCodecVideoDecoder() {
    release();
}

/** 判断 FFmpeg codec id 是否属于 v1.4 计划支持的硬解编码。 */
bool MediaCodecVideoDecoder::isSupportedCodecId(int codecId) {
    return codecId == AV_CODEC_ID_H264 || codecId == AV_CODEC_ID_HEVC;
}

/** 根据 FFmpeg codec id 返回 MediaCodec MIME。 */
const char *MediaCodecVideoDecoder::mimeFromCodecId(int codecId) {
    if (codecId == AV_CODEC_ID_H264) {
        return "video/avc";
    }
    if (codecId == AV_CODEC_ID_HEVC) {
        return "video/hevc";
    }
    return nullptr;
}

/** 把状态转换成易读文本。 */
std::string MediaCodecVideoDecoder::statusToString(Status status) {
    switch (status) {
        case Status::OK:
            return "ok";
        case Status::UNSUPPORTED_CODEC:
            return "unsupported codec";
        case Status::INVALID_PARAMETERS:
            return "invalid parameters";
        case Status::CREATE_FAILED:
            return "create MediaCodec failed";
        case Status::CONFIGURE_FAILED:
            return "configure MediaCodec failed";
        case Status::START_FAILED:
            return "start MediaCodec failed";
        default:
            return "unknown";
    }
}

/** 根据视频流参数创建并启动 MediaCodec。 */
MediaCodecVideoDecoder::Status MediaCodecVideoDecoder::configure(
        const AVCodecParameters *codecParameters) {
    release();

    if (codecParameters == nullptr
        || codecParameters->width <= 0
        || codecParameters->height <= 0) {
        return Status::INVALID_PARAMETERS;
    }

    const char *targetMime = mimeFromCodecId(codecParameters->codec_id);
    if (targetMime == nullptr) {
        return Status::UNSUPPORTED_CODEC;
    }

    mime = targetMime;
    codec = AMediaCodec_createDecoderByType(targetMime);
    if (codec == nullptr) {
        ECH_LOGE("AMediaCodec_createDecoderByType failed, mime=%s", targetMime);
        release();
        return Status::CREATE_FAILED;
    }

    format = AMediaFormat_new();
    if (format == nullptr) {
        release();
        return Status::CONFIGURE_FAILED;
    }

    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, targetMime);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, codecParameters->width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, codecParameters->height);
    fillCodecSpecificData(codecParameters);

    media_status_t configureStatus = AMediaCodec_configure(
            codec,
            format,
            nullptr,
            nullptr,
            0
    );
    if (configureStatus != AMEDIA_OK) {
        ECH_LOGE(
                "AMediaCodec_configure failed, mime=%s, status=%d",
                targetMime,
                configureStatus
        );
        release();
        return Status::CONFIGURE_FAILED;
    }

    media_status_t startStatus = AMediaCodec_start(codec);
    if (startStatus != AMEDIA_OK) {
        ECH_LOGE("AMediaCodec_start failed, mime=%s, status=%d", targetMime, startStatus);
        release();
        return Status::START_FAILED;
    }

    started = true;
    codecName = targetMime;
    ECH_LOGI(
            "MediaCodec configured, mime=%s, size=%dx%d",
            targetMime,
            codecParameters->width,
            codecParameters->height
    );
    return Status::OK;
}

/** 停止并释放 MediaCodec。 */
void MediaCodecVideoDecoder::release() {
    if (codec != nullptr) {
        if (started) {
            AMediaCodec_stop(codec);
        }
        AMediaCodec_delete(codec);
        codec = nullptr;
    }

    if (format != nullptr) {
        AMediaFormat_delete(format);
        format = nullptr;
    }

    started = false;
    codecName.clear();
    mime.clear();
}

/** 返回当前是否已经成功启动。 */
bool MediaCodecVideoDecoder::isStarted() const {
    return started;
}

/** 返回当前 MIME。 */
std::string MediaCodecVideoDecoder::getMime() const {
    return mime;
}

/** 返回当前解码器名称。 */
std::string MediaCodecVideoDecoder::getCodecName() const {
    return codecName;
}

/** 从 codec specific data 中填充 MediaFormat。 */
void MediaCodecVideoDecoder::fillCodecSpecificData(const AVCodecParameters *codecParameters) {
    if (format == nullptr
        || codecParameters == nullptr
        || codecParameters->extradata == nullptr
        || codecParameters->extradata_size <= 0) {
        return;
    }

    AMediaFormat_setBuffer(
            format,
            "csd-0",
            codecParameters->extradata,
            static_cast<size_t>(codecParameters->extradata_size)
    );
}
