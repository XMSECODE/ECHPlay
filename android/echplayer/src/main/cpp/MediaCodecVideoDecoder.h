#ifndef ECHPLAY_MEDIA_CODEC_VIDEO_DECODER_H
#define ECHPLAY_MEDIA_CODEC_VIDEO_DECODER_H

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <cstdint>
#include <string>

struct AVCodecParameters;

/**
 * MediaCodec 视频解码器封装，负责 Android 硬解生命周期。
 */
class MediaCodecVideoDecoder {
public:
    /** 解码器初始化结果。 */
    enum class Status {
        /** 操作成功。 */
        OK,
        /** 当前编码暂不支持硬解。 */
        UNSUPPORTED_CODEC,
        /** 输入参数无效。 */
        INVALID_PARAMETERS,
        /** 创建 MediaCodec 失败。 */
        CREATE_FAILED,
        /** 配置 MediaCodec 失败。 */
        CONFIGURE_FAILED,
        /** 启动 MediaCodec 失败。 */
        START_FAILED
    };

    /** 创建硬解封装。 */
    MediaCodecVideoDecoder();

    /** 释放硬解资源。 */
    ~MediaCodecVideoDecoder();

    /** 禁止拷贝，避免重复释放 MediaCodec。 */
    MediaCodecVideoDecoder(const MediaCodecVideoDecoder &) = delete;

    /** 禁止赋值，避免重复释放 MediaCodec。 */
    MediaCodecVideoDecoder &operator=(const MediaCodecVideoDecoder &) = delete;

    /** 判断 FFmpeg codec id 是否属于 v1.4 计划支持的硬解编码。 */
    static bool isSupportedCodecId(int codecId);

    /** 根据 FFmpeg codec id 返回 MediaCodec MIME。 */
    static const char *mimeFromCodecId(int codecId);

    /** 把状态转换成易读文本。 */
    static std::string statusToString(Status status);

    /** 根据视频流参数创建并启动 MediaCodec。 */
    Status configure(const AVCodecParameters *codecParameters);

    /** 停止并释放 MediaCodec。 */
    void release();

    /** 返回当前是否已经成功启动。 */
    bool isStarted() const;

    /** 返回当前 MIME。 */
    std::string getMime() const;

    /** 返回当前解码器名称。 */
    std::string getCodecName() const;

private:
    /** 当前 MediaCodec 实例。 */
    AMediaCodec *codec;
    /** 当前 MediaFormat 实例。 */
    AMediaFormat *format;
    /** 当前 MIME。 */
    std::string mime;
    /** 当前解码器名称。 */
    std::string codecName;
    /** 当前是否已经启动。 */
    bool started;

    /** 从 codec specific data 中填充 MediaFormat。 */
    void fillCodecSpecificData(const AVCodecParameters *codecParameters);
};

#endif // ECHPLAY_MEDIA_CODEC_VIDEO_DECODER_H
