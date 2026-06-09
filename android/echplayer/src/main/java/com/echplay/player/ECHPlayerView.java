package com.echplay.player;

import android.content.Context;
import android.os.Environment;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

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

    /** 视频区域容器，用于承载 SurfaceView 并裁剪边缘。 */
    private final FrameLayout surfaceContainer;
    /** 视频渲染 SurfaceView。 */
    private final SurfaceView surfaceView;
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
    /** 当前视频宽度。 */
    private int videoWidth = 0;
    /** 当前视频高度。 */
    private int videoHeight = 0;

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

    /** 设置播放地址。 */
    public void setVideoPath(String path) {
        videoPath = path == null ? "" : path.trim();
        if (videoPath.length() == 0) {
            emitEvent("PlayerView setVideoPath ignored: path is empty");
            return;
        }

        ensurePlayer();
        if (player.getState() != ECHPlayer.State.IDLE) {
            stopRecordingIfNeeded();
            player.reset();
        }
        resetVideoSize();
        applyRenderModeToPlayer();

        try {
            player.setDataSource(videoPath);
        } catch (RuntimeException e) {
            emitEvent("PlayerView setVideoPath failed: " + e.getMessage());
        }
    }

    /** 设置 RTSP 传输方式。 */
    public void setRtspTransport(int transport) {
        ensurePlayer();
        player.setRtspTransport(transport);
    }

    /** 开始播放。 */
    public void start() {
        ensurePlayer();
        if (!surfaceReady || currentSurface == null || !currentSurface.isValid()) {
            emitEvent("PlayerView start ignored: Surface not ready");
            return;
        }

        if (videoPath.length() == 0) {
            emitEvent("PlayerView start ignored: videoPath is empty");
            return;
        }

        applySurfaceScaleTypeToPlayer();
        applyRenderModeToPlayer();
        player.setSurface(currentSurface);
        if (!player.isPrepared()) {
            String prepareResult = player.prepare();
            emitEvent(prepareResult);
            if (!player.isPrepared()) {
                return;
            }
            updateVideoSizeFromPlayer();
        }
        try {
            emitEvent(player.start());
        } catch (IllegalStateException e) {
            emitEvent("PlayerView start ignored: " + e.getMessage());
        }
        updateRecordButtonState();
    }

    /** 暂停播放。 */
    public void pause() {
        if (player != null) {
            try {
                player.pause();
                emitEvent("PlayerView pause");
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
    }

    /** 确保播放器实例存在。 */
    private void ensurePlayer() {
        if (player != null) {
            return;
        }

        player = new ECHPlayer();
        player.setOnInfoListener((targetPlayer, infoCode, message) -> {
            emitEvent("PlayerView info " + infoCode + "\n" + message);
            return true;
        });
        player.setOnErrorListener((targetPlayer, errorCode, message) -> {
            emitEvent("PlayerView error " + errorCode + "\n" + message);
            return true;
        });
        player.setOnVideoSizeChangedListener((targetPlayer, width, height) -> {
            updateVideoSize(width, height);
            emitEvent("PlayerView video size: " + width + "x" + height);
        });
        applySurfaceScaleTypeToPlayer();
        applyRenderModeToPlayer();
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
                    + "\nsize: " + result.width + "x" + result.height);
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
