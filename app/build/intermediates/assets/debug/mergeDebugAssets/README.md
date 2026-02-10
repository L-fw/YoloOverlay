# NCNN 模型文件说明

此目录用于存放 NCNN 格式的 YOLO 模型文件。

## 所需文件

1. `yolov8n.param` - 模型结构文件
2. `yolov8n.bin` - 模型权重文件

## 模型转换

### 方法一：使用 pnnx (推荐)

```bash
# 安装 pnnx
pip install pnnx

# 转换 ONNX 模型
pnnx yolov8n.onnx inputshape=[1,3,320,320]

# 生成的文件重命名为:
# yolov8n.ncnn.param -> yolov8n.param
# yolov8n.ncnn.bin -> yolov8n.bin
```

### 方法二：使用 onnx2ncnn

```bash
# 下载 onnx2ncnn 工具
# https://github.com/Tencent/ncnn/releases

# 转换
onnx2ncnn yolov8n.onnx yolov8n.param yolov8n.bin
```

## 模型建议

- 推荐使用 **320x320** 或 **256x256** 尺寸的模型
- 使用 FP16 量化版本以提高性能
- 确保模型是针对 2 类检测训练的 (person, head)

## NCNN 预编译库

还需要下载 NCNN 预编译库:

1. 访问 https://github.com/Tencent/ncnn/releases
2. 下载 `ncnn-YYYYMMDD-android-vulkan.zip`
3. 解压到 `app/src/main/jni/ncnn-android-vulkan/`

目录结构应为:
```
jni/
├── CMakeLists.txt
├── yolo_ncnn.cpp
└── ncnn-android-vulkan/
    ├── arm64-v8a/
    │   └── lib/
    │       └── cmake/
    │           └── ncnn/
    └── armeabi-v7a/
        └── ...
```
