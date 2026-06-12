#include "MediaCodecVideoDecoder.h"

#include <android/log.h>
#include <algorithm>
#include <cstring>

extern "C" {
#include <libavcodec/codec_id.h>
#include <libavcodec/codec_par.h>
}

#define ECH_LOG_TAG "ECHPlayer"
#define ECH_LOGI(...) __android_log_print(ANDROID_LOG_INFO, ECH_LOG_TAG, __VA_ARGS__)
#define ECH_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ECH_LOG_TAG, __VA_ARGS__)

static constexpr int COLOR_FORMAT_YUV420_PLANAR = 19;
static constexpr int COLOR_FORMAT_YUV420_SEMIPLANAR = 21;
static constexpr int COLOR_FORMAT_YUV420_FLEXIBLE = 0x7F420888;

/** 清空当前帧数据。 */
void MediaCodecVideoDecoder::DecodedVideoFrame::clear() {
    width = 0;
    height = 0;
    ptsUs = 0;
    yPlane.clear();
    uPlane.clear();
    vPlane.clear();
    yStride = 0;
    uStride = 0;
    vStride = 0;
}

/** 创建硬解封装。 */
MediaCodecVideoDecoder::MediaCodecVideoDecoder()
        : codec(nullptr),
          format(nullptr),
          mime(),
          codecName(),
          started(false),
          outputWidth(0),
          outputHeight(0),
          outputStride(0),
          outputSliceHeight(0),
          outputColorFormat(0),
          codecId(AV_CODEC_ID_NONE),
          nalLengthSize(4),
          lengthPrefixedInput(false) {
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
        case Status::INPUT_TRY_AGAIN:
            return "input buffer try again";
        case Status::QUEUE_INPUT_FAILED:
            return "queue input failed";
        case Status::OUTPUT_TRY_AGAIN:
            return "output buffer try again";
        case Status::OUTPUT_FORMAT_CHANGED:
            return "output format changed";
        case Status::UNSUPPORTED_OUTPUT_FORMAT:
            return "unsupported output format";
        case Status::OUTPUT_END_OF_STREAM:
            return "output end of stream";
        case Status::OUTPUT_FAILED:
            return "output failed";
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
    codecId = codecParameters->codec_id;
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
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, COLOR_FORMAT_YUV420_FLEXIBLE);
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
    outputWidth = codecParameters->width;
    outputHeight = codecParameters->height;
    outputStride = codecParameters->width;
    outputSliceHeight = codecParameters->height;
    outputColorFormat = COLOR_FORMAT_YUV420_FLEXIBLE;
    updateOutputFormat();
    ECH_LOGI(
            "MediaCodec configured, mime=%s, size=%dx%d",
            targetMime,
            codecParameters->width,
            codecParameters->height
    );
    return Status::OK;
}

/** 向 MediaCodec 送入一个压缩包。 */
MediaCodecVideoDecoder::Status MediaCodecVideoDecoder::queueInput(
        const uint8_t *data,
        size_t size,
        int64_t ptsUs,
        bool endOfStream) {
    if (!started || codec == nullptr) {
        return Status::INVALID_PARAMETERS;
    }

    ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec, 10000);
    if (inputIndex < 0) {
        return Status::INPUT_TRY_AGAIN;
    }

    size_t inputBufferSize = 0;
    uint8_t *inputBuffer = AMediaCodec_getInputBuffer(
            codec,
            static_cast<size_t>(inputIndex),
            &inputBufferSize
    );
    if (inputBuffer == nullptr) {
        return Status::QUEUE_INPUT_FAILED;
    }

    size_t copySize = endOfStream ? 0 : std::min(inputBufferSize, size);
    if (!endOfStream && data != nullptr && copySize > 0) {
        std::vector<uint8_t> convertedPacket;
        if (lengthPrefixedInput && !isAnnexB(data, size)) {
            if (!convertLengthPrefixedPacket(data, size, convertedPacket)) {
                return Status::QUEUE_INPUT_FAILED;
            }
            copySize = std::min(inputBufferSize, convertedPacket.size());
            memcpy(inputBuffer, convertedPacket.data(), copySize);
        } else {
            memcpy(inputBuffer, data, copySize);
        }
    }

    uint32_t flags = endOfStream ? AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM : 0;
    media_status_t queueStatus = AMediaCodec_queueInputBuffer(
            codec,
            static_cast<size_t>(inputIndex),
            0,
            copySize,
            static_cast<uint64_t>(std::max<int64_t>(0, ptsUs)),
            flags
    );
    if (queueStatus != AMEDIA_OK) {
        ECH_LOGE("AMediaCodec_queueInputBuffer failed: %d", queueStatus);
        return Status::QUEUE_INPUT_FAILED;
    }

    return Status::OK;
}

/** 从 MediaCodec 取出一个解码帧。 */
MediaCodecVideoDecoder::Status MediaCodecVideoDecoder::dequeueOutput(
        DecodedVideoFrame &frame,
        int64_t timeoutUs) {
    frame.clear();
    if (!started || codec == nullptr) {
        return Status::INVALID_PARAMETERS;
    }

    AMediaCodecBufferInfo bufferInfo;
    ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(codec, &bufferInfo, timeoutUs);
    if (outputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
        return Status::OUTPUT_TRY_AGAIN;
    }

    if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        if (!updateOutputFormat()) {
            return Status::UNSUPPORTED_OUTPUT_FORMAT;
        }
        return Status::OUTPUT_FORMAT_CHANGED;
    }

    if (outputIndex < 0) {
        return Status::OUTPUT_FAILED;
    }

    if ((bufferInfo.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) {
        AMediaCodec_releaseOutputBuffer(codec, static_cast<size_t>(outputIndex), false);
        return Status::OUTPUT_END_OF_STREAM;
    }

    size_t outputBufferSize = 0;
    uint8_t *outputBuffer = AMediaCodec_getOutputBuffer(
            codec,
            static_cast<size_t>(outputIndex),
            &outputBufferSize
    );
    if (outputBuffer == nullptr || bufferInfo.size <= 0) {
        AMediaCodec_releaseOutputBuffer(codec, static_cast<size_t>(outputIndex), false);
        return Status::OUTPUT_FAILED;
    }

    bool copied = copyOutputBufferToFrame(outputBuffer, outputBufferSize, bufferInfo, frame);
    AMediaCodec_releaseOutputBuffer(codec, static_cast<size_t>(outputIndex), false);
    return copied ? Status::OK : Status::UNSUPPORTED_OUTPUT_FORMAT;
}

/** 清空 MediaCodec 内部状态。 */
void MediaCodecVideoDecoder::flush() {
    if (codec != nullptr && started) {
        AMediaCodec_flush(codec);
    }
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
    outputWidth = 0;
    outputHeight = 0;
    outputStride = 0;
    outputSliceHeight = 0;
    outputColorFormat = 0;
    codecId = AV_CODEC_ID_NONE;
    nalLengthSize = 4;
    lengthPrefixedInput = false;
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

    const uint8_t *extraData = codecParameters->extradata;
    size_t extraSize = static_cast<size_t>(codecParameters->extradata_size);

    bool handled = false;
    if (codecParameters->codec_id == AV_CODEC_ID_H264) {
        handled = fillH264CodecSpecificData(extraData, extraSize);
    } else if (codecParameters->codec_id == AV_CODEC_ID_HEVC) {
        handled = fillH265CodecSpecificData(extraData, extraSize);
    }

    if (!handled) {
        AMediaFormat_setBuffer(format, "csd-0", extraData, extraSize);
    }
}

/** 填充 H.264 的 SPS/PPS 配置。 */
bool MediaCodecVideoDecoder::fillH264CodecSpecificData(const uint8_t *data, size_t size) {
    if (format == nullptr || data == nullptr || size < 7) {
        return false;
    }

    if (isAnnexB(data, size)) {
        AMediaFormat_setBuffer(format, "csd-0", data, size);
        lengthPrefixedInput = false;
        return true;
    }

    nalLengthSize = (data[4] & 0x03) + 1;
    size_t offset = 5;
    int spsCount = data[offset++] & 0x1F;
    std::vector<uint8_t> csd;

    for (int i = 0; i < spsCount; ++i) {
        if (offset + 2 > size) {
            return false;
        }
        size_t nalSize = (static_cast<size_t>(data[offset]) << 8) | data[offset + 1];
        offset += 2;
        if (offset + nalSize > size) {
            return false;
        }
        appendStartCode(csd);
        csd.insert(csd.end(), data + offset, data + offset + nalSize);
        offset += nalSize;
    }

    if (offset >= size) {
        return false;
    }

    int ppsCount = data[offset++];
    for (int i = 0; i < ppsCount; ++i) {
        if (offset + 2 > size) {
            return false;
        }
        size_t nalSize = (static_cast<size_t>(data[offset]) << 8) | data[offset + 1];
        offset += 2;
        if (offset + nalSize > size) {
            return false;
        }
        appendStartCode(csd);
        csd.insert(csd.end(), data + offset, data + offset + nalSize);
        offset += nalSize;
    }

    if (csd.empty()) {
        return false;
    }

    AMediaFormat_setBuffer(format, "csd-0", csd.data(), csd.size());
    lengthPrefixedInput = true;
    return true;
}

/** 填充 H.265 的 VPS/SPS/PPS 配置。 */
bool MediaCodecVideoDecoder::fillH265CodecSpecificData(const uint8_t *data, size_t size) {
    if (format == nullptr || data == nullptr || size < 23) {
        return false;
    }

    if (isAnnexB(data, size)) {
        AMediaFormat_setBuffer(format, "csd-0", data, size);
        lengthPrefixedInput = false;
        return true;
    }

    nalLengthSize = (data[21] & 0x03) + 1;
    size_t offset = 22;
    int arrayCount = data[offset++];
    std::vector<uint8_t> csd;

    for (int arrayIndex = 0; arrayIndex < arrayCount; ++arrayIndex) {
        if (offset + 3 > size) {
            return false;
        }
        offset += 1;
        int nalCount = (static_cast<int>(data[offset]) << 8) | data[offset + 1];
        offset += 2;
        for (int nalIndex = 0; nalIndex < nalCount; ++nalIndex) {
            if (offset + 2 > size) {
                return false;
            }
            size_t nalSize = (static_cast<size_t>(data[offset]) << 8) | data[offset + 1];
            offset += 2;
            if (offset + nalSize > size) {
                return false;
            }
            appendStartCode(csd);
            csd.insert(csd.end(), data + offset, data + offset + nalSize);
            offset += nalSize;
        }
    }

    if (csd.empty()) {
        return false;
    }

    AMediaFormat_setBuffer(format, "csd-0", csd.data(), csd.size());
    lengthPrefixedInput = true;
    return true;
}

/** 判断数据是否已经是 Annex B 起始码格式。 */
bool MediaCodecVideoDecoder::isAnnexB(const uint8_t *data, size_t size) {
    if (data == nullptr || size < 4) {
        return false;
    }

    return (data[0] == 0 && data[1] == 0 && data[2] == 1)
           || (data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1);
}

/** 向缓冲区追加 Annex B 起始码。 */
void MediaCodecVideoDecoder::appendStartCode(std::vector<uint8_t> &buffer) {
    buffer.push_back(0);
    buffer.push_back(0);
    buffer.push_back(0);
    buffer.push_back(1);
}

/** 把长度前缀 packet 转成 Annex B packet。 */
bool MediaCodecVideoDecoder::convertLengthPrefixedPacket(
        const uint8_t *data,
        size_t size,
        std::vector<uint8_t> &annexBPacket) const {
    if (data == nullptr || size == 0 || nalLengthSize <= 0 || nalLengthSize > 4) {
        return false;
    }

    annexBPacket.clear();
    size_t offset = 0;
    while (offset + static_cast<size_t>(nalLengthSize) <= size) {
        size_t nalSize = 0;
        for (int i = 0; i < nalLengthSize; ++i) {
            nalSize = (nalSize << 8) | data[offset + static_cast<size_t>(i)];
        }
        offset += static_cast<size_t>(nalLengthSize);

        if (nalSize == 0 || offset + nalSize > size) {
            return false;
        }

        appendStartCode(annexBPacket);
        annexBPacket.insert(annexBPacket.end(), data + offset, data + offset + nalSize);
        offset += nalSize;
    }

    return !annexBPacket.empty();
}

/** 刷新 MediaCodec 输出格式。 */
bool MediaCodecVideoDecoder::updateOutputFormat() {
    if (codec == nullptr) {
        return false;
    }

    AMediaFormat *outputFormat = AMediaCodec_getOutputFormat(codec);
    if (outputFormat == nullptr) {
        return true;
    }

    int32_t value = 0;
    if (AMediaFormat_getInt32(outputFormat, AMEDIAFORMAT_KEY_WIDTH, &value) && value > 0) {
        outputWidth = value;
    }
    if (AMediaFormat_getInt32(outputFormat, AMEDIAFORMAT_KEY_HEIGHT, &value) && value > 0) {
        outputHeight = value;
    }
    if (AMediaFormat_getInt32(outputFormat, AMEDIAFORMAT_KEY_STRIDE, &value) && value > 0) {
        outputStride = value;
    } else {
        outputStride = outputWidth;
    }
    if (AMediaFormat_getInt32(outputFormat, AMEDIAFORMAT_KEY_SLICE_HEIGHT, &value) && value > 0) {
        outputSliceHeight = value;
    } else {
        outputSliceHeight = outputHeight;
    }
    if (AMediaFormat_getInt32(outputFormat, AMEDIAFORMAT_KEY_COLOR_FORMAT, &value) && value > 0) {
        outputColorFormat = value;
    }

    ECH_LOGI(
            "MediaCodec output format, size=%dx%d, stride=%d, slice=%d, color=%d",
            outputWidth,
            outputHeight,
            outputStride,
            outputSliceHeight,
            outputColorFormat
    );
    AMediaFormat_delete(outputFormat);

    return outputWidth > 0 && outputHeight > 0;
}

/** 把输出缓冲复制为 YUV420P。 */
bool MediaCodecVideoDecoder::copyOutputBufferToFrame(
        const uint8_t *buffer,
        size_t bufferSize,
        const AMediaCodecBufferInfo &bufferInfo,
        DecodedVideoFrame &frame) const {
    if (buffer == nullptr || bufferSize == 0 || outputWidth <= 0 || outputHeight <= 0) {
        return false;
    }

    int width = outputWidth;
    int height = outputHeight;
    int stride = outputStride > 0 ? outputStride : width;
    int sliceHeight = outputSliceHeight > 0 ? outputSliceHeight : height;
    size_t offset = static_cast<size_t>(std::max<int32_t>(0, bufferInfo.offset));
    if (offset >= bufferSize) {
        return false;
    }

    const uint8_t *base = buffer + offset;
    size_t available = bufferSize - offset;
    size_t yPlaneSize = static_cast<size_t>(stride) * static_cast<size_t>(sliceHeight);
    size_t chromaWidth = static_cast<size_t>((width + 1) / 2);
    size_t chromaHeight = static_cast<size_t>((height + 1) / 2);

    frame.width = width;
    frame.height = height;
    frame.ptsUs = bufferInfo.presentationTimeUs;
    frame.yStride = width;
    frame.uStride = static_cast<int>(chromaWidth);
    frame.vStride = static_cast<int>(chromaWidth);
    frame.yPlane.assign(static_cast<size_t>(width) * static_cast<size_t>(height), 0);
    frame.uPlane.assign(chromaWidth * chromaHeight, 128);
    frame.vPlane.assign(chromaWidth * chromaHeight, 128);

    if (available < yPlaneSize) {
        return false;
    }

    for (int y = 0; y < height; ++y) {
        memcpy(
                frame.yPlane.data() + static_cast<size_t>(y) * static_cast<size_t>(width),
                base + static_cast<size_t>(y) * static_cast<size_t>(stride),
                static_cast<size_t>(width)
        );
    }

    const uint8_t *uvBase = base + yPlaneSize;
    size_t uvAvailable = available - yPlaneSize;
    if (uvAvailable == 0) {
        return false;
    }

    if (outputColorFormat == COLOR_FORMAT_YUV420_PLANAR) {
        size_t chromaStride = static_cast<size_t>(stride / 2);
        size_t planeSize = chromaStride * chromaHeight;
        if (uvAvailable < planeSize * 2) {
            return false;
        }

        const uint8_t *uBase = uvBase;
        const uint8_t *vBase = uvBase + planeSize;
        for (size_t y = 0; y < chromaHeight; ++y) {
            memcpy(
                    frame.uPlane.data() + y * chromaWidth,
                    uBase + y * chromaStride,
                    chromaWidth
            );
            memcpy(
                    frame.vPlane.data() + y * chromaWidth,
                    vBase + y * chromaStride,
                    chromaWidth
            );
        }
        return true;
    }

    size_t uvStride = static_cast<size_t>(stride);
    size_t requiredSemiPlanar = uvStride * chromaHeight;
    if (uvAvailable < requiredSemiPlanar) {
        return false;
    }

    for (size_t y = 0; y < chromaHeight; ++y) {
        const uint8_t *srcRow = uvBase + y * uvStride;
        for (size_t x = 0; x < chromaWidth; ++x) {
            size_t srcIndex = x * 2;
            frame.uPlane[y * chromaWidth + x] = srcRow[srcIndex];
            frame.vPlane[y * chromaWidth + x] = srcRow[srcIndex + 1];
        }
    }

    return outputColorFormat == COLOR_FORMAT_YUV420_SEMIPLANAR
           || outputColorFormat == COLOR_FORMAT_YUV420_FLEXIBLE
           || outputColorFormat == 0;
}
