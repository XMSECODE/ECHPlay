#ifndef ECHPLAY_MEDIA_CODEC_VIDEO_DECODER_H
#define ECHPLAY_MEDIA_CODEC_VIDEO_DECODER_H

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <cstdint>
#include <string>
#include <vector>

struct AVCodecParameters;

/**
 * MediaCodec 视频解码器封装，负责 Android 硬解生命周期。
 */
class MediaCodecVideoDecoder {
public:
    /** 解码后的视频帧，统一整理为 YUV420P。 */
    struct DecodedVideoFrame {
        /** 视频宽度。 */
        int width = 0;
        /** 视频高度。 */
        int height = 0;
        /** 显示时间戳，单位微秒。 */
        int64_t ptsUs = 0;
        /** Y 平面数据。 */
        std::vector<uint8_t> yPlane;
        /** U 平面数据。 */
        std::vector<uint8_t> uPlane;
        /** V 平面数据。 */
        std::vector<uint8_t> vPlane;
        /** Y 平面行跨度。 */
        int yStride = 0;
        /** U 平面行跨度。 */
        int uStride = 0;
        /** V 平面行跨度。 */
        int vStride = 0;

        /** 清空当前帧数据。 */
        void clear();
    };

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
        START_FAILED,
        /** 输入缓冲不可用。 */
        INPUT_TRY_AGAIN,
        /** 送入压缩包失败。 */
        QUEUE_INPUT_FAILED,
        /** 输出缓冲暂不可用。 */
        OUTPUT_TRY_AGAIN,
        /** 输出格式发生变化。 */
        OUTPUT_FORMAT_CHANGED,
        /** 输出格式暂不支持。 */
        UNSUPPORTED_OUTPUT_FORMAT,
        /** 已经读到输出结尾。 */
        OUTPUT_END_OF_STREAM,
        /** 输出缓冲读取失败。 */
        OUTPUT_FAILED
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

    /** 向 MediaCodec 送入一个压缩包。 */
    Status queueInput(const uint8_t *data, size_t size, int64_t ptsUs, bool endOfStream);

    /** 从 MediaCodec 取出一个解码帧。 */
    Status dequeueOutput(DecodedVideoFrame &frame, int64_t timeoutUs);

    /** 清空 MediaCodec 内部状态。 */
    void flush();

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
    /** 当前输出宽度。 */
    int outputWidth;
    /** 当前输出高度。 */
    int outputHeight;
    /** 当前输出 stride。 */
    int outputStride;
    /** 当前输出 slice height。 */
    int outputSliceHeight;
    /** 当前输出颜色格式。 */
    int outputColorFormat;
    /** 当前 FFmpeg codec id。 */
    int codecId;
    /** 长度前缀 NALU 的长度字段字节数。 */
    int nalLengthSize;
    /** 输入 packet 是否需要从长度前缀格式转 Annex B。 */
    bool lengthPrefixedInput;

    /** 从 codec specific data 中填充 MediaFormat。 */
    void fillCodecSpecificData(const AVCodecParameters *codecParameters);

    /** 填充 H.264 的 SPS/PPS 配置。 */
    bool fillH264CodecSpecificData(const uint8_t *data, size_t size);

    /** 填充 H.265 的 VPS/SPS/PPS 配置。 */
    bool fillH265CodecSpecificData(const uint8_t *data, size_t size);

    /** 判断数据是否已经是 Annex B 起始码格式。 */
    static bool isAnnexB(const uint8_t *data, size_t size);

    /** 向缓冲区追加 Annex B 起始码。 */
    static void appendStartCode(std::vector<uint8_t> &buffer);

    /** 把长度前缀 packet 转成 Annex B packet。 */
    bool convertLengthPrefixedPacket(
            const uint8_t *data,
            size_t size,
            std::vector<uint8_t> &annexBPacket
    ) const;

    /** 刷新 MediaCodec 输出格式。 */
    bool updateOutputFormat();

    /** 把输出缓冲复制为 YUV420P。 */
    bool copyOutputBufferToFrame(
            const uint8_t *buffer,
            size_t bufferSize,
            const AMediaCodecBufferInfo &bufferInfo,
            DecodedVideoFrame &frame
    ) const;
};

#endif // ECHPLAY_MEDIA_CODEC_VIDEO_DECODER_H
