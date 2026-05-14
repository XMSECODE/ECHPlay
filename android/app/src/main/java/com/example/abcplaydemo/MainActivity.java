package com.example.abcplaydemo;

import android.os.Bundle;
import android.widget.TextView;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        player = new ECHPlayer();

        TextView tv = binding.sampleText;

        StringBuilder text = new StringBuilder();
        text.append("ECHPlayer decode demo\n");
        text.append("FFmpeg version: ");
        text.append(player.getFFmpegVersion());
        text.append("\n\n");

        try {
            File videoFile = copyAssetToCache("test.mp4");

            player.setDataSource(videoFile.getAbsolutePath());

            String prepareInfo = player.prepare();
            text.append(prepareInfo);
            text.append("\n\n");

            String decodeInfo = player.decodeFirstVideoFrame();
            text.append(decodeInfo);

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
