# Proposal: rocam - 스마트폰 직접 녹화 및 네트워크 제어 카메라 앱

## Why

동일 Wi-Fi 네트워크에 연결된 외부 기기(PC, 태블릿, 다른 스마트폰 등)에서 HTTP API 요청을 통해 원격으로 카메라 녹화를 제어하고, 실시간으로 자막 및 워터마크 텍스트를 비디오 프레임에 직접 하드코딩 합성하여 MP4 파일로 저장할 수 있는 스마트폰 카메라 애플리케이션(`rocam`)이 필요합니다.

이를 통해 방송/촬영 보조 장비, 스마트 스튜디오, 원격 자동 녹화 시스템 등에서 네트워크 명령만으로 텍스트 오버레이가 포함된 고화질 비디오를 안정적으로 녹화 및 저장할 수 있습니다. 구형 스마트폰도 서브 카메라로 폭넓게 활용할 수 있도록 **Android 5.0(Lollipop, API 21)부터 최신 Android 14+까지 광범위하게 지원**합니다.

## What Changes

- **신규 안드로이드 네이티브 앱 구조 구축 (`rocam`)**: Kotlin 기반 MVVM 아키텍처 및 Gradle 빌드 시스템 구축 (`minSdk = 21` Android 5.0+ 지원, Release APK 자동 빌드 지원)
- **임베디드 HTTP 서버 및 브라우저 연동 (`http-server`)**:
  - 앱 시작 시 경량 HTTP 서버(NanoHTTPD) 구동
  - 기본 8080 포트 바인딩 시도 및 포트 충돌 시 8081, 8082... 순으로 가용 포트 자동 검색 및 바인딩
  - 브라우저 CORS 완벽 지원 (`Access-Control-Allow-Origin: *`, `OPTIONS` Preflight)
  - 원격 제어 REST API 엔드포인트 제공 (`POST`/`GET /start`, `POST`/`GET /stop`, `POST`/`GET /update-text`, `GET`/`POST /status`)
  - PC 브라우저에서 바로 조작 가능한 내장 웹 대시보드 (`GET /`)
- **OpenGL ES 기반 비디오 프레임 텍스트/워터마크 합성 및 인코딩 (`camera-recording`)**:
  - CameraX + OpenGL ES (EGL / SurfaceTexture) + MediaCodec 파이프라인 구성
  - 단순 화면 레이아웃 오버레이가 아닌, 인코딩되는 비디오 프레임 자체에 배경 박스와 자막 텍스트 렌더링
  - `/update-text` 요청 수신 시 지연 없이 실시간 텍스트 텍스처 업데이트
  - H.264 비디오 및 AAC 오디오를 결합하여 표준 MP4 비디오 파일 저장 (Android 5.0~9.0 레거시 스토리지 및 Android 10+ MediaStore/Scoped Storage 지원)
- **백그라운드 지속 구동 및 버전별 권한 관리 (`foreground-service`)**:
  - 앱 백그라운드 전환 또는 화면 오프 시에도 HTTP 서버와 녹화가 중단되지 않도록 Notification 포그라운드 서비스 유지 (`NotificationCompat` 기반 구형 OS 호환)
  - Android 5.0~14+ 버전별 필수 런타임 권한 자동 요청 및 처리
- **앱 UI 및 정보 화면 (`app-ui`)**:
  - 메인 카메라 프리뷰 화면, 녹화 상태 인디케이터, 텍스트 오버레이 미리보기
  - 상단 설정(톱니바퀴) 아이콘 클릭 시 현재 할당된 로컬 IP:Port(예: `http://192.168.0.15:8080`) 및 API 사용 가이드 다이얼로그/화면 표출

## Capabilities

### New Capabilities
- `http-server`: NanoHTTPD 기반의 경량 임베디드 HTTP 서버, 동적 포트 할당, 로컬 IP 감지, CORS 지원, POST/GET REST 엔드포인트 (`/start`, `/stop`, `/update-text`, `/status`) 및 내장 웹 대시보드
- `camera-recording`: CameraX + OpenGL ES + MediaCodec / MediaMuxer 기반 하드웨어 가속 비디오 렌더링, 프레임 내 텍스트/박스 워터마크 합성, MP4 저장
- `foreground-service`: Android 5.0~14+ 백그라운드 및 화면 꺼짐 상태에서도 녹화 및 HTTP 서버가 중단되지 않도록 보장하는 포그라운드 서비스 및 권한 처리
- `app-ui`: 카메라 프리뷰, 실시간 상태 UI, 네트워크 연결 정보 및 가이드를 제공하는 설정 화면

### Modified Capabilities
<!-- No existing capabilities being modified -->

## Impact

- **신규 프로젝트**: `rocam` 디렉토리에 안드로이드 네이티브 프로젝트(Gradle, Kotlin, AndroidX, CameraX, NanoHTTPD) 및 APK 산출물 생성
- **시스템 권한**: `CAMERA`, `RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `WRITE_EXTERNAL_STORAGE` (API 21-28)
- **외부 연동**: 로컬 네트워크 내 HTTP 클라이언트(curl, PC 웹 브라우저 JavaScript, 외부 스크립트 등)를 통한 무선 제어
