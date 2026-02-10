package com.yolo.overlay;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 1001;
    private static final int MEDIA_PROJECTION_REQUEST_CODE = 1002;

    // 推理模式常量
    public static final String EXTRA_INFERENCE_MODE = "inference_mode";
    public static final int INFERENCE_MODE_PC = 0;
    public static final int INFERENCE_MODE_PHONE = 1;

    private Button btnDetection;
    private Button btnAutoAim;
    private TextView tvStatus;
    private android.widget.Spinner spinnerModels;
    private android.widget.Spinner spinnerBackend;
    private android.widget.ArrayAdapter<String> modelAdapter;
    private java.util.List<String> modelList = new java.util.ArrayList<>();
    private java.util.List<String> allModelList = new java.util.ArrayList<>();
    private boolean isUserSelection = false;
    private boolean isBackendUserSelection = false;
    private int currentBackend = 0; // 0 = GPU, 1 = CPU
    private int currentInferenceMode = INFERENCE_MODE_PC; // 0 = PC, 1 = Phone
    private TextView tvInferenceModeDesc;
    private Switch swInferenceLocation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnDetection = findViewById(R.id.btn_detection);
        btnAutoAim = findViewById(R.id.btn_autoaim);
        tvStatus = findViewById(R.id.tv_status);

        btnDetection.setOnClickListener(v -> startDetectionService());
        btnAutoAim.setOnClickListener(v -> startAutoAimService());

        spinnerModels = findViewById(R.id.spinner_models);
        modelAdapter = new android.widget.ArrayAdapter<>(this, R.layout.spinner_item, modelList);
        modelAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerModels.setAdapter(modelAdapter);

        spinnerModels.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (isUserSelection) {
                    String selectedModel = modelList.get(position);
                    Intent intent = new Intent(OverlayService.ACTION_SWITCH_MODEL);
                    intent.putExtra("model", selectedModel);
                    intent.putExtra("backend", currentBackend); // Include current backend
                    intent.putExtra("size", getModelSize(selectedModel));

                    intent.setPackage(getPackageName());
                    sendBroadcast(intent);
                    String backendStr = currentBackend == 0 ? "GPU" : "CPU";
                    Toast.makeText(MainActivity.this,
                            "切换模型: " + selectedModel + " (" + backendStr + ") [" + getModelSize(selectedModel) + "]",
                            Toast.LENGTH_SHORT).show();
                }
                isUserSelection = true;
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        // Backend spinner (GPU/CPU)
        spinnerBackend = findViewById(R.id.spinner_backend);
        String[] backendOptions = { getString(R.string.backend_gpu), getString(R.string.backend_cpu) };
        android.widget.ArrayAdapter<String> backendAdapter = new android.widget.ArrayAdapter<>(
                this, R.layout.spinner_item, backendOptions);
        backendAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerBackend.setAdapter(backendAdapter);
        spinnerBackend.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (isBackendUserSelection && position != currentBackend) {
                    currentBackend = position;
                    Intent intent = new Intent(OverlayService.ACTION_SWITCH_BACKEND);
                    intent.putExtra("backend", currentBackend);
                    intent.setPackage(getPackageName());
                    sendBroadcast(intent);
                    String backendStr = currentBackend == 0 ? "GPU" : "CPU";
                    Toast.makeText(MainActivity.this, "切换推理模式: " + backendStr, Toast.LENGTH_SHORT).show();

                    // Filter models based on backend
                    filterModels(currentBackend);
                }
                isBackendUserSelection = true;
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        android.widget.Switch swShowBoxes = findViewById(R.id.sw_show_boxes);
        swShowBoxes.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Intent intent = new Intent(OverlayService.ACTION_TOGGLE_BOXES);
            intent.putExtra("show", isChecked);
            // Send to both packages/services just in case
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        });

        // 推理位置开关
        tvInferenceModeDesc = findViewById(R.id.tv_inference_mode_desc);
        swInferenceLocation = findViewById(R.id.sw_inference_location);
        swInferenceLocation.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // 请求屏幕录制权限
                MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(
                        MEDIA_PROJECTION_SERVICE);
                startActivityForResult(
                        mediaProjectionManager.createScreenCaptureIntent(),
                        MEDIA_PROJECTION_REQUEST_CODE);
            } else {
                currentInferenceMode = INFERENCE_MODE_PC;
                updateInferenceModeUI();
                Toast.makeText(this, "切换到 PC 推理模式", Toast.LENGTH_SHORT).show();
            }
        });

        TextView tvPortInfo = findViewById(R.id.tv_port_info);
        String portInfo = "端口: " + OverlayService.PORT + "\n使用 ADB 端口转发:\nadb forward tcp:" + OverlayService.PORT
                + " tcp:" + OverlayService.PORT;
        tvPortInfo.setText(portInfo);

        // Load saved models from SharedPreferences to avoid empty dropdown on cold
        // start
        android.content.SharedPreferences prefs = getSharedPreferences("yolo_prefs", MODE_PRIVATE);
        String savedModelsStr = prefs.getString("saved_models", "");
        if (!savedModelsStr.isEmpty()) {
            String[] models = savedModelsStr.split(",");
            for (String m : models) {
                if (!m.trim().isEmpty()) {
                    allModelList.add(m.trim());
                }
            }
        }

        // If no saved models, add a placeholder so spinner doesn't look disabled
        if (allModelList.isEmpty()) {
            modelList.add("等待PC连接...");
            modelAdapter.notifyDataSetChanged();
        } else {
            filterModels(currentBackend);
        }

        // 注册广播接收器
        registerReceivers();

        // 检查权限
        checkOverlayPermission();
    }

    private void filterModels(int backend) {
        String currentSelection = null;
        if (spinnerModels.getSelectedItemPosition() >= 0
                && spinnerModels.getSelectedItemPosition() < modelList.size()) {
            currentSelection = modelList.get(spinnerModels.getSelectedItemPosition());
        }

        modelList.clear();
        if (backend == 1) { // CPU
            for (String model : allModelList) {
                if (!model.toLowerCase().endsWith(".engine")) {
                    modelList.add(model);
                }
            }
        } else { // GPU - show all
            modelList.addAll(allModelList);
        }

        // Temporarily disable listener to avoid triggering switch during filter update
        boolean oldUserSelection = isUserSelection;
        isUserSelection = false;

        modelAdapter.notifyDataSetChanged();

        // Restore selection or select first
        boolean selectionRestored = false;
        if (currentSelection != null) {
            // New logic: When switching to GPU (backend 0), prefer .engine over .onnx for
            // performance
            if (backend == 0 && currentSelection.toLowerCase().endsWith(".onnx")) {
                String possibleEngine = currentSelection.substring(0, currentSelection.length() - 5) + ".engine";
                // Check if the engine version exists in the new list (which includes all
                // models)
                if (modelList.contains(possibleEngine)) {
                    spinnerModels.setSelection(modelList.indexOf(possibleEngine));
                    selectionRestored = true;
                }
            }

            if (!selectionRestored) {
                if (modelList.contains(currentSelection)) {
                    spinnerModels.setSelection(modelList.indexOf(currentSelection));
                    selectionRestored = true;
                } else {
                    // Try finding fallback (e.g. .engine -> .onnx)
                    String fallback = null;
                    if (currentSelection.toLowerCase().endsWith(".engine")) {
                        // Replace .engine with .onnx
                        fallback = currentSelection.substring(0, currentSelection.length() - 7) + ".onnx";
                    }

                    // Check case-insensitive match if direct contains fails (though model names
                    // usually consistent)
                    if (fallback != null) {
                        if (modelList.contains(fallback)) {
                            spinnerModels.setSelection(modelList.indexOf(fallback));
                            selectionRestored = true;
                        } else {
                            // Scan list for match ignoring case
                            for (int i = 0; i < modelList.size(); i++) {
                                if (modelList.get(i).equalsIgnoreCase(fallback)) {
                                    spinnerModels.setSelection(i);
                                    selectionRestored = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        } // Closing if (currentSelection != null)

        if (!selectionRestored && !modelList.isEmpty()) {
            spinnerModels.setSelection(0);
        }

        // Restore listener state (posted to let layout pass finish)
        spinnerModels.post(() -> isUserSelection = true);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Initial State Sync (for when returning from Settings or minimizing)
        isAutoAimRunning = isAccessibilityServiceEnabled();
        isDetectionRunning = isDetectionServiceRunning();

        // Sync connection status from SocketReceiver (fixes bug where switching apps
        // shows disconnected)
        isClientConnected = SocketReceiver.isConnected();

        // Restore model list if empty (fixes dropdown becoming unavailable after
        // switching apps)
        if (allModelList.isEmpty()) {
            java.util.ArrayList<String> savedModels = SocketReceiver.getModelList();
            if (!savedModels.isEmpty()) {
                allModelList.clear();
                allModelList.addAll(savedModels);
                filterModels(currentBackend);
            }
        }

        // Sync "Show Boxes" if AutoAim just started
        if (isAutoAimRunning) {
            android.widget.Switch swShowBoxes = findViewById(R.id.sw_show_boxes);
            Intent intent = new Intent(OverlayService.ACTION_TOGGLE_BOXES);
            intent.putExtra("show", swShowBoxes.isChecked());
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }

        updateUI();
    }

    private void checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            // 请求悬浮窗权限
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "需要悬浮窗权限才能运行", Toast.LENGTH_LONG).show();
                finish();
            }
        } else if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // 权限已授予，切换到手机推理模式
                currentInferenceMode = INFERENCE_MODE_PHONE;
                updateInferenceModeUI();
                Toast.makeText(this, "切换到手机独立推理模式", Toast.LENGTH_SHORT).show();

                // 保存 MediaProjection 数据供服务使用
                android.content.SharedPreferences prefs = getSharedPreferences("yolo_prefs", MODE_PRIVATE);
                prefs.edit()
                        .putInt("media_projection_result_code", resultCode)
                        .apply();

                // 设置检测结果回调 (连接到 OverlayService 或 DetectionService 的 OverlayView)
                ScreenCaptureService.setDetectionListener((boxes, screenW, screenH) -> {
                    // 通过广播通知服务更新检测结果
                    Intent updateIntent = new Intent("com.yolo.overlay.ACTION_UPDATE_DETECTIONS");
                    updateIntent.putExtra("screenW", screenW);
                    updateIntent.putExtra("screenH", screenH);
                    // 将 boxes 转为数组传递
                    int count = boxes.size();
                    float[] boxData = new float[count * 10]; // x, y, w, h, r, g, b, conf, classId, labelLen
                    String[] labels = new String[count];
                    for (int i = 0; i < count; i++) {
                        DetectionBox box = boxes.get(i);
                        boxData[i * 10] = box.x;
                        boxData[i * 10 + 1] = box.y;
                        boxData[i * 10 + 2] = box.w;
                        boxData[i * 10 + 3] = box.h;
                        boxData[i * 10 + 4] = box.r;
                        boxData[i * 10 + 5] = box.g;
                        boxData[i * 10 + 6] = box.b;
                        boxData[i * 10 + 7] = box.confidence;
                        boxData[i * 10 + 8] = box.classId;
                        boxData[i * 10 + 9] = 0; // reserved
                        labels[i] = box.label;
                    }
                    updateIntent.putExtra("count", count);
                    updateIntent.putExtra("boxData", boxData);
                    updateIntent.putExtra("labels", labels);
                    updateIntent.setPackage(getPackageName());
                    sendBroadcast(updateIntent);
                });

                // 直接启动 ScreenCaptureService (不通过广播，避免 Intent 传递问题)
                Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                serviceIntent.putExtra("result_code", resultCode);
                serviceIntent.putExtra("data", data);
                startForegroundService(serviceIntent);

                // 发送广播通知服务切换推理模式 (仅通知状态变化)
                Intent modeIntent = new Intent(OverlayService.ACTION_SWITCH_INFERENCE_MODE);
                modeIntent.putExtra("mode", INFERENCE_MODE_PHONE);
                modeIntent.setPackage(getPackageName());
                sendBroadcast(modeIntent);
            } else {
                // 权限被拒绝，恢复开关状态
                swInferenceLocation.setChecked(false);
                currentInferenceMode = INFERENCE_MODE_PC;
                updateInferenceModeUI();
                Toast.makeText(this, "需要屏幕录制权限才能使用手机推理", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startDetectionService() {
        if (!Settings.canDrawOverlays(this)) {
            checkOverlayPermission();
            return;
        }

        // Toggle Logic
        if (isDetectionRunning) {
            // Stop it
            stopService(new Intent(this, DetectionService.class));
            // UI update will happen via Broadcast, but we can optimistically set it to
            // prevent double clicks
            isDetectionRunning = false;
            updateUI();
            return;
        }

        // Ensure AutoAim is stopped or handled
        if (isAccessibilityServiceEnabled()) {
            Intent stopIntent = new Intent(OverlayService.ACTION_STOP_SERVICE);
            stopIntent.setPackage(getPackageName());
            sendBroadcast(stopIntent);
            isAutoAimRunning = false;
        }

        // Start
        Intent intent = new Intent(this, DetectionService.class);
        // Pass the "Show Boxes" state
        android.widget.Switch swShowBoxes = findViewById(R.id.sw_show_boxes);
        intent.putExtra("show_boxes", swShowBoxes.isChecked());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        Toast.makeText(this, "正在启动检测...", Toast.LENGTH_SHORT).show();
    }

    private void startAutoAimService() {
        if (!Settings.canDrawOverlays(this)) {
            checkOverlayPermission();
            return;
        }

        // Toggle Logic
        if (isAccessibilityServiceEnabled()) {
            // If currently enabled/running, we treat this click as a "Stop" command for the
            // Overlay logic
            // But we can't fully kill AccessibilityService from here without System
            // Settings.
            // However, OverlayService listens to ACTION_STOP_SERVICE to disableSelf().

            Intent stopIntent = new Intent(OverlayService.ACTION_STOP_SERVICE);
            stopIntent.setPackage(getPackageName());
            sendBroadcast(stopIntent);

            // Optimistic UI update
            isAutoAimRunning = false;
            updateUI();
            return;
        }

        // Stop Detection Service if running
        if (isDetectionRunning) {
            stopService(new Intent(this, DetectionService.class));
            isDetectionRunning = false;
        }

        // 引导用户开启无障碍服务
        Toast.makeText(this, "请开启无障碍服务以使用自瞄功能", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);

        // We can't really pass "show_boxes" here because the service is started by the
        // System.
        // But we store the intent extras in a way OverlayService can pick up?
        // Or simply send a broadcast right after?
        // Since we go to Settings, the user has to come back.
        // Better: OverlayService should invoke a callback or we send a broadcast when
        // we detect it started.
        // For now, we rely on the Switch listener to sync state if changed.
        // To ensure initial state is correct, we can send a broadcast when onResume
        // detects service is enabled.
    }

    private boolean isAccessibilityServiceEnabled() {
        android.view.accessibility.AccessibilityManager am = (android.view.accessibility.AccessibilityManager) getSystemService(
                ACCESSIBILITY_SERVICE);
        java.util.List<android.accessibilityservice.AccessibilityServiceInfo> enabledServices = am
                .getEnabledAccessibilityServiceList(
                        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

        for (android.accessibilityservice.AccessibilityServiceInfo enabledService : enabledServices) {
            android.content.pm.ServiceInfo enabledServiceInfo = enabledService.getResolveInfo().serviceInfo;
            if (enabledServiceInfo.packageName.equals(getPackageName())
                    && enabledServiceInfo.name.equals(OverlayService.class.getName())) {
                return true;
            }
        }
        return false;
    }

    // 简单的判断 DetectionService 是否在运行 (这需要遍历运行中的服务，或者使用静态标志)
    private boolean isDetectionServiceRunning() {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (DetectionService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isDetectionRunning = false;
    private boolean isAutoAimRunning = false;
    private boolean isClientConnected = false;

    private void updateUI() {
        // 更新按钮状态和文本
        if (isAutoAimRunning) {
            btnAutoAim.setText("关闭自瞄");
            btnAutoAim.setEnabled(true);
            btnAutoAim.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF4444)); // Red

            // AutoAim running implies we shouldn't start detection separately, or it
            // overrides it.
            // But we keep Detection button available if user wants to switch.
            btnDetection.setEnabled(false);
            btnDetection.setText("自瞄运行中");
        } else {
            btnAutoAim.setText("开启自瞄 (需无障碍)");
            btnAutoAim.setEnabled(true);
            btnAutoAim.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF0088)); // Pink

            if (isDetectionRunning) {
                btnDetection.setText("关闭仅检测");
                btnDetection.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF4444)); // Red
            } else {
                btnDetection.setText("仅检测 (无需无障碍)");
                btnDetection.setEnabled(true);
                btnDetection.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF0088FF)); // Blue
            }
        }

        // status text
        if (isAutoAimRunning) {
            tvStatus.setText("无障碍自瞄服务已开启" + (isClientConnected ? " (已连接)" : " (等待连接)"));
        } else if (isDetectionRunning) {
            tvStatus.setText("仅检测服务运行中" + (isClientConnected ? " (已连接)" : " (等待连接)"));
        } else {
            tvStatus.setText("服务未开启");
        }
    }

    private void updateInferenceModeUI() {
        if (tvInferenceModeDesc != null) {
            if (currentInferenceMode == INFERENCE_MODE_PHONE) {
                tvInferenceModeDesc.setText(R.string.inference_mode_phone);
            } else {
                tvInferenceModeDesc.setText(R.string.inference_mode_pc);
            }
        }

        // 根据推理模式同步模型和推理模式下拉框
        if (spinnerModels != null && spinnerBackend != null) {
            if (currentInferenceMode == INFERENCE_MODE_PHONE) {
                // 手机模式：禁用 PC 相关下拉框，显示手机模型信息
                spinnerModels.setEnabled(false);
                spinnerBackend.setEnabled(false);
                spinnerModels.setAlpha(0.5f);
                spinnerBackend.setAlpha(0.5f);

                // 更新模型下拉框显示手机模型
                isUserSelection = false;
                modelList.clear();
                modelList.add("yolov8n (本地 NCNN)");
                modelAdapter.notifyDataSetChanged();
                spinnerModels.setSelection(0);

                // 更新推理模式显示
                isBackendUserSelection = false;
                // 由于手机模式使用 NCNN Vulkan/CPU，显示为"手机 GPU"
                // 这里我们不改变 backend spinner 的选项，只是禁用它
            } else {
                // PC 模式：启用下拉框，恢复 PC 模型列表
                spinnerModels.setEnabled(true);
                spinnerBackend.setEnabled(true);
                spinnerModels.setAlpha(1.0f);
                spinnerBackend.setAlpha(1.0f);

                // 恢复 PC 模型列表
                isUserSelection = false;
                filterModels(currentBackend);
            }
        }
    }

    private android.content.BroadcastReceiver statusReceiver;

    private void registerReceivers() {
        if (statusReceiver == null) {
            statusReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, Intent intent) {
                    if (OverlayService.ACTION_SERVICE_STATE_CHANGED.equals(intent.getAction())) {
                        boolean running = intent.getBooleanExtra("running", false);
                        int type = intent.getIntExtra("service_type", 0);
                        if (type == 1) { // OverlayService
                            isAutoAimRunning = running;
                            if (running)
                                isDetectionRunning = false; // Mutually exclusive UI logic
                        } else if (type == 2) { // DetectionService
                            isDetectionRunning = running;
                            if (running)
                                isAutoAimRunning = false;
                        }
                        updateUI();
                    } else if (OverlayService.ACTION_CLIENT_CONNECTION_STATUS.equals(intent.getAction())) {
                        isClientConnected = intent.getBooleanExtra("connected", false);
                        updateUI();

                        // 连接成功时，强制同步 Backend 给 PC (以手机端为准)
                        if (isClientConnected) {
                            Intent backendIntent = new Intent(OverlayService.ACTION_SWITCH_BACKEND);
                            backendIntent.putExtra("backend", currentBackend);
                            backendIntent.setPackage(getPackageName());
                            sendBroadcast(backendIntent);
                        }
                    } else if (SocketReceiver.ACTION_MODEL_LIST.equals(intent.getAction())) {
                        java.util.ArrayList<String> models = intent.getStringArrayListExtra("models");
                        if (models != null) {
                            allModelList.clear();
                            allModelList.addAll(models);

                            // Save to SharedPreferences
                            android.content.SharedPreferences prefs = getSharedPreferences("yolo_prefs", MODE_PRIVATE);
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < models.size(); i++) {
                                sb.append(models.get(i));
                                if (i < models.size() - 1)
                                    sb.append(",");
                            }
                            prefs.edit().putString("saved_models", sb.toString()).apply();

                            // Apply current filter
                            filterModels(currentBackend);

                            // 列表更新后，强制同步当前选中的模型给 PC (以手机端为准)
                            if (spinnerModels.getSelectedItemPosition() >= 0 &&
                                    spinnerModels.getSelectedItemPosition() < modelList.size()) {
                                String selectedModel = modelList.get(spinnerModels.getSelectedItemPosition());
                                // 通过广播发送模型切换命令给服务，服务会通过 socketReceiver 发送给 PC
                                Intent modelIntent = new Intent(OverlayService.ACTION_SWITCH_MODEL);
                                modelIntent.putExtra("model", selectedModel);
                                modelIntent.putExtra("backend", currentBackend);
                                modelIntent.putExtra("size", getModelSize(selectedModel));
                                modelIntent.setPackage(getPackageName());
                                sendBroadcast(modelIntent);
                            }

                            // Re-enable user selection flag after a short delay or just let the next touch
                            // handle it
                            // Actually, setting selection programmatically triggers listener.
                            // We use isUserSelection to guard, but filterModels sets it false temporarily.
                            // It will be set to true on next user interaction or we just leave it.
                            // Better approach:
                            // spinnerModels.post(() -> isUserSelection = true); // Handled in filterModels
                            // now
                        }
                    }
                }
            };
            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction(OverlayService.ACTION_SERVICE_STATE_CHANGED);
            filter.addAction(OverlayService.ACTION_CLIENT_CONNECTION_STATUS);
            filter.addAction(SocketReceiver.ACTION_MODEL_LIST);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(statusReceiver, filter);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (statusReceiver != null) {
            unregisterReceiver(statusReceiver);
            statusReceiver = null;
        }
    }

    private int getModelSize(String modelName) {
        if (modelName == null)
            return 416;
        String lowerName = modelName.toLowerCase();
        if (lowerName.contains("256"))
            return 256;
        if (lowerName.contains("320"))
            return 320;
        if (lowerName.contains("416"))
            return 416;
        if (lowerName.contains("640"))
            return 640;
        if (lowerName.contains("1280"))
            return 1280;
        return 416;
    }
}
