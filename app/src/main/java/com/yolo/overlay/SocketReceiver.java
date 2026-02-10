package com.yolo.overlay;

import android.util.Log;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.BufferedInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Socket 接收线程，接收 PC 发送的检测数据
 */
public class SocketReceiver extends Thread {

    private static final String TAG = "SocketReceiver";

    private final int port;
    private final OverlayView overlayView;
    private final android.content.Context context;
    private volatile boolean running = true;
    private ServerSocket serverSocket;
    private volatile Socket activeClientSocket;
    private final Object outputLock = new Object();

    // Static flag to track connection status (for MainActivity to query on resume)
    private static volatile boolean sIsConnected = false;

    // Static model list to preserve across app switches
    private static volatile java.util.ArrayList<String> sModelList = new java.util.ArrayList<>();

    public static boolean isConnected() {
        return sIsConnected;
    }

    public static java.util.ArrayList<String> getModelList() {
        return new java.util.ArrayList<>(sModelList);
    }

    public static final int MAGIC_DATA = 0xABCD;
    public static final int MAGIC_MODEL_LIST = 0x1234;
    public static final int MAGIC_SWITCH_MODEL = 0x5678;
    public static final int MAGIC_SWITCH_BACKEND = 0x9ABC;
    public static final int MAGIC_BACKEND_STATUS = 0xDEF0;

    public static final String ACTION_MODEL_LIST = "com.yolo.overlay.ACTION_MODEL_LIST";

    // UI Handler to show toasts
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final AutoAimController autoAimController;

    public SocketReceiver(int port, OverlayView overlayView, android.content.Context context,
            AutoAimController controller) {
        this.port = port;
        this.overlayView = overlayView;
        this.context = context;
        this.autoAimController = controller;
    }

    @Override
    public void run() {
        try {
            // Bind to all interfaces (0.0.0.0)
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);

            Log.i(TAG, "Server started on port " + port);
            showToast("Overlay服务已启动: " + port);

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setTcpNoDelay(true); // Disable Nagle's algorithm
                    Log.i(TAG, "Client connected: " + clientSocket.getRemoteSocketAddress());
                    showToast("PC已连接");

                    // 发送连接成功广播
                    sIsConnected = true;
                    Intent intent = new Intent(OverlayService.ACTION_CLIENT_CONNECTION_STATUS);
                    intent.putExtra("connected", true);
                    intent.setPackage(context.getPackageName());
                    context.sendBroadcast(intent);

                    handleClient(clientSocket);

                } catch (Exception e) {
                    if (running) {
                        Log.e(TAG, "Error accepting connection", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Server error", e);
            showToast("Server错误: " + e.getMessage());
        }
    }

    private void showToast(String msg) {
        uiHandler.post(() -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show());
    }

    private static class Packet {
        List<DetectionBox> boxes;
        int screenW, screenH;
        int modelSize;

        Packet(List<DetectionBox> boxes, int w, int h, int ms) {
            this.boxes = boxes;
            this.screenW = w;
            this.screenH = h;
            this.modelSize = ms;
        }
    }

    private Packet readPacket(DataInputStream dis) throws Exception {
        // Read Magic (0xABCD)
        int magic = dis.readUnsignedShort();

        if (magic != 0xABCD) {
            Log.w(TAG, "Invalid magic: " + Integer.toHexString(magic));
            return null; // Sync error
        }

        int count = dis.readUnsignedShort();
        int screenW = dis.readUnsignedShort();
        int screenH = dis.readUnsignedShort();
        int modelSize = dis.readUnsignedShort(); // New Protocol Field
        if (modelSize != 0) {
            Log.i(TAG, "Received model size: " + modelSize);
        } else {
            Log.w(TAG, "Received model size 0 (Old DLL?)");
        }

        List<DetectionBox> boxes = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            int x = dis.readUnsignedShort();
            int y = dis.readUnsignedShort();
            int w = dis.readUnsignedShort();
            int h = dis.readUnsignedShort();
            int r = dis.readUnsignedByte();
            int g = dis.readUnsignedByte();
            int b = dis.readUnsignedByte();
            int confInt = dis.readUnsignedByte();
            int classId = dis.readUnsignedByte(); // New Protocol Field
            int labelLen = dis.readUnsignedByte();

            byte[] labelBytes = new byte[labelLen];
            dis.readFully(labelBytes);
            String label = new String(labelBytes, "UTF-8");

            float conf = confInt / 100.0f;

            boxes.add(new DetectionBox(x, y, w, h, r, g, b, label, conf, classId));
        }

        return new Packet(boxes, screenW, screenH, modelSize);
    }

    private void readModelList(DataInputStream dis) throws Exception {
        int count = dis.readUnsignedShort();
        ArrayList<String> models = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            int len = dis.readUnsignedByte();
            byte[] nameBytes = new byte[len];
            dis.readFully(nameBytes);
            models.add(new String(nameBytes, "UTF-8"));
        }

        Log.i(TAG, "Received model list: " + models);

        // Save to static variable for persistence across app switches
        sModelList = new ArrayList<>(models);

        // Broadcast models
        Intent intent = new Intent(ACTION_MODEL_LIST);
        intent.putStringArrayListExtra("models", models);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    public void sendSwitchModel(String modelName, int backend) {
        new Thread(() -> {
            synchronized (outputLock) {
                if (activeClientSocket != null && !activeClientSocket.isClosed()) {
                    try {
                        java.io.DataOutputStream dos = new java.io.DataOutputStream(
                                activeClientSocket.getOutputStream());
                        dos.writeShort(MAGIC_SWITCH_MODEL);
                        byte[] nameBytes = modelName.getBytes("UTF-8");
                        dos.writeByte(nameBytes.length);
                        dos.write(nameBytes);
                        dos.writeByte(backend); // 0 = GPU, 1 = CPU
                        dos.flush();
                        Log.i(TAG, "Sent switch model request: " + modelName + " backend=" + backend);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send switch model", e);
                    }
                }
            }
        }).start();
    }

    public void sendSwitchBackend(int backend) {
        new Thread(() -> {
            synchronized (outputLock) {
                if (activeClientSocket != null && !activeClientSocket.isClosed()) {
                    try {
                        java.io.DataOutputStream dos = new java.io.DataOutputStream(
                                activeClientSocket.getOutputStream());
                        dos.writeShort(MAGIC_SWITCH_BACKEND);
                        dos.writeByte(backend); // 0 = GPU, 1 = CPU
                        dos.flush();
                        Log.i(TAG, "Sent switch backend request: " + (backend == 0 ? "GPU" : "CPU"));
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send switch backend", e);
                    }
                }
            }
        }).start();
    }

    private void handleClient(Socket clientSocket) {
        try {
            activeClientSocket = clientSocket;
            // Remove BufferedInputStream to reduce latency
            DataInputStream dis = new DataInputStream(clientSocket.getInputStream());

            while (running) {
                Packet latestPacket = null;

                try {
                    int magic = dis.readUnsignedShort();
                    if (magic == MAGIC_DATA) {
                        // proceed to read rest of packet (without magic)
                        int count = dis.readUnsignedShort();
                        int screenW = dis.readUnsignedShort();
                        int screenH = dis.readUnsignedShort();
                        int modelSize = dis.readUnsignedShort(); // New Protocol Field

                        List<DetectionBox> boxes = new ArrayList<>();

                        for (int i = 0; i < count; i++) {
                            int x = dis.readUnsignedShort();
                            int y = dis.readUnsignedShort();
                            int w = dis.readUnsignedShort();
                            int h = dis.readUnsignedShort();
                            int r = dis.readUnsignedByte();
                            int g = dis.readUnsignedByte();
                            int b = dis.readUnsignedByte();
                            int confInt = dis.readUnsignedByte();
                            int classId = dis.readUnsignedByte();
                            int labelLen = dis.readUnsignedByte();

                            byte[] labelBytes = new byte[labelLen];
                            dis.readFully(labelBytes);
                            String label = new String(labelBytes, "UTF-8");

                            float conf = confInt / 100.0f;
                            boxes.add(new DetectionBox(x, y, w, h, r, g, b, label, conf, classId));
                        }
                        latestPacket = new Packet(boxes, screenW, screenH, modelSize);

                    } else if (magic == MAGIC_MODEL_LIST) {
                        readModelList(dis);
                        continue; // Wait for next packet
                    } else if (magic == MAGIC_BACKEND_STATUS) {
                        // Read backend switch result from PC
                        int success = dis.readUnsignedByte();
                        int msgLen = dis.readUnsignedByte();
                        byte[] msgBytes = new byte[msgLen];
                        dis.readFully(msgBytes);
                        String message = new String(msgBytes, "UTF-8");

                        Log.i(TAG, "Backend status: success=" + success + " msg=" + message);

                        // Broadcast result to MainActivity
                        Intent intent = new Intent(OverlayService.ACTION_BACKEND_STATUS);
                        intent.putExtra("success", success == 1);
                        intent.putExtra("message", message);
                        intent.setPackage(context.getPackageName());
                        context.sendBroadcast(intent);

                        // Show toast
                        final String toastMsg = success == 1 ? message : "切换失败: " + message;
                        uiHandler.post(() -> android.widget.Toast
                                .makeText(context, toastMsg, android.widget.Toast.LENGTH_LONG).show());
                        continue;
                    } else {
                        Log.w(TAG, "Unknown magic: " + Integer.toHexString(magic));
                        continue;
                    }

                } catch (java.io.EOFException e) {
                    break;
                } catch (Exception e) {
                    break;
                }

                if (latestPacket == null)
                    continue; // Invalid magic or error -> retry

                // DRAIN: Check if more packets are waiting in the OS buffer
                int skipped = 0;
                try {
                    while (dis.available() > 0) {
                        // We strictly limit the time spent skipping to avoid CPU lock
                        // But since we want the LATEST, we usually just want to skip all.
                        // Ideally we peek the magic, but readPacket consumes.
                        Packet next = readPacket(dis);
                        if (next != null) {
                            latestPacket = next; // Upgrade to newer packet
                            skipped++;
                        } else {
                            // Sync error in queued data, stop skipping
                            break;
                        }

                        // Safety: Don't skip more than 10 frames to allow UI to update eventually
                        if (skipped > 10)
                            break;
                    }
                } catch (Exception e) {
                    // unexpected error during skipping, just use what we have
                }

                if (skipped > 0) {
                    // Log.v(TAG, "Skipped " + skipped + " frames for zero-latency");
                }

                if (latestPacket.modelSize > 0) {
                    overlayView.setDetectionSize(latestPacket.modelSize);
                }
                overlayView.updateBoxes(latestPacket.boxes, latestPacket.screenW, latestPacket.screenH);

                // --- Auto Aim Logic ---
                // --- Auto Aim Logic ---
                try {
                    List<DetectionBox> boxes = latestPacket.boxes;
                    int screenW = latestPacket.screenW;
                    int screenH = latestPacket.screenH;

                    if (!boxes.isEmpty()) {
                        DetectionBox bestTarget = null;
                        float minDistSq = Float.MAX_VALUE;

                        // 寻找距离准星最近的目标
                        // 使用接收到的 screenW/H 作为画布尺寸
                        float cropCx = screenW / 2f;
                        float cropCy = screenH / 2f;

                        for (DetectionBox box : boxes) {
                            // 只瞄准 Class 0 (例如敌人)
                            // 如果不区分则去掉这个判断
                            // if (box.label.contains("0")) ...

                            float dx = box.x + box.w / 2f - cropCx;
                            float dy = box.y + box.h / 2f - cropCy;
                            float distSq = dx * dx + dy * dy;

                            if (distSq < minDistSq) {
                                minDistSq = distSq;
                                bestTarget = box;
                            }
                        }

                        if (bestTarget != null) {
                            // 触发自瞄
                            float targetCx = bestTarget.x + bestTarget.w / 2f;
                            float targetCy = bestTarget.y + bestTarget.h / 2f;
                            autoAimController.performAutoAim(targetCx, targetCy, (float) screenW, (float) screenH);
                        }
                    }
                } catch (Exception e) {
                    // Log silently or show verbose error only if needed
                    Log.w(TAG, "Auto-aim error: " + e.getMessage());
                }
                // ---------------------
                // ---------------------
            }

            clientSocket.close();
            Log.i(TAG, "Client disconnected");

            // 客户端断开时清除检测框
            overlayView.clearBoxes();

            Log.i(TAG, "Client disconnected, exiting...");

            uiHandler.post(() -> {
                android.widget.Toast.makeText(context, "连接已断开，应用已退出", android.widget.Toast.LENGTH_LONG).show();
            });

            // 发送连接断开广播
            sIsConnected = false;
            Intent intent = new Intent(OverlayService.ACTION_CLIENT_CONNECTION_STATUS);
            intent.putExtra("connected", false);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);

            try {
                Thread.sleep(2000);
            } catch (Exception e) {
            }
            // android.os.Process.killProcess(android.os.Process.myPid());
            // System.exit(0);

        } catch (Exception e) {
            Log.e(TAG, "Client handler error", e);
        } finally {
            activeClientSocket = null;
        }
    }

    public void stopReceiving() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing server", e);
        }
    }
}
