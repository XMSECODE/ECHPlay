#ifndef ECHPLAY_GL_VIDEO_RENDERER_H
#define ECHPLAY_GL_VIDEO_RENDERER_H

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/native_window.h>
#include <cstdint>
#include <string>

/**
 * OpenGL ES 视频渲染器骨架，负责 EGL、shader、纹理和 Surface 生命周期。
 */
class GlVideoRenderer {
public:
    /** 创建 OpenGL ES 渲染器。 */
    GlVideoRenderer();

    /** 销毁 OpenGL ES 渲染器并释放资源。 */
    ~GlVideoRenderer();

    /** 禁止复制，避免 EGL 资源被多个对象重复释放。 */
    GlVideoRenderer(const GlVideoRenderer &) = delete;

    /** 禁止赋值，避免 EGL 资源所有权混乱。 */
    GlVideoRenderer &operator=(const GlVideoRenderer &) = delete;

    /** 绑定 Surface 并初始化 EGL、shader 和纹理资源。 */
    std::string initialize(ANativeWindow *window);

    /** 渲染一帧黑色画面，用于验证 EGL swap 链路。 */
    std::string renderBlackFrame();

    /** 上传并渲染一帧 YUV420P 视频帧，scaleType 为 0 保持比例，1 拉伸填满。 */
    std::string renderYuv420PFrame(
            const uint8_t *yData,
            int yLineSize,
            const uint8_t *uData,
            int uLineSize,
            const uint8_t *vData,
            int vLineSize,
            int frameWidth,
            int frameHeight,
            int scaleType
    );

    /** 判断当前 EGL Surface 是否匹配指定 Surface 尺寸。 */
    bool matchesSurfaceSize(int width, int height) const;

    /** 释放 EGL、shader、纹理和 Surface 相关资源。 */
    void release();

    /** 返回当前 OpenGL ES 渲染器是否已经初始化。 */
    bool isInitialized() const;

    /** 返回最近一次错误信息。 */
    std::string getLastError() const;

private:
    /** EGL 显示连接。 */
    EGLDisplay eglDisplay;
    /** EGL 窗口 Surface。 */
    EGLSurface eglSurface;
    /** EGL 渲染上下文。 */
    EGLContext eglContext;
    /** EGL 像素格式配置。 */
    EGLConfig eglConfig;
    /** OpenGL shader 程序。 */
    GLuint programId;
    /** 顶点坐标属性位置。 */
    GLint positionLocation;
    /** 纹理坐标属性位置。 */
    GLint texCoordLocation;
    /** Y 纹理 uniform 位置。 */
    GLint yTextureLocation;
    /** U 纹理 uniform 位置。 */
    GLint uTextureLocation;
    /** V 纹理 uniform 位置。 */
    GLint vTextureLocation;
    /** YUV 三纹理 ID，用于上传 Y、U、V 平面。 */
    GLuint textureIds[3];
    /** 当前 Surface 宽度。 */
    int surfaceWidth;
    /** 当前 Surface 高度。 */
    int surfaceHeight;
    /** 是否已经完成初始化。 */
    bool initialized;
    /** 最近一次错误信息。 */
    std::string lastError;

    /** 让当前 EGL 上下文成为当前线程上下文。 */
    bool makeCurrent();

    /** 创建基础 shader 程序。 */
    bool createProgram();

    /** 编译一个 shader 并返回 shader ID。 */
    GLuint compileShader(GLenum shaderType, const char *shaderSource, std::string &errorMessage);

    /** 创建并配置三张 2D 纹理。 */
    bool createTextures();

    /** 上传单个 YUV 平面到指定纹理。 */
    bool uploadPlane(
            GLuint textureId,
            const uint8_t *planeData,
            int lineSize,
            int width,
            int height
    );

    /** 释放 OpenGL 对象资源。 */
    void releaseGlObjects();

    /** 生成 EGL 错误文本。 */
    std::string makeEglError(const std::string &step);

    /** 读取 shader 或 program 编译链接错误。 */
    std::string readGlInfoLog(GLuint objectId, bool shader);
};

#endif // ECHPLAY_GL_VIDEO_RENDERER_H
