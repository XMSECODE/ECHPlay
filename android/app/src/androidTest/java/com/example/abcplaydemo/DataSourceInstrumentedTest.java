package com.example.abcplaydemo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.echplay.player.ECHPlayer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 验证真实 FFmpeg 数据源读取、fd 生命周期和 Demo 的 content Uri 播放入口。 */
@RunWith(AndroidJUnit4.class)
public class DataSourceInstrumentedTest {
    /** 被测 Demo 的上下文，用于访问 Debug 文件提供方。 */
    private Context context;
    /** 当前用例的本地视频文件，文件名包含需要 Uri 转义的字符。 */
    private File mediaFile;
    /** 当前用例的播放器，测试后统一释放。 */
    private ECHPlayer player;
    /** 测试前的 Demo 偏好，运行后恢复，避免覆盖用户的播放地址和模式。 */
    private Map<String, ?> originalPreferences;

    /** 准备独立的测试视频，避免依赖用户选择的文件或网络源。 */
    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalPreferences = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
                .getAll();
        File directory = new File(context.getCacheDir(), "data-source-regression");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        mediaFile = File.createTempFile("probe #", ".mp4", directory);
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream input = testContext.getAssets().open("data_source_sample.mp4");
             FileOutputStream output = new FileOutputStream(mediaFile)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        player = new ECHPlayer();
        player.setDecodeMode(ECHPlayer.DECODE_MODE_SOFTWARE);
    }

    /** 释放播放器和当前用例的文件，避免用例之间共享打开的 fd。 */
    @After
    public void tearDown() {
        if (player != null) {
            player.release();
        }
        if (originalPreferences != null) {
            restorePreferences();
        }
        if (mediaFile != null && mediaFile.exists()) {
            assertTrue("cannot remove test media", mediaFile.delete());
        }
    }

    /** 恢复 SharedPreferences 支持的各类值，隔离自动播放对 Demo 设置的影响。 */
    @SuppressWarnings("unchecked")
    private void restorePreferences() {
        SharedPreferences.Editor editor = context
                .getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().clear();
        for (Map.Entry<String, ?> entry : originalPreferences.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) {
                editor.putString(key, (String) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Float) {
                editor.putFloat(key, (Float) value);
            } else if (value instanceof Set) {
                editor.putStringSet(key, (Set<String>) value);
            }
        }
        assertTrue("cannot restore demo settings", editor.commit());
    }

    /** 普通 fd 的调用方提前关闭文件后，播放器仍应读取出媒体信息。 */
    @Test
    public void fileDescriptorSurvivesCallerClose() throws Exception {
        try (FileInputStream input = new FileInputStream(mediaFile)) {
            player.setDataSource(input.getFD());
        }
        assertPreparedVideo();
    }

    /** ParcelFileDescriptor 的调用方提前关闭后，也不能影响播放器副本。 */
    @Test
    public void parcelDescriptorSurvivesCallerClose() throws Exception {
        try (ParcelFileDescriptor original = openMediaDescriptor()) {
            player.setDataSource(original);
            assertNotEquals("/proc/self/fd/" + original.getFd(), player.getDataSource());
        }
        assertPreparedVideo();
    }

    /** reset/release 只关闭播放器的副本，调用方的原始 fd 仍由调用方拥有。 */
    @Test
    public void resetAndReleaseCloseOnlyOwnedCopies() throws Exception {
        try (ParcelFileDescriptor original = openMediaDescriptor()) {
            player.setDataSource(original);
            String firstPath = player.getDataSource();
            player.reset();
            assertNotPointingAtMedia(firstPath);
            assertTrue(original.getFileDescriptor().valid());
            player.setDataSource(original);
            String secondPath = player.getDataSource();
            player.release();
            player.release();
            assertNotPointingAtMedia(secondPath);
            assertTrue(original.getFileDescriptor().valid());
        }
    }

    /** content Uri 经 ContentResolver 打开后可准备，切换地址时释放旧 fd。 */
    @Test
    public void contentUriPreparesAndReplacementClosesDescriptor() throws Exception {
        player.setDataSource(context, mediaUri());
        String ownedPath = player.getDataSource();
        assertPreparedVideo();
        player.stop();
        player.setDataSource(mediaFile.getAbsolutePath());
        assertNotPointingAtMedia(ownedPath);
        assertPreparedVideo();
    }

    /** 不允许设置数据源时先拒绝操作，原有媒体和调用方 fd 均保持可用。 */
    @Test
    public void invalidStatePreservesOriginalSourceAndCallerDescriptor() throws Exception {
        player.setDataSource(context, mediaUri());
        String originalSource = player.getDataSource();
        try (ParcelFileDescriptor original = openMediaDescriptor()) {
            try {
                player.setDataSource(original);
                fail("INITIALIZED must reject another source");
            } catch (IllegalStateException expected) {
                assertTrue(original.getFileDescriptor().valid());
                assertEquals(originalSource, player.getDataSource());
            }
        }
        assertPreparedVideo();
    }

    /** 带空格和井号的 file Uri 应还原为完整文件路径。 */
    @Test
    public void fileUriPreservesEscapedPath() {
        player.setDataSource(context, Uri.fromFile(mediaFile));
        assertEquals(mediaFile.getAbsolutePath(), player.getDataSource());
        assertPreparedVideo();
    }

    /** 从 Demo 的真实入口自动播放 content Uri，验证不是传给 FFmpeg 的原始字符串。 */
    @Test
    public void demoPlaysContentUri() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(playIntent(mediaUri()))) {
            String log = waitForLog(scenario, "play started");
            assertTrue(log, log.contains("play started"));
            assertFalse(log, log.contains("prepare failed"));
        }
    }

    /** 文件授权失效或文件不存在时 Demo 显示错误，不因异常退出。 */
    @Test
    public void demoReportsUnreadableContentUri() throws Exception {
        Uri missing = mediaUri().buildUpon().appendPath("missing.mp4").build();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(playIntent(missing))) {
            String log = waitForLog(scenario, "error:");
            assertTrue(log, log.contains("没有找到可播放的数据源"));
            assertTrue(log, log.contains("error:"));
        }
    }

    /** 打开调用方持有的测试 fd。 */
    private ParcelFileDescriptor openMediaDescriptor() throws Exception {
        return ParcelFileDescriptor.open(mediaFile, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 获取 Debug 提供方返回的真实 content Uri。 */
    private Uri mediaUri() {
        return FileProvider.getUriForFile(context,
                context.getPackageName() + ".regression.files", mediaFile);
    }

    /** 通过媒体尺寸和时长确认 FFmpeg 真正解析了测试文件。 */
    private void assertPreparedVideo() {
        String result = player.prepare();
        assertEquals(result, ECHPlayer.State.PREPARED, player.getState());
        assertEquals(160, player.getVideoWidth());
        assertEquals(96, player.getVideoHeight());
        assertTrue(player.getDuration() >= 2000L);
    }

    /** 验证旧 fd 已关闭或被系统复用，不能再指向本次媒体文件。 */
    private void assertNotPointingAtMedia(String descriptorPath) throws Exception {
        try {
            assertNotEquals(mediaFile.getCanonicalPath(), Os.readlink(descriptorPath));
        } catch (ErrnoException expected) {
            assertEquals(android.system.OsConstants.ENOENT, expected.errno);
        }
    }

    /** 创建自动播放请求，固定软件解码以排除设备硬解能力差异。 */
    private Intent playIntent(Uri uri) {
        return new Intent(context, MainActivity.class)
                .putExtra("echplay_auto_play", true)
                .putExtra("echplay_source_mode", "local")
                .putExtra("echplay_source", uri.toString())
                .putExtra("echplay_decode_mode", "software");
    }

    /** 等待主页面日志出现结果，超时后返回最后日志便于定位失败。 */
    private String waitForLog(ActivityScenario<MainActivity> scenario, String expected) {
        AtomicReference<String> log = new AtomicReference<>("");
        long deadline = SystemClock.elapsedRealtime() + 10000L;
        do {
            scenario.onActivity(activity -> {
                TextView output = activity.findViewById(R.id.sample_text);
                log.set(output.getText().toString());
            });
            if (log.get().contains(expected)) {
                return log.get();
            }
            SystemClock.sleep(100L);
        } while (SystemClock.elapsedRealtime() < deadline);
        return log.get();
    }
}
