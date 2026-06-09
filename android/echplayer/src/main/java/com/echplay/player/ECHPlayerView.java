package com.echplay.player;

import android.content.Context;
import android.os.Environment;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 播放器组件雏形，内部管理 SurfaceView、ECHPlayer 和基础控制条。
 */
public class ECHPlayerView extends LinearLayout {

    /** 截图输出子目录名。 */
    private static final String SCREENSHOT_DIR = "screenshots";
    /** 录制输出子目录名。 */
    private static final String RECORD_DIR = "records";

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

        surfaceView = new SurfaceView(context);
        addView(surfaceView, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
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

        player.setSurface(currentSurface);
        if (!player.isPrepared()) {
            String prepareResult = player.prepare();
            emitEvent(prepareResult);
            if (!player.isPrepared()) {
                return;
            }
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
        player.setOnVideoSizeChangedListener((targetPlayer, width, height) ->
                emitEvent("PlayerView video size: " + width + "x" + height));
        if (surfaceReady && currentSurface != null && currentSurface.isValid()) {
            player.setSurface(currentSurface);
        }
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
