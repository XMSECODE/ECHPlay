package com.example.abcplaydemo;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.echplay.player.ECHPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 播放器 Demo 主页面，负责播放控制、截图和录制交互。
 */
public class MainActivity extends AppCompatActivity {

    /** SharedPreferences 文件名。 */
    private static final String PREFS_NAME = "player_prefs";
    /** 上次数据源缓存 key。 */
    private static final String KEY_LAST_DATA_SOURCE = "last_data_source";
    /** RTSP 传输方式缓存 key。 */
    private static final String KEY_RTSP_TRANSPORT = "rtsp_transport";
    /** 默认网络播放地址。 */
    private static final String DEFAULT_NETWORK_SOURCE = "rtsp://192.168.1.1:554/live";
    /** 截图目录名。 */
    private static final String SCREENSHOT_DIR = "screenshots";
    /** 录制目录名。 */
    private static final String RECORD_DIR = "records";
    /** 模式：网络 URL。 */
    private static final int MODE_NETWORK = 0;
    /** 模式：本地文件。 */
    private static final int MODE_LOCAL = 1;
    /** 文件选择请求码。 */
    private static final int REQUEST_PICK_FILE = 1001;
    /** 播放模式缓存 key。 */
    private static final String KEY_PLAY_MODE = "play_mode";
    /** 本地文件路径缓存 key。 */
    private static final String KEY_LOCAL_FILE_PATH = "local_file_path";
    /** Demo 日志最大字符数，避免长时间播放时 UI 文本过大。 */
    private static final int MAX_LOG_TEXT_LENGTH = 12000;

    /** 视频显示控件。 */
    private SurfaceView surfaceView;
    /** 数据源输入框。 */
    private EditText dataSourceInput;
    /** 播放按钮。 */
    private Button openButton;
    /** PlayerView Demo 入口按钮。 */
    private Button openPlayerViewDemoButton;
    /** RTSP 传输组选框，仅 RTSP 协议生效。 */
    private RadioGroup transportGroup;
    /** TCP 选项按钮。 */
    private RadioButton transportTcpButton;
    /** UDP 选项按钮。 */
    private RadioButton transportUdpButton;
    /** 渲染模式组选框。 */
    private RadioGroup renderModeGroup;
    /** 自动渲染模式按钮。 */
    private RadioButton renderModeAutoButton;
    /** OpenGL 渲染模式按钮。 */
    private RadioButton renderModeOpenGlButton;
    /** NativeWindow 渲染模式按钮。 */
    private RadioButton renderModeNativeButton;
    /** 解码模式组选框。 */
    private RadioGroup decodeModeGroup;
    /** 自动解码模式按钮。 */
    private RadioButton decodeModeAutoButton;
    /** 软件解码模式按钮。 */
    private RadioButton decodeModeSoftwareButton;
    /** 硬件解码模式按钮。 */
    private RadioButton decodeModeMediaCodecButton;
    /** 播放模式组选框。 */
    private RadioGroup modeGroup;
    /** 网络 URL 模式按钮。 */
    private RadioButton modeRtspButton;
    /** 本地文件模式按钮。 */
    private RadioButton modeLocalButton;
    /** 网络 URL 输入区域布局。 */
    private LinearLayout rtspInputLayout;
    /** 本地文件输入区域布局。 */
    private LinearLayout localInputLayout;
    /** 本地文件路径输入框。 */
    private EditText localPathInput;
    /** 选择文件按钮。 */
    private Button pickFileButton;
    /** 暂停按钮。 */
    private Button pauseButton;
    /** 继续按钮。 */
    private Button resumeButton;
    /** 跳转到中间位置按钮。 */
    private Button seekMiddleButton;
    /** 停止按钮。 */
    private Button stopButton;
    /** 截图按钮。 */
    private Button captureButton;
    /** 录制按钮。 */
    private Button recordButton;
    /** 播放进度条。 */
    private SeekBar progressSeekBar;
    /** 当前播放时间文本。 */
    private TextView currentTimeText;
    /** 总时长文本。 */
    private TextView durationTimeText;
    /** 当前解码状态文本。 */
    private TextView decodeStatusText;
    /** 日志文本。 */
    private TextView sampleText;
    /** Java 播放器实例。 */
    private ECHPlayer player;
    /** Surface 是否已准备完毕。 */
    private boolean demoStarted = false;
    /** 用户是否正在拖动进度条。 */
    private boolean userSeeking = false;
    /** 当前媒体总时长。 */
    private long durationMs = 0;
    /** Surface 宽度。 */
    private int surfaceWidth = 0;
    /** Surface 高度。 */
    private int surfaceHeight = 0;
    /** 当前录制文件路径。 */
    private String currentRecordingPath = null;
    /** 最近一次截图文件路径。 */
    private String lastCapturePath = null;
    /** 最近一次展示的缓冲百分比。 */
    private int lastBufferingPercent = -1;
    /** 主线程 Handler。 */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    /** 定时刷新进度的任务。 */
    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            updateProgressUi();
            uiHandler.postDelayed(this, 300);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.surfaceView);
        dataSourceInput = findViewById(R.id.dataSourceInput);
        openButton = findViewById(R.id.openButton);
        openPlayerViewDemoButton = findViewById(R.id.openPlayerViewDemoButton);
        transportGroup = findViewById(R.id.transportGroup);
        transportTcpButton = findViewById(R.id.transportTcpButton);
        transportUdpButton = findViewById(R.id.transportUdpButton);
        renderModeGroup = findViewById(R.id.renderModeGroup);
        renderModeAutoButton = findViewById(R.id.renderModeAutoButton);
        renderModeOpenGlButton = findViewById(R.id.renderModeOpenGlButton);
        renderModeNativeButton = findViewById(R.id.renderModeNativeButton);
        decodeModeGroup = findViewById(R.id.decodeModeGroup);
        decodeModeAutoButton = findViewById(R.id.decodeModeAutoButton);
        decodeModeSoftwareButton = findViewById(R.id.decodeModeSoftwareButton);
        decodeModeMediaCodecButton = findViewById(R.id.decodeModeMediaCodecButton);
        modeGroup = findViewById(R.id.modeGroup);
        modeRtspButton = findViewById(R.id.modeRtspButton);
        modeLocalButton = findViewById(R.id.modeLocalButton);
        rtspInputLayout = findViewById(R.id.rtspInputLayout);
        localInputLayout = findViewById(R.id.localInputLayout);
        localPathInput = findViewById(R.id.localPathInput);
        pickFileButton = findViewById(R.id.pickFileButton);
        pauseButton = findViewById(R.id.pauseButton);
        resumeButton = findViewById(R.id.resumeButton);
        seekMiddleButton = findViewById(R.id.seekMiddleButton);
        stopButton = findViewById(R.id.stopButton);
        captureButton = findViewById(R.id.captureButton);
        recordButton = findViewById(R.id.recordButton);
        progressSeekBar = findViewById(R.id.progressSeekBar);
        currentTimeText = findViewById(R.id.currentTimeText);
        durationTimeText = findViewById(R.id.durationTimeText);
        decodeStatusText = findViewById(R.id.decodeStatusText);
        sampleText = findViewById(R.id.sample_text);

        restorePlayMode();
        updateModeUi();
        sampleText.setText("等待 Surface 创建...");
        currentTimeText.setText(formatTime(0));
        durationTimeText.setText(formatTime(0));
        updateDecodeStatusUi();
        updateRecordButtonState();

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            persistPlayMode();
            updateModeUi();
        });
        renderModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (player != null) {
                player.setRenderMode(resolveRenderMode());
                appendLog("renderMode: " + renderModeToText(player.getRenderMode()));
            }
        });
        decodeModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (player != null) {
                player.setDecodeMode(resolveDecodeMode());
                appendLog("decodeMode: " + decodeModeToText(player.getDecodeMode()));
                updateDecodeStatusUi();
            }
        });

        openButton.setOnClickListener(v -> tryStartPlayback());
        openPlayerViewDemoButton.setOnClickListener(v ->
                startActivity(new Intent(this, PlayerViewDemoActivity.class)));
        pickFileButton.setOnClickListener(v -> pickLocalFile());

        pauseButton.setOnClickListener(v -> {
            if (player != null) {
                try {
                    player.pause();
                    appendLog("pause\nstate: " + player.getState());
                } catch (IllegalStateException e) {
                    appendLog("pause ignored: " + e.getMessage());
                }
            }
        });

        resumeButton.setOnClickListener(v -> {
            if (player != null) {
                try {
                    appendLog(player.start() + "\nstate: " + player.getState());
                } catch (IllegalStateException e) {
                    appendLog("start ignored: " + e.getMessage());
                }
            }
        });

        seekMiddleButton.setOnClickListener(v -> seekToMiddle());

        stopButton.setOnClickListener(v -> {
            if (player != null) {
                stopRecordingWithLog();
                player.stop();
                updateRecordButtonState();
                appendLog("stop");
            }
        });

        captureButton.setOnClickListener(v -> captureCurrentFrame());
        recordButton.setOnClickListener(v -> toggleRecording());

        progressSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    long previewPositionMs = durationMs * progress / 1000L;
                    currentTimeText.setText(formatTime(previewPositionMs));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;

                if (player != null && durationMs > 0) {
                    if (player.getState() == ECHPlayer.State.SEEKING) {
                        appendLog("seek ignored: 正在处理上一次 seek");
                        return;
                    }
                    if (!player.isSeekable()) {
                        appendLog("seek ignored: 当前媒体不支持 seek");
                        progressSeekBar.setProgress(0);
                        currentTimeText.setText(formatTime(0));
                        return;
                    }
                    long targetPositionMs = durationMs * seekBar.getProgress() / 1000L;
                    try {
                        String seekInfo = player.seekTo(targetPositionMs);
                        appendLog(seekInfo + "\nstate: " + player.getState());
                        updateProgressUi();
                    } catch (IllegalStateException e) {
                        appendLog("seek ignored: " + e.getMessage());
                    }
                }
            }
        });

        SurfaceHolder holder = surfaceView.getHolder();
        holder.setFormat(PixelFormat.RGBA_8888);

        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                sampleText.setText("Surface created，等待 surfaceChanged...");
            }

            @Override
            public void surfaceChanged(
                    @NonNull SurfaceHolder holder,
                    int format,
                    int width,
                    int height) {

                surfaceWidth = width;
                surfaceHeight = height;

                if (demoStarted) {
                    return;
                }

                Surface surface = holder.getSurface();
                if (surface == null || !surface.isValid()) {
                    sampleText.setText("Surface 无效");
                    return;
                }

                demoStarted = true;
                sampleText.setText("Surface 已创建，点击“播放”开始");
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (player != null) {
                    stopRecordingWithLog();
                    player.stop();
                    player.setSurface(null);
                    updateRecordButtonState();
                }
            }
        });
    }

    /** 跳到媒体中间位置，方便验证 HTTP / HLS 等点播流 seek。 */
    private void seekToMiddle() {
        if (player == null) {
            appendLog("seek middle ignored: player is null");
            return;
        }

        long latestDurationMs = Math.max(durationMs, player.getDuration());
        if (latestDurationMs <= 0 || !player.isSeekable()) {
            appendLog("seek middle ignored: 当前媒体不支持 seek");
            return;
        }

        long targetPositionMs = latestDurationMs / 2L;
        try {
            String seekInfo = player.seekTo(targetPositionMs);
            appendLog("seek middle: " + formatTime(targetPositionMs)
                    + "\n" + seekInfo
                    + "\nstate: " + player.getState());
            updateProgressUi();
        } catch (IllegalStateException e) {
            appendLog("seek middle ignored: " + e.getMessage());
        }
    }

    /** 尝试启动播放。 */
    private void tryStartPlayback() {
        Surface surface = surfaceView.getHolder().getSurface();
        if (surface == null || !surface.isValid()) {
            sampleText.setText("Surface 还没准备好");
            return;
        }

        persistPlayMode();
        runPlayDemo(surface, surfaceWidth, surfaceHeight);
    }

    /** 运行播放器演示并开始播放。 */
    private void runPlayDemo(Surface surface, int surfaceWidth, int surfaceHeight) {
        stopProgressUpdates();

        if (player != null) {
            stopRecordingWithLog();
            player.stop();
            player.release();
            player = null;
        }

        player = new ECHPlayer();
        bindPlayerCallbacks(player);
        currentRecordingPath = null;
        lastBufferingPercent = -1;
        durationMs = 0;
        progressSeekBar.setProgress(0);
        currentTimeText.setText(formatTime(0));
        durationTimeText.setText(formatTime(0));

        StringBuilder text = new StringBuilder();
        text.append("ECHPlayer video play demo\n");
        text.append("Surface: ");
        text.append(surfaceWidth);
        text.append("x");
        text.append(surfaceHeight);
        text.append("\n");
        text.append("FFmpeg version: ");
        text.append(player.getFFmpegVersion());
        text.append("\n\n");
        text.append("Render mode: ");
        text.append(renderModeToText(resolveRenderMode()));
        text.append("\n\n");
        text.append("Decode mode: ");
        text.append(decodeModeToText(resolveDecodeMode()));
        text.append("\n\n");
        sampleText.setText(text.toString());

        int playMode = modeGroup.getCheckedRadioButtonId() == R.id.modeLocalButton
                ? MODE_LOCAL
                : MODE_NETWORK;
        try {
            String dataSource = resolveDataSource();
            String protocolText = resolveProtocolText(dataSource);
            appendLog("protocol: " + protocolText + "\nsource: " + dataSource);

            player.setSurface(surface);
            player.setRenderMode(resolveRenderMode());
            player.setDecodeMode(resolveDecodeMode());
            player.setDataSource(dataSource);

            if (playMode == MODE_NETWORK) {
                applyNetworkOptions(player, dataSource);
            }

            String prepareInfo = player.prepare();
            appendLog(prepareInfo);
            updateDecodeStatusUi();
            if (player.getState() != ECHPlayer.State.PREPARED) {
                appendLog("play aborted: prepare failed\nstate: " + player.getState());
                updateRecordButtonState();
                return;
            }
            appendLog(formatMediaInfo(player.getMediaInfo()));
            appendLog(formatTrackInfo(player.getTrackInfo()));

            durationMs = Math.max(0, player.getDuration());
            durationTimeText.setText(formatTime(durationMs));
            updateSeekableUi();

            String playInfo = player.start();
            appendLog(playInfo
                    + "\nrenderMode: " + renderModeToText(player.getRenderMode())
                    + "\ndecodeMode: " + decodeModeToText(player.getDecodeMode())
                    + "\ncurrentDecode: " + player.getCurrentDecodeType()
                    + "\ndecoder: " + player.getCurrentDecoderName()
                    + "\nfallbackReason: " + player.getLastDecodeFallbackReason()
                    + "\nstate: " + player.getState());
            updateDecodeStatusUi();
            startProgressUpdates();
            updateRecordButtonState();

        } catch (IOException e) {
            StringBuilder errorText = new StringBuilder();
            errorText.append("没有找到可播放的数据源。\n\n");
            if (playMode == MODE_LOCAL) {
                errorText.append("请通过选择文件按钮选取本地视频文件。\n\n");
            } else {
                errorText.append("可以输入 rtsp://、http://、https:// 或 .m3u8 地址\n\n");
            }
            errorText.append("error: ");
            errorText.append(e.getMessage());
            appendLog(errorText.toString());
        }
    }

    /** 绑定播放器回调并把事件展示到 Demo 日志。 */
    private void bindPlayerCallbacks(ECHPlayer targetPlayer) {
        targetPlayer.setOnPreparedListener(callbackPlayer ->
                postToUi(() -> appendLog("回调 OnPrepared\nstate: " + callbackPlayer.getState())));

        targetPlayer.setOnCompletionListener(callbackPlayer ->
                postToUi(() -> {
                    stopProgressUpdates();
                    updateRecordButtonState();
                    appendLog("回调 OnCompletion\nstate: " + callbackPlayer.getState());
                }));

        targetPlayer.setOnErrorListener((callbackPlayer, errorCode, message) -> {
            postToUi(() -> appendLog(
                    "错误码 " + errorCode + ": " + describeErrorCode(errorCode)
                            + "\nmessage: " + message
                            + "\nstate: " + callbackPlayer.getState()
            ));
            return true;
        });

        targetPlayer.setOnInfoListener((callbackPlayer, infoCode, message) -> {
            postToUi(() -> {
                updateRecordButtonState();
                updateDecodeStatusUi();
                appendLog(
                        "信息码 " + infoCode + ": " + describeInfoCode(infoCode)
                                + "\nmessage: " + message
                                + "\nstate: " + callbackPlayer.getState()
                );
            });
            return true;
        });

        targetPlayer.setOnBufferingUpdateListener((callbackPlayer, percent) ->
                postToUi(() -> appendBufferingLog(percent)));

        targetPlayer.setOnVideoSizeChangedListener((callbackPlayer, width, height) ->
                postToUi(() -> appendLog(
                        "视频尺寸变化"
                                + "\nwidth: " + width
                                + "\nheight: " + height
                )));
    }

    /** 应用网络播放参数，RTSP 会额外设置 TCP 或 UDP 传输方式。 */
    private void applyNetworkOptions(ECHPlayer targetPlayer, String dataSource) {
        targetPlayer.setOption(ECHPlayer.OPTION_CATEGORY_FORMAT, ECHPlayer.OPTION_TIMEOUT, 5_000_000L);
        targetPlayer.setOption(ECHPlayer.OPTION_CATEGORY_FORMAT, ECHPlayer.OPTION_RW_TIMEOUT, 5_000_000L);
        targetPlayer.setOption(ECHPlayer.OPTION_CATEGORY_FORMAT, ECHPlayer.OPTION_BUFFER_SIZE, 1_024_000L);
        if (isRtspSource(dataSource)) {
            targetPlayer.setRtspTransport(resolveRtspTransport());
            targetPlayer.setOption(ECHPlayer.OPTION_CATEGORY_FORMAT, ECHPlayer.OPTION_MAX_DELAY, 500_000L);
            targetPlayer.setReconnectEnabled(true);
            targetPlayer.setReconnectConfig(3, 2_000L);
        }
    }

    /** 判断数据源是否是 RTSP 地址。 */
    private boolean isRtspSource(String dataSource) {
        return dataSource != null && dataSource.toLowerCase(Locale.US).startsWith("rtsp://");
    }

    /** 判断数据源是否是 HLS m3u8 地址。 */
    private boolean isHlsSource(String dataSource) {
        return dataSource != null && dataSource.toLowerCase(Locale.US).contains(".m3u8");
    }

    /** 解析数据源协议类型，供 Demo 日志和验证记录使用。 */
    private String resolveProtocolText(String dataSource) {
        if (isHlsSource(dataSource)) {
            return "HLS";
        }
        if (dataSource == null) {
            return "UNKNOWN";
        }
        String lowerSource = dataSource.toLowerCase(Locale.US);
        if (lowerSource.startsWith("rtsp://")) {
            return "RTSP";
        }
        if (lowerSource.startsWith("https://")) {
            return "HTTPS MP4";
        }
        if (lowerSource.startsWith("http://")) {
            return "HTTP MP4";
        }
        return "LOCAL";
    }

    /** 根据单选框解析当前渲染模式。 */
    private int resolveRenderMode() {
        int checkedId = renderModeGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.renderModeOpenGlButton) {
            return ECHPlayer.RENDER_MODE_OPENGL;
        }
        if (checkedId == R.id.renderModeNativeButton) {
            return ECHPlayer.RENDER_MODE_NATIVE_WINDOW;
        }
        return ECHPlayer.RENDER_MODE_AUTO;
    }

    /** 把渲染模式转换成日志文本。 */
    private String renderModeToText(int renderMode) {
        if (renderMode == ECHPlayer.RENDER_MODE_OPENGL) {
            return "OpenGL";
        }
        if (renderMode == ECHPlayer.RENDER_MODE_NATIVE_WINDOW) {
            return "NativeWindow";
        }
        return "AUTO";
    }

    /** 根据单选框解析当前解码模式。 */
    private int resolveDecodeMode() {
        int checkedId = decodeModeGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.decodeModeSoftwareButton) {
            return ECHPlayer.DECODE_MODE_SOFTWARE;
        }
        if (checkedId == R.id.decodeModeMediaCodecButton) {
            return ECHPlayer.DECODE_MODE_MEDIACODEC;
        }
        return ECHPlayer.DECODE_MODE_AUTO;
    }

    /** 把解码模式转换成日志文本。 */
    private String decodeModeToText(int decodeMode) {
        if (decodeMode == ECHPlayer.DECODE_MODE_SOFTWARE) {
            return "SOFTWARE";
        }
        if (decodeMode == ECHPlayer.DECODE_MODE_MEDIACODEC) {
            return "MEDIACODEC";
        }
        return "AUTO";
    }

    /** 把任务安全切回主线程执行。 */
    private void postToUi(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            uiHandler.post(action);
        }
    }

    /** 把播放器错误码转换成中文说明。 */
    private String describeErrorCode(int errorCode) {
        switch (errorCode) {
            case ECHPlayer.ERROR_OPEN_INPUT_FAILED:
                return "打开输入失败";
            case ECHPlayer.ERROR_STREAM_INFO_FAILED:
                return "读取流信息失败";
            case ECHPlayer.ERROR_NO_VIDEO_STREAM:
                return "没有找到视频流";
            case ECHPlayer.ERROR_DECODER_OPEN_FAILED:
                return "打开解码器失败";
            case ECHPlayer.ERROR_NETWORK_TIMEOUT:
                return "网络读取超时";
            case ECHPlayer.ERROR_RTSP_AUTH_FAILED:
                return "RTSP 鉴权失败";
            case ECHPlayer.ERROR_RENDER_SURFACE_INVALID:
                return "渲染 Surface 无效";
            case ECHPlayer.ERROR_RECORD_FAILED:
                return "录制失败";
            case ECHPlayer.ERROR_INVALID_STATE:
                return "播放器状态不允许当前操作";
            case ECHPlayer.ERROR_STREAM_NOT_SEEKABLE:
                return "当前媒体不支持 seek";
            default:
                return "未知错误";
        }
    }

    /** 把播放器信息码转换成中文说明。 */
    private String describeInfoCode(int infoCode) {
        switch (infoCode) {
            case ECHPlayer.INFO_PREPARE_STARTED:
                return "开始准备数据源";
            case ECHPlayer.INFO_PREPARED:
                return "数据源准备完成";
            case ECHPlayer.INFO_PLAY_STARTED:
                return "播放开始";
            case ECHPlayer.INFO_SEEK_COMPLETE:
                return "seek 完成";
            case ECHPlayer.INFO_RECORDING_START:
                return "录制开始";
            case ECHPlayer.INFO_RECORDING_END:
                return "录制结束";
            case ECHPlayer.INFO_PAUSED:
                return "播放暂停";
            case ECHPlayer.INFO_STOPPED:
                return "播放停止";
            case ECHPlayer.INFO_VIDEO_RENDERING_START:
                return "首帧视频开始渲染";
            case ECHPlayer.INFO_AUDIO_RENDERING_START:
                return "音频开始输出";
            case ECHPlayer.INFO_BUFFERING_START:
                return "缓冲开始";
            case ECHPlayer.INFO_BUFFERING_END:
                return "缓冲结束";
            case ECHPlayer.INFO_DECODE_MODE_CHANGED:
                return "当前解码方式变化";
            case ECHPlayer.INFO_MEDIACODEC_OPENED:
                return "MediaCodec 打开成功";
            case ECHPlayer.INFO_MEDIACODEC_FALLBACK:
                return "MediaCodec 回退软解";
            case ECHPlayer.INFO_MEDIACODEC_UNSUPPORTED:
                return "MediaCodec 不支持";
            case ECHPlayer.INFO_RECONNECTING:
                return "正在自动重连";
            case ECHPlayer.INFO_RECONNECTED:
                return "自动重连成功";
            case ECHPlayer.INFO_RECONNECT_FAILED:
                return "自动重连失败";
            default:
                return "普通播放信息";
        }
    }

    /** 解析当前应该使用的数据源。 */
    private String resolveDataSource() throws IOException {
        int mode = modeGroup.getCheckedRadioButtonId() == R.id.modeLocalButton
                ? MODE_LOCAL
                : MODE_NETWORK;
        if (mode == MODE_LOCAL) {
            String path = localPathInput.getText().toString().trim();
            if (!path.isEmpty()) {
                return path;
            }
            throw new IOException("本地文件路径为空，请选择文件或输入路径");
        } else {
            String input = dataSourceInput.getText().toString().trim();
            if (!input.isEmpty()) {
                return input;
            }
            throw new IOException("网络 URL 为空，请输入 rtsp://、http://、https:// 或 .m3u8 地址");
        }
    }

    /** 恢复上次选择的播放模式及对应输入内容。 */
    private void restorePlayMode() {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int mode = preferences.getInt(KEY_PLAY_MODE, MODE_NETWORK);
        if (mode == MODE_LOCAL) {
            modeLocalButton.setChecked(true);
        } else {
            modeRtspButton.setChecked(true);
        }

        String lastNetworkUrl = preferences.getString(KEY_LAST_DATA_SOURCE, DEFAULT_NETWORK_SOURCE);
        dataSourceInput.setText(lastNetworkUrl);
        dataSourceInput.setSelection(dataSourceInput.getText().length());

        int transport = preferences.getInt(KEY_RTSP_TRANSPORT, ECHPlayer.RTSP_TRANSPORT_TCP);
        if (transport == ECHPlayer.RTSP_TRANSPORT_UDP) {
            transportUdpButton.setChecked(true);
        } else {
            transportTcpButton.setChecked(true);
        }

        String localPath = preferences.getString(KEY_LOCAL_FILE_PATH, "");
        localPathInput.setText(localPath);
        localPathInput.setSelection(localPath.length());
    }

    /** 持久化当前播放模式及对应输入内容。 */
    private void persistPlayMode() {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int mode = modeGroup.getCheckedRadioButtonId() == R.id.modeLocalButton
                ? MODE_LOCAL
                : MODE_NETWORK;
        preferences.edit().putInt(KEY_PLAY_MODE, mode).apply();

        String networkInput = dataSourceInput.getText().toString().trim();
        if (!networkInput.isEmpty()) {
            preferences.edit().putString(KEY_LAST_DATA_SOURCE, networkInput).apply();
        }
        String localPath = localPathInput.getText().toString().trim();
        preferences.edit().putString(KEY_LOCAL_FILE_PATH, localPath).apply();
        preferences.edit().putInt(KEY_RTSP_TRANSPORT, resolveRtspTransport()).apply();
    }

    /** 根据当前播放模式切换输入区域的显示/隐藏。 */
    private void updateModeUi() {
        boolean isNetworkMode = modeGroup.getCheckedRadioButtonId() == R.id.modeRtspButton;
        rtspInputLayout.setVisibility(isNetworkMode ? View.VISIBLE : View.GONE);
        localInputLayout.setVisibility(isNetworkMode ? View.GONE : View.VISIBLE);
    }

    /** 打开系统文件选择器。 */
    private void pickLocalFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        startActivityForResult(intent, REQUEST_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                localPathInput.setText(uri.toString());
                persistPlayMode();
            }
        }
    }

    /** 解析当前 RTSP 传输方式。 */
    private int resolveRtspTransport() {
        return transportGroup.getCheckedRadioButtonId() == R.id.transportUdpButton
                ? ECHPlayer.RTSP_TRANSPORT_UDP
                : ECHPlayer.RTSP_TRANSPORT_TCP;
    }

    /** 追加一条日志到页面。 */
    private void appendLog(String message) {
        sampleText.append("\n" + message);
        trimLogTextIfNeeded();
    }

    /** 追加缓冲日志，相同百分比不重复刷屏。 */
    private void appendBufferingLog(int percent) {
        if (percent == lastBufferingPercent) {
            return;
        }
        lastBufferingPercent = percent;
        appendLog("缓冲进度: " + percent + "%");
    }

    /** 日志过长时只保留尾部，保证 Demo 长时间播放仍可操作。 */
    private void trimLogTextIfNeeded() {
        CharSequence currentText = sampleText.getText();
        if (currentText == null || currentText.length() <= MAX_LOG_TEXT_LENGTH) {
            return;
        }
        int start = currentText.length() - MAX_LOG_TEXT_LENGTH;
        sampleText.setText("...日志已截断，保留最近内容...\n" + currentText.subSequence(start, currentText.length()));
    }

    /** 启动进度刷新任务。 */
    private void startProgressUpdates() {
        uiHandler.removeCallbacks(progressUpdater);
        uiHandler.post(progressUpdater);
    }

    /** 停止进度刷新任务。 */
    private void stopProgressUpdates() {
        uiHandler.removeCallbacks(progressUpdater);
    }

    /** 刷新播放进度 UI。 */
    private void updateProgressUi() {
        if (player == null) {
            return;
        }

        long latestDurationMs = player.getDuration();
        if (latestDurationMs > 0) {
            durationMs = latestDurationMs;
            durationTimeText.setText(formatTime(durationMs));
        }

        long currentPositionMs = player.getCurrentPosition();
        if (currentPositionMs < 0) {
            return;
        }

        if (!userSeeking) {
            currentTimeText.setText(formatTime(currentPositionMs));

            if (durationMs > 0) {
                int progress = (int) Math.min(1000L, currentPositionMs * 1000L / durationMs);
                progressSeekBar.setProgress(progress);
            }
        }

        updateRecordButtonState();
        updateSeekableUi();
        updateDecodeStatusUi();
    }

    /** 根据当前媒体能力更新 seek 进度条状态。 */
    private void updateSeekableUi() {
        boolean seekable = player != null && durationMs > 0 && player.isSeekable();
        progressSeekBar.setEnabled(seekable);
        if (!seekable) {
            progressSeekBar.setProgress(0);
        }
    }

    /** 更新录制按钮的显示文本。 */
    private void updateRecordButtonState() {
        if (player != null && player.getRecordingState() == ECHPlayer.RecordingState.RECORDING) {
            recordButton.setText("停止录制");
        } else if (player != null && player.getRecordingState() == ECHPlayer.RecordingState.STOPPING) {
            recordButton.setText("停止中");
        } else if (player != null && player.getRecordingState() == ECHPlayer.RecordingState.FAILED) {
            recordButton.setText("录制失败");
        } else {
            recordButton.setText("开始录制");
        }
    }

    /** 更新当前解码状态展示。 */
    private void updateDecodeStatusUi() {
        if (decodeStatusText == null) {
            return;
        }

        if (player == null) {
            decodeStatusText.setText("当前解码：未开始");
            return;
        }

        String fallbackReason = player.getLastDecodeFallbackReason();
        ECHPlayer.PlaybackStats stats = player.getPlaybackStats();
        StringBuilder builder = new StringBuilder();
        builder.append("目标解码：");
        builder.append(decodeModeToText(player.getDecodeMode()));
        builder.append("  当前：");
        builder.append(player.getCurrentDecodeType());
        builder.append("  解码器：");
        builder.append(player.getCurrentDecoderName());
        if (fallbackReason != null && fallbackReason.length() > 0) {
            builder.append("  回退：");
            builder.append(fallbackReason);
        }
        builder.append("\n速度：");
        builder.append(formatByteSpeed(stats.readSpeedBytesPerSecond));
        builder.append("  已读：");
        builder.append(formatBytes(stats.readBytes));
        builder.append("  队列 V/A：");
        builder.append(stats.videoPacketQueueSize);
        builder.append("/");
        builder.append(stats.audioPacketQueueSize);
        builder.append("  缓冲：");
        builder.append(stats.bufferedPercent);
        builder.append("%");
        builder.append("  FPS：");
        builder.append(String.format(Locale.US, "%.1f", stats.decodeFps));
        builder.append("/");
        builder.append(String.format(Locale.US, "%.1f", stats.renderFps));
        builder.append("  重连：");
        builder.append(player.getReconnectCount());
        builder.append("\n帧数 D/R/Drop：");
        builder.append(stats.decodedFrameCount);
        builder.append("/");
        builder.append(stats.renderedFrameCount);
        builder.append("/");
        builder.append(stats.droppedFrameCount);
        builder.append("  prepare：");
        builder.append(formatCostMs(stats.prepareCostMs));
        builder.append("  首帧：");
        builder.append(formatCostMs(stats.firstFrameCostMs));
        decodeStatusText.setText(builder.toString());
    }

    /** 格式化媒体信息，方便 Demo 日志阅读。 */
    private String formatMediaInfo(ECHPlayer.MediaInfo info) {
        if (info == null) {
            return "media info: unavailable";
        }

        return "media info"
                + "\nformat: " + emptyToDash(info.format)
                + "\nduration: " + formatTime(info.durationMs)
                + "\nbitRate: " + formatBitRate(info.bitRate)
                + "\nvideo: #" + info.videoStreamIndex
                + " " + emptyToDash(info.videoCodec)
                + " " + info.videoWidth + "x" + info.videoHeight
                + "\naudio: #" + info.audioStreamIndex
                + " " + emptyToDash(info.audioCodec)
                + " " + info.audioSampleRate + "Hz"
                + " " + info.audioChannels + "ch";
    }

    /** 格式化轨道信息，方便 Demo 日志阅读。 */
    private String formatTrackInfo(List<ECHPlayer.TrackInfo> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return "track info: empty";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("track info: ");
        builder.append(tracks.size());
        builder.append(" track(s)");
        for (ECHPlayer.TrackInfo track : tracks) {
            builder.append("\n#");
            builder.append(track.streamIndex);
            builder.append(" ");
            builder.append(emptyToDash(track.type));
            builder.append(" ");
            builder.append(emptyToDash(track.codec));
            if (track.width > 0 && track.height > 0) {
                builder.append(" ");
                builder.append(track.width);
                builder.append("x");
                builder.append(track.height);
            }
            if (track.sampleRate > 0) {
                builder.append(" ");
                builder.append(track.sampleRate);
                builder.append("Hz");
            }
            if (track.channels > 0) {
                builder.append(" ");
                builder.append(track.channels);
                builder.append("ch");
            }
            if (track.language != null && track.language.length() > 0) {
                builder.append(" ");
                builder.append(track.language);
            }
        }
        return builder.toString();
    }

    /** 把最近一帧解码后的 RGBA 数据保存为 PNG。 */
    private void captureCurrentFrame() {
        if (player == null) {
            appendLog("capture failed: player is null");
            return;
        }

        File outputFile = buildOutputFile(SCREENSHOT_DIR, "png");
        try {
            ECHPlayer.CaptureResult result = player.captureCurrentFramePng(outputFile.getAbsolutePath());
            lastCapturePath = result.filePath;
            appendLog(
                    "capture success"
                            + "\nfile: " + result.filePath
                            + "\nsize: " + result.width + "x" + result.height
                            + "\ntimestampMs: " + result.timestampMs
                            + "\ndecodeSource: decoded frame, not SurfaceView"
            );
            updateDecodeStatusUi();
        } catch (IOException e) {
            appendLog("capture failed: " + e.getMessage());
        }
    }

    /** 切换录制状态。 */
    private void toggleRecording() {
        if (player == null) {
            appendLog("record failed: player is null");
            return;
        }

        if (player.isRecording()) {
            stopRecordingWithLog();
            updateRecordButtonState();
            return;
        }

        File outputFile = buildOutputFile(RECORD_DIR, "mkv");
        currentRecordingPath = outputFile.getAbsolutePath();
        appendLog(player.startRecording(currentRecordingPath));
        appendLog("record state: " + player.getRecordingState()
                + "\nfile: " + player.getLastRecordingPath()
                + "\nrecordSource: demux packet stream, not screen recording");
        if (!player.isRecording()) {
            currentRecordingPath = null;
        }
        updateRecordButtonState();
    }

    /** 停止录制并展示录制状态与文件路径。 */
    private void stopRecordingWithLog() {
        if (player == null || !player.isRecording()) {
            return;
        }

        appendLog(player.stopRecording());
        appendLog("record state: " + player.getRecordingState()
                + "\nlast file: " + player.getLastRecordingPath());
        currentRecordingPath = null;
    }

    /** 生成输出文件路径。 */
    private File buildOutputFile(String subDirName, String extension) {
        File baseDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (baseDir == null) {
            baseDir = getFilesDir();
        }

        File targetDir = new File(baseDir, subDirName);
        if (!targetDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            targetDir.mkdirs();
        }

        String timeText = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(targetDir, "echplay_" + timeText + "." + extension);
    }

    /** 把毫秒格式化成 mm:ss。 */
    private String formatTime(long timeMs) {
        long totalSeconds = Math.max(0, timeMs / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    /** 把字节数格式化成易读文本。 */
    private String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    /** 把字节每秒格式化成易读速度文本。 */
    private String formatByteSpeed(long bytesPerSecond) {
        return formatBytes(bytesPerSecond) + "/s";
    }

    /** 把码率格式化成易读文本。 */
    private String formatBitRate(long bitRate) {
        if (bitRate <= 0L) {
            return "unknown";
        }
        if (bitRate < 1000L * 1000L) {
            return String.format(Locale.US, "%.1f Kbps", bitRate / 1000.0);
        }
        return String.format(Locale.US, "%.2f Mbps", bitRate / 1000.0 / 1000.0);
    }

    /** 把耗时格式化成易读文本。 */
    private String formatCostMs(long costMs) {
        return costMs < 0L ? "--" : costMs + "ms";
    }

    /** 空字符串展示为短横线。 */
    private String emptyToDash(String value) {
        return value == null || value.length() == 0 ? "-" : value;
    }

    /** 把 asset 中的测试文件复制到缓存目录。 */
    private File copyAssetToCache(String assetName) throws IOException {
        File outFile = new File(getCacheDir(), assetName);

        try (
                InputStream inputStream = getAssets().open(assetName);
                FileOutputStream outputStream = new FileOutputStream(outFile, false)
        ) {
            byte[] buffer = new byte[8192];
            int length;

            while ((length = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, length);
            }

            outputStream.flush();
        }

        return outFile;
    }

    @Override
    protected void onDestroy() {
        stopProgressUpdates();

        if (player != null) {
            stopRecordingWithLog();
            player.stop();
            player.release();
            player = null;
        }

        super.onDestroy();
    }
}
