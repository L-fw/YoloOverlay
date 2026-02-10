package com.yolo.overlay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.graphics.PixelFormat;
import android.os.Build;

public class OverlayService extends AccessibilityService implements AutoAimController {

    public static final int PORT = 19888;
    private static final String CHANNEL_ID = "yolo_overlay_channel";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windowManager;
    private OverlayView overlayView;
    private SocketReceiver socketReceiver;

    private int screenWidth;
    private int screenHeight;

    // 自瞄灵敏度 (像素/像素)
    // 提高灵敏度配合更高频或更短的滑动
    // PID 控制器参数
    // P (比例): 0.15 (降低以减少震荡)
    // I (积分): 0.0 (暂时禁用)
    // D (微分): 0.0 (暂时禁用)
    private static final float PID_KP = 0.15f;
    private static final float PID_KI = 0.0f;
    private static final float PID_KD = 0.0f;

    // PID 状态变量
    private float integralX = 0;
    private float integralY = 0;
    private float prevErrorX = 0;
    private float prevErrorY = 0;

    // 死区 (像素)
    private static final float DEAD_ZONE = 18.0f;

    // 安全触摸区域 (相对于屏幕宽高的比例)
    // 左半屏安全区：避开左上角(地图/菜单)、左下角(摇杆)
    // 假设：
    // X范围: 20% - 45% (避开极左边和中间)
    // Y范围: 30% - 70% (避开顶部和底部)
    public static final float SAFE_ZONE_LEFT_RATIO = 0.20f;
    public static final float SAFE_ZONE_TOP_RATIO = 0.30f;
    public static final float SAFE_ZONE_RIGHT_RATIO = 0.45f;
    public static final float SAFE_ZONE_BOTTOM_RATIO = 0.70f;

    // 自瞄状态
    private boolean isAutoAimRunning = true; // 默认启用，可以通过设置切换

    private StopReceiver stopReceiver;
    public static final String ACTION_STOP_SERVICE = "com.yolo.overlay.ACTION_STOP_SERVICE";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        // 注册停止广播
        stopReceiver = new StopReceiver();
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(ACTION_STOP_SERVICE);
        filter.addAction(ACTION_TOGGLE_BOXES);
        filter.addAction(ACTION_SWITCH_MODEL);
        filter.addAction(ACTION_SWITCH_BACKEND);
        filter.addAction(ACTION_SWITCH_INFERENCE_MODE);
        filter.addAction(ACTION_UPDATE_DETECTIONS);
        filter.addAction(ACTION_TOGGLE_AUTOAIM);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stopReceiver, filter);
        }

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        // 获取屏幕尺寸
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

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

        // Auto-Aim needs the touch zone
        overlayView.setShowTouchZone(true);

        // 启动 Socket 接收线程
        socketReceiver = new SocketReceiver(PORT, overlayView, this, this);
        socketReceiver.start();

        // 发送服务启动广播
        Intent intent = new Intent(ACTION_SERVICE_STATE_CHANGED);
        intent.putExtra("running", true);
        intent.putExtra("service_type", 1); // 1 = OverlayService
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要处理事件
    }

    @Override
    public void onInterrupt() {
        // 服务被中断
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // 发送服务停止广播
        Intent intent = new Intent(ACTION_SERVICE_STATE_CHANGED);
        intent.putExtra("running", false);
        intent.putExtra("service_type", 1); // 1 = OverlayService
        intent.setPackage(getPackageName());
        sendBroadcast(intent);

        if (stopReceiver != null) {
            unregisterReceiver(stopReceiver);
            stopReceiver = null;
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

    public static final String ACTION_TOGGLE_BOXES = "com.yolo.overlay.ACTION_TOGGLE_BOXES";
    public static final String ACTION_SERVICE_STATE_CHANGED = "com.yolo.overlay.ACTION_SERVICE_STATE_CHANGED";
    public static final String ACTION_CLIENT_CONNECTION_STATUS = "com.yolo.overlay.ACTION_CLIENT_CONNECTION_STATUS";
    public static final String ACTION_SWITCH_MODEL = "com.yolo.overlay.ACTION_SWITCH_MODEL";
    public static final String ACTION_SWITCH_BACKEND = "com.yolo.overlay.ACTION_SWITCH_BACKEND";
    public static final String ACTION_BACKEND_STATUS = "com.yolo.overlay.ACTION_BACKEND_STATUS";
    public static final String ACTION_SWITCH_INFERENCE_MODE = "com.yolo.overlay.ACTION_SWITCH_INFERENCE_MODE";
    public static final String ACTION_UPDATE_DETECTIONS = "com.yolo.overlay.ACTION_UPDATE_DETECTIONS";
    public static final String ACTION_TOGGLE_AUTOAIM = "com.yolo.overlay.ACTION_TOGGLE_AUTOAIM";

    private class StopReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_STOP_SERVICE.equals(action)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    disableSelf();
                }
            } else if (ACTION_TOGGLE_AUTOAIM.equals(action)) {
                isAutoAimRunning = intent.getBooleanExtra("running", true);
            } else if (ACTION_TOGGLE_BOXES.equals(action)) {
                boolean show = intent.getBooleanExtra("show", false);
                if (overlayView != null) {
                    overlayView.setShowBoxes(show);
                }
            } else if (ACTION_SWITCH_MODEL.equals(action)) {
                String model = intent.getStringExtra("model");
                int backend = intent.getIntExtra("backend", 0);
                int size = intent.getIntExtra("size", 416); // Default 416

                if (overlayView != null) {
                    overlayView.setDetectionSize(size);
                }

                if (socketReceiver != null && model != null) {
                    socketReceiver.sendSwitchModel(model, backend);
                }
            } else if (ACTION_SWITCH_BACKEND.equals(action)) {
                int backend = intent.getIntExtra("backend", 0);
                if (socketReceiver != null) {
                    socketReceiver.sendSwitchBackend(backend);
                }
            } else if (ACTION_SWITCH_INFERENCE_MODE.equals(action)) {
                int mode = intent.getIntExtra("mode", 0);
                handleInferenceModeSwitch(mode, intent);
            } else if (ACTION_UPDATE_DETECTIONS.equals(action)) {
                if (overlayView != null) {
                    int screenW = intent.getIntExtra("screenW", 0);
                    int screenH = intent.getIntExtra("screenH", 0);
                    int count = intent.getIntExtra("count", 0);
                    float[] boxData = intent.getFloatArrayExtra("boxData");
                    String[] labels = intent.getStringArrayExtra("labels");

                    if (boxData != null && labels != null && count > 0) {
                        java.util.List<DetectionBox> boxes = new java.util.ArrayList<>();
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
                                String label = i < labels.length ? labels[i] : "";

                                boxes.add(new DetectionBox((int) x, (int) y, (int) w, (int) h, r, g, b, label, conf,
                                        classId));
                            }
                        }

                        // 主线程更新 UI
                        overlayView.post(() -> {
                            overlayView.updateDetections(boxes, screenW, screenH);
                        });

                        // 触发自瞄 (如果在手机模式下启用)
                        if (isAutoAimRunning && currentInferenceMode == 1) {
                            // Find best target (e.g. head/class 1)
                            float bestConf = 0;
                            DetectionBox bestBox = null;
                            float centerX = screenW / 2f;
                            float centerY = screenH / 2f;
                            float minDist = Float.MAX_VALUE;

                            for (DetectionBox box : boxes) {
                                // Priority: Class 1 (Head) > Class 0 (Body)
                                // Distance to center
                                float boxCx = box.x + box.w / 2f;
                                float boxCy = box.y + box.h / 2f;
                                float dist = (float) Math
                                        .sqrt(Math.pow(boxCx - centerX, 2) + Math.pow(boxCy - centerY, 2));

                                if (box.classId == 1) { // Head
                                    if (bestBox == null || bestBox.classId != 1 || dist < minDist) {
                                        bestBox = box;
                                        minDist = dist;
                                    }
                                } else if (bestBox == null || bestBox.classId != 1) { // Body
                                    if (dist < minDist) {
                                        bestBox = box;
                                        minDist = dist;
                                    }
                                }
                            }

                            if (bestBox != null) {
                                performAutoAim(bestBox.x + bestBox.w / 2f, bestBox.y + bestBox.h / 2f, screenW,
                                        screenH);
                            }
                        }
                    } else if (count == 0) {
                        overlayView.post(() -> {
                            overlayView.updateDetections(new java.util.ArrayList<>(), screenW, screenH);
                        });
                    }
                }
            }
        }
    }

    // 当前推理模式: 0 = PC, 1 = Phone
    private int currentInferenceMode = 0;

    private void handleInferenceModeSwitch(int mode, Intent originalIntent) {
        currentInferenceMode = mode;

        if (mode == 1) {
            // 手机独立模式: 停止 PC 连接(socket仍保持连接但忽略数据)
            // ScreenCaptureService 已经由 MainActivity 启动
            // Detection results are handled via broadcast ACTION_UPDATE_DETECTIONS
            if (overlayView != null) {
                overlayView.setShowTouchZone(true); // Maintain touch zone for auto-aim
            }
        } else {
            // PC 模式: 停止本地推理，恢复 PC 连接
            ScreenCaptureService captureService = ScreenCaptureService.getInstance();
            if (captureService != null) {
                captureService.stopCapture();
                stopService(new Intent(this, ScreenCaptureService.class));
            }
        }
    }

    private long lastAimTime = 0;
    // 冷却时间 (ms)：防止霸占输入通道，给用户留操作余地
    // 增加到 350ms 以便用户夺回控制权
    private static final long AIM_COOLDOWN = 500;

    private final java.util.Random random = new java.util.Random();

    // 执行自瞄逻辑
    public void performAutoAim(float targetCx, float targetCy, float canvasWidth, float canvasHeight) {
        long now = System.currentTimeMillis();
        // 添加 0-5ms 的随机抖动
        long jitter = random.nextInt(5);
        if (now - lastAimTime < AIM_COOLDOWN + jitter) {
            return;
        }

        // 计算时间差 (单位: 秒)
        // 必须在更新 lastAimTime 之前计算，否则为 0
        double deltaTime = (now - lastAimTime) / 1000.0;
        lastAimTime = now;

        // 动态获取屏幕尺寸，以处理横竖屏切换
        DisplayMetrics metrics = new DisplayMetrics();
        if (windowManager != null) {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
        }

        // 使用传入的画布宽高计算中心点
        float cropCenterX = canvasWidth / 2f;
        float cropCenterY = canvasHeight / 2f;

        float dx = targetCx - cropCenterX;
        float dy = targetCy - cropCenterY;

        // 检查死区
        if (Math.abs(dx) < DEAD_ZONE && Math.abs(dy) < DEAD_ZONE) {
            return;
        }

        // 模拟滑动
        // 修改为右半屏安全区域，避开FPS按键
        // X: 75% 屏幕宽度
        // Y: 40% 屏幕高度
        float startX = screenWidth * 0.75f;
        float startY = screenHeight * 0.4f;

        // --- PID Controller 实现 ---

        // 计算时间差 (已在上方计算)
        if (deltaTime > 0.5) {
            // 如果间隔太久(比如丢失目标或切换目标)，重置PID状态
            deltaTime = 0.016;
            integralX = 0;
            integralY = 0;
            prevErrorX = 0;
            prevErrorY = 0;
        }

        // 1. Proportional (比例项)
        // 降低 P 值以减少震荡
        float pX = PID_KP * dx;
        float pY = PID_KP * dy;

        // 2. Integral (积分项) - 暂时禁用
        integralX += dx * deltaTime;
        integralY += dy * deltaTime;

        // 积分限幅 (Anti-windup)
        float MAX_INTEGRAL = 200.0f;
        if (integralX > MAX_INTEGRAL)
            integralX = MAX_INTEGRAL;
        if (integralX < -MAX_INTEGRAL)
            integralX = -MAX_INTEGRAL;
        if (integralY > MAX_INTEGRAL)
            integralY = MAX_INTEGRAL;
        if (integralY < -MAX_INTEGRAL)
            integralY = -MAX_INTEGRAL;

        float iX = PID_KI * integralX;
        float iY = PID_KI * integralY;

        // 3. Derivative (微分项)
        // 只有当不是第一次运行时才计算微分
        float derivX = 0;
        float derivY = 0;
        if (deltaTime > 0) {
            derivX = (dx - prevErrorX) / (float) deltaTime;
            derivY = (dy - prevErrorY) / (float) deltaTime;
        }

        float dX = PID_KD * derivX;
        float dY = PID_KD * derivY;

        // 更新上一次误差
        prevErrorX = dx;
        prevErrorY = dy;

        // PID 输出合成
        float moveX = pX + iX + dX;
        float moveY = pY + iY + dY;

        // 分段步进：限制单次滑动的最大距离
        // 例如每次最多移动 60 像素，防止瞬间大跳
        float dist = (float) Math.sqrt(moveX * moveX + moveY * moveY);
        float MAX_STEP_DISTANCE = 60.0f;

        if (dist > MAX_STEP_DISTANCE) {
            float ratio = MAX_STEP_DISTANCE / dist;
            moveX *= ratio;
            moveY *= ratio;
        }

        float endX = startX + moveX;
        float endY = startY + moveY;

        dispatchGestureCompat(startX, startY, endX, endY);
    }

    private void dispatchGestureCompat(float startX, float startY, float endX, float endY) {
        Path path = new Path();
        path.moveTo(startX, startY);

        // 生成两个随机控制点，模拟贝塞尔曲线
        // 控制点1：在起点和终点之间，偏离直线一定距离
        float control1X = startX + (endX - startX) * (0.25f + random.nextFloat() * 0.25f);
        float control1Y = startY + (endY - startY) * (0.25f + random.nextFloat() * 0.25f);
        // 添加随机偏移 (-15 到 15 像素)
        control1X += (random.nextFloat() - 0.5f) * 30;
        control1Y += (random.nextFloat() - 0.5f) * 30;

        // 控制点2
        float control2X = startX + (endX - startX) * (0.5f + random.nextFloat() * 0.25f);
        float control2Y = startY + (endY - startY) * (0.5f + random.nextFloat() * 0.25f);
        control2X += (random.nextFloat() - 0.5f) * 30;
        control2Y += (random.nextFloat() - 0.5f) * 30;

        // 使用三阶贝塞尔曲线
        path.cubicTo(control1X, control1Y, control2X, control2Y, endX, endY);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        // 极短时间 (20ms) 完成滑动，几乎不占用用户触摸时间
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));

        dispatchGesture(builder.build(), null, null);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_description));

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("YOLO Overlay & AutoAim")
                .setContentText("无障碍服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pendingIntent)
                .build();
    }
}
