package com.yolo.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕截取服务
 * 
 * 使用 MediaProjection API 截取屏幕画面，
 * 然后使用 NCNN 进行本地 YOLO 推理
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "screen_capture_channel";
    private static final int NOTIFICATION_ID = 2;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Handler handler;
    private HandlerThread handlerThread;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    // 推理目标尺寸 (模型规格: [1,3,416,416])
    private int targetSize = 416;

    // 回调接口
    public interface OnDetectionListener {
        void onDetection(List<DetectionBox> boxes, int screenW, int screenH);
    }

    private static OnDetectionListener detectionListener;
    private static ScreenCaptureService instance;

    // 推理状态
    private volatile boolean isInferencing = false;
    private volatile boolean isRunning = false;

    // 模型是否已初始化
    private boolean modelInitialized = false;

    public static void setDetectionListener(OnDetectionListener listener) {
        detectionListener = listener;
    }

    public static ScreenCaptureService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // 创建后台线程
        handlerThread = new HandlerThread("ScreenCapture");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        // 获取屏幕参数
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        // 创建通知渠道
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification());

        // 获取 MediaProjection 权限数据
        int resultCode = intent.getIntExtra("result_code", -1);
        Intent data = intent.getParcelableExtra("data");

        if (resultCode != -1 && data != null) {
            startCapture(resultCode, data);
        }

        return START_STICKY;
    }

    public void startCapture(int resultCode, Intent data) {
        if (isRunning) {
            Log.w(TAG, "Capture already running");
            return;
        }

        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mgr.getMediaProjection(resultCode, data);

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection");
            stopSelf();
            return;
        }

        // 初始化 YOLO 模型
        if (!modelInitialized) {
            try {
                boolean useGpu = YoloNcnn.isGpuSupported();
                Log.i(TAG, "GPU support: " + useGpu);

                modelInitialized = YoloNcnn.init(
                        getAssets(),
                        "yolov8n.param",
                        "yolov8n.bin",
                        useGpu);

                if (!modelInitialized) {
                    Log.e(TAG, "Failed to initialize YOLO model");
                }
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "NCNN library not found: " + e.getMessage());
                modelInitialized = false;
            }
        }

        // 使用较小的分辨率以提高性能
        int captureWidth = screenWidth / 2;
        int captureHeight = screenHeight / 2;

        imageReader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "YoloCapture",
                captureWidth, captureHeight, screenDensity / 2,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler);

        isRunning = true;

        // 设置帧回调
        imageReader.setOnImageAvailableListener(reader -> {
            if (!isRunning || isInferencing) {
                // 跳过帧以避免积压
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    image.close();
                }
                return;
            }

            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    isInferencing = true;
                    Bitmap bitmap = imageToBitmap(image);
                    image.close();
                    image = null;

                    if (bitmap != null && modelInitialized) {
                        // 运行推理
                        float[] results = YoloNcnn.detect(bitmap, targetSize);
                        bitmap.recycle();

                        if (results != null && results.length > 0) {
                            List<DetectionBox> boxes = parseResults(results, captureWidth, captureHeight);

                            if (detectionListener != null) {
                                detectionListener.onDetection(boxes, screenWidth, screenHeight);
                            }
                        }
                    }

                    isInferencing = false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing frame: " + e.getMessage());
                isInferencing = false;
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }, handler);

        Log.i(TAG, "Screen capture started");
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        Bitmap bitmap = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(),
                Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);

        // 裁剪到正确尺寸
        if (bitmap.getWidth() != image.getWidth()) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
            bitmap.recycle();
            return cropped;
        }

        return bitmap;
    }

    private List<DetectionBox> parseResults(float[] results, int captureW, int captureH) {
        List<DetectionBox> boxes = new ArrayList<>();

        int count = (int) results[0];
        int stride = 6; // x, y, w, h, conf, classId

        // 计算缩放比例以映射回原始屏幕尺寸
        float scaleX = (float) screenWidth / captureW;
        float scaleY = (float) screenHeight / captureH;

        for (int i = 0; i < count && (1 + i * stride + 5) < results.length; i++) {
            int offset = 1 + i * stride;

            float x = results[offset] * scaleX;
            float y = results[offset + 1] * scaleY;
            float w = results[offset + 2] * scaleX;
            float h = results[offset + 3] * scaleY;
            float conf = results[offset + 4];
            int classId = (int) results[offset + 5];

            // 根据 classId 分配颜色
            int r, g, b;
            if (classId == 0) {
                r = 0;
                g = 255;
                b = 0; // 绿色
            } else if (classId == 1) {
                r = 255;
                g = 0;
                b = 0; // 红色
            } else {
                r = 0;
                g = 0;
                b = 255; // 蓝色
            }

            String label = "Class " + classId;

            boxes.add(new DetectionBox(
                    (int) x, (int) y, (int) w, (int) h,
                    r, g, b, label, conf, classId));
        }

        return boxes;
    }

    public void stopCapture() {
        isRunning = false;

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        Log.i(TAG, "Screen capture stopped");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        stopCapture();

        if (modelInitialized) {
            YoloNcnn.release();
            modelInitialized = false;
        }

        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }

        instance = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "屏幕录制服务",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("用于 YOLO 手机端推理的屏幕录制");

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("YOLO 手机推理")
                .setContentText("正在进行本地推理...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }
}
