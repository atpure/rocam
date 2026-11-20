# foreground-service Specification

## Purpose
Manages the Android Foreground Service lifecycle and runtime permission handling across Android 5.0 (API 21) up to Android 14+ (API 34) to guarantee continuous background server operation and uninterrupted video recording.

## Requirements

### Requirement: Persistent Foreground Service Execution
The application SHALL execute a Foreground Service with an ongoing Notification while the HTTP server is running or while recording is active, supporting backward-compatible notification display on Android 5.0 (API 21) through Android 14+ (API 34).

#### Scenario: App sent to background while recording
- **WHEN** the user minimizes the application or turns off the screen during active recording
- **THEN** the Foreground Service continues processing camera frames, server HTTP requests, and video encoding without interruption

#### Scenario: Notification display across OS versions
- **WHEN** the Foreground Service is started on any supported Android version (API 21+)
- **THEN** a system notification is posted showing the current server URL (`http://<ip>:<port>`) and recording status

### Requirement: Multi-Version Runtime Permission Management
The application SHALL dynamically check and request appropriate permissions based on the active Android OS version (`minSdk = 21`).

#### Scenario: First launch on Android 6.0 ~ 9.0
- **WHEN** launched on Android 6.0 to 9.0 (API 23-28)
- **THEN** the app requests `CAMERA`, `RECORD_AUDIO`, and `WRITE_EXTERNAL_STORAGE` permissions

#### Scenario: First launch on Android 10 ~ 14+
- **WHEN** launched on Android 10+ (API 29+)
- **THEN** the app requests `CAMERA`, `RECORD_AUDIO`, and `POST_NOTIFICATIONS` (Android 13+), using MediaStore for scoped video storage without requiring legacy storage permission
