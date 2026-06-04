package com.example.abcplaydemo;

import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.abcplaydemo.player.ECHPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "player_prefs";
    private static final String KEY_LAST_DATA_SOURCE = "last_data_source";
    private static final String DEFAULT_RTSP_SOURCE = "rtsp://192.168.1.1:554/live";

    private SurfaceView surfaceView;
    private EditText dataSourceInput;
    private Button openButton;
    private Button pauseButton;
    private Button resumeButton;
    private Button stopButton;
    private SeekBar progressSeekBar;
    private TextView currentTimeText;
    private TextView durationTimeText;
    private TextView sampleText;
    private ECHPlayer player;
    private boolean demoStarted = false;
    private boolean userSeeking = false;
    private long durationMs = 0;
    private int surfaceWidth = 0;
    private int surfaceHeight = 0;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
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
        pauseButton = findViewById(R.id.pauseButton);
        resumeButton = findViewById(R.id.resumeButton);
        stopButton = findViewById(R.id.stopButton);
        progressSeekBar = findViewById(R.id.progressSeekBar);
        currentTimeText = findViewById(R.id.currentTimeText);
        durationTimeText = findViewById(R.id.durationTimeText);
        sampleText = findViewById(R.id.sample_text);

        restoreLastDataSource();
        sampleText.setText("等待 Surface 创建...");
        currentTimeText.setText(formatTime(0));
        durationTimeText.setText(formatTime(0));

        openButton.setOnClickListener(v -> tryStartPlayback());

        pauseButton.setOnClickListener(v -> {
            if (player != null) {
                player.pause();
                appendLog("pause");
            }
        });

        resumeButton.setOnClickListener(v -> {
            if (player != null) {
                player.resume();
                appendLog("resume");
            }
        });

        stopButton.setOnClickListener(v -> {
            if (player != null) {
                player.stop();
                appendLog("stop");
            }
        });

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
                    long targetPositionMs = durationMs * seekBar.getProgress() / 1000L;
                    String seekInfo = player.seekToMs(targetPositionMs);
                    appendLog(seekInfo);
                    updateProgressUi();
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
                    player.stop();
                    player.setSurface(null);
                }
            }
        });
    }

    private void tryStartPlayback() {
        Surface surface = surfaceView.getHolder().getSurface();
        if (surface == null || !surface.isValid()) {
            sampleText.setText("Surface 还没准备好");
            return;
        }

        persistLastDataSource();
        runPlayDemo(surface, surfaceWidth, surfaceHeight);
    }

    private void runPlayDemo(Surface surface, int surfaceWidth, int surfaceHeight) {
        stopProgressUpdates();

        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }

        player = new ECHPlayer();
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

        try {
            String dataSource = resolveDataSource();

            player.setSurface(surface);
            player.setDataSource(dataSource);

            String prepareInfo = player.prepare();
            text.append(prepareInfo);
            text.append("\n\n");
            durationMs = Math.max(0, player.getDurationMs());
            durationTimeText.setText(formatTime(durationMs));

            String playInfo = player.play();
            text.append(playInfo);
            startProgressUpdates();

        } catch (IOException e) {
            text.append("没有找到可播放的数据源。\n\n");
            text.append("可以输入 rtsp:// 地址，或者保留空白回退到：\n");
            text.append("app/src/main/assets/test.mp4\n\n");
            text.append("error: ");
            text.append(e.getMessage());
        }

        sampleText.setText(text.toString());
    }

    private String resolveDataSource() throws IOException {
        String input = dataSourceInput.getText().toString().trim();
        if (!input.isEmpty()) {
            return input;
        }

        File videoFile = copyAssetToCache("test.mp4");
        return videoFile.getAbsolutePath();
    }

    private void restoreLastDataSource() {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lastDataSource = preferences.getString(KEY_LAST_DATA_SOURCE, DEFAULT_RTSP_SOURCE);
        dataSourceInput.setText(lastDataSource);
        dataSourceInput.setSelection(dataSourceInput.getText().length());
    }

    private void persistLastDataSource() {
        String input = dataSourceInput.getText().toString().trim();
        if (input.isEmpty()) {
            return;
        }

        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        preferences.edit().putString(KEY_LAST_DATA_SOURCE, input).apply();
    }

    private void appendLog(String message) {
        sampleText.append("\n" + message);
    }

    private void startProgressUpdates() {
        uiHandler.removeCallbacks(progressUpdater);
        uiHandler.post(progressUpdater);
    }

    private void stopProgressUpdates() {
        uiHandler.removeCallbacks(progressUpdater);
    }

    private void updateProgressUi() {
        if (player == null) {
            return;
        }

        long latestDurationMs = player.getDurationMs();
        if (latestDurationMs > 0) {
            durationMs = latestDurationMs;
            durationTimeText.setText(formatTime(durationMs));
        }

        long currentPositionMs = player.getCurrentPositionMs();
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
    }

    private String formatTime(long timeMs) {
        long totalSeconds = Math.max(0, timeMs / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

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
            player.stop();
            player.release();
            player = null;
        }

        super.onDestroy();
    }
}
