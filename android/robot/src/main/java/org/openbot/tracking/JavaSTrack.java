package org.openbot.tracking;

import android.graphics.RectF;

public class JavaSTrack {
    public int trackId;
    public int state; // 0: New, 1: Tracked, 2: Lost, 3: Removed
    public float score;
    public RectF rect; // 使用 tlbr 转换回来的 RectF

    public JavaSTrack(int trackId, int state, float score, RectF rect) {
        this.trackId = trackId;
        this.state = state;
        this.score = score;
        this.rect = rect;
    }

    @Override
    public String toString() {
        return "JavaSTrack{" +
                "trackId=" + trackId +
                ", state=" + state +
                ", score=" + score +
                ", rect=" + rect +
                '}';
    }
}