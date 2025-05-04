package org.openbot.tracking;

import android.graphics.RectF;

public class JavaObject {
    public RectF rect;
    public int label;
    public float prob;

    public JavaObject(RectF rect, int label, float prob) {
        this.rect = rect;
        this.label = label;
        this.prob = prob;
    }
}