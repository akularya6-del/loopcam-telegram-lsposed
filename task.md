# LoopCam Build Tasks

## Environment Bootstrap
- [x] brew install --cask android-commandlinetools ✅ /opt/homebrew/bin/sdkmanager
- [x] brew install openjdk@17 ✅ /opt/homebrew/opt/openjdk@17 (17.0.20)
- [x] Set JAVA_HOME to JDK 17
- [x] Accept SDK licenses ✅ All accepted
- [x] Install platform-tools, build-tools;34.0.0, platforms;android-34 ✅

## File Generation
- [x] settings.gradle.kts (root)
- [x] build.gradle.kts (root)
- [x] app/build.gradle.kts — compileOnly Xposed API ✅
- [x] gradle/wrapper/gradle-wrapper.properties — Gradle 8.6
- [x] gradlew (executable)
- [x] gradle.properties
- [x] local.properties — sdk.dir set
- [x] AndroidManifest.xml — Xposed meta-data, permissions ✅
- [x] assets/xposed_init — com.loopcam.xposed.MainHook ✅
- [x] res/xml/xposed_scope.xml — org.telegram.messenger + .web
- [x] res/layout/activity_main.xml — black bg, TextureView, SwitchMaterial
- [x] res/drawable/bg_status_pill.xml
- [x] res/values/strings.xml, colors.xml, themes.xml
- [x] MainActivity.kt — file picker, video cache IPC, toggle, pulse anim
- [x] VideoPreviewManager.kt — MediaPlayer looping on TextureView
- [x] xposed/MainHook.kt — package gate, prefs read, fallback path
- [x] xposed/VideoFrameInjector.kt — Camera2 surface swap + legacy fallback
- [x] proguard-rules.pro — 7 keep rules (4 mandated + 3 safety) ✅
- [x] README.md

## Quality Gate (PASSED)
- [x] ProGuard has all 4 mandatory -keep rules ✅
- [x] compileOnly used for Xposed API (verified grep) ✅
- [x] xposed_init contains correct class name ✅

## Build & Verification
- [x] ./gradlew assembleRelease — BUILD SUCCESSFUL in 57s ✅
- [x] aapt dump badging — all 4 xposed meta-data tags confirmed ✅
- [x] xposed_init verified: com.loopcam.xposed.MainHook ✅
- [x] APK path: /Users/racoon/Documents/TG APK/app/build/outputs/apk/release/app-release.apk (1.4MB) ✅
- [x] Source zip: /Users/racoon/Documents/LoopCam-source.zip (82KB) ✅
