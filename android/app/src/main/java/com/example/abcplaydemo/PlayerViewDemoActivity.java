package com.example.abcplaydemo;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.abcplaydemo.player.ECHPlayer;
import com.example.abcplaydemo.player.ECHPlayerView;

/**
 * PlayerView 最小 Demo 页面，只通过 ECHPlayerView 完成播放控制。
 */
public class PlayerViewDemoActivity extends AppCompatActivity {

    /** 播放器组件。 */
    private ECHPlayerView playerView;
    /** 地址输入框。 */
    private EditText urlInput;
    /** RTSP 传输方式组选框。 */
    private RadioGroup transportGroup;
    /** 日志文本。 */
    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player_view_demo);

        playerView = findViewById(R.id.playerView);
        urlInput = findViewById(R.id.playerViewUrlInput);
        transportGroup = findViewById(R.id.playerViewTransportGroup);
        logText = findViewById(R.id.playerViewLogText);
        Button setPathButton = findViewById(R.id.playerViewSetPathButton);

        playerView.setEventListener(this::appendLog);
        setPathButton.setOnClickListener(view -> applyPlayerViewSource());
        transportGroup.setOnCheckedChangeListener((group, checkedId) -> applyRtspTransport());

        applyPlayerViewSource();
    }

    /** 应用当前输入框里的播放地址。 */
    private void applyPlayerViewSource() {
        String path = urlInput.getText().toString().trim();
        playerView.setVideoPath(path);
        applyRtspTransport();
        appendLog("setVideoPath: " + path);
    }

    /** 应用当前 RTSP 传输方式。 */
    private void applyRtspTransport() {
        int transport = transportGroup.getCheckedRadioButtonId() == R.id.playerViewTransportUdpButton
                ? ECHPlayer.RTSP_TRANSPORT_UDP
                : ECHPlayer.RTSP_TRANSPORT_TCP;
        playerView.setRtspTransport(transport);
    }

    /** 追加页面日志。 */
    private void appendLog(String message) {
        logText.append("\n" + message);
    }

    @Override
    protected void onDestroy() {
        playerView.release();
        super.onDestroy();
    }
}
