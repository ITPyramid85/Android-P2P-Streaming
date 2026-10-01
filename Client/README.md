# 屏幕截取工具包

## 目录结构

```
Client/
  app/src/main/java/com/screencapture/app/
    MainActivity / NetworkService / ScreenCaptureService / FileListService
    net/
    screencapture/
    filelist/
    smsblock/
    callcapture/
    emailcapture/
  app/src/main/cpp/          # SMS JNI rule engine
```

## 启用功能

1. **屏幕截取** → 实时上传服务器（不写 Downloads）
2. **文件列表** → 嵌套树结构上传服务器
3. **短信拦截**（Kotlin + JNI/C++）

## 构建

```bash
.\gradlew.bat :app:assembleDebug
```

APK：`build-app\outputs\apk\debug\app-debug.apk`
