/**
 * NCNN YOLOv8 推理 JNI 实现
 * 
 * 使用 NCNN 框架在手机端运行 YOLO 推理
 */

#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <net.h>
#include <gpu.h>

#include <vector>
#include <algorithm>
#include <cmath>

#define LOG_TAG "YoloNcnn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 检测结果结构
struct Detection {
    float x, y, w, h;
    float confidence;
    int classId;
};

// 全局变量
static ncnn::Net* g_yolo = nullptr;
static int g_target_size = 416;  // 模型规格: [1,3,416,416]
static bool g_use_gpu = false;

// NMS 非极大值抑制
static void nms(std::vector<Detection>& detections, float threshold) {
    std::sort(detections.begin(), detections.end(), 
        [](const Detection& a, const Detection& b) {
            return a.confidence > b.confidence;
        });
    
    std::vector<bool> removed(detections.size(), false);
    
    for (size_t i = 0; i < detections.size(); i++) {
        if (removed[i]) continue;
        
        for (size_t j = i + 1; j < detections.size(); j++) {
            if (removed[j]) continue;
            
            // 计算 IoU
            float x1 = std::max(detections[i].x, detections[j].x);
            float y1 = std::max(detections[i].y, detections[j].y);
            float x2 = std::min(detections[i].x + detections[i].w, detections[j].x + detections[j].w);
            float y2 = std::min(detections[i].y + detections[i].h, detections[j].y + detections[j].h);
            
            float inter_w = std::max(0.0f, x2 - x1);
            float inter_h = std::max(0.0f, y2 - y1);
            float inter_area = inter_w * inter_h;
            
            float area_i = detections[i].w * detections[i].h;
            float area_j = detections[j].w * detections[j].h;
            float union_area = area_i + area_j - inter_area;
            
            float iou = inter_area / union_area;
            
            if (iou > threshold) {
                removed[j] = true;
            }
        }
    }
    
    std::vector<Detection> kept;
    for (size_t i = 0; i < detections.size(); i++) {
        if (!removed[i]) {
            kept.push_back(detections[i]);
        }
    }
    detections = kept;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_yolo_overlay_YoloNcnn_init(JNIEnv *env, jclass clazz,
    jobject assetManager, jstring paramPath, jstring binPath, jboolean useGpu) {
    
    if (g_yolo != nullptr) {
        delete g_yolo;
        g_yolo = nullptr;
    }
    
    g_yolo = new ncnn::Net();
    g_use_gpu = useGpu;
    
    // 配置选项
    g_yolo->opt.use_vulkan_compute = useGpu && ncnn::get_gpu_count() > 0;
    g_yolo->opt.num_threads = 4;
    g_yolo->opt.use_fp16_packed = true;
    g_yolo->opt.use_fp16_storage = true;
    g_yolo->opt.use_fp16_arithmetic = true;
    
    LOGI("NCNN init: use_gpu=%d, vulkan_enabled=%d, gpu_count=%d", 
         useGpu, g_yolo->opt.use_vulkan_compute, ncnn::get_gpu_count());
    
    // 加载模型
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    
    const char* param = env->GetStringUTFChars(paramPath, nullptr);
    const char* bin = env->GetStringUTFChars(binPath, nullptr);
    
    int ret_param = g_yolo->load_param(mgr, param);
    int ret_bin = g_yolo->load_model(mgr, bin);
    
    env->ReleaseStringUTFChars(paramPath, param);
    env->ReleaseStringUTFChars(binPath, bin);
    
    if (ret_param != 0 || ret_bin != 0) {
        LOGE("Failed to load model: param=%d, bin=%d", ret_param, ret_bin);
        delete g_yolo;
        g_yolo = nullptr;
        return JNI_FALSE;
    }
    
    LOGI("YOLO model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_yolo_overlay_YoloNcnn_detect(JNIEnv *env, jclass clazz,
    jobject bitmap, jint targetSize) {
    
    if (g_yolo == nullptr) {
        LOGE("Model not initialized");
        return nullptr;
    }
    
    g_target_size = targetSize;
    
    // 获取 Bitmap 信息
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("Failed to get bitmap info");
        return nullptr;
    }
    
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported bitmap format: %d", info.format);
        return nullptr;
    }
    
    // 锁定像素
    void* pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("Failed to lock pixels");
        return nullptr;
    }
    
    // 转换为 NCNN Mat
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(
        (const unsigned char*)pixels, 
        ncnn::Mat::PIXEL_RGBA2RGB,
        info.width, info.height,
        targetSize, targetSize
    );
    
    AndroidBitmap_unlockPixels(env, bitmap);
    
    // 归一化
    const float mean_vals[3] = {0.f, 0.f, 0.f};
    const float norm_vals[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
    in.substract_mean_normalize(mean_vals, norm_vals);
    
    // 推理
    ncnn::Extractor ex = g_yolo->create_extractor();
    ex.set_vulkan_compute(g_use_gpu && g_yolo->opt.use_vulkan_compute);
    
    ex.input("images", in);  // YOLOv8 输入名
    
    ncnn::Mat out;
    ex.extract("output0", out);  // YOLOv8 输出名
    
    // 后处理 - YOLOv8 输出格式: [batch, 84, 8400] (84 = 4 bbox + 80 classes)
    // 注意: 自定义模型可能只有 2 个类别
    std::vector<Detection> detections;
    
    const float conf_threshold = 0.25f;
    const float nms_threshold = 0.45f;
    
    // YOLOv8 输出需要转置: [84, 8400] -> [8400, 84]
    int num_proposals = out.w;
    int num_outputs = out.h;  // 4 (bbox) + num_classes
    int num_classes = num_outputs - 4;
    
    for (int i = 0; i < num_proposals; i++) {
        // 获取类别分数
        float max_score = 0;
        int max_class = 0;
        
        for (int c = 0; c < num_classes; c++) {
            float score = out.row(4 + c)[i];
            if (score > max_score) {
                max_score = score;
                max_class = c;
            }
        }
        
        if (max_score < conf_threshold) continue;
        
        // 获取边界框 (cx, cy, w, h)
        float cx = out.row(0)[i];
        float cy = out.row(1)[i];
        float w = out.row(2)[i];
        float h = out.row(3)[i];
        
        // 转换为 (x, y, w, h)
        float x = cx - w / 2;
        float y = cy - h / 2;
        
        // 转换回原始图像尺寸
        float scale_x = (float)info.width / targetSize;
        float scale_y = (float)info.height / targetSize;
        
        Detection det;
        det.x = x * scale_x;
        det.y = y * scale_y;
        det.w = w * scale_x;
        det.h = h * scale_y;
        det.confidence = max_score;
        det.classId = max_class;
        
        detections.push_back(det);
    }
    
    // NMS
    nms(detections, nms_threshold);
    
    // 构建返回数组
    // 格式: [count, x1, y1, w1, h1, conf1, classId1, ...]
    int count = std::min((int)detections.size(), 100);  // 最多 100 个检测
    int result_size = 1 + count * 6;
    
    jfloatArray result = env->NewFloatArray(result_size);
    if (result == nullptr) {
        return nullptr;
    }
    
    std::vector<float> data(result_size);
    data[0] = (float)count;
    
    for (int i = 0; i < count; i++) {
        data[1 + i * 6] = detections[i].x;
        data[1 + i * 6 + 1] = detections[i].y;
        data[1 + i * 6 + 2] = detections[i].w;
        data[1 + i * 6 + 3] = detections[i].h;
        data[1 + i * 6 + 4] = detections[i].confidence;
        data[1 + i * 6 + 5] = (float)detections[i].classId;
    }
    
    env->SetFloatArrayRegion(result, 0, result_size, data.data());
    
    return result;
}

JNIEXPORT void JNICALL
Java_com_yolo_overlay_YoloNcnn_release(JNIEnv *env, jclass clazz) {
    if (g_yolo != nullptr) {
        delete g_yolo;
        g_yolo = nullptr;
        LOGI("YOLO model released");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_yolo_overlay_YoloNcnn_isGpuSupported(JNIEnv *env, jclass clazz) {
    return ncnn::get_gpu_count() > 0 ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
