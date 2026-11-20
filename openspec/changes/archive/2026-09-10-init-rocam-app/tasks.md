# Tasks: rocam - 스마트폰 직접 녹화 및 네트워크 제어 카메라 앱

## 1. Project Setup & Gradle Build Configuration

- [x] 1.1 Create Android Gradle project structure in `rocam/apk` with Gradle wrapper, Kotlin DSL (`build.gradle.kts`, `app/build.gradle.kts`, `settings.gradle.kts`, `local.properties`) and CameraX, NanoHTTPD, Coroutines dependencies. Verify Gradle sync succeeds.
- [x] 1.2 Generate release keystore `rocam-release.jks` and configure signingConfig in `app/build.gradle.kts` to output `rocam-v${version}.apk`. Verify build task setup.

## 2. Embedded HTTP Server & Network Engine

- [x] 2.1 Implement `NetworkUtils` to detect active Wi-Fi IPv4 address on the Android device.
- [x] 2.2 Implement `RocamHttpServer` using NanoHTTPD with dynamic port search (8080..8090 fallback), CORS preflight headers, and REST endpoints (supporting both POST/JSON and GET for `/start`, `/stop`, `/update-text`, `/status`, plus a web dashboard at `/`).
- [x] 2.3 Implement state bridge (`CameraControlBridge` / `StateFlow`) between HTTP server and camera/recording manager.

## 3. OpenGL ES 2.0 & MediaCodec Hardware Overlay Engine

- [x] 3.1 Implement `EGLCore` and `EglSurface` abstractions for EGL14 display, context, and surface lifecycle management.
- [x] 3.2 Implement `CameraTextureRenderer` (GL_TEXTURE_EXTERNAL_OES shader) to render CameraX video stream with proper rotation/aspect transformation.
- [x] 3.3 Implement `OverlayRenderer` to draw semi-transparent rounded banner box and dynamic text bitmap texture directly onto OpenGL frames.
- [x] 3.4 Implement `VideoAudioEncoder` combining `MediaCodec` (H.264 video, AAC audio) and `MediaMuxer` to generate synthesized MP4 files saved to public MediaStore/Scoped Storage.

## 4. Foreground Service & Android Permissions

- [x] 4.1 Implement `RocamService` Foreground Service managing HTTP server and recording session persistence with continuous system notification.
- [x] 4.2 Configure `AndroidManifest.xml` with permissions (`CAMERA`, `RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`) and hardware feature declarations.

## 5. User Interface & Settings Dialog

- [x] 5.1 Implement `MainActivity` layout with live camera preview, overlay banner preview, recording indicator (REC dot & timer), and top action bar.
- [x] 5.2 Implement Information / Settings modal showing the device's local IP address, active HTTP port, sample POST/GET `curl` and `fetch()` JavaScript snippets.
- [x] 5.3 Implement runtime permission request flow and link UI buttons to server and recording triggers.

## 6. Verification & Release APK Generation

- [x] 6.1 Execute `./gradlew assembleRelease` to compile Kotlin sources, link OpenGL/MediaCodec assets, and generate signed `rocam-v1_0.apk`.
- [x] 6.2 Verify APK generation in `app/build/outputs/apk/release/` and validate server route handling with POST / GET and CORS.
