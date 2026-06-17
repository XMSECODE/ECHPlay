package com.echplay.player;

import android.graphics.Rect;

/**
 * 字幕文本快照，结构对齐 ijkplayer 的 TimedText 概念。
 */
public final class ECHTimedText {
    /** 字幕显示区域，暂时可为空。 */
    private final Rect bounds;
    /** 字幕文本内容。 */
    private final String text;

    /** 创建字幕文本快照。 */
    public ECHTimedText(Rect bounds, String text) {
        this.bounds = bounds;
        this.text = text == null ? "" : text;
    }

    /** 返回字幕显示区域。 */
    public Rect getBounds() {
        return bounds;
    }

    /** 返回字幕文本内容。 */
    public String getText() {
        return text;
    }
}
