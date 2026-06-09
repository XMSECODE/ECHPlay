#include "GlVideoRenderer.h"

#include <android/log.h>
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
        "void main() {\n"
        "    gl_FragColor = vec4(vTexCoord.x * 0.0, vTexCoord.y * 0.0, 0.0, 1.0);\n"
        "}\n";

/** 创建 OpenGL ES 渲染器并初始化默认值。 */
GlVideoRenderer::GlVideoRenderer()
        : eglDisplay(EGL_NO_DISPLAY),
          eglSurface(EGL_NO_SURFACE),
          eglContext(EGL_NO_CONTEXT),
          eglConfig(nullptr),
          programId(0),
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
