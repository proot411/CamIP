package com.camip.app.server

/**
 * Generates the single-page HTML/CSS/JS dashboard served at GET /.
 * It polls /api/status and posts to /api/control with plain fetch() calls;
 * no build step or bundler is involved since the whole thing ships as one
 * self-contained HTML string.
 *
 * Layout: the live (MJPEG) view and the controls sit side by side on wide
 * screens and stack — controls below the view — on narrow ones, so the
 * preview stays visible while you operate the camera.
 */
object DashboardHtml {

    fun render(httpPort: Int): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>CamIP Dashboard</title>
<style>
  :root {
    --bg: #0e1117; --surface: #161b22; --border: #262c36;
    --text: #e6edf3; --muted: #8b949e; --accent: #00e5a0; --accent-dim: #0a3d2c;
    --blue: #2979ff; --danger: #ff6b6b;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; background: var(--bg); color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  }
  header {
    padding: 16px 20px; border-bottom: 1px solid var(--border);
    display: flex; align-items: center; justify-content: space-between;
  }
  header h1 { font-size: 18px; margin: 0; }
  .badge {
    padding: 4px 10px; border-radius: 999px; font-size: 12px; font-weight: 600;
    background: var(--accent-dim); color: var(--accent);
  }
  .badge.off { background: #3a1414; color: var(--danger); }
  main { max-width: 1120px; margin: 0 auto; padding: 20px; display: grid; gap: 16px; grid-template-columns: 1fr 1fr; }
  @media (max-width: 720px) { main { grid-template-columns: 1fr; } }
  .card {
    background: var(--surface); border: 1px solid var(--border); border-radius: 12px; padding: 16px;
  }
  .card h2 { font-size: 14px; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); margin: 0 0 12px; }
  .row { display: flex; justify-content: space-between; padding: 6px 0; font-size: 14px; border-bottom: 1px solid #1e242c; }
  .row:last-child { border-bottom: none; }
  .row span:first-child { color: var(--muted); }
  .full { grid-column: 1 / -1; }

  /* Live view + controls side by side; stacks (controls under the view)
     once there isn't room for both. */
  .live { grid-column: 1 / -1; display: grid; grid-template-columns: minmax(0, 1fr) 330px; gap: 18px; }
  @media (max-width: 900px) { .live { grid-template-columns: 1fr; } }
  img#preview { width: 100%; border-radius: 8px; background: #000; display: block; aspect-ratio: 16/9; object-fit: contain; }

  .controls-col { display: flex; flex-direction: column; gap: 12px; }
  .group { display: flex; flex-direction: column; gap: 8px; }
  .label { font-size: 12px; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); }
  .btnrow { display: flex; flex-wrap: wrap; gap: 8px; }
  .btnrow button { flex: 1 1 130px; }

  button, select, input {
    background: #1c2430; color: var(--text); border: 1px solid var(--border); border-radius: 8px;
    padding: 10px 14px; font-size: 14px;
  }
  button { cursor: pointer; }
  button:disabled { opacity: .45; cursor: not-allowed; }
  button.primary { background: var(--blue); border-color: var(--blue); color: #fff; font-weight: 600; }
  button.accent { background: var(--accent); border-color: var(--accent); color: #05201a; font-weight: 600; }
  button.selected { border-color: var(--accent); color: var(--accent); background: rgba(255,255,255,0.06); font-weight: 600; }
  select { width: 100%; }
  input[type=range] { width: 100%; padding: 0; }
  code { background: #1c2430; padding: 2px 6px; border-radius: 4px; }
  .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  .hint { color: var(--muted); font-size: 13px; line-height: 1.5; margin: 6px 0 0; }
  #action-msg { min-height: 18px; margin: 0; font-size: 13px; color: var(--danger); }
  .value { color: var(--text); font-weight: 600; font-size: 13px; }
</style>
</head>
<body>
<header>
  <h1>CamIP</h1>
  <span id="status-badge" class="badge off">checking...</span>
</header>
<main>

  <!-- Live view with its controls beside it (below it on narrow screens) -->
  <section class="card live">
    <div class="preview-col">
      <h2>Live view (MJPEG preview)</h2>
      <img id="preview" src="/mjpeg/live" alt="Live preview" />
      <p class="hint" id="source-hint">Source: camera</p>
    </div>

    <div class="controls-col">
      <h2>Controls</h2>

      <div class="group">
        <span class="label">Source</span>
        <div class="btnrow">
          <button id="src-camera" onclick="setSource(false)">Camera</button>
          <button id="src-screen" onclick="setSource(true)">Screen share</button>
        </div>
        <p class="hint">Screen share pops Android's capture prompt on the phone
          (the app must be open on-screen) and streams the screen instead of the camera.</p>
      </div>

      <div class="group">
        <span class="label">Camera</span>
        <div class="btnrow">
          <button data-camera-only onclick="ctrl('switch_camera')">Switch camera</button>
          <button data-camera-only id="flash-btn" onclick="toggleFlash()">Flash: Off</button>
          <button data-camera-only onclick="ctrl('focus')">Focus</button>
          <button data-camera-only id="stab-btn" onclick="toggleStabilization()">Stabilize: Off</button>
        </div>
        <span class="label">Zoom</span>
        <input data-camera-only id="zoom" type="range" min="1" max="5" step="0.1" value="1" oninput="setZoom(this.value)" />
      </div>

      <div class="group">
        <span class="label">Stream</span>
        <div class="btnrow">
          <button class="accent" onclick="ctrl('keyframe')">Force keyframe</button>
        </div>
        <select id="resolution" onchange="setResolution(this.value)">
          <option value="1920x1080">1920x1080 (Full HD)</option>
          <option value="1280x720">1280x720</option>
          <option value="960x540">960x540</option>
          <option value="640x480">640x480</option>
        </select>
        <select id="bitrate" onchange="setBitrate(this.value)">
          <option value="1500">1.5 Mbps</option>
          <option value="3000" selected>3 Mbps</option>
          <option value="5000">5 Mbps</option>
          <option value="8000">8 Mbps</option>
          <option value="10000">10 Mbps</option>
          <option value="12000">12 Mbps</option>
        </select>
        <span class="label">MJPEG preview resolution</span>
        <select id="mjpeg-resolution" onchange="setMjpegResolution(this.value)">
          <option value="320x180">320x180</option>
          <option value="480x270">480x270</option>
          <option value="640x360" selected>640x360</option>
          <option value="960x540">960x540</option>
          <option value="1280x720">1280x720</option>
        </select>
        <span class="label">MJPEG quality: <span class="value" id="mjpeg-quality-value">80</span></span>
        <input id="mjpeg-quality" type="range" min="30" max="100" step="1" value="80"
               oninput="setMjpegQuality(this.value)" />
      </div>

      <p id="action-msg"></p>
    </div>
  </section>

  <div class="card">
    <h2>Stream Status</h2>
    <div class="row"><span>Source</span><span id="s-source">-</span></div>
    <div class="row"><span>Resolution</span><span id="s-res">-</span></div>
    <div class="row"><span>Target FPS</span><span id="s-target-fps">-</span></div>
    <div class="row"><span>Measured FPS</span><span id="s-fps">-</span></div>
    <div class="row"><span>Bitrate</span><span id="s-bitrate">-</span></div>
    <div class="row"><span>Encoder</span><span id="s-encoder">-</span></div>
    <div class="row"><span>MJPEG</span><span id="s-mjpeg-quality">-</span></div>
    <div class="row"><span>Battery</span><span id="s-battery">-</span></div>
    <div class="row"><span>Camera</span><span id="s-camera">-</span></div>
    <div class="row"><span>Zoom / Flash</span><span id="s-zoomflash">-</span></div>
    <div class="row"><span>HTTP server</span><span id="s-http">-</span></div>
    <div class="row"><span>Encoded frames</span><span id="s-frames">-</span></div>
    <div class="row"><span>UDP packets sent</span><span id="s-udp-sent">-</span></div>
    <div class="row"><span>Last error</span><span id="s-error">none</span></div>
  </div>

  <div class="card">
    <h2>Endpoints</h2>
    <div class="row"><span>Dashboard port</span><span class="mono" id="s-dash-port">$httpPort</span></div>
    <div class="row"><span>Primary / OBS</span><span class="mono" id="s-udp-url">-</span></div>
    <div class="row"><span>Fallback MJPEG</span><span class="mono" id="s-mjpeg-url">-</span></div>
    <div class="row"><span>Dashboard</span><span class="mono" id="s-dash-url">-</span></div>
    <p class="hint">
      In OBS: Sources &rarr; Add &rarr; Media Source &rarr; uncheck "Local File" &rarr;
      set Input to the UDP URL above &rarr; Input Format: <code>mpegts</code>.
      Camera JSON API: <code>GET /api/camera</code>,
      <code>/api/camera/zoom?value=2</code>, <code>/api/camera/torch?value=1</code>,
      <code>/api/camera/switch</code>, <code>/api/camera/focus</code>,
      <code>/api/camera/exposure?value=-1</code>,
      <code>/api/camera/stabilization?value=1</code>.
    </p>
  </div>

  <div class="card full">
    <h2>Change UDP Destination</h2>
    <div style="display:flex; gap:8px;">
      <input id="dest-ip" placeholder="192.168.1.50" style="flex:1" />
      <input id="dest-port" placeholder="5000" style="width:90px" />
      <button class="primary" onclick="setDestination()">Apply</button>
    </div>
  </div>

</main>
<script>
let lastStatus = { flash: false, zoom: 1, screen_share: false, streaming: false };

async function refreshStatus() {
  try {
    const r = await fetch('/api/status', { cache: 'no-store' });
    const s = await r.json();
    lastStatus = s;
    document.getElementById('s-source').textContent = s.source === 'screen' ? 'Screen share' : 'Camera';
    document.getElementById('s-res').textContent = s.resolution;
    document.getElementById('s-target-fps').textContent = s.target_fps;
    document.getElementById('s-fps').textContent = s.measured_fps.toFixed(1);
    document.getElementById('s-bitrate').textContent = s.bitrate_kbps + ' kbps';
    document.getElementById('s-encoder').textContent = s.encoder_status;
    document.getElementById('s-mjpeg-quality').textContent =
      (s.mjpeg_resolution || '?') + ' @ q' + s.mjpeg_quality;
    document.getElementById('s-battery').textContent = s.battery_percent >= 0 ? s.battery_percent + '%' : '-';
    document.getElementById('s-camera').textContent = s.screen_share ? '-' : (s.active_camera === 'back' ? 'Back' : 'Front');
    document.getElementById('s-zoomflash').textContent = s.screen_share ? '-' :
      (Number(s.zoom).toFixed(1) + 'x / ' + (s.flash ? 'On' : 'Off'));
    document.getElementById('s-http').textContent =
      s.http_server_up ? 'Up (port ' + s.http_port + ')' : 'Down';
    document.getElementById('s-frames').textContent = s.total_encoded_frames;
    document.getElementById('s-udp-sent').textContent = s.udp_packets_sent;
    document.getElementById('s-error').textContent = s.last_error || 'none';

    const udpUrl = 'udp://0.0.0.0:' + s.udp_destination_port;
    document.getElementById('s-udp-url').textContent = udpUrl + '  (sending to ' + s.udp_destination_ip + ':' + s.udp_destination_port + ')';
    document.getElementById('s-mjpeg-url').textContent = 'http://' + s.phone_ip + ':' + s.http_port + '/mjpeg/live';
    document.getElementById('s-dash-url').textContent = 'http://' + s.phone_ip + ':' + s.http_port + '/';

    const badge = document.getElementById('status-badge');
    if (s.streaming) {
      badge.textContent = s.screen_share ? 'LIVE — SCREEN' : 'LIVE';
      badge.classList.remove('off');
    } else if (s.encoder_status === 'STARTING') {
      badge.textContent = 'STARTING'; badge.classList.remove('off');
    } else {
      badge.textContent = (s.encoder_status === 'ERROR' ? 'ERROR' : 'STOPPED');
      badge.classList.add('off');
    }

    // Mirror server-side camera state instead of tracking a local copy,
    // so the controls stay correct no matter who changed them.
    document.getElementById('flash-btn').textContent = 'Flash: ' + (s.flash ? 'On' : 'Off');
    document.getElementById('stab-btn').textContent = 'Stabilize: ' + (s.video_stabilization ? 'On' : 'Off');
    const zoomEl = document.getElementById('zoom');
    zoomEl.max = Math.max(Number(s.max_zoom) || 5, 1);
    if (document.activeElement !== zoomEl) zoomEl.value = s.zoom;

    // Source buttons: filled(.selected) marks the source that is actually
    // live and is disabled (you can't re-select it); the other one is the
    // switch action. STARTING flips both so a swap reads instantly.
    const screenActive = !!s.screen_share;
    const starting = s.encoder_status === 'STARTING';
    const srcCam = document.getElementById('src-camera');
    const srcScr = document.getElementById('src-screen');
    srcCam.classList.toggle('selected', !screenActive);
    srcCam.disabled = !screenActive || starting;
    srcCam.textContent = screenActive
      ? (starting ? 'Switching…' : 'Stop screen share')
      : 'Camera';
    srcScr.classList.toggle('selected', screenActive);
    srcScr.disabled = screenActive || starting;
    srcScr.textContent = screenActive
      ? (starting ? 'Starting…' : 'Sharing screen')
      : 'Screen share';
    document.getElementById('source-hint').textContent = screenActive
      ? 'Source: the phone\u2019s screen (screen share active)'
      : 'Source: camera' + (s.streaming ? '' : ' — start the stream to see it');
    // Camera-only controls can't do anything while the screen is the source.
    document.querySelectorAll('[data-camera-only]').forEach(el => { el.disabled = screenActive; });

    // Keep select/slider values in sync with the server.
    const resEl = document.getElementById('resolution');
    if (document.activeElement !== resEl) resEl.value = s.resolution;
    const qEl = document.getElementById('mjpeg-quality');
    if (document.activeElement !== qEl) {
      qEl.value = s.mjpeg_quality;
      document.getElementById('mjpeg-quality-value').textContent = s.mjpeg_quality;
    }
    // The reported size may be camera-negotiated and thus off-list; only
    // move the select when the value actually exists among its options.
    const mrEl = document.getElementById('mjpeg-resolution');
    if (document.activeElement !== mrEl && s.mjpeg_resolution &&
        Array.prototype.some.call(mrEl.options, o => o.value === s.mjpeg_resolution)) {
      mrEl.value = s.mjpeg_resolution;
    }
  } catch (e) {
    document.getElementById('status-badge').textContent = 'UNREACHABLE';
  }
}

async function ctrl(action, extraParams) {
  const params = new URLSearchParams({ action: action, ...(extraParams || {}) });
  const msg = document.getElementById('action-msg');
  try {
    const r = await fetch('/api/control?' + params.toString());
    const j = await r.json();
    if (j && j.ok === false) {
      msg.textContent = j.error || 'Request failed';
    } else {
      msg.textContent = '';
    }
  } catch (e) {
    msg.textContent = 'Request failed: ' + e;
  }
  refreshStatus();
}

function setSource(onScreen) { ctrl('screen_share', { value: onScreen ? '1' : '0' }); }
function toggleFlash() { ctrl('flash', { value: lastStatus.flash ? '0' : '1' }); }
function toggleStabilization() {
  ctrl('stabilization', { value: lastStatus.video_stabilization ? '0' : '1' });
}
let zoomTimer = null;
function setZoom(v) {
  lastStatus.zoom = v;
  clearTimeout(zoomTimer);
  zoomTimer = setTimeout(() => ctrl('zoom', { value: v }), 150);
}
let qualityTimer = null;
function setMjpegQuality(v) {
  document.getElementById('mjpeg-quality-value').textContent = v;
  clearTimeout(qualityTimer);
  qualityTimer = setTimeout(() => ctrl('mjpeg_quality', { value: v }), 200);
}
function setResolution(v) { ctrl('resolution', { value: v }); }
function setMjpegResolution(v) { ctrl('mjpeg_resolution', { value: v }); }
function setBitrate(v) { ctrl('bitrate', { value: v }); }
function setDestination() {
  const ip = document.getElementById('dest-ip').value.trim();
  const port = document.getElementById('dest-port').value.trim();
  if (!ip || !port) return;
  ctrl('udp_destination', { ip: ip, port: port });
}

refreshStatus();
setInterval(refreshStatus, 1000);
</script>
</body>
</html>
""".trimIndent()
}
