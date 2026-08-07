// J.A.R.V.I.S. dashboard client — WebSocket bridge to the FastAPI backend,
// HUD gauges, chat log, and browser-mic push-to-talk.

const GAUGE_RADIUS = 30;
const GAUGE_CIRCUMFERENCE = 2 * Math.PI * GAUGE_RADIUS;

const els = {
  connPill: document.getElementById("conn-pill"),
  modelTag: document.getElementById("model-tag"),
  clock: document.getElementById("clock"),
  coreWrap: document.getElementById("core-wrap"),
  coreState: document.getElementById("core-state"),
  coreWave: document.getElementById("core-wave"),
  chatLog: document.getElementById("chat-log"),
  toolLog: document.getElementById("tool-log"),
  chatForm: document.getElementById("chat-form"),
  chatInput: document.getElementById("chat-input"),
  micBtn: document.getElementById("mic-btn"),
  gpuName: document.getElementById("gpu-name"),
};

// ---------------------------------------------------------------------
// Clock
// ---------------------------------------------------------------------
function tickClock() {
  const now = new Date();
  els.clock.textContent = now.toLocaleTimeString([], { hour12: false });
}
setInterval(tickClock, 1000);
tickClock();

// ---------------------------------------------------------------------
// Gauges
// ---------------------------------------------------------------------
function initGauge(id) {
  const root = document.getElementById(id);
  const fill = root.querySelector(".gauge-fill");
  fill.style.strokeDasharray = `${GAUGE_CIRCUMFERENCE}`;
  fill.style.strokeDashoffset = `${GAUGE_CIRCUMFERENCE}`;
  return {
    fill,
    pct: root.querySelector(".gauge-percent-text"),
    detail: root.querySelector(".gauge-detail"),
  };
}

const gauges = {
  cpu: initGauge("gauge-cpu"),
  ram: initGauge("gauge-ram"),
  gpu: initGauge("gauge-gpu"),
  vram: initGauge("gauge-vram"),
};

function setGauge(g, percent, detailText) {
  const p = Math.max(0, Math.min(100, percent ?? 0));
  const offset = GAUGE_CIRCUMFERENCE * (1 - p / 100);
  g.fill.style.strokeDashoffset = String(offset);
  g.fill.classList.toggle("warn", p >= 70 && p < 90);
  g.fill.classList.toggle("danger", p >= 90);
  g.pct.textContent = `${Math.round(p)}%`;
  if (detailText !== undefined) g.detail.textContent = detailText;
}

function applySystemStats(msg) {
  if (msg.cpu) {
    setGauge(gauges.cpu, msg.cpu.percent, "load");
  }
  if (msg.ram) {
    setGauge(gauges.ram, msg.ram.percent, `${msg.ram.used_gb}/${msg.ram.total_gb} GB`);
  }
  if (msg.gpu) {
    setGauge(gauges.gpu, msg.gpu.percent, `${msg.gpu.temp_c}°C`);
    const vramPct = (msg.gpu.vram_used_gb / msg.gpu.vram_total_gb) * 100;
    setGauge(gauges.vram, vramPct, `${msg.gpu.vram_used_gb}/${msg.gpu.vram_total_gb} GB`);
    els.gpuName.textContent = `GPU: ${msg.gpu.name}`;
  } else {
    els.gpuName.textContent = "GPU: nvidia-smi not found";
  }
}

// ---------------------------------------------------------------------
// Core state
// ---------------------------------------------------------------------
const STATE_LABELS = {
  idle: "STANDBY",
  thinking: "PROCESSING",
  speaking: "RESPONDING",
  listening: "LISTENING",
};

function setCoreState(state) {
  els.coreWrap.className = `core-wrap state-${state}`;
  els.coreState.textContent = STATE_LABELS[state] || state.toUpperCase();
  els.coreWave.classList.toggle("active", state === "speaking" || state === "listening");
}
setCoreState("idle");

// ---------------------------------------------------------------------
// Chat + activity log rendering
// ---------------------------------------------------------------------
function clearHint(container) {
  const hint = container.querySelector(".empty-hint");
  if (hint) hint.remove();
}

function addMessage(role, text) {
  clearHint(els.chatLog);
  const div = document.createElement("div");
  div.className = `msg ${role}`;
  const who = document.createElement("span");
  who.className = "who";
  who.textContent = role === "user" ? "YOU" : role === "error" ? "ERROR" : "JARVIS";
  div.appendChild(who);
  div.appendChild(document.createTextNode(text));
  els.chatLog.appendChild(div);
  els.chatLog.scrollTop = els.chatLog.scrollHeight;
}

function addToolEntry(name, args) {
  clearHint(els.toolLog);
  const div = document.createElement("div");
  div.className = "tool-entry";
  const time = new Date().toLocaleTimeString([], { hour12: false });
  div.innerHTML = `<span class="tool-time">${time}</span><span class="tool-name">${escapeHtml(name)}</span>` +
    `<span class="tool-args">${escapeHtml(JSON.stringify(args))}</span>`;
  els.toolLog.appendChild(div);
  els.toolLog.scrollTop = els.toolLog.scrollHeight;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[c]));
}

// ---------------------------------------------------------------------
// WebSocket
// ---------------------------------------------------------------------
let ws;
let reconnectDelay = 1000;

function connect() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  ws = new WebSocket(`${proto}://${location.host}/ws`);

  ws.onopen = () => {
    els.connPill.textContent = "ONLINE";
    els.connPill.className = "pill online";
    reconnectDelay = 1000;
  };

  ws.onclose = () => {
    els.connPill.textContent = "OFFLINE";
    els.connPill.className = "pill offline";
    setCoreState("idle");
    setTimeout(connect, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 1.6, 15000);
  };

  ws.onerror = () => ws.close();

  ws.onmessage = (event) => {
    let msg;
    try {
      msg = JSON.parse(event.data);
    } catch {
      return;
    }
    handleMessage(msg);
  };
}

function handleMessage(msg) {
  switch (msg.type) {
    case "user_message":
      addMessage("user", msg.text);
      break;
    case "assistant_message":
      addMessage("assistant", msg.text);
      break;
    case "error":
      addMessage("error", msg.text);
      break;
    case "tool_call":
      addToolEntry(msg.name, msg.args);
      break;
    case "status":
      setCoreState(msg.state);
      break;
    case "system_stats":
      applySystemStats(msg);
      break;
  }
}

connect();

// ---------------------------------------------------------------------
// Chat input
// ---------------------------------------------------------------------
els.chatForm.addEventListener("submit", (e) => {
  e.preventDefault();
  const text = els.chatInput.value.trim();
  if (!text || ws.readyState !== WebSocket.OPEN) return;
  ws.send(JSON.stringify({ type: "user_message", text }));
  els.chatInput.value = "";
});

// ---------------------------------------------------------------------
// Browser mic push-to-talk -> POST /api/voice
// ---------------------------------------------------------------------
let mediaRecorder = null;
let audioChunks = [];
let isRecording = false;

async function toggleRecording() {
  if (!isRecording) {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      mediaRecorder = new MediaRecorder(stream);
      audioChunks = [];
      mediaRecorder.ondataavailable = (e) => audioChunks.push(e.data);
      mediaRecorder.onstop = onRecordingStop;
      mediaRecorder.start();
      isRecording = true;
      els.micBtn.classList.add("recording");
      setCoreState("listening");
    } catch (err) {
      addMessage("error", `Microphone access failed: ${err.message}`);
    }
  } else {
    mediaRecorder.stop();
    mediaRecorder.stream.getTracks().forEach((t) => t.stop());
    isRecording = false;
    els.micBtn.classList.remove("recording");
    setCoreState("thinking");
  }
}

async function onRecordingStop() {
  const blob = new Blob(audioChunks, { type: "audio/webm" });
  const form = new FormData();
  form.append("file", blob, "clip.webm");

  try {
    const res = await fetch("/api/voice", { method: "POST", body: form });
    const data = await res.json();
    if (data.error) {
      addMessage("error", data.error);
      setCoreState("idle");
    }
    // On success the server broadcasts user_message/assistant_message/status
    // over the WebSocket, so no further action needed here.
  } catch (err) {
    addMessage("error", `Upload failed: ${err.message}`);
    setCoreState("idle");
  }
}

els.micBtn.addEventListener("click", toggleRecording);

// ---------------------------------------------------------------------
// Header status info
// ---------------------------------------------------------------------
fetch("/api/status")
  .then((r) => r.json())
  .then((data) => {
    els.modelTag.textContent = `model: ${data.model}`;
  })
  .catch(() => {});

fetch("/api/history")
  .then((r) => r.json())
  .then((rows) => {
    rows.forEach((m) => addMessage(m.role, m.content));
  })
  .catch(() => {});
