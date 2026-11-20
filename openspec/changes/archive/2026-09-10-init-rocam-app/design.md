# Technical Design: rocam - 스마트폰 직접 녹화 및 네트워크 제어 카메라 앱

## Context

See [proposal.md](file:///Users/macmini/git/rocam/openspec/changes/init-rocam-app/proposal.md) for background and motivation.
The project requires an Android Kotlin application running on Android 5.0+ (minSdk 21, Lollipop) up to Android 14+ (targetSdk 34) with hardware video encoding (H.264/AAC), OpenGL ES 2.0 frame rendering with hardcoded subtitle text overlays, an embedded HTTP control server with automatic dynamic port binding, CORS support for browser integration, and a persistent foreground service with backward compatibility across all Android versions.

## Goals / Non-Goals

**Goals:**
- **최대 호환성 지원**: `minSdk = 21` (Android 5.0 Lollipop)부터 최신 Android 14+ (API 34)까지 지원.
- Provide an embedded HTTP server (NanoHTTPD) listening on port 8080 (or auto-incremented fallback port 8081+) that accepts both `POST` (JSON body / form) and `GET` for `/start`, `/stop`, `/update-text`, and `/status`.
- Support full CORS (`Access-Control-Allow-Origin: *`, `OPTIONS` preflight) so PC web browsers and JavaScript web apps (`fetch`, `axios`) can control the camera without cross-origin blocks.
- Provide a built-in interactive Web Control UI at `http://<ip>:<port>/` for convenient browser testing.
- Render camera video frames combined with a subtitle graphic box and text directly onto MediaCodec input Surface via OpenGL ES 2.0, producing MP4 files where subtitles are permanently burned into the video stream.
- Ensure zero-latency subtitle updates (<1 frame) when `/update-text` is called during active recording.
- Run as an Android Foreground Service with ongoing notification (with Android 5.0~14+ backward compatibility) so recording and HTTP server persist in background or screen-off conditions.
- Provide a modern, clean camera UI with a live preview, status indicators, and an Information dialog displaying the device's local IP address, active port, and API usage commands.
- Provide automated Gradle build configuration outputting release signed APK (`rocam-v1_0.apk`).

**Non-Goals:**
- Cloud video streaming or RTMP/WebRTC broadcasting (the app focuses on direct local MP4 recording and local Wi-Fi control).
- Multi-camera simultaneous recording or complex NLE video post-editing.

## Decisions

### 1. OS Compatibility: minSdk 21 (Android 5.0 Lollipop)
- **Decision**: Set `minSdk = 21`, `targetSdk = 34`, `compileSdk = 34`.
- **Rationale**:
  - CameraX, OpenGL ES 2.0, MediaCodec, and NanoHTTPD all run reliably starting from Android 5.0 (API 21).
  - Storage: Use legacy public storage for API 21-28 (`Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)`) and Scoped Storage / MediaStore for API 29+.
  - Notifications: Use `NotificationCompat` with NotificationChannel creation guarded by `Build.VERSION.SDK_INT >= Build.VERSION_CODES.O`.
  - Foreground Service Types: Declare types (`camera|microphone|specialUse`) with `tools:targetApi="34"` to comply with Android 14 without breaking older devices.

### 2. Embedded HTTP Server & Browser Integration (POST / GET + CORS)
- **Decision**: Use `org.nanohttpd:nanohttpd:2.3.1` with full CORS headers (`Access-Control-Allow-Origin: *`, `OPTIONS` handling) and support for:
  - `POST /update-text` with JSON body `{"text": "문구"}` or form-data
  - `GET /update-text?text=문구`
  - `POST /start` and `GET /start`
  - `POST /stop` and `GET /stop`
  - `GET /status` and `POST /status`
  - `GET /` serving an embedded web control UI.
- **Rationale**: Web browsers on PC require CORS headers to send `fetch()` or `axios()` POST requests across different origins/ports. Supporting both JSON POST and URL query GET ensures compatibility with web apps, Postman, curl, and browser extensions.

### 3. OpenGL ES Video & Watermark Pipeline
- **Decision**: Architecture with `SurfaceTexture` + `EGLCore` + `OpenGL ES 2.0` shader pipeline feeding two outputs: Preview `SurfaceView` and `MediaCodec` input surface.
- **Pipeline Architecture**:
  1. CameraX `Preview` provides frame stream to an OpenGL `SurfaceTexture` (GL_TEXTURE_EXTERNAL_OES).
  2. For each frame, OpenGL ES renders:
     - Pass 1: Camera OES texture transformed with CameraX display matrix.
     - Pass 2: Semi-transparent rounded banner box at bottom/top of the frame.
     - Pass 3: Dynamic 2D text texture generated via Android `Bitmap` + `Canvas.drawText()`.
  3. The composed EGL context renders to:
     - On-screen Window Surface (`SurfaceView` for display).
     - Encoder Input Surface (`MediaCodec.createInputSurface()` for MP4 recording).
- **Rationale**: Frame-level synthesis guarantees that the recorded MP4 file contains the burned-in subtitles regardless of external players, aspect ratios, or playback platforms.

### 4. Audio & Video Synchronization with MediaCodec and MediaMuxer
- **Decision**: Video encoded with `MediaCodec` (`video/avc`, H.264, baseline profile for universal hardware playback) and Audio captured via `AudioRecord` and encoded with `MediaCodec` (`audio/mp4a-latm`, AAC). Both tracks muxed into a single `.mp4` container via Android `MediaMuxer`.
- **Rationale**: Gives full low-level control over frame presentation timestamps (PTS), guaranteeing synchronized A/V playback and clean container finalization.

### 5. Foreground Service & Lifecycle
- **Decision**: `RocamService` running as a foreground service with `NotificationCompat`.
- **Rationale**: Keeps process alive across all Android versions (5.0 through 14+).

### 6. Project Directory & APK Packaging
- **Decision**: Android project structured in `rocam/apk/` with Gradle wrapper, release keystore, and task producing `app/build/outputs/apk/release/rocam-v1_0.apk`.
- **Rationale**: Aligns with the project standard established in `meditime`.

## Risks / Trade-offs

- **[Risk] Pre-Android 10 Storage Permissions vs Android 10+ Scoped Storage**:
  - **Mitigation**: Request `WRITE_EXTERNAL_STORAGE` at runtime on Android 9 and below, and use MediaStore API on Android 10+.
- **[Risk] Browser CORS blocking**: PC web browsers block cross-origin requests by default.
  - **Mitigation**: Handle `OPTIONS` preflight requests and inject `Access-Control-Allow-Origin: *`, `Access-Control-Allow-Headers: *`, `Access-Control-Allow-Methods: GET, POST, OPTIONS` in all HTTP responses.
- **[Risk] Device Orientation & Camera Sensor Matrix**: Camera sensors have native orientations (e.g. 90 or 270 degrees).
  - **Mitigation**: Apply the `SurfaceTexture.getTransformMatrix()` matrix directly in the vertex shader to ensure upright video orientation.
- **[Risk] Port 8080 Collision with other LAN services**:
  - **Mitigation**: Dynamic auto-incrementing port binding checks sequential ports up to 8090, notifying UI and notification immediately with the active port.
