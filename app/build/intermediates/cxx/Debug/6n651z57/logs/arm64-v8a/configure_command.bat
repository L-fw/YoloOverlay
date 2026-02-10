@echo off
"D:\\Android\\SDK\\cmake\\3.22.1\\bin\\cmake.exe" ^
  "-HD:\\pycharm\\YoloGPU\\YoloOverlay\\app\\src\\main\\cpp" ^
  "-DCMAKE_SYSTEM_NAME=Android" ^
  "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON" ^
  "-DCMAKE_SYSTEM_VERSION=26" ^
  "-DANDROID_PLATFORM=android-26" ^
  "-DANDROID_ABI=arm64-v8a" ^
  "-DCMAKE_ANDROID_ARCH_ABI=arm64-v8a" ^
  "-DANDROID_NDK=D:\\Android\\SDK\\ndk\\26.1.10909125" ^
  "-DCMAKE_ANDROID_NDK=D:\\Android\\SDK\\ndk\\26.1.10909125" ^
  "-DCMAKE_TOOLCHAIN_FILE=D:\\Android\\SDK\\ndk\\26.1.10909125\\build\\cmake\\android.toolchain.cmake" ^
  "-DCMAKE_MAKE_PROGRAM=D:\\Android\\SDK\\cmake\\3.22.1\\bin\\ninja.exe" ^
  "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=D:\\pycharm\\YoloGPU\\YoloOverlay\\app\\build\\intermediates\\cxx\\Debug\\6n651z57\\obj\\arm64-v8a" ^
  "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=D:\\pycharm\\YoloGPU\\YoloOverlay\\app\\build\\intermediates\\cxx\\Debug\\6n651z57\\obj\\arm64-v8a" ^
  "-DCMAKE_BUILD_TYPE=Debug" ^
  "-BD:\\pycharm\\YoloGPU\\YoloOverlay\\app\\.cxx\\Debug\\6n651z57\\arm64-v8a" ^
  -GNinja
