# http-server Specification

## Purpose
Provides an embedded HTTP server within the Android app to receive remote control commands over local Wi-Fi with dynamic port allocation, CORS support for browser integration, and status monitoring.

## Requirements

### Requirement: Dynamic Port Binding
The HTTP server SHALL bind to port 8080 by default. If port 8080 is already in use, it MUST sequentially increment the port number (8081, 8082, etc.) until an available port is successfully bound and started.

#### Scenario: Port 8080 available
- **WHEN** the server starts and port 8080 is not occupied
- **THEN** the server binds to port 8080 and begins listening for HTTP requests

#### Scenario: Port 8080 occupied
- **WHEN** the server starts and port 8080 is already in use by another process
- **THEN** the server attempts port 8081 and continues incrementing until a free port is bound

### Requirement: CORS and Browser Preflight Support
The HTTP server SHALL include CORS headers (`Access-Control-Allow-Origin: *`, `Access-Control-Allow-Methods: GET, POST, OPTIONS`, `Access-Control-Allow-Headers: *`) on all responses and handle `OPTIONS` preflight requests to allow cross-origin requests from PC web browsers.

#### Scenario: Preflight OPTIONS request from browser
- **WHEN** a web browser sends an `OPTIONS` preflight request to any endpoint
- **THEN** the server responds with 200 OK and appropriate CORS headers allowing the subsequent request

### Requirement: Start Recording Endpoint
The HTTP server SHALL support both `POST /start` and `GET /start` to trigger camera video recording.

#### Scenario: Successful start recording via POST or GET
- **WHEN** a client sends a `POST /start` or `GET /start` request while recording is idle
- **THEN** the system initiates video recording and returns a 200 OK JSON response with status `"recording"`

#### Scenario: Start recording when already recording
- **WHEN** a client sends a `POST /start` or `GET /start` request while recording is already in progress
- **THEN** the system maintains active recording and returns a 200 OK JSON response indicating recording is already active

### Requirement: Stop Recording Endpoint
The HTTP server SHALL support both `POST /stop` and `GET /stop` to finish camera video recording and finalize the video file.

#### Scenario: Successful stop recording via POST or GET
- **WHEN** a client sends a `POST /stop` or `GET /stop` request while recording is active
- **THEN** the system finishes video encoding, saves the MP4 file to storage, and returns a 200 OK JSON response with the saved file details

#### Scenario: Stop recording when idle
- **WHEN** a client sends a `POST /stop` or `GET /stop` request while recording is not active
- **THEN** the system returns a 200 OK JSON response indicating no recording was in progress

### Requirement: Update Subtitle Text Endpoint
The HTTP server SHALL support both `POST /update-text` (with JSON body `{"text": "..."}` or form data) and `GET /update-text?text={value}` to dynamically update the subtitle overlay text in real time.

#### Scenario: Update text via POST JSON body
- **WHEN** a client sends a `POST /update-text` request with JSON payload `{"text": "Scene 1"}`
- **THEN** the system immediately updates the internal subtitle text buffer to `"Scene 1"` and returns a 200 OK JSON response

#### Scenario: Update text via GET query parameter
- **WHEN** a client sends a `GET /update-text?text=Scene%201` request
- **THEN** the system immediately updates the internal subtitle text buffer to `"Scene 1"` and returns a 200 OK JSON response

#### Scenario: Update text with empty payload
- **WHEN** a client sends an update request with an empty text string or empty body
- **THEN** the system clears the overlay text and returns a 200 OK JSON response

### Requirement: Query System Status Endpoint
The HTTP server SHALL support both `GET /status` and `POST /status` to report the current recording state, current overlay text, active port, and recording duration.

#### Scenario: Query status
- **WHEN** a client sends a `GET /status` or `POST /status` request
- **THEN** the system returns a 200 OK JSON response containing `isRecording`, `currentText`, `serverPort`, and `durationSeconds`

### Requirement: Built-in Web Control UI
The HTTP server SHALL serve a lightweight HTML/JS web control panel at `GET /` so users can directly control the camera from any PC browser without installing additional tools.

#### Scenario: Access root URL from browser
- **WHEN** a user opens `http://<device-ip>:<port>/` in a web browser
- **THEN** the server returns an interactive web control page with Start/Stop buttons, Text input, and live status updates
