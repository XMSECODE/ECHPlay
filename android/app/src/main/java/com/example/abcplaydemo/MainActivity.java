package com.example.abcplaydemo;

import android.graphics.PixelFormat;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.abcplaydemo.databinding.ActivityMainBinding;
import com.example.abcplaydemo.player.ECHPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ECHPlayer player;
    private boolean demoStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.sampleText.setText("等待 Surface 创建...");

        SurfaceHolder holder = binding.surfaceView.getHolder();
        holder.setFormat(PixelFormat.RGBA_8888);

        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                binding.sampleText.setText("Surface created，等待 surfaceChanged...");
            }

            @Override
            public void surfaceChanged(
                    @NonNull SurfaceHolder holder,
                    int format,
                    int width,
                    int height) {

                if (demoStarted) {
                    return;
                }

                Surface surface = holder.getSurface();
                if (surface == null || !surface.isValid()) {
                    binding.sampleText.setText("Surface 无效");
                    return;
                }

                demoStarted = true;

                binding.surfaceView.postDelayed(() -> {
                    runPlayDemo(holder.getSurface(), width, height);
                }, 500);
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

    private void runPlayDemo(Surface surface, int surfaceWidth, int surfaceHeight) {
        player = new ECHPlayer();

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
            File videoFile = copyAssetToCache("test.mp4");

            player.setSurface(surface);
            player.setDataSource(videoFile.getAbsolutePath());

            String prepareInfo = player.prepare();
            text.append(prepareInfo);
            text.append("\n\n");

            String playInfo = player.play();
            text.append(playInfo);

        } catch (IOException e) {
            text.append("没有找到测试视频。\n\n");
            text.append("请放一个 mp4 文件到：\n");
            text.append("app/src/main/assets/test.mp4\n\n");
            text.append("然后重新运行 App。\n\n");
            text.append("error: ");
            text.append(e.getMessage());
        }

        binding.sampleText.setText(text.toString());
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
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }

        super.onDestroy();
    }
}
