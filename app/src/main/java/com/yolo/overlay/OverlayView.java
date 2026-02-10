package com.yolo.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 修复闪烁问题的 OverlayView
 * 
 * 主要修复:
 * 1. 双缓冲机制 - 使用读写锁保护数据
 * 2. 深拷贝列表 - 避免直接引用替换
 * 3. 同步更新 - 确保原子性操作
 * 4. 减少重绘触发 - 优化 invalidate 调用
 */
public class OverlayView extends View {

    // === 数据双缓冲 ===
    private List<DetectionBox> frontBuffer = new ArrayList<>(); // 绘制用
    private List<DetectionBox> backBuffer = new ArrayList<>(); // 更新用
    private final ReentrantReadWriteLock bufferLock = new ReentrantReadWriteLock();

    private final Paint boxPaint;
    private final Paint textPaint;
    private final Paint textBgPaint;
    private final Paint rangePaint;
    private final Paint borderPaint;
    private final Paint safeZonePaint;

    private volatile int sourceWidth = 1080;
    private volatile int sourceHeight = 1920;
    private volatile int detectionSize = 416; // Default 416

    public void setDetectionSize(int size) {
        if (this.detectionSize != size) {
            this.detectionSize = size;
            post(this::recalculateLayout);
        }
    }

    // 缓存的布局参数
    private float scaleX = 1f;
    private float scaleY = 1f;
    private float viewCropLeft, viewCropTop, viewCropRight, viewCropBottom;
    private float safeX, safeY;
    private final float safeRadius = 30f;

    // FPS 计算
    private long lastFrameTime = 0;
    private float fps = 0f;
    private final Paint fpsPaint;
    private final Paint fpsBgPaint;

    // 防止过度重绘
    private volatile boolean isDrawing = false;
    private volatile boolean needsRedraw = false;

    private volatile boolean showBoxes = false; // Default show boxes off
    private volatile boolean showTouchZone = true; // Default to true, controlled by Service

    public OverlayView(Context context) {
        super(context);

        boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(2f);
        boxPaint.setAntiAlias(true);

        textPaint = new Paint();
        textPaint.setTextSize(28f);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setAntiAlias(true);

        textBgPaint = new Paint();
        textBgPaint.setStyle(Paint.Style.FILL);

        rangePaint = new Paint();
        rangePaint.setColor(Color.BLUE);
        rangePaint.setAlpha(40);
        rangePaint.setStyle(Paint.Style.FILL);

        borderPaint = new Paint();
        borderPaint.setColor(Color.BLUE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);
        borderPaint.setAlpha(200);
        borderPaint.setAntiAlias(true);

        safeZonePaint = new Paint();
        safeZonePaint.setColor(Color.GREEN);
        safeZonePaint.setStyle(Paint.Style.STROKE);
        safeZonePaint.setStrokeWidth(3f);
        safeZonePaint.setAlpha(128);

        fpsPaint = new Paint();
        fpsPaint.setTextSize(40f);
        fpsPaint.setColor(Color.GREEN);
        fpsPaint.setTypeface(Typeface.DEFAULT_BOLD);
        fpsPaint.setAntiAlias(true);

        fpsBgPaint = new Paint();
        fpsBgPaint.setColor(Color.parseColor("#80000000"));
        fpsBgPaint.setStyle(Paint.Style.FILL);

        // 启用硬件加速
        setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // 设置背景透明
        setBackgroundColor(Color.TRANSPARENT);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recalculateLayout();
    }

    private void recalculateLayout() {
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth == 0 || viewHeight == 0 || sourceWidth == 0 || sourceHeight == 0)
            return;

        // scaleX/scaleY will be calculated in onDraw to ensure sync with frame data
        float currentScaleX = (float) viewWidth / sourceWidth;
        float currentScaleY = (float) viewHeight / sourceHeight;

        float cropLeft = (sourceWidth - detectionSize) / 2f;
        float cropTop = (sourceHeight - detectionSize) / 2f;

        // These are only for the 'Range' box (Blue/Green boundary), so async update is
        // acceptable
        viewCropLeft = cropLeft * currentScaleX;
        viewCropTop = cropTop * currentScaleY;
        viewCropRight = (cropLeft + detectionSize) * currentScaleX;
        viewCropBottom = (cropTop + detectionSize) * currentScaleY;

        safeX = viewWidth * 0.75f;
        safeY = viewHeight * 0.40f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        isDrawing = true;

        if (getWidth() == 0 || getHeight() == 0) {
            isDrawing = false;
            return;
        }

        // 1. 绘制检测范围边框 - 受 showBoxes 控制 (只绘制边框，不绘制填充掩膜)
        if (showBoxes) {
            canvas.drawRect(viewCropLeft, viewCropTop, viewCropRight, viewCropBottom, borderPaint);

            // 2. 绘制自瞄安全触摸点 (仅当 showTouchZone 为 true 时)
            if (showTouchZone) {
                canvas.drawCircle(safeX, safeY, safeRadius, safeZonePaint);
                canvas.drawLine(safeX - safeRadius, safeY, safeX + safeRadius, safeY, safeZonePaint);
                canvas.drawLine(safeX, safeY - safeRadius, safeX, safeY + safeRadius, safeZonePaint);

                textPaint.setColor(Color.GREEN);
                textPaint.setTextSize(20f);
                canvas.drawText("Auto-Touch Zone", safeX - 60, safeY + 50, textPaint);
            }
        }

        // 3. 绘制 FPS
        String fpsText = String.format("FPS: %.1f", fps);
        float fpsWidth = fpsPaint.measureText(fpsText);
        canvas.drawRect(20, 20, 40 + fpsWidth, 80, fpsBgPaint);
        canvas.drawText(fpsText, 30, 70, fpsPaint);

        // 4. 绘制检测框 - 使用读锁保护
        // 4. 绘制检测框 - 使用读锁保护 (Recalculate scale strictly inside lock context)
        bufferLock.readLock().lock();
        try {
            if (sourceWidth == 0 || sourceHeight == 0)
                return;

            // Calculate scale synchronously with the data frame
            float currentScaleX = (float) getWidth() / sourceWidth;
            float currentScaleY = (float) getHeight() / sourceHeight;

            List<DetectionBox> drawBoxes = frontBuffer;

            textPaint.setColor(Color.GREEN); // Default text color
            textPaint.setTextSize(36f); // Larger text for summary

            int countClass0 = 0;
            int countClass1 = 0;
            float near0X = -1, near0Y = -1;
            float near1X = -1, near1Y = -1;
            float minD0 = Float.MAX_VALUE;
            float minD1 = Float.MAX_VALUE;

            float screenCx = getWidth() / 2f;
            float screenCy = getHeight() / 2f;

            for (DetectionBox box : drawBoxes) {
                float left = box.x * currentScaleX;
                float top = box.y * currentScaleY;
                float right = (box.x + box.w) * currentScaleX;
                float bottom = (box.y + box.h) * currentScaleY;
                float cx = (left + right) / 2f;
                float cy = (top + bottom) / 2f;

                int color = Color.rgb(box.r, box.g, box.b);
                boxPaint.setColor(color);

                // Draw Box Only (No Text)
                if (showBoxes) {
                    canvas.drawRect(left, top, right, bottom, boxPaint);
                }

                // Stats Logic
                float d = (cx - screenCx) * (cx - screenCx) + (cy - screenCy) * (cy - screenCy);

                if (box.classId == 0) {
                    countClass0++;
                    if (d < minD0) {
                        minD0 = d;
                        near0X = cx;
                        near0Y = cy;
                    }
                } else if (box.classId == 1) {
                    countClass1++;
                    if (d < minD1) {
                        minD1 = d;
                        near1X = cx;
                        near1Y = cy;
                    }
                }
            }

            // Draw Summary at Top Center
            String info0 = String.format("person: %d", countClass0);
            if (near0X != -1)
                info0 += String.format(" (%.0f, %.0f)", near0X, near0Y);

            String info1 = String.format("head: %d", countClass1);
            if (near1X != -1)
                info1 += String.format(" (%.0f, %.0f)", near1X, near1Y);

            String totalInfo = String.format("%s | %s", info0, info1);
            String info2 = String.format("检测框内有 %d 个人", countClass0);

            float textW = textPaint.measureText(totalInfo);
            float tx = (getWidth() - textW) / 2f;
            float ty = 80f; // Top margin

            // Background for text
            textBgPaint.setColor(Color.parseColor("#80000000"));
            canvas.drawRect(tx - 10, ty - 40, tx + textW + 10, ty + 10, textBgPaint);
            canvas.drawText(totalInfo, tx, ty, textPaint);

            // Draw second line
            float textW2 = textPaint.measureText(info2);
            float tx2 = (getWidth() - textW2) / 2f;
            float ty2 = ty + 50f;
            canvas.drawRect(tx2 - 10, ty2 - 40, tx2 + textW2 + 10, ty2 + 10, textBgPaint);
            canvas.drawText(info2, tx2, ty2, textPaint);

        } finally {
            bufferLock.readLock().unlock();
            isDrawing = false;

            // 如果绘制期间有新数据到达,立即触发下一帧
            if (needsRedraw) {
                needsRedraw = false;
                postInvalidate();
            }
        }
    }

    /**
     * 线程安全的数据更新 - 修复闪烁的关键
     */
    public void updateBoxes(List<DetectionBox> newBoxes, int srcWidth, int srcHeight) {
        // 检查源尺寸是否变化
        boolean sizeChanged = false;
        if (this.sourceWidth != srcWidth || this.sourceHeight != srcHeight) {
            this.sourceWidth = srcWidth;
            this.sourceHeight = srcHeight;
            sizeChanged = true;
        }

        // 使用写锁保护缓冲区交换
        bufferLock.writeLock().lock();
        try {
            // 清空 backBuffer 并深拷贝新数据
            backBuffer.clear();
            backBuffer.addAll(newBoxes);

            // 交换缓冲区(指针交换,非常快)
            List<DetectionBox> temp = frontBuffer;
            frontBuffer = backBuffer;
            backBuffer = temp;

        } finally {
            bufferLock.writeLock().unlock();
        }

        // 如果尺寸改变,重新计算布局
        if (sizeChanged) {
            post(this::recalculateLayout);
        }

        // FPS 计算
        long now = System.currentTimeMillis();
        if (lastFrameTime > 0) {
            long diff = now - lastFrameTime;
            if (diff > 0) {
                float currentFps = 1000f / diff;
                if (fps == 0)
                    fps = currentFps;
                else
                    fps = fps * 0.9f + currentFps * 0.1f;
            }
        }
        lastFrameTime = now;

        // 智能重绘:如果正在绘制,标记需要重绘;否则立即触发
        if (isDrawing) {
            needsRedraw = true;
        } else {
            postInvalidate();
        }
    }

    public void clearBoxes() {
        bufferLock.writeLock().lock();
        try {
            frontBuffer.clear();
            backBuffer.clear();
        } finally {
            bufferLock.writeLock().unlock();
        }
        postInvalidate();
    }

    public void setShowBoxes(boolean show) {
        this.showBoxes = show;
        postInvalidate();
    }

    public void setShowTouchZone(boolean show) {
        this.showTouchZone = show;
        postInvalidate();
    }

    /**
     * 更新检测结果 - 用于手机本地推理模式
     * 
     * @param boxes   检测框列表
     * @param screenW 截屏宽度 (用于坐标计算)
     * @param screenH 截屏高度 (用于坐标计算)
     */
    public void updateDetections(List<DetectionBox> boxes, int screenW, int screenH) {
        updateBoxes(boxes, screenW, screenH);
    }
}