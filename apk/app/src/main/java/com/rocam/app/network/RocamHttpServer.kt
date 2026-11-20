package com.rocam.app.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rocam.app.model.CameraControlBridge
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class RocamHttpServer(port: Int = 8080) : NanoHTTPD(port) {

    private val gson = Gson()
    var actualPort: Int = port
        private set

    companion object {
        private const val TAG = "RocamHttpServer"

        /**
         * Attempts to start NanoHTTPD starting from [startPort] and incrementing up to [maxPort]
         * if the port is already bound.
         */
        fun createAndStart(startPort: Int = 8080, maxPort: Int = 8090): RocamHttpServer {
            var currentPort = startPort
            while (currentPort <= maxPort) {
                try {
                    val server = RocamHttpServer(currentPort)
                    server.start(SOCKET_READ_TIMEOUT, false)
                    server.actualPort = currentPort
                    CameraControlBridge.serverPort = currentPort
                    Log.i(TAG, "Rocam HTTP Server successfully started on port $currentPort")
                    return server
                } catch (e: IOException) {
                    Log.w(TAG, "Port $currentPort is busy, trying port ${currentPort + 1}...")
                    currentPort++
                }
            }
            throw IOException("Unable to bind to any port in range $startPort..$maxPort")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // 1. Handle CORS Preflight OPTIONS requests
        if (method == Method.OPTIONS) {
            val res = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            addCorsHeaders(res)
            return res
        }

        val res: Response = try {
            when {
                // Root: Serve Web Control Dashboard
                uri == "/" || uri == "/index.html" -> serveDashboard()

                // GET or POST /start : Start video recording
                uri.equals("/start", ignoreCase = true) -> handleStart(session)

                // GET or POST /stop : Stop recording and save file
                uri.equals("/stop", ignoreCase = true) -> handleStop(session)

                // GET or POST /update-text : Update video subtitle text
                uri.equals("/update-text", ignoreCase = true) -> handleUpdateText(session)

                // GET or POST /zoom : Control camera zoom ratio (0.5x ~ 10x)
                uri.equals("/zoom", ignoreCase = true) -> handleZoom(session)

                // GET or POST /status : Query current recording & text status
                uri.equals("/status", ignoreCase = true) -> handleStatus(session)

                else -> {
                    val errorJson = mapOf("status" to "error", "message" to "Endpoint not found: $uri")
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", gson.toJson(errorJson))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request $uri", e)
            val errorJson = mapOf("status" to "error", "message" to (e.message ?: "Unknown error"))
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", gson.toJson(errorJson))
        }

        addCorsHeaders(res)
        return res
    }

    private fun readBodyAsString(session: IHTTPSession): String? {
        return try {
            val contentLengthStr = session.headers["content-length"]
                ?: session.headers["Content-Length"]
            val contentLength = contentLengthStr?.toIntOrNull() ?: 0
            if (contentLength > 0) {
                val buffer = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = session.inputStream.read(buffer, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                String(buffer, 0, totalRead, StandardCharsets.UTF_8)
            } else {
                val available = session.inputStream.available()
                if (available > 0) {
                    val buffer = ByteArray(available)
                    val read = session.inputStream.read(buffer, 0, available)
                    if (read > 0) String(buffer, 0, read, StandardCharsets.UTF_8) else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading request body directly from inputStream", e)
            null
        }
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun handleZoom(session: IHTTPSession): Response {
        var requestedRatio: Float? = null

        // 1. Try URL Query parameters (e.g. /zoom?ratio=2.0)
        val params = session.parameters
        if (params.containsKey("ratio") && !params["ratio"].isNullOrEmpty()) {
            requestedRatio = params["ratio"]?.firstOrNull()?.toFloatOrNull()
        }

        // Also check raw query string if not parsed
        if (requestedRatio == null && !session.queryParameterString.isNullOrBlank()) {
            val pairs = session.queryParameterString.split("&")
            for (pair in pairs) {
                val parts = pair.split("=")
                if (parts.size == 2 && parts[0] == "ratio") {
                    requestedRatio = parts[1].toFloatOrNull()
                    break
                }
            }
        }

        // 2. Try POST body if not found in query
        if (requestedRatio == null && session.method == Method.POST) {
            val rawBody = readBodyAsString(session)
            if (!rawBody.isNullOrBlank()) {
                try {
                    val jsonElement = JsonParser.parseString(rawBody)
                    if (jsonElement.isJsonObject) {
                        val jsonObj = jsonElement.asJsonObject
                        if (jsonObj.has("ratio")) {
                            requestedRatio = jsonObj.get("ratio").asFloat
                        }
                    }
                } catch (e: Exception) {
                    // Try form-urlencoded or plain float
                    val pairs = rawBody.split("&")
                    for (pair in pairs) {
                        val parts = pair.split("=")
                        if (parts.size == 2 && parts[0] == "ratio") {
                            requestedRatio = parts[1].toFloatOrNull()
                            break
                        }
                    }
                    if (requestedRatio == null) {
                        requestedRatio = rawBody.trim().toFloatOrNull()
                    }
                }
            }
        }

        val ratioToApply = requestedRatio ?: 1.0f
        val actualRatio = CameraControlBridge.setZoom(ratioToApply)
        val currentStatus = CameraControlBridge.getStatus()

        val responseMap = mapOf(
            "status" to "ok",
            "message" to "Zoom ratio set to ${String.format(Locale.US, "%.1fx", actualRatio)}",
            "zoomRatio" to actualRatio,
            "minZoomRatio" to currentStatus.minZoomRatio,
            "maxZoomRatio" to currentStatus.maxZoomRatio
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(responseMap))
    }

    private fun handleStart(session: IHTTPSession): Response {
        val currentStatus = CameraControlBridge.getStatus()
        return if (currentStatus.isRecording) {
            val responseMap = mapOf(
                "status" to "ok",
                "message" to "Recording already in progress",
                "isRecording" to true,
                "currentText" to currentStatus.currentText
            )
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(responseMap))
        } else {
            val success = CameraControlBridge.startRecording()
            val responseMap = mapOf(
                "status" to if (success) "ok" else "error",
                "message" to if (success) "Recording started" else "Failed to start recording",
                "isRecording" to success,
                "currentText" to currentStatus.currentText
            )
            newFixedLengthResponse(
                if (success) Response.Status.OK else Response.Status.INTERNAL_ERROR,
                "application/json; charset=utf-8",
                gson.toJson(responseMap)
            )
        }
    }

    private fun handleStop(session: IHTTPSession): Response {
        val currentStatus = CameraControlBridge.getStatus()
        return if (!currentStatus.isRecording) {
            val responseMap = mapOf(
                "status" to "ok",
                "message" to "No active recording to stop",
                "isRecording" to false
            )
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(responseMap))
        } else {
            val filePath = CameraControlBridge.stopRecording()
            val responseMap = mapOf(
                "status" to "ok",
                "message" to "Recording stopped",
                "isRecording" to false,
                "savedFilePath" to (filePath ?: "")
            )
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(responseMap))
        }
    }

    private fun handleUpdateText(session: IHTTPSession): Response {
        var newText: String? = null

        // 1. Try URL Query parameters first (e.g. /update-text?text=Hello)
        val params = session.parameters
        if (params.containsKey("text") && !params["text"].isNullOrEmpty()) {
            newText = params["text"]?.firstOrNull()
        }

        // Also check raw query parameter string with explicit UTF-8 decoding
        if (newText.isNullOrEmpty() && !session.queryParameterString.isNullOrBlank()) {
            try {
                val pairs = session.queryParameterString.split("&")
                for (pair in pairs) {
                    val idx = pair.indexOf('=')
                    if (idx > 0) {
                        val key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name())
                        if (key == "text") {
                            newText = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing query parameter string", e)
            }
        }

        // 2. Try POST body if not found in query
        if (newText == null && session.method == Method.POST) {
            val rawBody = readBodyAsString(session)
            if (!rawBody.isNullOrBlank()) {
                // Try JSON parsing
                try {
                    val jsonElement = JsonParser.parseString(rawBody)
                    if (jsonElement.isJsonObject) {
                        val jsonObj = jsonElement.asJsonObject
                        if (jsonObj.has("text")) {
                            newText = jsonObj.get("text").asString
                        }
                    }
                } catch (e: Exception) {
                    // Try form-urlencoded parsing
                    val pairs = rawBody.split("&")
                    for (pair in pairs) {
                        val idx = pair.indexOf('=')
                        if (idx > 0) {
                            val key = try { URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()) } catch (ex: Exception) { pair.substring(0, idx) }
                            if (key == "text") {
                                newText = try { URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name()) } catch (ex: Exception) { pair.substring(idx + 1) }
                                break
                            }
                        }
                    }
                }

                // If still null and body is plain text, use the body as text
                if (newText == null && rawBody.isNotBlank() && !rawBody.startsWith("{") && !rawBody.startsWith("[")) {
                    newText = rawBody.trim()
                }
            }
        }

        val textToApply = newText ?: ""
        CameraControlBridge.updateText(textToApply)

        val responseMap = mapOf(
            "status" to "ok",
            "message" to "Subtitle text updated",
            "currentText" to textToApply
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(responseMap))
    }

    private fun handleStatus(session: IHTTPSession): Response {
        val status = CameraControlBridge.getStatus()
        val responseMap = mapOf(
            "status" to "ok",
            "isRecording" to status.isRecording,
            "currentText" to status.currentText,
            "serverPort" to status.serverPort,
            "durationSeconds" to status.durationSeconds,
            "lastSavedFile" to (status.currentFile ?: ""),
            "localIp" to status.localIp,
            "zoomRatio" to status.zoomRatio,
            "minZoomRatio" to status.minZoomRatio,
            "maxZoomRatio" to status.maxZoomRatio
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(responseMap))
    }

    private fun serveDashboard(): Response {
        val ip = CameraControlBridge.localIp
        val port = actualPort
        val status = CameraControlBridge.getStatus()
        val escapedText = escapeHtml(status.currentText)

        val html = """
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Content-Type" content="text/html; charset=utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>RoCam - 원격 카메라 제어기</title>
    <style>
        :root {
            --bg-color: #0f172a;
            --card-bg: #1e293b;
            --accent: #38bdf8;
            --accent-hover: #0ea5e9;
            --danger: #ef4444;
            --danger-hover: #dc2626;
            --success: #22c55e;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
        body { background: var(--bg-color); color: var(--text-main); display: flex; justify-content: center; align-items: center; min-height: 100vh; padding: 20px; }
        .card { background: var(--card-bg); border-radius: 16px; padding: 28px; width: 100%; max-width: 480px; box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5), 0 8px 10px -6px rgba(0, 0, 0, 0.5); border: 1px solid #334155; }
        .header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; }
        .title { font-size: 1.5rem; font-weight: 700; color: #fff; display: flex; align-items: center; gap: 8px; }
        .ip-badge { background: #334155; padding: 4px 10px; border-radius: 8px; font-size: 0.85rem; color: var(--accent); font-family: monospace; }
        
        .status-box { background: #0f172a; border-radius: 12px; padding: 16px; margin-bottom: 24px; border: 1px solid #334155; }
        .status-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
        .status-row:last-child { margin-bottom: 0; }
        .status-label { color: var(--text-muted); font-size: 0.9rem; }
        .status-value { font-weight: 600; font-size: 0.95rem; }
        
        .rec-pill { display: inline-flex; align-items: center; gap: 6px; padding: 4px 12px; border-radius: 20px; font-size: 0.85rem; font-weight: 700; }
        .rec-active { background: rgba(239, 68, 68, 0.2); color: #f87171; border: 1px solid #ef4444; }
        .rec-idle { background: rgba(148, 163, 184, 0.2); color: #cbd5e1; border: 1px solid #64748b; }
        .rec-dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
        .rec-active .rec-dot { animation: pulse 1s infinite; }
        @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }

        .form-group { margin-bottom: 20px; }
        .form-label { display: block; font-size: 0.88rem; color: var(--text-muted); margin-bottom: 8px; font-weight: 500; }
        .input-group { display: flex; gap: 8px; }
        input[type="text"] { flex: 1; background: #0f172a; border: 1px solid #475569; color: #fff; padding: 12px 14px; border-radius: 10px; font-size: 1rem; outline: none; transition: border-color 0.2s; }
        input[type="text"]:focus { border-color: var(--accent); }
        
        button { cursor: pointer; border: none; font-weight: 600; border-radius: 10px; padding: 12px 18px; font-size: 0.95rem; transition: all 0.2s; }
        .btn-accent { background: var(--accent); color: #0f172a; }
        .btn-accent:hover { background: var(--accent-hover); }
        .btn-record { width: 100%; padding: 14px; font-size: 1.1rem; display: flex; justify-content: center; align-items: center; gap: 8px; margin-bottom: 12px; }
        .btn-start { background: var(--danger); color: #fff; }
        .btn-start:hover { background: var(--danger-hover); }
        .btn-stop { background: #475569; color: #fff; }
        .btn-stop:hover { background: #64748b; }

        .api-guide { background: #090d16; padding: 14px; border-radius: 10px; font-size: 0.8rem; color: var(--text-muted); line-height: 1.5; font-family: monospace; word-break: break-all; margin-top: 18px; }
        .toast { margin-top: 10px; font-size: 0.85rem; color: var(--success); text-align: center; min-height: 20px; }
    </style>
</head>
<body>
    <div class="card">
        <div class="header">
            <div class="title">RoCam</div>
            <div class="ip-badge" id="hostBadge">$ip:$port</div>
        </div>

        <div class="status-box">
            <div class="status-row">
                <span class="status-label">녹화 상태</span>
                <span id="recBadge" class="rec-pill rec-idle">
                    <span class="rec-dot"></span><span id="recText">대기 중 (IDLE)</span>
                </span>
            </div>
            <div class="status-row" style="margin-top: 10px;">
                <span class="status-label">현재 자막 문구</span>
                <span id="currentOverlayText" class="status-value" style="color: var(--accent);">$escapedText</span>
            </div>
            <div class="status-row" style="margin-top: 10px;">
                <span class="status-label">현재 줌 배율</span>
                <span id="zoomValue" class="status-value" style="color: var(--accent);">${String.format(Locale.US, "%.1fx", status.zoomRatio)}</span>
            </div>
            <div class="status-row" style="margin-top: 10px;">
                <span class="status-label">녹화 시간</span>
                <span id="durationTimer" class="status-value">00:00</span>
            </div>
        </div>

        <div class="form-group">
            <label class="form-label" for="subtitleInput">자막/워터마크 텍스트 변경</label>
            <div class="input-group">
                <input type="text" id="subtitleInput" placeholder="화면에 합성할 문구 입력" value="$escapedText" onkeydown="if(event.key==='Enter') updateText()">
                <button class="btn-accent" onclick="updateText()">적용</button>
            </div>
        </div>

        <div class="form-group">
            <label class="form-label">카메라 줌 배율 (0.5x ~ 10x)</label>
            <div style="display: flex; gap: 8px; margin-bottom: 10px;">
                <button class="btn-accent" style="flex: 1;" onclick="setZoom(0.5)">0.5x</button>
                <button class="btn-accent" style="flex: 1;" onclick="setZoom(1.0)">1.0x</button>
                <button class="btn-accent" style="flex: 1;" onclick="setZoom(2.0)">2.0x</button>
            </div>
            <div style="display: flex; align-items: center; gap: 10px;">
                <input type="range" id="zoomSlider" min="0.5" max="10.0" step="0.1" value="${status.zoomRatio}" style="flex: 1;" oninput="onSliderChange(this.value)">
                <span id="sliderValue" style="min-width: 45px; font-weight: bold; font-family: monospace;">${String.format(Locale.US, "%.1fx", status.zoomRatio)}</span>
            </div>
        </div>

        <button id="btnRecToggle" class="btn-record btn-start" onclick="toggleRecord()">
            ⏺ 녹화 시작 (Start)
        </button>

        <div class="toast" id="toastMsg"></div>

        <div class="api-guide">
            <strong>HTTP REST API (POST/GET)</strong><br>
            • POST /update-text (JSON: {"text":"문구"}) 또는 GET /update-text?text=문구<br>
            • POST /zoom (JSON: {"ratio":2.0}) 또는 GET /zoom?ratio=0.5<br>
            • POST /start | POST /stop<br>
            • GET /status
        </div>
    </div>

    <script>
        let isRecording = ${status.isRecording};
        let durationSec = ${status.durationSeconds};

        function showToast(msg) {
            const el = document.getElementById('toastMsg');
            el.innerText = msg;
            setTimeout(() => { if (el.innerText === msg) el.innerText = ''; }, 3000);
        }

        async function updateText() {
            const inputEl = document.getElementById('subtitleInput');
            const text = inputEl.value;
            try {
                const queryParam = encodeURIComponent(text);
                const res = await fetch('/update-text?text=' + queryParam, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json; charset=utf-8' },
                    body: JSON.stringify({ text: text })
                });
                const data = await res.json();
                document.getElementById('currentOverlayText').innerText = data.currentText !== undefined ? data.currentText : text;
                showToast('✓ 자막 문구가 실시간으로 변경되었습니다.');
            } catch (e) {
                showToast('❌ 문구 변경 실패: ' + e);
            }
        }

        async function setZoom(ratio) {
            try {
                const res = await fetch('/zoom', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ ratio: ratio })
                });
                const data = await res.json();
                const actual = Number(data.zoomRatio).toFixed(1) + 'x';
                document.getElementById('zoomValue').innerText = actual;
                document.getElementById('zoomSlider').value = data.zoomRatio;
                document.getElementById('sliderValue').innerText = actual;
                showToast('✓ 줌 배율이 ' + actual + '로 변경되었습니다.');
            } catch (e) {
                showToast('❌ 줌 변경 실패: ' + e);
            }
        }

        let sliderDebounce = null;
        function onSliderChange(val) {
            document.getElementById('sliderValue').innerText = Number(val).toFixed(1) + 'x';
            clearTimeout(sliderDebounce);
            sliderDebounce = setTimeout(() => {
                setZoom(parseFloat(val));
            }, 100);
        }

        async function toggleRecord() {
            const endpoint = isRecording ? '/stop' : '/start';
            try {
                const res = await fetch(endpoint, { method: 'POST' });
                const data = await res.json();
                fetchStatus();
                showToast(isRecording ? '✓ 녹화가 종료되었습니다.' : '✓ 녹화가 시작되었습니다.');
            } catch (e) {
                showToast('❌ 명령 전송 실패: ' + e);
            }
        }

        async function fetchStatus() {
            try {
                const res = await fetch('/status');
                const data = await res.json();
                isRecording = data.isRecording;
                durationSec = data.durationSeconds || 0;
                document.getElementById('currentOverlayText').innerText = data.currentText;

                if (data.zoomRatio) {
                    const zText = Number(data.zoomRatio).toFixed(1) + 'x';
                    document.getElementById('zoomValue').innerText = zText;
                }

                const recBadge = document.getElementById('recBadge');
                const recText = document.getElementById('recText');
                const btnRec = document.getElementById('btnRecToggle');

                if (isRecording) {
                    recBadge.className = 'rec-pill rec-active';
                    recText.innerText = '녹화 중 (REC)';
                    btnRec.className = 'btn-record btn-stop';
                    btnRec.innerText = '⏹ 녹화 종료 (Stop)';
                } else {
                    recBadge.className = 'rec-pill rec-idle';
                    recText.innerText = '대기 중 (IDLE)';
                    btnRec.className = 'btn-record btn-start';
                    btnRec.innerText = '⏺ 녹화 시작 (Start)';
                }

                const mins = String(Math.floor(durationSec / 60)).padStart(2, '0');
                const secs = String(durationSec % 60).padStart(2, '0');
                document.getElementById('durationTimer').innerText = mins + ':' + secs;
            } catch (e) {
                console.error(e);
            }
        }

        setInterval(fetchStatus, 1500);
    </script>
</body>
</html>
        """.trimIndent()

        val res = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        res.addHeader("Pragma", "no-cache")
        res.addHeader("Expires", "0")
        return res
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE")
        response.addHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Authorization")
        response.addHeader("Access-Control-Max-Age", "86400")
    }
}
