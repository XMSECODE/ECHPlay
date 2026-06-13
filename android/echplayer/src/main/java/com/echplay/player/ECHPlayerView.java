package com.echplay.player;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 播放器组件雏形，内部管理 SurfaceView、ECHPlayer 和基础控制条。
 */
public class ECHPlayerView extends LinearLayout {

    /** 保持比例完整显示。 */
    public static final int SCALE_TYPE_FIT_CENTER = 0;
    /** 保持比例并裁剪边缘填满容器。 */
    public static final int SCALE_TYPE_CENTER_CROP = 1;
    /** 拉伸填满容器，允许变形。 */
    public static final int SCALE_TYPE_FILL = 2;
    /** 尽量按视频原始尺寸显示。 */
    public static final int SCALE_TYPE_ORIGINAL = 3;

    /** 截图输出子目录名。 */
    private static final String SCREENSHOT_DIR = "screenshots";
    /** 录制输出子目录名。 */
    private static final String RECORD_DIR = "records";

    /**
     * PlayerView 内部状态，用于控制覆盖层显示。
     */
    private enum ViewState {
        /** 等待设置地址或等待播放。 */
        IDLE,
        /** 正在准备或启动播放。 */
        LOADING,
        /** 播放中遇到缓冲。 */
        BUFFERING,
        /** 正在播放，覆盖层隐藏。 */
        PLAYING,
        /** 已暂停。 */
        PAUSED,
        /** 已停止。 */
        STOPPED,
        /** 播放出错。 */
        ERROR,
        /** 组件已经释放。 */
        RELEASED
    }

    /** 视频区域容器，用于承载 SurfaceView 并裁剪边缘。 */
    private final FrameLayout surfaceContainer;
    /** 视频渲染 SurfaceView。 */
    private final SurfaceView surfaceView;
    /** 状态覆盖层，用于展示 loading、buffering、error 和 retry。 */
    private final LinearLayout statusOverlay;
    /** 状态文案。 */
    private final TextView statusText;
    /** 重试按钮。 */
    private final Button retryButton;
    /** 播放按钮。 */
    private final Button startButton;
    /** 暂停按钮。 */
    private final Button pauseButton;
    /** 停止按钮。 */
    private final Button stopButton;
    /** 截图按钮。 */
    private final Button captureButton;
    /** 录制按钮。 */
    private final Button recordButton;
    /** 内部播放器实例。 */
    private ECHPlayer player;
    /** 当前播放地址。 */
    private String videoPath = "";
    /** Surface 是否已经可用。 */
    private boolean surfaceReady = false;
    /** 当前 Surface 对象。 */
    private Surface currentSurface;
    /** 事件监听器。 */
    private EventListener eventListener;
    /** 当前画面比例模式。 */
    private int scaleType = SCALE_TYPE_FIT_CENTER;
    /** 当前渲染模式。 */
    private int renderMode = ECHPlayer.RENDER_MODE_AUTO;
    /** 当前解码模式。 */
    private int decodeMode = ECHPlayer.DECODE_MODE_AUTO;
    /** 当前 PlayerView 状态。 */
    private ViewState viewState = ViewState.IDLE;
    /** 组件是否已释放，用于忽略旧回调。 */
    private boolean released = false;
    /** 当前视频宽度。 */
    private int videoWidth = 0;
    /** 当前视频高度。 */
    private int videoHeight = 0;
    /** 主线程 Handler，用于把播放器回调安全切回 UI 线程。 */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** 组件事件监听器，用于 Demo 展示日志。 */
    public interface EventListener {
        /** 收到组件事件时回调。 */
        void onEvent(String message);
    }

    /** 代码创建组件。 */
    public ECHPlayerView(Context context) {
        this(context, null);
    }

    /** XML 创建组件。 */
    public ECHPlayerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);

        surfaceContainer = new FrameLayout(context);
        surfaceContainer.setBackgroundColor(0xFF000000);
        addView(surfaceContainer, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        surfaceContainer.addOnLayoutChangeListener(
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        updateSurfaceLayout()
        );

        surfaceView = new SurfaceView(context);
        surfaceContainer.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));

        statusOverlay = new LinearLayout(context);
        statusOverlay.setOrientation(VERTICAL);
        statusOverlay.setGravity(Gravity.CENTER);
        statusOverlay.setPadding(dp(16), dp(16), dp(16), dp(16));
        statusOverlay.setBackgroundColor(0x99000000);
        surfaceContainer.addView(statusOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));

        statusText = new TextView(context);
        statusText.setTextColor(0xFFFFFFFF);
        statusText.setTextSize(15f);
        statusText.setGravity(Gravity.CENTER);
        statusOverlay.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        retryButton = new Button(context);
        retryButton.setText("重试");
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        retryParams.topMargin = dp(8);
        statusOverlay.addView(retryButton, retryParams);

        LinearLayout controlsLayout = new LinearLayout(context);
        controlsLayout.setOrientation(HORIZONTAL);
        addView(controlsLayout, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        startButton = addControlButton(controlsLayout, "播放");
        pauseButton = addControlButton(controlsLayout, "暂停");
        stopButton = addControlButton(controlsLayout, "停止");
        captureButton = addControlButton(controlsLayout, "截图");
        recordButton = addControlButton(controlsLayout, "录制");

        bindSurfaceCallback();
        bindControlActions();
        updateViewState(ViewState.IDLE, "等待播放", false);
    }

    /** 设置组件事件监听器。 */
    public void setEventListener(EventListener listener) {
        eventListener = listener;
    }

    /** 设置画面比例模式。 */
    public void setScaleType(int scaleType) {
        this.scaleType = normalizeScaleType(scaleType);
        applySurfaceScaleTypeToPlayer();
        updateSurfaceLayout();
        emitEvent("PlayerView scaleType: " + scaleTypeToText(this.scaleType));
    }

    /** 返回当前画面比例模式。 */
    public int getScaleType() {
        return scaleType;
    }

    /** 设置渲染模式。 */
    public void setRenderMode(int renderMode) {
        this.renderMode = normalizeRenderMode(renderMode);
        applyRenderModeToPlayer();
        emitEvent("PlayerView renderMode: " + renderModeToText(this.renderMode));
    }

    /** 返回当前渲染模式。 */
    public int getRenderMode() {
        return renderMode;
    }

    /** 设置解码模式。 */
    public void setDecodeMode(int decodeMode) {
        this.decodeMode = normalizeDecodeMode(decodeMode);
        applyDecodeModeToPlayer();
        emitEvent("PlayerView decodeMode: " + decodeModeToText(this.decodeMode));
    }

    /** 返回当前解码模式。 */
    public int getDecodeMode() {
        return decodeMode;
    }

    /** 设置播放地址。 */
    public void setVideoPath(String path) {
        released = false;
        videoPath = path == null ? "" : path.trim();
        if (videoPath.length() == 0) {
            emitEvent("PlayerView setVideoPath ignored: path is empty");
            updateViewState(ViewState.IDLE, "请输入播放地址", false);
            return;
        }

        ensurePlayer();
        if (player.getState() != ECHPlayer.State.IDLE) {
            stopRecordingIfNeeded();
            player.reset();
        }
        resetVideoSize();
        applyRenderModeToPlayer();
        applyDecodeModeToPlayer();
        updateViewState(ViewState.IDLE, "点击播放开始", false);

        try {
            player.setDataSource(videoPath);
        } catch (RuntimeException e) {
            emitEvent("PlayerView setVideoPath failed: " + e.getMessage());
            updateViewState(ViewState.ERROR, "设置地址失败\n" + e.getMessage(), true);
        }
    }

    /** 设置 RTSP 传输方式。 */
    public void setRtspTransport(int transport) {
        ensurePlayer();
        player.setRtspTransport(transport);
    }

    /** 开始播放。 */
    public void start() {
        released = false;
        ensurePlayer();
        if (!surfaceReady || currentSurface == null || !currentSurface.isValid()) {
            emitEvent("PlayerView start ignored: Surface not ready");
            updateViewState(ViewState.IDLE, "Surface 未准备好", false);
            return;
        }

        if (videoPath.length() == 0) {
            emitEvent("PlayerView start ignored: videoPath is empty");
            updateViewState(ViewState.IDLE, "请输入播放地址", false);
            return;
        }

        updateViewState(ViewState.LOADING, "正在准备播放...", false);
        applySurfaceScaleTypeToPlayer();
        applyRenderModeToPlayer();
        applyDecodeModeToPlayer();
        player.setSurface(currentSurface);
        if (!player.isPrepared()) {
            String prepareResult = player.prepare();
            emitEvent(prepareResult);
            if (!player.isPrepared()) {
                updateViewState(ViewState.ERROR, "播放准备失败\n" + prepareResult, true);
                return;
            }
            updateVideoSizeFromPlayer();
        }
        try {
            emitEvent(player.start());
            updateViewState(ViewState.PLAYING, "", false);
        } catch (IllegalStateException e) {
            emitEvent("PlayerView start ignored: " + e.getMessage());
            updateViewState(ViewState.ERROR, "播放启动失败\n" + e.getMessage(), true);
        }
        updateRecordButtonState();
    }

    /** 暂停播放。 */
    public void pause() {
        if (player != null) {
            try {
                player.pause();
                emitEvent("PlayerView pause");
                updateViewState(ViewState.PAUSED, "已暂停", false);
            } catch (IllegalStateException e) {
                emitEvent("PlayerView pause ignored: " + e.getMessage());
            }
        }
    }

    /** 停止播放。 */
    public void stop() {
        if (player != null) {
            try {
                stopRecordingIfNeeded();
                player.stop();
                emitEvent("PlayerView stop");
                updateViewState(ViewState.STOPPED, "已停止", false);
            } catch (IllegalStateException e) {
                emitEvent("PlayerView stop ignored: " + e.getMessage());
            }
            updateRecordButtonState();
        }
    }

    /** 释放播放器资源。 */
    public void release() {
        if (player != null) {
            stopRecordingIfNeeded();
            player.release();
            player = null;
            released = true;
            updateViewState(ViewState.RELEASED, "", false);
            updateRecordButtonState();
        }
    }

    /** 返回内部播放器，方便高级页面设置回调。 */
    public ECHPlayer getPlayer() {
        ensurePlayer();
        return player;
    }

    /** 创建一个等宽控制按钮。 */
    private Button addControlButton(LinearLayout parent, String text) {
        Button button = new Button(getContext());
        button.setText(text);
        parent.addView(button, new LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        return button;
    }

    /** 绑定 Surface 生命周期。 */
    private void bindSurfaceCallback() {
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                currentSurface = holder.getSurface();
                surfaceReady = currentSurface != null && currentSurface.isValid();
                released = false;
                if (player != null && surfaceReady) {
                    player.setSurface(currentSurface);
                }
                emitEvent("PlayerView Surface created");
            }

            @Override
            public void surfaceChanged(
                    SurfaceHolder holder,
                    int format,
                    int width,
                    int height) {
                currentSurface = holder.getSurface();
                surfaceReady = currentSurface != null && currentSurface.isValid();
                updateSurfaceLayout();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                currentSurface = null;
                updateViewState(ViewState.IDLE, "Surface 已销毁", false);
                if (player != null) {
                    try {
                        stopRecordingIfNeeded();
                        player.stop();
                        if (!player.isReleased()) {
                            player.setSurface(null);
                        }
                    } catch (IllegalStateException e) {
                        emitEvent("PlayerView surface destroy ignored: " + e.getMessage());
                    }
                }
                emitEvent("PlayerView Surface destroyed");
            }
        });
    }

    /** 绑定基础控制条动作。 */
    private void bindControlActions() {
        startButton.setOnClickListener(view -> start());
        pauseButton.setOnClickListener(view -> pause());
        stopButton.setOnClickListener(view -> stop());
        captureButton.setOnClickListener(view -> capture());
        recordButton.setOnClickListener(view -> toggleRecording());
        retryButton.setOnClickListener(view -> retry());
    }

    /** 处理播放器 info 回调并刷新组件状态。 */
    private void handlePlayerInfo(ECHPlayer targetPlayer, int infoCode, String message) {
        if (released) {
            return;
        }

        if (infoCode == ECHPlayer.INFO_PREPARE_STARTED) {
            updateViewState(ViewState.LOADING, "正在准备播放...", false);
        } else if (infoCode == ECHPlayer.INFO_BUFFERING_START) {
            updateViewState(ViewState.BUFFERING, "正在缓冲...", false);
        } else if (infoCode == ECHPlayer.INFO_BUFFERING_END
                || infoCode == ECHPlayer.INFO_PLAY_STARTED
                || infoCode == ECHPlayer.INFO_VIDEO_RENDERING_START) {
            updateViewState(ViewState.PLAYING, "", false);
        }

        emitEvent("PlayerView info " + infoCode
                + "\n" + message
                + "\ncurrentDecode: " + targetPlayer.getCurrentDecodeType()
                + "\ndecoder: " + targetPlayer.getCurrentDecoderName()
                + "\nfallbackReason: " + targetPlayer.getLastDecodeFallbackReason());
    }

    /** 处理播放器错误并显示重试入口。 */
    private void handlePlayerError(int errorCode, String message) {
        if (released) {
            return;
        }

        String safeMessage = message == null ? "" : message;
        updateViewState(ViewState.ERROR, "播放出错\n错误码: " + errorCode + "\n" + safeMessage, true);
        emitEvent("PlayerView error " + errorCode + "\n" + safeMessage);
    }

    /** 根据缓冲百分比刷新覆盖层。 */
    private void handleBufferingPercent(int percent) {
        if (released) {
            return;
        }

        if (percent <= 0) {
            updateViewState(ViewState.BUFFERING, "正在缓冲...", false);
        } else if (percent >= 100 && viewState == ViewState.BUFFERING) {
            updateViewState(ViewState.PLAYING, "", false);
        }
    }

    /** 使用最近一次播放地址和配置重新播放。 */
    private void retry() {
        if (released) {
            released = false;
        }
        if (videoPath.length() == 0) {
            updateViewState(ViewState.IDLE, "请输入播放地址", false);
            emitEvent("PlayerView retry ignored: videoPath is empty");
            return;
        }

        emitEvent("PlayerView retry");
        setVideoPath(videoPath);
        start();
    }

    /** 确保播放器实例存在。 */
    private void ensurePlayer() {
        if (player != null) {
            return;
        }

        player = new ECHPlayer();
        player.setOnInfoListener((targetPlayer, infoCode, message) -> {
            postToUi(() -> handlePlayerInfo(targetPlayer, infoCode, message));
            return true;
        });
        player.setOnErrorListener((targetPlayer, errorCode, message) -> {
            postToUi(() -> handlePlayerError(errorCode, message));
            return true;
        });
        player.setOnCompletionListener(targetPlayer ->
                postToUi(() -> updateViewState(ViewState.STOPPED, "播放完成", false)));
        player.setOnBufferingUpdateListener((targetPlayer, percent) ->
                postToUi(() -> handleBufferingPercent(percent)));
        player.setOnVideoSizeChangedListener((targetPlayer, width, height) -> {
            postToUi(() -> {
                updateVideoSize(width, height);
                emitEvent("PlayerView video size: " + width + "x" + height);
            });
        });
        applySurfaceScaleTypeToPlayer();
        applyRenderModeToPlayer();
        applyDecodeModeToPlayer();
        if (surfaceReady && currentSurface != null && currentSurface.isValid()) {
            player.setSurface(currentSurface);
        }
    }

    /** 把当前渲染模式应用到播放器。 */
    private void applyRenderModeToPlayer() {
        if (player == null || player.isReleased()) {
            return;
        }

        try {
            player.setRenderMode(renderMode);
        } catch (IllegalStateException e) {
            emitEvent("PlayerView renderMode ignored: " + e.getMessage());
        }
    }

    /** 把当前解码模式应用到播放器。 */
    private void applyDecodeModeToPlayer() {
        if (player == null || player.isReleased()) {
            return;
        }

        try {
            player.setDecodeMode(decodeMode);
        } catch (IllegalStateException e) {
            emitEvent("PlayerView decodeMode ignored: " + e.getMessage());
        }
    }

    /** 把当前比例模式转换为 NativeWindow 渲染缩放方式。 */
    private void applySurfaceScaleTypeToPlayer() {
        if (player == null || player.isReleased()) {
            return;
        }

        int nativeScaleType = scaleType == SCALE_TYPE_FILL
                ? ECHPlayer.SURFACE_SCALE_TYPE_FILL
                : ECHPlayer.SURFACE_SCALE_TYPE_FIT_CENTER;
        try {
            player.setSurfaceScaleType(nativeScaleType);
        } catch (IllegalStateException e) {
            emitEvent("PlayerView scaleType ignored: " + e.getMessage());
        }
    }

    /** 从播放器读取当前视频尺寸并刷新布局。 */
    private void updateVideoSizeFromPlayer() {
        if (player == null || player.isReleased()) {
            return;
        }

        try {
            updateVideoSize(player.getVideoWidth(), player.getVideoHeight());
        } catch (IllegalStateException e) {
            emitEvent("PlayerView video size ignored: " + e.getMessage());
        }
    }

    /** 更新视频尺寸并刷新 SurfaceView 布局。 */
    private void updateVideoSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }

        videoWidth = width;
        videoHeight = height;
        updateSurfaceLayout();
    }

    /** 重置视频尺寸并恢复 SurfaceView 默认布局。 */
    private void resetVideoSize() {
        videoWidth = 0;
        videoHeight = 0;
        updateSurfaceLayout();
    }

    /** 按当前比例模式计算 SurfaceView 在容器中的尺寸。 */
    private void updateSurfaceLayout() {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        int containerWidth = surfaceContainer.getWidth();
        int containerHeight = surfaceContainer.getHeight();

        if (containerWidth <= 0 || containerHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.CENTER;
            surfaceView.setLayoutParams(params);
            return;
        }

        int targetWidth = containerWidth;
        int targetHeight = containerHeight;

        if (scaleType == SCALE_TYPE_FIT_CENTER) {
            float scale = Math.min(
                    (float) containerWidth / (float) videoWidth,
                    (float) containerHeight / (float) videoHeight
            );
            targetWidth = Math.max(1, Math.round(videoWidth * scale));
            targetHeight = Math.max(1, Math.round(videoHeight * scale));
        } else if (scaleType == SCALE_TYPE_CENTER_CROP) {
            float scale = Math.max(
                    (float) containerWidth / (float) videoWidth,
                    (float) containerHeight / (float) videoHeight
            );
            targetWidth = Math.max(1, Math.round(videoWidth * scale));
            targetHeight = Math.max(1, Math.round(videoHeight * scale));
        } else if (scaleType == SCALE_TYPE_ORIGINAL) {
            targetWidth = videoWidth;
            targetHeight = videoHeight;
            if (targetWidth > containerWidth || targetHeight > containerHeight) {
                float scale = Math.min(
                        (float) containerWidth / (float) videoWidth,
                        (float) containerHeight / (float) videoHeight
                );
                targetWidth = Math.max(1, Math.round(videoWidth * scale));
                targetHeight = Math.max(1, Math.round(videoHeight * scale));
            }
        }

        params.width = targetWidth;
        params.height = targetHeight;
        params.gravity = Gravity.CENTER;
        surfaceView.setLayoutParams(params);
    }

    /** 规范化外部传入的比例模式。 */
    private int normalizeScaleType(int requestedScaleType) {
        if (requestedScaleType == SCALE_TYPE_CENTER_CROP
                || requestedScaleType == SCALE_TYPE_FILL
                || requestedScaleType == SCALE_TYPE_ORIGINAL) {
            return requestedScaleType;
        }
        return SCALE_TYPE_FIT_CENTER;
    }

    /** 规范化外部传入的渲染模式。 */
    private int normalizeRenderMode(int requestedRenderMode) {
        if (requestedRenderMode == ECHPlayer.RENDER_MODE_OPENGL
                || requestedRenderMode == ECHPlayer.RENDER_MODE_NATIVE_WINDOW) {
            return requestedRenderMode;
        }
        return ECHPlayer.RENDER_MODE_AUTO;
    }

    /** 规范化外部传入的解码模式。 */
    private int normalizeDecodeMode(int requestedDecodeMode) {
        if (requestedDecodeMode == ECHPlayer.DECODE_MODE_SOFTWARE
                || requestedDecodeMode == ECHPlayer.DECODE_MODE_MEDIACODEC) {
            return requestedDecodeMode;
        }
        return ECHPlayer.DECODE_MODE_AUTO;
    }

    /** 把比例模式转成易读文本。 */
    private String scaleTypeToText(int value) {
        if (value == SCALE_TYPE_CENTER_CROP) {
            return "centerCrop";
        }
        if (value == SCALE_TYPE_FILL) {
            return "fill";
        }
        if (value == SCALE_TYPE_ORIGINAL) {
            return "original";
        }
        return "fitCenter";
    }

    /** 把渲染模式转成易读文本。 */
    private String renderModeToText(int value) {
        if (value == ECHPlayer.RENDER_MODE_OPENGL) {
            return "opengl";
        }
        if (value == ECHPlayer.RENDER_MODE_NATIVE_WINDOW) {
            return "nativeWindow";
        }
        return "auto";
    }

    /** 把解码模式转成易读文本。 */
    private String decodeModeToText(int value) {
        if (value == ECHPlayer.DECODE_MODE_SOFTWARE) {
            return "software";
        }
        if (value == ECHPlayer.DECODE_MODE_MEDIACODEC) {
            return "mediacodec";
        }
        return "auto";
    }

    /** 保存当前解码帧截图。 */
    private void capture() {
        if (player == null) {
            emitEvent("PlayerView capture ignored: player is null");
            return;
        }

        try {
            File outputFile = buildOutputFile(SCREENSHOT_DIR, "png");
            ECHPlayer.CaptureResult result = player.captureCurrentFramePng(outputFile.getAbsolutePath());
            emitEvent("PlayerView capture success\nfile: " + result.filePath
                    + "\nsize: " + result.width + "x" + result.height
                    + "\ndecodeSource: decoded frame, not SurfaceView");
        } catch (Exception e) {
            emitEvent("PlayerView capture failed: " + e.getMessage());
        }
    }

    /** 切换录制状态。 */
    private void toggleRecording() {
        ensurePlayer();
        if (player.isRecording()) {
            stopRecordingIfNeeded();
            updateRecordButtonState();
            return;
        }

        File outputFile = buildOutputFile(RECORD_DIR, "mkv");
        emitEvent(player.startRecording(outputFile.getAbsolutePath()));
        emitEvent("PlayerView record source: demux packet stream, not screen recording");
        updateRecordButtonState();
    }

    /** 如有录制则安全停止。 */
    private void stopRecordingIfNeeded() {
        if (player != null && player.isRecording()) {
            emitEvent(player.stopRecording());
        }
    }

    /** 更新录制按钮显示文本。 */
    private void updateRecordButtonState() {
        if (recordButton == null) {
            return;
        }

        if (player != null && player.getRecordingState() == ECHPlayer.RecordingState.RECORDING) {
            recordButton.setText("停止录制");
        } else {
            recordButton.setText("录制");
        }
    }

    /** 更新 PlayerView 状态覆盖层。 */
    private void updateViewState(ViewState state, String message, boolean showRetry) {
        viewState = state;
        String safeMessage = message == null ? "" : message;

        if (state == ViewState.PLAYING || state == ViewState.RELEASED) {
            statusOverlay.setVisibility(View.GONE);
            retryButton.setVisibility(View.GONE);
            statusText.setText("");
            return;
        }

        if (safeMessage.length() == 0) {
            safeMessage = defaultMessageForState(state);
        }

        statusText.setText(safeMessage);
        retryButton.setVisibility(showRetry ? View.VISIBLE : View.GONE);
        statusOverlay.setVisibility(View.VISIBLE);
    }

    /** 返回状态默认文案。 */
    private String defaultMessageForState(ViewState state) {
        if (state == ViewState.LOADING) {
            return "正在准备播放...";
        }
        if (state == ViewState.BUFFERING) {
            return "正在缓冲...";
        }
        if (state == ViewState.ERROR) {
            return "播放出错";
        }
        if (state == ViewState.PAUSED) {
            return "已暂停";
        }
        if (state == ViewState.STOPPED) {
            return "已停止";
        }
        return "等待播放";
    }

    /** 把任务切回主线程执行。 */
    private void postToUi(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            uiHandler.post(action);
        }
    }

    /** 把 dp 转换成像素。 */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 生成组件输出文件。 */
    private File buildOutputFile(String subDirName, String extension) {
        File baseDir = getContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (baseDir == null) {
            baseDir = getContext().getFilesDir();
        }

        File targetDir = new File(baseDir, subDirName);
        if (!targetDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            targetDir.mkdirs();
        }

        String timeText = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(targetDir, "echplay_view_" + timeText + "." + extension);
    }

    /** 分发组件事件。 */
    private void emitEvent(String message) {
        if (eventListener != null) {
            eventListener.onEvent(message == null ? "" : message);
        }
    }
}
