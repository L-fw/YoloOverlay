package com.yolo.overlay;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

/**
 * NCNN YOLOv8 推理接口
 * 
 * 使用 NCNN 框架在手机端本地运行 YOLO 推理
 * 支持 Vulkan GPU 加速
 */
public class YoloNcnn {

    static {
        System.loadLibrary("yolo_ncnn");
    }

    /**
     * 初始化 YOLO 模型
     * 
     * @param assetManager Android AssetManager
     * @param paramPath    模型参数文件路径 (assets 目录下)
     * @param binPath      模型权重文件路径 (assets 目录下)
     * @param useGpu       是否使用 Vulkan GPU 加速
     * @return true 如果初始化成功
     */
    public static native boolean init(AssetManager assetManager,
            String paramPath,
            String binPath,
            boolean useGpu);

    /**
     * 运行 YOLO 推理
     * 
     * @param bitmap     输入图像
     * @param targetSize 模型输入尺寸 (如 320)
     * @return 检测结果数组，格式: [count, x1, y1, w1, h1, conf1, classId1, ...]
     *         第一个元素是检测到的目标数量
     */
    public static native float[] detect(Bitmap bitmap, int targetSize);

    /**
     * 释放模型资源
     */
    public static native void release();

    /**
     * 检查是否支持 Vulkan GPU
     * 
     * @return true 如果设备支持 Vulkan
     */
    public static native boolean isGpuSupported();
}
