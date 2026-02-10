# FPS等于0
- taskkill /f /im adb.exe
- adb forward --remove-all
- adb forward tcp:19888 tcp:19888 

# 安装指令
- adb install D:\pycharm\YoloGPU\YoloOverlay\app\build\outputs\apk\debug\app-debug.apk 