/**
 * NCNN YOLOv8 推理 JNI 桩实现
 * 
 * 当 NCNN 库未安装时使用此桩实现
 * 提供空实现以避免编译失败
 */

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "YoloNcnn"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_yolo_overlay_YoloNcnn_init(JNIEnv *env, jclass clazz,
    jobject assetManager, jstring paramPath, jstring binPath, jboolean useGpu) {
    
    LOGW("NCNN library not available - using stub implementation");
    return JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_yolo_overlay_YoloNcnn_detect(JNIEnv *env, jclass clazz,
    jobject bitmap, jint targetSize) {
    
    LOGW("NCNN library not available - detect() returning null");
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_yolo_overlay_YoloNcnn_release(JNIEnv *env, jclass clazz) {
    // No-op
}

JNIEXPORT jboolean JNICALL
Java_com_yolo_overlay_YoloNcnn_isGpuSupported(JNIEnv *env, jclass clazz) {
    return JNI_FALSE;
}

} // extern "C"
