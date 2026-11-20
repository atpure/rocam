# rocam 개발 가이드 및 프로젝트 규칙

이 문서는 `rocam` 프로젝트에서 작업하는 모든 개발자 및 AI 에이전트를 위한 가이드입니다.

---

## 📚 OpenSpec 문서 체계

본 프로젝트의 모든 설계, 아키텍처, 구현 명세는 `openspec/specs/` 내의 **OpenSpec Markdown 문서**를 단일 진실 공급원(Single Source of Truth)으로 관리합니다.

1. `openspec/specs/http-server/spec.md`: 경량 임베디드 HTTP 서버 (포트 8080 동적 자동 증가 할당, CORS 완벽 지원, POST/GET API, 웹 대시보드)
2. `openspec/specs/camera-recording/spec.md`: CameraX + OpenGL ES 2.0 프레임 하드웨어 자막/박스 합성 및 MediaCodec H.264/AAC MP4 인코딩
3. `openspec/specs/foreground-service/spec.md`: Android 5.0(Lollipop, API 21) ~ Android 14+ 포그라운드 서비스 및 하위 호환 권한 관리
4. `openspec/specs/app-ui/spec.md`: 카메라 프리뷰 뷰, 실시간 자막/REC 인디케이터, 로컬 IP/Port 정보 설정 다이얼로그

---

## 🛠️ 핵심 개발 및 아키텍처 규칙

1. **최대 OS 하위 호환성 (`minSdk = 21`)**
   - 구형 스마트폰도 보조 카메라로 활용할 수 있도록 **Android 5.0 (API 21)** 부터 지원합니다.
   - 스토리지: Android 5.0~9.0에서는 `WRITE_EXTERNAL_STORAGE` 기반 공용 Movies 디렉토리에 저장하며, Android 10+에서는 MediaStore/Scoped Storage를 사용합니다.
2. **PC 브라우저 연동 및 HTTP API (POST / GET & CORS 지원)**
   - PC 웹 브라우저(`fetch`, `axios`)에서 교차 출처 리소스 공유(CORS) 제한 없이 원활하게 요청할 수 있도록 모든 응답에 CORS 헤더(`Access-Control-Allow-Origin: *`) 및 `OPTIONS` Preflight 처리가 기본 탑재되어 있습니다.
   - 엔드포인트:
     - `POST /update-text` (JSON: `{"text": "문구"}`) 또는 `GET /update-text?text=문구`
     - `POST /start` 또는 `GET /start`
     - `POST /stop` 또는 `GET /stop`
     - `GET /status` 또는 `POST /status`
     - `GET /` (내장 웹 제어 패널)
3. **OpenGL ES 기반 비디오 프레임 합성**
   - 단순 화면 뷰 오버레이가 아닌, **OpenGL ES 2.0 셰이더 및 EGL Input Surface**를 통해 인코딩되는 MP4 비디오 스트림 자체에 실시간 타임스탬프와 자막을 프레임 단위로 영구 합성합니다.
   - **좌측 상단 타임스탬프**: 배경 박스 없이 깔끔한 텍스트로 초 단위 실시간 시간 표출 (`YYYY-MM-DD HH:mm:ss`).
   - **상단 UI**: 상단 바/박스를 제거하여 화면을 넓게 확보하고, 우측 상단에 80% 투명도의 은은한 설정(톱니바퀴) 아이콘만 배치하여 탭 시 IP/Port 및 제어 가이드 다이얼로그 표출.

---

## 📱 APK 빌드 정보

- **빌드 명령어**: `cd /Users/macmini/git/rocam/apk && ./gradlew assembleRelease`
- **키스토어**: `app/rocam-release.jks` (Alias: `rocam_key`, PW: `rocam1234`)
- **출력 경로**: `app/build/outputs/apk/release/rocam-v1_0.apk`
