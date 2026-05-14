package com.example.abcplaydemo;

import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.TextView;

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

        binding.surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                if (!demoStarted) {
                    demoStarted = true;
                    runRenderDemo(holder.getSurface());
                }
            }

            @Override
            public void surfaceChanged(
                    @NonNull SurfaceHolder holder,
                    int format,
                    int width,
                    int height) {
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            }
        });
    }

    private void runRenderDemo(Surface surface) {
        player = new ECHPlayer();

        TextView tv = binding.sampleText;

        StringBuilder text = new StringBuilder();
        text.append("ECHPlayer render demo\n");
        text.append("FFmpeg version: ");
        text.append(player.getFFmpegVersion());
        text.append("\n\n");

        try {
            File videoFile = copyAssetToCache("test.mp4");

            player.setDataSource(videoFile.getAbsolutePath());

            String prepareInfo = player.prepare();
            text.append(prepareInfo);
            text.append("\n\n");

            String renderInfo = player.renderFirstVideoFrame(surface);
            text.append(renderInfo);

        } catch (IOException e) {
            text.append("没有找到测试视频。\n\n");
            text.append("请放一个 mp4 文件到：\n");
            text.append("app/src/main/assets/test.mp4\n\n");
            text.append("然后重新运行 App。\n\n");
            text.append("error: ");
            text.append(e.getMessage());
        }

        tv.setText(text.toString());
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
            player.release();
            player = null;
        }

        super.onDestroy();
    }
}
