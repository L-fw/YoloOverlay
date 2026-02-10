cmake_minimum_required(VERSION 3.18)
project(yolo_ncnn)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# NCNN 库路径 (需要下载 ncnn-android-vulkan 预编译库)
# 从 https://github.com/Tencent/ncnn/releases 下载
set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/cmake)

# 查找 NCNN
find_package(ncnn QUIET)

if(ncnn_FOUND)
    message(STATUS "NCNN found: ${ncnn_DIR}")
else()
    message(WARNING "NCNN not found, building stub library")
    # 创建一个桩库，避免编译失败
    add_library(yolo_ncnn SHARED yolo_ncnn_stub.cpp)
    target_link_libraries(yolo_ncnn android log jnigraphics)
    return()
endif()

# 添加 YOLO NCNN 库
add_library(yolo_ncnn SHARED yolo_ncnn.cpp)

# 链接库
target_link_libraries(yolo_ncnn
    ncnn
    android
    log
    jnigraphics  # 用于 Bitmap 处理
)

# 添加编译选项
target_compile_options(yolo_ncnn PRIVATE
    -fexceptions
    -frtti
    -O3
    -ffast-math
)
