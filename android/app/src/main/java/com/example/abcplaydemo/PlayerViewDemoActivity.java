package com.example.abcplaydemo;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.echplay.player.ECHPlayer;
import com.echplay.player.ECHPlayerView;

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
    /** 渲染模式组选框。 */
    private RadioGroup renderModeGroup;
    /** 解码模式组选框。 */
    private RadioGroup decodeModeGroup;
    /** 画面比例模式组选框。 */
    private RadioGroup scaleTypeGroup;
    /** 日志文本。 */
    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player_view_demo);

        playerView = findViewById(R.id.playerView);
        urlInput = findViewById(R.id.playerViewUrlInput);
        transportGroup = findViewById(R.id.playerViewTransportGroup);
        renderModeGroup = findViewById(R.id.playerViewRenderModeGroup);
        decodeModeGroup = findViewById(R.id.playerViewDecodeModeGroup);
        scaleTypeGroup = findViewById(R.id.playerViewScaleGroup);
        logText = findViewById(R.id.playerViewLogText);
        Button setPathButton = findViewById(R.id.playerViewSetPathButton);

        playerView.setEventListener(this::appendLog);
        setPathButton.setOnClickListener(view -> applyPlayerViewSource());
        transportGroup.setOnCheckedChangeListener((group, checkedId) -> applyRtspTransport());
        renderModeGroup.setOnCheckedChangeListener((group, checkedId) -> applyRenderMode());
        decodeModeGroup.setOnCheckedChangeListener((group, checkedId) -> applyDecodeMode());
        scaleTypeGroup.setOnCheckedChangeListener((group, checkedId) -> applyScaleType());

        applyRenderMode();
        applyDecodeMode();
        applyScaleType();
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

    /** 应用当前渲染模式。 */
    private void applyRenderMode() {
        int checkedId = renderModeGroup.getCheckedRadioButtonId();
        int renderMode = ECHPlayer.RENDER_MODE_AUTO;
        String renderModeText = "AUTO";

        if (checkedId == R.id.playerViewRenderOpenGlButton) {
            renderMode = ECHPlayer.RENDER_MODE_OPENGL;
            renderModeText = "OpenGL";
        } else if (checkedId == R.id.playerViewRenderNativeButton) {
            renderMode = ECHPlayer.RENDER_MODE_NATIVE_WINDOW;
            renderModeText = "NativeWindow";
        }

        playerView.setRenderMode(renderMode);
        appendLog("renderMode: " + renderModeText);
    }

    /** 应用当前解码模式。 */
    private void applyDecodeMode() {
        int checkedId = decodeModeGroup.getCheckedRadioButtonId();
        int decodeMode = ECHPlayer.DECODE_MODE_AUTO;
        String decodeModeText = "AUTO";

        if (checkedId == R.id.playerViewDecodeSoftwareButton) {
            decodeMode = ECHPlayer.DECODE_MODE_SOFTWARE;
            decodeModeText = "software";
        } else if (checkedId == R.id.playerViewDecodeMediaCodecButton) {
            decodeMode = ECHPlayer.DECODE_MODE_MEDIACODEC;
            decodeModeText = "mediacodec";
        }

        playerView.setDecodeMode(decodeMode);
        appendLog("decodeMode: " + decodeModeText);
    }

    /** 应用当前画面比例模式。 */
    private void applyScaleType() {
        int checkedId = scaleTypeGroup.getCheckedRadioButtonId();
        int scaleType = ECHPlayerView.SCALE_TYPE_FIT_CENTER;
        String scaleText = "fitCenter";

        if (checkedId == R.id.playerViewScaleCropButton) {
            scaleType = ECHPlayerView.SCALE_TYPE_CENTER_CROP;
            scaleText = "centerCrop";
        } else if (checkedId == R.id.playerViewScaleFillButton) {
            scaleType = ECHPlayerView.SCALE_TYPE_FILL;
            scaleText = "fill";
        } else if (checkedId == R.id.playerViewScaleOriginalButton) {
            scaleType = ECHPlayerView.SCALE_TYPE_ORIGINAL;
            scaleText = "original";
        }

        playerView.setScaleType(scaleType);
        appendLog("scaleType: " + scaleText);
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
