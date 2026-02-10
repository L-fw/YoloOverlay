package com.yolo.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.List;

/**
 * 仅检测服务 (不使用无障碍功能)
 * 这是一个标准的 Android Service，用于显示悬浮窗
 */
public class DetectionService extends Service implements AutoAimController {

    private static final String CHANNEL_ID = "yolo_detection_channel";
    private static final int NOTIFICATION_ID = 2;

    private WindowManager windowManager;
    private OverlayView overlayView;
    private SocketReceiver socketReceiver;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        // 获取屏幕尺寸
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        // OverlayView 会处理宽高，不需要存

        // 创建 OverlayView
        overlayView = new OverlayView(this);

        // 设置悬浮窗参数
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 0;
        params.y = 0;

        // 添加悬浮窗
        windowManager.addView(overlayView, params);

        // Detection Only does not need the touch zone
        overlayView.setShowTouchZone(false);

        // 启动 Socket 接收线程
        // 传入 this 作为 AutoAimController (空实现)
        socketReceiver = new SocketReceiver(OverlayService.PORT, overlayView, this, this);
        socketReceiver.start();
    }

    private ControlReceiver controlReceiver;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (controlReceiver == null) {
            controlReceiver = new ControlReceiver();
            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction(OverlayService.ACTION_TOGGLE_BOXES);
            filter.addAction(OverlayService.ACTION_SWITCH_MODEL);
            filter.addAction(OverlayService.ACTION_SWITCH_BACKEND);
            filter.addAction(OverlayService.ACTION_UPDATE_DETECTIONS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(controlReceiver, filter);
            }
        }

        // 发送服务启动广播
        Intent broadcastIntent = new Intent(OverlayService.ACTION_SERVICE_STATE_CHANGED);
        broadcastIntent.putExtra("running", true);
        broadcastIntent.putExtra("service_type", 2); // 2 = DetectionService
        broadcastIntent.setPackage(getPackageName());
        sendBroadcast(broadcastIntent);

        // 处理 initial show_boxes extra, if present
        if (intent != null && intent.hasExtra("show_boxes")) {
            boolean show = intent.getBooleanExtra("show_boxes", false);
            if (overlayView != null) {
                overlayView.setShowBoxes(show);
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    private class ControlReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            String action = intent.getAction();
            if (OverlayService.ACTION_TOGGLE_BOXES.equals(action)) {
                boolean show = intent.getBooleanExtra("show", false);
                if (overlayView != null) {
                    overlayView.setShowBoxes(show);
                }
            } else if (OverlayService.ACTION_SWITCH_MODEL.equals(action)) {
                String model = intent.getStringExtra("model");
                int backend = intent.getIntExtra("backend", 0);
                if (socketReceiver != null && model != null) {
                    socketReceiver.sendSwitchModel(model, backend);
                }
            } else if (OverlayService.ACTION_SWITCH_BACKEND.equals(action)) {
                int backend = intent.getIntExtra("backend", 0);
                if (socketReceiver != null) {
                    socketReceiver.sendSwitchBackend(backend);
                }
            } else if (OverlayService.ACTION_UPDATE_DETECTIONS.equals(action)) {
                if (overlayView != null) {
                    int screenW = intent.getIntExtra("screenW", 0);
                    int screenH = intent.getIntExtra("screenH", 0);
                    int count = intent.getIntExtra("count", 0);
                    float[] boxData = intent.getFloatArrayExtra("boxData");
                    String[] labels = intent.getStringArrayExtra("labels");

                    if (boxData != null && labels != null && count > 0) {
                        List<DetectionBox> boxes = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            int offset = i * 10;
                            // Check bounds
                            if (offset + 9 < boxData.length) {
                                float x = boxData[offset];
                                float y = boxData[offset + 1];
                                float w = boxData[offset + 2];
                                float h = boxData[offset + 3];
                                int r = (int) boxData[offset + 4];
                                int g = (int) boxData[offset + 5];
                                int b = (int) boxData[offset + 6];
                                float conf = boxData[offset + 7];
                                int classId = (int) boxData[offset + 8];
                                String label = (i < labels.length) ? labels[i] : "";

                                boxes.add(new DetectionBox((int) x, (int) y, (int) w, (int) h, r, g, b, label, conf,
                                        classId));
                            }
                        }

                        // 主线程更新 UI
                        overlayView.post(() -> {
                            overlayView.updateDetections(boxes, screenW, screenH);
                        });
                    } else if (count == 0) {
                        overlayView.post(() -> {
                            overlayView.updateDetections(new ArrayList<>(), screenW, screenH);
                        });
                    }
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // 发送服务停止广播
        Intent intent = new Intent(OverlayService.ACTION_SERVICE_STATE_CHANGED);
        intent.putExtra("running", false);
        intent.putExtra("service_type", 2); // 2 = DetectionService
        intent.setPackage(getPackageName());
        sendBroadcast(intent);

        if (controlReceiver != null) {
            unregisterReceiver(controlReceiver);
            controlReceiver = null;
        }

        // 停止 Socket 接收
        if (socketReceiver != null) {
            socketReceiver.stopReceiving();
        }

        // 移除悬浮窗
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
        }
    }

    // 实现 AutoAimController，但什么都不做
    @Override
    public void performAutoAim(float targetCx, float targetCy, float canvasWidth, float canvasHeight) {
        // 仅检测模式：不执行任何操作
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "YOLO Detection Service",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Running in detection only mode");

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("YOLO Overlay (Detection Only)")
                .setContentText("仅检测模式运行中")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pendingIntent)
                .build();
    }
}
