package com.yolo.overlay;

/**
 * 优化版检测框 - 缓存绘制坐标
 */
public class DetectionBox {
    public int x, y, w, h;
    public int r, g, b;
    public String label;
    public float confidence;
    public String displayString;

    // 缓存的绘制坐标(避免每帧重复计算)
    public float cachedLeft, cachedTop, cachedRight, cachedBottom;
    public float cachedTextWidth;
    public int cachedColor;
    public boolean cacheValid = false;

    public int classId;

    public DetectionBox(int x, int y, int w, int h, int r, int g, int b,
            String label, float confidence, int classId) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.r = r;
        this.g = g;
        this.b = b;
        this.label = label;
        this.confidence = confidence;
        this.classId = classId;
        this.displayString = label + " " + String.format("%.0f%%", confidence * 100);
        this.cachedColor = android.graphics.Color.rgb(r, g, b);
    }

    /**
     * 预计算绘制坐标
     */
    public void precalculateCoordinates(float scaleX, float scaleY, android.graphics.Paint textPaint) {
        cachedLeft = x * scaleX;
        cachedTop = y * scaleY;
        cachedRight = (x + w) * scaleX;
        cachedBottom = (y + h) * scaleY;
        cachedTextWidth = textPaint.measureText(displayString);
        cacheValid = true;
    }
}