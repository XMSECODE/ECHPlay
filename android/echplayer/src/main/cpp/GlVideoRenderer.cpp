#include "GlVideoRenderer.h"

#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <sstream>
#include <vector>

#define ECH_GL_LOG_TAG "ECHGlRenderer"
#define ECH_GL_LOGI(...) __android_log_print(ANDROID_LOG_INFO, ECH_GL_LOG_TAG, __VA_ARGS__)
#define ECH_GL_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ECH_GL_LOG_TAG, __VA_ARGS__)

static const char *VERTEX_SHADER_SOURCE =
        "attribute vec4 aPosition;\n"
        "attribute vec2 aTexCoord;\n"
        "varying vec2 vTexCoord;\n"
        "void main() {\n"
        "    gl_Position = aPosition;\n"
        "    vTexCoord = aTexCoord;\n"
        "}\n";

static const char *FRAGMENT_SHADER_SOURCE =
        "precision mediump float;\n"
        "varying vec2 vTexCoord;\n"
        "uniform sampler2D yTexture;\n"
        "uniform sampler2D uTexture;\n"
        "uniform sampler2D vTexture;\n"
        "void main() {\n"
        "    float y = texture2D(yTexture, vTexCoord).r;\n"
        "    float u = texture2D(uTexture, vTexCoord).r - 0.5;\n"
        "    float v = texture2D(vTexture, vTexCoord).r - 0.5;\n"
        "    y = 1.1643 * (y - 0.0625);\n"
        "    float r = y + 1.5958 * v;\n"
        "    float g = y - 0.3917 * u - 0.8129 * v;\n"
        "    float b = y + 2.0170 * u;\n"
        "    gl_FragColor = vec4(r, g, b, 1.0);\n"
        "}\n";

/** 创建 OpenGL ES 渲染器并初始化默认值。 */
GlVideoRenderer::GlVideoRenderer()
        : eglDisplay(EGL_NO_DISPLAY),
          eglSurface(EGL_NO_SURFACE),
          eglContext(EGL_NO_CONTEXT),
          eglConfig(nullptr),
          programId(0),
          positionLocation(-1),
          texCoordLocation(-1),
          yTextureLocation(-1),
          uTextureLocation(-1),
          vTextureLocation(-1),
          textureIds{0, 0, 0},
          surfaceWidth(0),
          surfaceHeight(0),
          initialized(false),
          lastError() {
}

/** 销毁 OpenGL ES 渲染器并释放资源。 */
GlVideoRenderer::~GlVideoRenderer() {
    release();
}

/** 绑定 Surface 并初始化 EGL、shader 和纹理资源。 */
std::string GlVideoRenderer::initialize(ANativeWindow *window) {
    release();

    if (window == nullptr) {
        lastError = "OpenGL init failed: window is null";
        return lastError;
    }

    surfaceWidth = ANativeWindow_getWidth(window);
    surfaceHeight = ANativeWindow_getHeight(window);
    if (surfaceWidth <= 0 || surfaceHeight <= 0) {
        lastError = "OpenGL init failed: invalid surface size";
        return lastError;
    }

    eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL_NO_DISPLAY) {
        lastError = makeEglError("eglGetDisplay");
        return lastError;
    }

    if (eglInitialize(eglDisplay, nullptr, nullptr) != EGL_TRUE) {
        lastError = makeEglError("eglInitialize");
        release();
        return lastError;
    }

    const EGLint configAttributes[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0,
            EGL_STENCIL_SIZE, 0,
            EGL_NONE
    };

    EGLint configCount = 0;
    if (eglChooseConfig(
            eglDisplay,
            configAttributes,
            &eglConfig,
            1,
            &configCount) != EGL_TRUE || configCount <= 0) {
        lastError = makeEglError("eglChooseConfig");
        release();
        return lastError;
    }

    const EGLint contextAttributes[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE
    };

    eglContext = eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL_NO_CONTEXT,
            contextAttributes
    );
    if (eglContext == EGL_NO_CONTEXT) {
        lastError = makeEglError("eglCreateContext");
        release();
        return lastError;
    }

    eglSurface = eglCreateWindowSurface(eglDisplay, eglConfig, window, nullptr);
    if (eglSurface == EGL_NO_SURFACE) {
        lastError = makeEglError("eglCreateWindowSurface");
        release();
        return lastError;
    }

    if (!makeCurrent()) {
        release();
        return lastError;
    }

    if (!createProgram()) {
        release();
        return lastError;
    }

    if (!createTextures()) {
        release();
        return lastError;
    }

    glViewport(0, 0, surfaceWidth, surfaceHeight);
    initialized = true;
    lastError.clear();

    ECH_GL_LOGI("OpenGL renderer initialized, size=%dx%d", surfaceWidth, surfaceHeight);
    return "OpenGL renderer initialized";
}

/** 渲染一帧黑色画面，用于验证 EGL swap 链路。 */
std::string GlVideoRenderer::renderBlackFrame() {
    if (!initialized) {
        lastError = "OpenGL render skipped: renderer is not initialized";
        return lastError;
    }

    if (!makeCurrent()) {
        return lastError;
    }

    glViewport(0, 0, surfaceWidth, surfaceHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    if (eglSwapBuffers(eglDisplay, eglSurface) != EGL_TRUE) {
        lastError = makeEglError("eglSwapBuffers");
        return lastError;
    }

    return "OpenGL black frame rendered";
}

/** 上传并渲染一帧 YUV420P 视频帧。 */
std::string GlVideoRenderer::renderYuv420PFrame(
        const uint8_t *yData,
        int yLineSize,
        const uint8_t *uData,
        int uLineSize,
        const uint8_t *vData,
        int vLineSize,
        int frameWidth,
        int frameHeight,
        int scaleType) {

    if (!initialized) {
        lastError = "OpenGL YUV render skipped: renderer is not initialized";
        return lastError;
    }

    if (yData == nullptr || uData == nullptr || vData == nullptr
        || yLineSize <= 0 || uLineSize <= 0 || vLineSize <= 0
        || frameWidth <= 0 || frameHeight <= 0) {
        lastError = "OpenGL YUV render failed: invalid frame";
        return lastError;
    }

    if ((frameWidth % 2) != 0 || (frameHeight % 2) != 0) {
        lastError = "OpenGL YUV render failed: YUV420P size must be even";
        return lastError;
    }

    if (!makeCurrent()) {
        return lastError;
    }

    int chromaWidth = frameWidth / 2;
    int chromaHeight = frameHeight / 2;

    if (!uploadPlane(textureIds[0], yData, yLineSize, frameWidth, frameHeight)
        || !uploadPlane(textureIds[1], uData, uLineSize, chromaWidth, chromaHeight)
        || !uploadPlane(textureIds[2], vData, vLineSize, chromaWidth, chromaHeight)) {
        return lastError;
    }

    int viewportWidth = surfaceWidth;
    int viewportHeight = surfaceHeight;
    int viewportX = 0;
    int viewportY = 0;

    if (scaleType != 1) {
        float scaleX = static_cast<float>(surfaceWidth) / static_cast<float>(frameWidth);
        float scaleY = static_cast<float>(surfaceHeight) / static_cast<float>(frameHeight);
        float scale = std::min(scaleX, scaleY);

        viewportWidth = std::max(1, static_cast<int>(frameWidth * scale));
        viewportHeight = std::max(1, static_cast<int>(frameHeight * scale));
        viewportX = (surfaceWidth - viewportWidth) / 2;
        viewportY = (surfaceHeight - viewportHeight) / 2;
    }

    static const GLfloat vertices[] = {
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f
    };
    static const GLfloat texCoords[] = {
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
    };

    glViewport(0, 0, surfaceWidth, surfaceHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glViewport(viewportX, viewportY, viewportWidth, viewportHeight);

    glUseProgram(programId);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, textureIds[0]);
    glUniform1i(yTextureLocation, 0);

    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, textureIds[1]);
    glUniform1i(uTextureLocation, 1);

    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, textureIds[2]);
    glUniform1i(vTextureLocation, 2);

    glVertexAttribPointer(positionLocation, 2, GL_FLOAT, GL_FALSE, 0, vertices);
    glEnableVertexAttribArray(positionLocation);

    glVertexAttribPointer(texCoordLocation, 2, GL_FLOAT, GL_FALSE, 0, texCoords);
    glEnableVertexAttribArray(texCoordLocation);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(positionLocation);
    glDisableVertexAttribArray(texCoordLocation);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);

    GLenum glError = glGetError();
    if (glError != GL_NO_ERROR) {
        std::ostringstream oss;
        oss << "OpenGL YUV render failed, glError=0x" << std::hex << glError;
        lastError = oss.str();
        return lastError;
    }

    if (eglSwapBuffers(eglDisplay, eglSurface) != EGL_TRUE) {
        lastError = makeEglError("eglSwapBuffers");
        return lastError;
    }

    return "OpenGL YUV frame rendered";
}

/** 释放 EGL、shader、纹理和 Surface 相关资源。 */
void GlVideoRenderer::release() {
    if (eglDisplay != EGL_NO_DISPLAY) {
        if (eglContext != EGL_NO_CONTEXT && eglSurface != EGL_NO_SURFACE) {
            eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
            releaseGlObjects();
        }

        eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

        if (eglSurface != EGL_NO_SURFACE) {
            eglDestroySurface(eglDisplay, eglSurface);
        }

        if (eglContext != EGL_NO_CONTEXT) {
            eglDestroyContext(eglDisplay, eglContext);
        }

        eglTerminate(eglDisplay);
    }

    eglDisplay = EGL_NO_DISPLAY;
    eglSurface = EGL_NO_SURFACE;
    eglContext = EGL_NO_CONTEXT;
    eglConfig = nullptr;
    surfaceWidth = 0;
    surfaceHeight = 0;
    initialized = false;
}

/** 返回当前 OpenGL ES 渲染器是否已经初始化。 */
bool GlVideoRenderer::isInitialized() const {
    return initialized;
}

/** 判断当前 EGL Surface 是否匹配指定 Surface 尺寸。 */
bool GlVideoRenderer::matchesSurfaceSize(int width, int height) const {
    return initialized && surfaceWidth == width && surfaceHeight == height;
}

/** 返回最近一次错误信息。 */
std::string GlVideoRenderer::getLastError() const {
    return lastError;
}

/** 让当前 EGL 上下文成为当前线程上下文。 */
bool GlVideoRenderer::makeCurrent() {
    if (eglDisplay == EGL_NO_DISPLAY
        || eglSurface == EGL_NO_SURFACE
        || eglContext == EGL_NO_CONTEXT) {
        lastError = "OpenGL makeCurrent failed: EGL resource is incomplete";
        return false;
    }

    if (eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext) != EGL_TRUE) {
        lastError = makeEglError("eglMakeCurrent");
        return false;
    }

    return true;
}

/** 创建基础 shader 程序。 */
bool GlVideoRenderer::createProgram() {
    std::string errorMessage;
    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER_SOURCE, errorMessage);
    if (vertexShader == 0) {
        lastError = errorMessage;
        return false;
    }

    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SOURCE, errorMessage);
    if (fragmentShader == 0) {
        glDeleteShader(vertexShader);
        lastError = errorMessage;
        return false;
    }

    programId = glCreateProgram();
    if (programId == 0) {
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        lastError = "OpenGL create program failed";
        return false;
    }

    glAttachShader(programId, vertexShader);
    glAttachShader(programId, fragmentShader);
    glLinkProgram(programId);

    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    GLint linkStatus = GL_FALSE;
    glGetProgramiv(programId, GL_LINK_STATUS, &linkStatus);
    if (linkStatus != GL_TRUE) {
        lastError = "OpenGL link program failed: " + readGlInfoLog(programId, false);
        glDeleteProgram(programId);
        programId = 0;
        return false;
    }

    positionLocation = glGetAttribLocation(programId, "aPosition");
    texCoordLocation = glGetAttribLocation(programId, "aTexCoord");
    yTextureLocation = glGetUniformLocation(programId, "yTexture");
    uTextureLocation = glGetUniformLocation(programId, "uTexture");
    vTextureLocation = glGetUniformLocation(programId, "vTexture");

    if (positionLocation < 0
        || texCoordLocation < 0
        || yTextureLocation < 0
        || uTextureLocation < 0
        || vTextureLocation < 0) {
        lastError = "OpenGL locate shader variable failed";
        glDeleteProgram(programId);
        programId = 0;
        return false;
    }

    return true;
}

/** 编译一个 shader 并返回 shader ID。 */
GLuint GlVideoRenderer::compileShader(
        GLenum shaderType,
        const char *shaderSource,
        std::string &errorMessage) {

    GLuint shaderId = glCreateShader(shaderType);
    if (shaderId == 0) {
        errorMessage = "OpenGL create shader failed";
        return 0;
    }

    glShaderSource(shaderId, 1, &shaderSource, nullptr);
    glCompileShader(shaderId);

    GLint compileStatus = GL_FALSE;
    glGetShaderiv(shaderId, GL_COMPILE_STATUS, &compileStatus);
    if (compileStatus != GL_TRUE) {
        errorMessage = "OpenGL compile shader failed: " + readGlInfoLog(shaderId, true);
        glDeleteShader(shaderId);
        return 0;
    }

    return shaderId;
}

/** 创建并配置三张 2D 纹理。 */
bool GlVideoRenderer::createTextures() {
    glGenTextures(3, textureIds);
    if (textureIds[0] == 0 || textureIds[1] == 0 || textureIds[2] == 0) {
        lastError = "OpenGL create texture failed";
        return false;
    }

    for (GLuint textureId : textureIds) {
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    glBindTexture(GL_TEXTURE_2D, 0);
    return true;
}

/** 上传单个 YUV 平面到指定纹理。 */
bool GlVideoRenderer::uploadPlane(
        GLuint textureId,
        const uint8_t *planeData,
        int lineSize,
        int width,
        int height) {

    if (textureId == 0 || planeData == nullptr || lineSize < width || width <= 0 || height <= 0) {
        lastError = "OpenGL upload plane failed: invalid plane";
        return false;
    }

    const uint8_t *uploadData = planeData;
    std::vector<uint8_t> compactBuffer;
    if (lineSize != width) {
        compactBuffer.resize(static_cast<size_t>(width * height));
        for (int y = 0; y < height; ++y) {
            memcpy(
                    compactBuffer.data() + static_cast<size_t>(y * width),
                    planeData + static_cast<size_t>(y * lineSize),
                    static_cast<size_t>(width)
            );
        }
        uploadData = compactBuffer.data();
    }

    glBindTexture(GL_TEXTURE_2D, textureId);
    glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_LUMINANCE,
            width,
            height,
            0,
            GL_LUMINANCE,
            GL_UNSIGNED_BYTE,
            uploadData
    );

    GLenum glError = glGetError();
    if (glError != GL_NO_ERROR) {
        std::ostringstream oss;
        oss << "OpenGL upload plane failed, glError=0x" << std::hex << glError;
        lastError = oss.str();
        return false;
    }

    return true;
}

/** 释放 OpenGL 对象资源。 */
void GlVideoRenderer::releaseGlObjects() {
    if (textureIds[0] != 0 || textureIds[1] != 0 || textureIds[2] != 0) {
        glDeleteTextures(3, textureIds);
        textureIds[0] = 0;
        textureIds[1] = 0;
        textureIds[2] = 0;
    }

    if (programId != 0) {
        glDeleteProgram(programId);
        programId = 0;
    }
}

/** 生成 EGL 错误文本。 */
std::string GlVideoRenderer::makeEglError(const std::string &step) {
    std::ostringstream oss;
    oss << step << " failed, eglError=0x" << std::hex << eglGetError();
    ECH_GL_LOGE("%s", oss.str().c_str());
    return oss.str();
}

/** 读取 shader 或 program 编译链接错误。 */
std::string GlVideoRenderer::readGlInfoLog(GLuint objectId, bool shader) {
    GLint logLength = 0;
    if (shader) {
        glGetShaderiv(objectId, GL_INFO_LOG_LENGTH, &logLength);
    } else {
        glGetProgramiv(objectId, GL_INFO_LOG_LENGTH, &logLength);
    }

    if (logLength <= 1) {
        return "empty log";
    }

    std::vector<char> logBuffer(static_cast<size_t>(logLength));
    if (shader) {
        glGetShaderInfoLog(objectId, logLength, nullptr, logBuffer.data());
    } else {
        glGetProgramInfoLog(objectId, logLength, nullptr, logBuffer.data());
    }

    return std::string(logBuffer.data());
}
