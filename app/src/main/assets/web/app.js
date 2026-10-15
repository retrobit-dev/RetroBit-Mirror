const PIDS = { ls: 3, rs: 4, a: 5, b: 6, x: 7, y: 8, lb: 10, rb: 11, lt: 12, rt: 13, start: 14, select: 15, l3: 16, r3: 17, du: 18, dd: 19, dl: 20, dr: 21 };
const DEAD = 0.14;

function control(kind, id, label, x, y, extra = {}) {
  return { kind, id, label, x, y, ux: extra.ux ?? x, uy: extra.uy ?? y, r: extra.r ?? 0.1, mode: extra.mode ?? "hold", look: !!extra.look, gt: !!extra.gt, linked: extra.linked !== false };
}

function defaultProfiles() {
  return [
    { id: "touch", name: "Sentuh", controls: [] },
    {
      id: "moba", name: "MOBA", controls: [
        control("stick", "ls", "LS", 0.16, 0.74, { r: 0.11 }),
        control("button", "a", "A", 0.90, 0.74, { mode: "tap" }),
        control("button", "b", "B", 0.80, 0.84, { mode: "tap" }),
        control("button", "x", "X", 0.80, 0.58, { mode: "tap" }),
        control("button", "y", "Y", 0.70, 0.70, { mode: "tap" }),
        control("button", "lb", "LB", 0.16, 0.18),
        control("button", "rb", "RB", 0.84, 0.18),
        control("button", "lt", "GT L", 0.10, 0.42, { ux: 0.07, uy: 0.50, gt: true, linked: false }),
        control("button", "rt", "GT R", 0.90, 0.42, { ux: 0.93, uy: 0.50, gt: true, linked: false }),
        control("button", "start", "Start", 0.56, 0.08, { mode: "tap" }),
        control("button", "select", "Select", 0.44, 0.08, { mode: "tap" }),
        control("dpad", "dpad", "D", 0.16, 0.40, { r: 0.07 })
      ]
    },
    {
      id: "fps", name: "FPS", controls: [
        control("stick", "ls", "LS", 0.16, 0.72, { r: 0.11 }),
        control("stick", "rs", "RS", 0.58, 0.48, { ux: 0.82, uy: 0.62, r: 0.07, look: true, linked: false }),
        control("button", "a", "Lompat", 0.90, 0.46),
        control("button", "b", "Jongkok", 0.78, 0.46),
        control("button", "x", "Isi", 0.70, 0.78, { mode: "tap" }),
        control("button", "y", "Senjata", 0.90, 0.28, { mode: "tap" }),
        control("button", "lt", "GT L", 0.74, 0.70, { ux: 0.07, uy: 0.46, gt: true, linked: false }),
        control("button", "rt", "GT R", 0.88, 0.72, { ux: 0.93, uy: 0.46, gt: true, linked: false }),
        control("button", "lb", "LB", 0.16, 0.16),
        control("button", "rb", "RB", 0.84, 0.16),
        control("button", "start", "Start", 0.56, 0.08, { mode: "tap" }),
        control("button", "select", "Select", 0.44, 0.08, { mode: "tap" })
      ]
    },
    {
      id: "racing", name: "Balap", controls: [
        control("button", "dl", "Kiri", 0.12, 0.62),
        control("button", "dr", "Kanan", 0.28, 0.62),
        control("button", "a", "Rem", 0.78, 0.70),
        control("button", "rt", "GT R", 0.90, 0.55, { ux: 0.93, uy: 0.42, gt: true, linked: false }),
        control("button", "lt", "GT L", 0.10, 0.40, { ux: 0.07, uy: 0.42, gt: true, linked: false }),
        control("button", "b", "Nitro", 0.88, 0.78),
        control("button", "start", "Start", 0.56, 0.08, { mode: "tap" }),
        control("button", "select", "Select", 0.44, 0.08, { mode: "tap" })
      ]
    }
  ];
}

const state = {
  demo: true,
  token: null,
  ws: null,
  profiles: loadProfiles(),
  profileId: localStorage.getItem("rb.profile") || "moba",
  showPad: true,
  editing: false,
  calibrate: null,
  aspect: 20 / 9,
  injection: "demo",
  videoMode: "demo",
  pad: { lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0, buttons: {}, touchLs: false, touchRs: false },
  held: {},
  look: {},
  queue: [],
  flushTimer: 0,
  decoder: null,
  usingMjpeg: false,
  game: { x: 0.32, y: 0.62, aim: 0, shots: [], score: 0, flash: "" }
};

const $ = (id) => document.getElementById(id);
const screen = $("screen");
const view = $("view");
const ctx = view.getContext("2d");

function loadProfiles() {
  try {
    const raw = localStorage.getItem("rb.profiles");
    if (!raw) return defaultProfiles();
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) && parsed.length ? parsed : defaultProfiles();
  } catch (_) {
    return defaultProfiles();
  }
}

function saveProfiles() {
  localStorage.setItem("rb.profiles", JSON.stringify(state.profiles));
  localStorage.setItem("rb.profile", state.profileId);
}

function profile() {
  return state.profiles.find((p) => p.id === state.profileId) || state.profiles[0];
}

function setStatus(text) {
  $("status").textContent = text || "";
}

function showStage() {
  $("gate").hidden = true;
  $("stage").hidden = false;
  fit();
  renderPad();
  renderKeys();
  banner();
  if (navigator.wakeLock) navigator.wakeLock.request("screen").catch(() => {});
}

function leaveStage() {
  $("stage").hidden = true;
  $("gate").hidden = false;
  if (state.ws) state.ws.close();
  state.ws = null;
  $("stream").removeAttribute("src");
  state.demo = true;
  state.videoMode = "demo";
}

function banner() {
  const el = $("banner");
  if (state.calibrate) {
    el.hidden = false;
    el.textContent = "Ketuk posisi skill di layar untuk " + state.calibrate.toUpperCase();
    return;
  }
  if (state.demo) {
    el.hidden = false;
    el.textContent = "Mode demo — gerakan stick dan tombol GT di sini. Sambungan asli butuh APK di GT 30 Pro.";
    return;
  }
  if (state.injection === "none" || state.injection === "accessibility") {
    el.hidden = false;
    el.textContent = state.injection === "none"
      ? "Host belum punya metode input. Aktifkan Shizuku di GT 30 Pro."
      : "Host hanya aksesibilitas. Stick analog butuh Shizuku.";
    return;
  }
  el.hidden = true;
}

function fit() {
  document.documentElement.style.setProperty("--aspect", String(state.aspect));
  const bezel = $("bezel");
  const maxW = window.innerWidth - 16;
  const maxH = window.innerHeight - 108;
  let w = maxW;
  let h = w / state.aspect;
  if (h > maxH) {
    h = maxH;
    w = h * state.aspect;
  }
  bezel.style.width = w + "px";
  bezel.style.height = h + "px";
  view.width = Math.round(w * devicePixelRatio);
  view.height = Math.round(h * devicePixelRatio);
}

function renderPad() {
  const root = $("pad");
  root.innerHTML = "";
  const current = profile();
  $("cycle").textContent = current.name;
  if (!state.showPad || current.id === "touch") return;
  current.controls.forEach((c) => {
    const el = document.createElement("button");
    el.type = "button";
    el.className = "ctrl " + c.kind + (c.gt ? " gt" : "");
    el.style.left = (c.ux * 100) + "%";
    el.style.top = (c.uy * 100) + "%";
    el.dataset.id = c.id;
    if (c.kind === "stick") el.innerHTML = "<i></i><span>" + c.label + "</span>";
    else el.innerHTML = "<span>" + c.label + "</span>";
    el.addEventListener("pointerdown", (e) => ctrlDown(e, c, el));
    root.appendChild(el);
  });
}

function ctrlDown(e, control, el) {
  e.preventDefault();
  e.stopPropagation();
  el.setPointerCapture(e.pointerId);
  if (state.editing) {
    const move = (ev) => {
      const r = screen.getBoundingClientRect();
      control.ux = clamp((ev.clientX - r.left) / r.width, 0.04, 0.96);
      control.uy = clamp((ev.clientY - r.top) / r.height, 0.04, 0.96);
      if (control.linked) {
        control.x = control.ux;
        control.y = control.uy;
      }
      el.style.left = (control.ux * 100) + "%";
      el.style.top = (control.uy * 100) + "%";
    };
    const up = () => {
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
      saveProfiles();
    };
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
    return;
  }
  if (control.kind === "stick") {
    const move = (ev) => {
      const rect = el.getBoundingClientRect();
      const dx = (ev.clientX - (rect.left + rect.width / 2)) / (rect.width * 0.34);
      const dy = (ev.clientY - (rect.top + rect.height / 2)) / (rect.height * 0.34);
      const mag = Math.hypot(dx, dy) || 1;
      const scale = Math.min(1, mag);
      const x = dx / mag * scale;
      const y = dy / mag * scale;
      if (control.id === "rs") {
        state.pad.touchRs = true;
        state.pad.rx = x;
        state.pad.ry = y;
      } else {
        state.pad.touchLs = true;
        state.pad.lx = x;
        state.pad.ly = y;
      }
      const knob = el.querySelector("i");
      if (knob) knob.style.transform = "translate(" + (x * 28) + "px," + (y * 28) + "px)";
    };
    const up = () => {
      if (control.id === "rs") {
        state.pad.touchRs = false;
        state.pad.rx = 0;
        state.pad.ry = 0;
      } else {
        state.pad.touchLs = false;
        state.pad.lx = 0;
        state.pad.ly = 0;
      }
      const knob = el.querySelector("i");
      if (knob) knob.style.transform = "";
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
    };
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
    move(e);
    return;
  }
  const ids = control.kind === "dpad" ? [] : [control.id];
  if (control.kind === "dpad") {
    const rect = el.getBoundingClientRect();
    const nx = (e.clientX - rect.left) / rect.width - 0.5;
    const ny = (e.clientY - rect.top) / rect.height - 0.5;
    if (Math.abs(nx) > Math.abs(ny)) ids.push(nx < 0 ? "dl" : "dr");
    else ids.push(ny < 0 ? "du" : "dd");
  }
  ids.forEach((id) => { state.pad.buttons[id] = true; });
  el.classList.add("pressed");
  if (navigator.vibrate) navigator.vibrate(8);
  const up = () => {
    ids.forEach((id) => { state.pad.buttons[id] = false; });
    el.classList.remove("pressed");
    el.removeEventListener("pointerup", up);
    el.removeEventListener("pointercancel", up);
  };
  el.addEventListener("pointerup", up);
  el.addEventListener("pointercancel", up);
}

function norm(e) {
  const r = screen.getBoundingClientRect();
  return {
    x: clamp((e.clientX - r.left) / r.width, 0, 1),
    y: clamp((e.clientY - r.top) / r.height, 0, 1)
  };
}

function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }

screen.addEventListener("pointerdown", onScreen);
screen.addEventListener("pointermove", onScreen);
screen.addEventListener("pointerup", onScreen);
screen.addEventListener("pointercancel", onScreen);

const touchSlots = new Map();
function onScreen(e) {
  if (e.target.closest(".ctrl")) return;
  const p = norm(e);
  if (state.calibrate && e.type === "pointerup") {
    const c = profile().controls.find((item) => item.id === state.calibrate);
    if (c) {
      c.x = p.x;
      c.y = p.y;
      c.linked = false;
      saveProfiles();
    }
    state.calibrate = null;
    banner();
    return;
  }
  let id = touchSlots.get(e.pointerId);
  const action = e.type === "pointerdown" ? "down" : (e.type === "pointermove" ? "move" : "up");
  if (action === "down") {
    id = [0, 1, 2].find((slot) => ![...touchSlots.values()].includes(slot)) ?? 0;
    touchSlots.set(e.pointerId, id);
  }
  if (id == null) return;
  if (action === "up") touchSlots.delete(e.pointerId);
  emit({ type: "pointer", action, id, x: p.x, y: p.y }, action !== "move");
}

function emit(event, urgent) {
  if (!state.demo && state.ws && state.ws.readyState === 1) {
    state.queue.push(event);
    if (urgent) flush();
    else if (!state.flushTimer) state.flushTimer = setTimeout(flush, 8);
  }
}

function flush() {
  clearTimeout(state.flushTimer);
  state.flushTimer = 0;
  if (!state.queue.length || !state.ws || state.ws.readyState !== 1) {
    state.queue = [];
    return;
  }
  state.ws.send(JSON.stringify({ type: "input", events: state.queue.splice(0) }));
}

function sendNav(which) {
  emit({ type: "nav", which }, true);
}

function pressed(id) {
  if (state.pad.buttons[id]) return true;
  if (id === "lt" && state.pad.lt > 0.55) return true;
  if (id === "rt" && state.pad.rt > 0.55) return true;
  return false;
}

function pointer(id, action, x, y) {
  const was = state.held[id];
  if (action === "up") delete state.held[id];
  else state.held[id] = { x, y };
  if (!was && action !== "up") emit({ type: "pointer", action: "down", id, x, y }, true);
  else if (action === "up" && was) emit({ type: "pointer", action: "up", id, x, y }, true);
  else if (was && (Math.abs(was.x - x) > 0.002 || Math.abs(was.y - y) > 0.002)) {
    emit({ type: "pointer", action: "move", id, x, y }, false);
    state.held[id] = { x, y };
  }
}

function mapControls() {
  const current = profile();
  current.controls.forEach((c) => {
    if (c.kind === "stick") mapStick(c);
    else if (c.kind === "button") mapButton(c, pressed(c.id));
    else if (c.kind === "dpad") {
      [["du", 0, -1], ["dd", 0, 1], ["dl", -1, 0], ["dr", 1, 0]].forEach(([id, dx, dy]) => {
        mapButton({ id, x: c.x + dx * c.r, y: c.y + dy * c.r, mode: "hold" }, pressed(id));
      });
    }
  });
}

function mapStick(c) {
  const ax = c.id === "rs" ? state.pad.rx : state.pad.lx;
  const ay = c.id === "rs" ? state.pad.ry : state.pad.ly;
  const pid = c.id === "rs" ? PIDS.rs : PIDS.ls;
  const mag = Math.hypot(ax, ay);
  if (c.look) {
    const tracker = state.look[c.id] || (state.look[c.id] = { x: c.x, y: c.y, down: false });
    if (mag < DEAD) {
      if (tracker.down) pointer(pid, "up", tracker.x, tracker.y);
      tracker.down = false;
      tracker.x = c.x;
      tracker.y = c.y;
      return;
    }
    if (!tracker.down) {
      tracker.x = c.x;
      tracker.y = c.y;
      tracker.down = true;
      pointer(pid, "down", tracker.x, tracker.y);
    }
    tracker.x = clamp(tracker.x + ax * 0.035, c.x - c.r, c.x + c.r);
    tracker.y = clamp(tracker.y + ay * 0.035, c.y - c.r, c.y + c.r);
    if (Math.abs(tracker.x - c.x) > c.r * 0.9 || Math.abs(tracker.y - c.y) > c.r * 0.9) {
      pointer(pid, "up", tracker.x, tracker.y);
      tracker.x = c.x;
      tracker.y = c.y;
      pointer(pid, "down", tracker.x, tracker.y);
    } else pointer(pid, "move", tracker.x, tracker.y);
    return;
  }
  if (mag < DEAD) {
    if (state.held[pid]) pointer(pid, "up", c.x, c.y);
    return;
  }
  const adj = Math.min(1, (mag - DEAD) / (1 - DEAD));
  pointer(pid, state.held[pid] ? "move" : "down", clamp(c.x + (ax / mag) * adj * c.r, 0.01, 0.99), clamp(c.y + (ay / mag) * adj * c.r, 0.01, 0.99));
}

const wasButton = {};
function mapButton(c, isDown) {
  const pid = PIDS[c.id] || 9;
  const was = !!wasButton[c.id];
  if (isDown && !was) {
    pointer(pid, "down", c.x, c.y);
    if (c.mode === "tap") pointer(pid, "up", c.x, c.y);
  } else if (!isDown && was && c.mode !== "tap") {
    pointer(pid, "up", c.x, c.y);
  }
  wasButton[c.id] = isDown;
}

function pollHardware() {
  const pads = navigator.getGamepads ? navigator.getGamepads() : [];
  for (const gp of pads) {
    if (!gp) continue;
    if (!state.pad.touchLs) {
      state.pad.lx = gp.axes[0] || 0;
      state.pad.ly = gp.axes[1] || 0;
    }
    if (!state.pad.touchRs) {
      state.pad.rx = gp.axes[2] || 0;
      state.pad.ry = gp.axes[3] || 0;
    }
    state.pad.lt = gp.buttons[6] ? gp.buttons[6].value : 0;
    state.pad.rt = gp.buttons[7] ? gp.buttons[7].value : 0;
    const names = ["a", "b", "x", "y", "lb", "rb", "lt", "rt", "select", "start", "l3", "r3", "du", "dd", "dl", "dr"];
    names.forEach((id, i) => {
      if (gp.buttons[i]) state.pad.buttons[id] = gp.buttons[i].pressed || gp.buttons[i].value > 0.5;
    });
  }
}

function loop() {
  pollHardware();
  if (state.showPad) mapControls();
  draw();
  requestAnimationFrame(loop);
}

function draw() {
  if (state.videoMode === "webcodecs" || state.videoMode === "mjpeg") return;
  const w = view.width;
  const h = view.height;
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  const g = ctx.createLinearGradient(0, 0, w, h);
  g.addColorStop(0, "#14100d");
  g.addColorStop(1, "#0c1418");
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, w, h);
  ctx.strokeStyle = "rgba(255,255,255,0.05)";
  ctx.lineWidth = 1;
  for (let x = 0; x < w; x += 48) {
    ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke();
  }
  for (let y = 0; y < h; y += 48) {
    ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke();
  }
  const game = state.game;
  const speed = 0.008;
  game.x = clamp(game.x + state.pad.lx * speed, 0.08, 0.92);
  game.y = clamp(game.y + state.pad.ly * speed, 0.12, 0.88);
  if (Math.hypot(state.pad.rx, state.pad.ry) > 0.2) game.aim = Math.atan2(state.pad.ry, state.pad.rx);
  const firing = pressed("rt") || pressed("a");
  const shield = pressed("lt");
  if (firing && (!game.lastShot || performance.now() - game.lastShot > 140)) {
    game.lastShot = performance.now();
    game.shots.push({ x: game.x, y: game.y, a: game.aim, life: 1 });
    game.score += 1;
    game.flash = "TEMBAK";
  }
  if (pressed("b")) game.flash = "SKILL";
  if (pressed("x") || pressed("y")) game.flash = "ULTI";
  game.shots.forEach((s) => {
    s.x += Math.cos(s.a) * 0.02;
    s.y += Math.sin(s.a) * 0.02;
    s.life -= 0.02;
  });
  game.shots = game.shots.filter((s) => s.life > 0 && s.x > 0 && s.x < 1 && s.y > 0 && s.y < 1);
  ctx.fillStyle = "#ffc14a";
  game.shots.forEach((s) => {
    ctx.globalAlpha = s.life;
    ctx.beginPath();
    ctx.arc(s.x * w, s.y * h, 5 * devicePixelRatio, 0, Math.PI * 2);
    ctx.fill();
  });
  ctx.globalAlpha = 1;
  if (shield) {
    ctx.strokeStyle = "#3ee0ff";
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.arc(game.x * w, game.y * h, 28 * devicePixelRatio, 0, Math.PI * 2);
    ctx.stroke();
  }
  ctx.fillStyle = "#ff7a1a";
  ctx.beginPath();
  ctx.arc(game.x * w, game.y * h, 14 * devicePixelRatio, 0, Math.PI * 2);
  ctx.fill();
  ctx.strokeStyle = "#f4f1ea";
  ctx.beginPath();
  ctx.moveTo(game.x * w, game.y * h);
  ctx.lineTo(game.x * w + Math.cos(game.aim) * 46 * devicePixelRatio, game.y * h + Math.sin(game.aim) * 46 * devicePixelRatio);
  ctx.stroke();
  ctx.fillStyle = "rgba(244,241,234,0.8)";
  ctx.font = (14 * devicePixelRatio) + "px sans-serif";
  ctx.fillText("DEMO  " + game.score, 16 * devicePixelRatio, 28 * devicePixelRatio);
  if (game.flash) {
    ctx.fillStyle = "#ffc14a";
    ctx.font = (22 * devicePixelRatio) + "px sans-serif";
    ctx.fillText(game.flash, w * 0.42, h * 0.2);
    if (!game.flashUntil) game.flashUntil = performance.now() + 280;
    if (performance.now() > game.flashUntil) {
      game.flash = "";
      game.flashUntil = 0;
    }
  }
}

async function connect() {
  const pin = $("pin").value.trim();
  if (pin.length < 4) {
    setStatus("PIN minimal 4 digit.");
    return;
  }
  setStatus("Menyambung…");
  try {
    const res = await fetch("/api/auth", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ pin })
    });
    if (res.status === 404) throw new Error("preview");
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error("PIN salah");
    state.token = data.token;
    state.demo = false;
    openSocket();
    showStage();
    setStatus("");
  } catch (err) {
    if (String(err.message) === "preview" || err instanceof TypeError) {
      setStatus("Ini pratinjau, bukan siaran dari GT 30 Pro. Pakai mode demo, atau buka URL yang tertulis di ponsel.");
    } else {
      setStatus(err.message || "Gagal sambung");
    }
  }
}

function openSocket() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const ws = new WebSocket(proto + "://" + location.host + "/ws?token=" + state.token);
  ws.binaryType = "arraybuffer";
  state.ws = ws;
  ws.onmessage = (ev) => {
    if (typeof ev.data === "string") onText(ev.data);
    else onBinary(ev.data);
  };
  ws.onclose = () => {
    $("stage-meta").textContent = "Terputus";
  };
  setTimeout(() => {
    if (state.videoMode === "demo" && !state.demo) startMjpeg();
  }, 1600);
}

function onText(raw) {
  let msg;
  try { msg = JSON.parse(raw); } catch (_) { return; }
  if (msg.ok || msg.type === "state" || msg.videoW) {
    if (msg.videoW && msg.videoH) {
      state.aspect = msg.videoW / msg.videoH;
      fit();
    }
    if (msg.injection) state.injection = msg.injection;
    if (msg.name) $("stage-meta").textContent = msg.name + (msg.injection ? " · " + msg.injection : "");
    banner();
  }
  if (msg.type === "pong" && msg.t) {
    const rtt = Math.round(performance.now() - msg.t);
    $("stage-meta").textContent = (msg.name || "Host") + " · " + rtt + " ms";
  }
}

function onBinary(buffer) {
  const bytes = new Uint8Array(buffer);
  const type = bytes[0];
  const payload = bytes.subarray(1);
  if (type === 1) setupDecoder(payload);
  else if (state.decoder && state.decoder.state === "configured") {
    try {
      state.decoder.decode(new EncodedVideoChunk({
        type: type === 2 ? "key" : "delta",
        timestamp: Math.round(performance.now() * 1000),
        data: annexToAvcc(payload)
      }));
    } catch (_) {
      startMjpeg();
    }
  }
}

function setupDecoder(config) {
  if (!window.VideoDecoder) {
    startMjpeg();
    return;
  }
  const nals = splitNals(config);
  const sps = nals.find((n) => (n[0] & 31) === 7);
  const pps = nals.find((n) => (n[0] & 31) === 8);
  if (!sps || !pps) {
    startMjpeg();
    return;
  }
  const hex = (n) => n.toString(16).padStart(2, "0");
  try {
    const decoder = new VideoDecoder({
      output: (frame) => {
        state.videoMode = "webcodecs";
        $("stream").hidden = true;
        view.hidden = false;
        ctx.drawImage(frame, 0, 0, view.width, view.height);
        frame.close();
      },
      error: () => startMjpeg()
    });
    decoder.configure({
      codec: "avc1." + hex(sps[1]) + hex(sps[2]) + hex(sps[3]),
      description: avcC(sps, pps),
      optimizeForLatency: true,
      hardwareAcceleration: "prefer-hardware"
    });
    state.decoder = decoder;
    state.ws.send(JSON.stringify({ type: "keyframe" }));
  } catch (_) {
    startMjpeg();
  }
}

function startMjpeg() {
  if (state.usingMjpeg || state.demo || !state.token) return;
  state.usingMjpeg = true;
  state.videoMode = "mjpeg";
  view.hidden = true;
  const img = $("stream");
  img.hidden = false;
  img.src = "/stream.mjpeg?token=" + state.token + "&t=" + Date.now();
}

function splitNals(data) {
  const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
  const starts = [];
  for (let i = 0; i < bytes.length - 3; i++) {
    if (bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 1) {
      starts.push(i + 3);
      i += 2;
    } else if (bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 0 && bytes[i + 3] === 1) {
      starts.push(i + 4);
      i += 3;
    }
  }
  return starts.map((begin, index) => {
    let end = index + 1 < starts.length ? starts[index + 1] : bytes.length;
    if (index + 1 < starts.length) {
      let back = end;
      while (back > begin && bytes[back - 1] === 0) back--;
      const mark = starts[index + 1] - begin;
      end = bytes[starts[index + 1] - 1] === 1 && bytes[starts[index + 1] - 2] === 0 ? starts[index + 1] - (bytes[starts[index + 1] - 3] === 0 ? 4 : 3) : back;
      if (end < begin) end = starts[index + 1];
      void mark;
    }
    return bytes.subarray(begin, end);
  });
}

function avcC(sps, pps) {
  const out = new Uint8Array(11 + sps.length + pps.length);
  let o = 0;
  out[o++] = 1;
  out[o++] = sps[1];
  out[o++] = sps[2];
  out[o++] = sps[3];
  out[o++] = 255;
  out[o++] = 225;
  out[o++] = sps.length >> 8;
  out[o++] = sps.length & 255;
  out.set(sps, o); o += sps.length;
  out[o++] = 1;
  out[o++] = pps.length >> 8;
  out[o++] = pps.length & 255;
  out.set(pps, o);
  return out;
}

function annexToAvcc(data) {
  const nals = splitNals(data).filter((n) => n.length && (n[0] & 31) !== 7 && (n[0] & 31) !== 8);
  const use = nals.length ? nals : splitNals(data);
  let size = 0;
  use.forEach((n) => { size += 4 + n.length; });
  const out = new Uint8Array(size);
  let o = 0;
  use.forEach((n) => {
    out[o++] = n.length >>> 24;
    out[o++] = n.length >>> 16;
    out[o++] = n.length >>> 8;
    out[o++] = n.length & 255;
    out.set(n, o);
    o += n.length;
  });
  return out;
}

function renderKeys() {
  const rows = [
    [["1", 8], ["2", 9], ["3", 10], ["4", 11], ["5", 12], ["6", 13], ["7", 14], ["8", 15], ["9", 16], ["0", 7]],
    [["Q", 45], ["W", 51], ["E", 33], ["R", 46], ["T", 48], ["Y", 53], ["U", 49], ["I", 37], ["O", 43], ["P", 44]],
    [["A", 29], ["S", 47], ["D", 32], ["F", 34], ["G", 35], ["H", 36], ["J", 38], ["K", 39], ["L", 40]],
    [["Z", 54], ["X", 52], ["C", 31], ["V", 50], ["B", 30], ["N", 42], ["M", 41]]
  ];
  const root = $("keyboard");
  root.innerHTML = "";
  rows.forEach((row) => {
    const line = document.createElement("div");
    line.className = "keyrow";
    row.forEach(([label, code]) => line.appendChild(keyCap(label, code)));
    root.appendChild(line);
  });
  const bottom = document.createElement("div");
  bottom.className = "keyrow";
  bottom.appendChild(keyCap("Spasi", 62, true));
  bottom.appendChild(keyCap("⌫", 67));
  bottom.appendChild(keyCap("⏎", 66));
  bottom.appendChild(keyCap("Esc", 111));
  root.appendChild(bottom);
  const send = document.createElement("div");
  send.className = "send-row";
  const input = document.createElement("input");
  input.placeholder = "Ketik lalu kirim";
  const button = document.createElement("button");
  button.className = "go";
  button.type = "button";
  button.textContent = "Kirim";
  button.style.width = "auto";
  button.addEventListener("click", () => {
    if (!input.value) return;
    emit({ type: "text", text: input.value }, true);
    input.value = "";
  });
  send.appendChild(input);
  send.appendChild(button);
  root.appendChild(send);
}

function keyCap(label, code, wide) {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "key" + (wide ? " wide" : "");
  btn.textContent = label;
  btn.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    emit({ type: "key", action: "down", code, source: "keyboard" }, true);
  });
  btn.addEventListener("pointerup", () => emit({ type: "key", action: "up", code, source: "keyboard" }, true));
  return btn;
}

const KEYMAP = {
  Space: 62, Enter: 66, Backspace: 67, Escape: 111, Tab: 61,
  ArrowLeft: 21, ArrowRight: 22, ArrowUp: 19, ArrowDown: 20,
  ShiftLeft: 59, ShiftRight: 60, ControlLeft: 113, AltLeft: 57
};
window.addEventListener("keydown", (e) => {
  if ($("stage").hidden) return;
  if (e.target.matches("input, textarea")) return;
  if (e.repeat) return;
  const code = KEYMAP[e.code] || (e.key.length === 1 && /[a-z]/i.test(e.key) ? e.key.toUpperCase().charCodeAt(0) - 65 + 29 : 0) || (e.key >= "0" && e.key <= "9" ? (e.key === "0" ? 7 : 7 + Number(e.key)) : 0);
  if (!code) return;
  emit({ type: "key", action: "down", code, source: "keyboard" }, true);
  e.preventDefault();
});
window.addEventListener("keyup", (e) => {
  if ($("stage").hidden || e.target.matches("input, textarea")) return;
  const code = KEYMAP[e.code] || (e.key.length === 1 && /[a-z]/i.test(e.key) ? e.key.toUpperCase().charCodeAt(0) - 65 + 29 : 0);
  if (code) emit({ type: "key", action: "up", code, source: "keyboard" }, true);
});

$("connect").addEventListener("click", connect);
$("pin").addEventListener("keydown", (e) => { if (e.key === "Enter") connect(); });
$("demo").addEventListener("click", () => {
  state.demo = true;
  state.videoMode = "demo";
  state.aspect = 20 / 9;
  showStage();
  $("stage-meta").textContent = "Demo · MatePad";
});
$("leave").addEventListener("click", leaveStage);
$("cycle").addEventListener("click", () => {
  const list = state.profiles;
  const idx = Math.max(0, list.findIndex((p) => p.id === state.profileId));
  state.profileId = list[(idx + 1) % list.length].id;
  state.showPad = state.profileId !== "touch";
  saveProfiles();
  renderPad();
});
$("keys-toggle").addEventListener("click", () => { $("keyboard").hidden = !$("keyboard").hidden; });
$("pad-toggle").addEventListener("click", () => {
  state.showPad = !state.showPad;
  $("pad-toggle").classList.toggle("on", state.showPad);
  renderPad();
});
$("edit-toggle").addEventListener("click", () => {
  state.editing = !state.editing;
  $("edit-toggle").classList.toggle("on", state.editing);
  $("edit-toggle").textContent = state.editing ? "Selesai" : "Edit";
});
$("full").addEventListener("click", () => {
  if (!document.fullscreenElement) document.documentElement.requestFullscreen?.();
  else document.exitFullscreen?.();
});
document.querySelectorAll("[data-nav]").forEach((btn) => {
  btn.addEventListener("click", () => sendNav(btn.dataset.nav));
});
$("cal-l").addEventListener("click", () => { state.calibrate = "lt"; banner(); });
$("cal-r").addEventListener("click", () => { state.calibrate = "rt"; banner(); });
$("reset-layout").addEventListener("click", () => {
  state.profiles = defaultProfiles();
  saveProfiles();
  renderPad();
});
document.querySelectorAll(".chip").forEach((chip) => {
  chip.addEventListener("click", () => {
    document.querySelectorAll(".chip").forEach((c) => c.classList.remove("on"));
    chip.classList.add("on");
  });
});
$("mock-start").addEventListener("click", () => {
  const on = $("mock-kicker").textContent !== "SIARAN HIDUP";
  $("mock-kicker").textContent = on ? "SIARAN HIDUP" : "SIAP DI LAN";
  $("mock-kicker").style.color = on ? "var(--ok)" : "var(--gold)";
  $("mock-start").textContent = on ? "Berhenti pantulan" : "Mulai pantulan";
});
window.addEventListener("resize", () => { if (!$("stage").hidden) fit(); });
document.addEventListener("contextmenu", (e) => e.preventDefault());
setInterval(() => {
  if (state.ws && state.ws.readyState === 1) {
    state.ws.send(JSON.stringify({ type: "ping", t: performance.now() }));
  }
}, 1000);

if (location.port === "28731") {
  $("connect-hint").textContent = "Halaman ini disajikan oleh GT 30 Pro. Masukkan PIN dari aplikasi host.";
} else {
  $("demo").className = "go";
  $("connect").className = "ghost";
  $("connect-hint").textContent = "Pratinjau di browser ini bukan siaran ponsel. Tombol demo menjalankan kontrolnya. Di MatePad asli, halaman ini dibuka dari IP GT 30 Pro.";
}
if (new URLSearchParams(location.search).has("demo")) {
  $("demo").click();
}
requestAnimationFrame(loop);
