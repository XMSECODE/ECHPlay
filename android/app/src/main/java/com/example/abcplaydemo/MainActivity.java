package com.example.abcplaydemo;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.abcplaydemo.databinding.ActivityMainBinding;
import com.example.abcplaydemo.player.ECHPlayer;

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
        tv.setText(
                "ECHPlayer init success\n"
                        + "FFmpeg version: "
                        + player.getFFmpegVersion()
        );
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
