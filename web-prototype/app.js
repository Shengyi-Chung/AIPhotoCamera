/**
 * app.js —— 最小原型主逻辑
 * 流程：取帧(320×240) → 三个 cue → 规则表 → 一句话提示 + 叠加层 → 事件日志
 */
import { toGray, estimateHorizon, measureBrightness, detectGlare } from './detect.js';

const W = 320, H = 240;

const el = {
  video: document.getElementById('video'),
  overlay: document.getElementById('overlay'),
  banner: document.getElementById('banner'),
  cueName: document.getElementById('cueName'),
  cueText: document.getElementById('cueText'),
  modeTag: document.getElementById('modeTag'),
  stEnabled: document.getElementById('stEnabled'),
  stAngle: document.getElementById('stAngle'),
  stSim: document.getElementById('stSim'),
  simTilt: document.getElementById('simTilt'),
  simTiltVal: document.getElementById('simTiltVal'),
  stConf: document.getElementById('stConf'),
  stLuma: document.getElementById('stLuma'),
  stGlare: document.getElementById('stGlare'),
  stFps: document.getElementById('stFps'),
  btnStart: document.getElementById('btnStart'),
  btnDemo: document.getElementById('btnDemo'),
  btnToggle: document.getElementById('btnToggle'),
  btnShot: document.getElementById('btnShot'),
  btnExport: document.getElementById('btnExport'),
  btnClear: document.getElementById('btnClear'),
  thr: document.getElementById('thr'),
  thrVal: document.getElementById('thrVal'),
  logBox: document.getElementById('logBox'),
};

const ctx = el.overlay.getContext('2d');
const work = Object.assign(document.createElement('canvas'), { width: W, height: H });
const wctx = work.getContext('2d', { willReadFrequently: true });

let policy = {
  cooldownMs: 3000, globalGapMs: 8000, confidenceMin: 0.6,
  priority: ['dark', 'glare', 'tilt'],
  rules: {
    tilt: { thresholdDeg: 5, confidenceMin: 0.6, text: 'Level the horizon' },
    dark: { meanLumaMax: 62, darkRatioMin: 0.35, text: 'Move to the light' },
    glare: { ratioMin: 0.06, text: 'Angle away from the glare' },
  },
};

const state = {
  enabled: true,
  mode: 'idle',         // idle | camera | demo
  demo: { tilt: 8 },
  simTilt: 0,           // 虚拟倾斜（度）：摄像头模式下把画面旋转后再检测
  lastPromptAt: {},     // cue → ts
  lastAnyAt: 0,
  current: null,        // {cue, text, at}
  metrics: null,
  fps: 0,
  log: [],
};

/* ---------- 启动 ---------- */
async function startCamera() {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { width: 640, height: 480, facingMode: 'environment' }, audio: false,
    });
    el.video.srcObject = stream;
    await el.video.play();
    state.mode = 'camera';
    el.modeTag.textContent = '摄像头';
  } catch (e) {
    state.mode = 'demo';
    el.modeTag.textContent = '演示模式（摄像头不可用）';
    toast('init', '无法访问摄像头，已切到演示模式');
  }
  startLoop();
}

/** 摄像头帧：可带虚拟倾斜（旋转后再检测，模拟"相机拿歪了"） */
function drawCameraFrame() {
  if (!el.video.videoWidth) return;
  const rad = state.simTilt * Math.PI / 180;
  wctx.save();
  wctx.fillStyle = '#000'; wctx.fillRect(0, 0, W, H);
  wctx.translate(W / 2, H / 2);
  wctx.rotate(rad);
  const zoom = 1 + Math.abs(state.simTilt) / 40;   // 旋转后放大，避免黑角
  wctx.scale(zoom, zoom);
  wctx.drawImage(el.video, -W / 2, -H / 2, W, H);
  wctx.restore();
}

function drawDemoFrame() {
  const { tilt } = state.demo;
  wctx.fillStyle = '#b8cde0'; wctx.fillRect(0, 0, W, H);          // 天空
  const slope = Math.tan(tilt * Math.PI / 180);
  const yc = H / 2;
  wctx.beginPath();
  wctx.moveTo(0, yc - slope * W / 2);
  wctx.lineTo(W, yc + slope * W / 2);
  wctx.lineTo(W, H); wctx.lineTo(0, H); wctx.closePath();
  wctx.fillStyle = '#4c5b3f'; wctx.fill();
  // 一个“主体”，方便日后接入检测
  wctx.fillStyle = '#d9b48f';
  wctx.beginPath(); wctx.arc(W * 0.62, H * 0.42, 16, 0, Math.PI * 2); wctx.fill();
}

/* ---------- 主循环 ---------- */
let lastTs = 0;
let loopActive = false;

function startLoop() {
  if (loopActive) return;
  loopActive = true;
  requestAnimationFrame(loop);
}

function loop(ts) {
  if (state.mode === 'idle') { loopActive = false; return; }
  requestAnimationFrame(loop);
  if (ts - lastTs < 1000 / 30) return;      // 限帧
  const dt = ts - lastTs; lastTs = ts;
  state.fps = state.fps ? state.fps * 0.9 + (1000 / dt) * 0.1 : 1000 / dt;

  if (state.mode === 'camera') {
    if (el.video.readyState < 2) return;
    drawCameraFrame();
  } else {
    drawDemoFrame();
  }

  const img = wctx.getImageData(0, 0, W, H);
  const gray = toGray(img.data, W, H);

  const hz = estimateHorizon(gray, W, H);
  const br = measureBrightness(gray);
  const gl = detectGlare(img.data, W, H);

  state.metrics = { hz, br, gl };

  const cue = decide({ hz, br, gl }, ts);
  render(cue, hz);
  updateHud();
}

/* ---------- 规则层 ---------- */
function decide(m, ts) {
  if (!state.enabled) return null;

  const cands = [];
  if (m.br.meanLuma < policy.rules.dark.meanLumaMax && m.br.darkRatio > policy.rules.dark.darkRatioMin) {
    cands.push({ cue: 'dark', text: policy.rules.dark.text, conf: 0.9 });
  }
  if (m.gl.ratio > policy.rules.glare.ratioMin) {
    cands.push({ cue: 'glare', text: policy.rules.glare.text, conf: Math.min(1, m.gl.ratio / 0.15) });
  }
  if (Math.abs(m.hz.angleDeg) > policy.rules.tilt.thresholdDeg && m.hz.confidence >= policy.rules.tilt.confidenceMin) {
    cands.push({ cue: 'tilt', text: policy.rules.tilt.text, conf: m.hz.confidence, angle: m.hz.angleDeg });
  }
  if (!cands.length) return null;

  cands.sort((a, b) => policy.priority.indexOf(a.cue) - policy.priority.indexOf(b.cue));
  const top = cands[0];

  const lastOfCue = state.lastPromptAt[top.cue] || 0;
  if (ts - lastOfCue < policy.cooldownMs) return state.current;
  if (ts - state.lastAnyAt < policy.globalGapMs) return state.current;

  state.lastPromptAt[top.cue] = ts;
  state.lastAnyAt = ts;
  state.current = { ...top, at: ts };
  logEvent('prompt', { cue: top.cue, conf: +top.conf.toFixed(2), angle: top.angle ? +top.angle.toFixed(1) : null });
  return state.current;
}

/* ---------- 呈现层 ---------- */
function render(cue, hz) {
  ctx.clearRect(0, 0, W, H);

  // 参考线：跟随当前虚拟倾斜/演示倾斜，代表"真实世界的水平"
  const refDeg = state.mode === 'demo' ? state.demo.tilt : state.simTilt;
  const refSlope = Math.tan(refDeg * Math.PI / 180);
  ctx.strokeStyle = 'rgba(255,255,255,.35)';
  ctx.setLineDash([6, 6]); ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(0, H / 2 - refSlope * W / 2);
  ctx.lineTo(W, H / 2 + refSlope * W / 2);
  ctx.stroke();
  ctx.setLineDash([]);

  if (cue) {
    el.banner.classList.remove('off');
    el.cueName.textContent = ({ tilt: '构图', dark: '光线', glare: '反光' })[cue.cue] || '';
    el.cueText.textContent = cue.text + (cue.angle ? `（当前 ${cue.angle.toFixed(0)}°）` : '');
  } else {
    el.banner.classList.add('off');
    el.cueName.textContent = state.enabled ? '就绪' : '已关闭';
    el.cueText.textContent = state.enabled ? '画面正常，无提示' : '助手已关闭';
  }

  if (cue && cue.cue === 'tilt') {
    // 理想水平线：始终回到画面中心水平，提示"把相机转回来"
    ctx.strokeStyle = 'rgba(110,225,204,.9)'; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.moveTo(0, H / 2); ctx.lineTo(W, H / 2); ctx.stroke();
    ctx.fillStyle = 'rgba(110,225,204,.95)';
    ctx.font = 'bold 15px "Microsoft YaHei", sans-serif';
    ctx.fillText(cue.angle > 0 ? '↺ 反向旋转相机' : '↻ 反向旋转相机', 10, H / 2 - 12);
  }
}

function updateHud() {
  const { hz, br, gl } = state.metrics || {};
  el.stAngle.textContent = hz ? `${hz.angleDeg.toFixed(1)}°` : '—';
  el.stSim.textContent = `${(state.mode === 'demo' ? state.demo.tilt : state.simTilt)}°`;
  el.stConf.textContent = hz ? hz.confidence.toFixed(2) : '—';
  el.stLuma.textContent = br ? `${br.meanLuma.toFixed(0)}（暗部 ${(br.darkRatio * 100).toFixed(0)}%）` : '—';
  el.stGlare.textContent = gl ? `${(gl.ratio * 100).toFixed(1)}%` : '—';
  el.stFps.textContent = state.fps.toFixed(0);
}

/* ---------- 日志 ---------- */
function logEvent(type, payload) {
  const e = { t: Date.now(), type, ...payload };
  state.log.push(e);
  const lines = state.log.slice(-8).map(x =>
    `${new Date(x.t).toLocaleTimeString()} ${x.type}${x.cue ? ' ' + x.cue : ''}${x.note ? ' ' + x.note : ''}`);
  el.logBox.textContent = lines.join('\n') || '（无事件）';
}

function toast(cue, text) {
  el.banner.classList.remove('off');
  el.cueName.textContent = '';
  el.cueText.textContent = text;
  setTimeout(() => { if (!state.current) el.banner.classList.add('off'); }, 2200);
}

function exportLog() {
  const blob = new Blob([JSON.stringify({ policy, events: state.log }, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `photo-assistant-log-${Date.now()}.json`;
  a.click();
  URL.revokeObjectURL(a.href);
}

/* ---------- 交互 ---------- */
function setEnabled(on) {
  state.enabled = on;
  el.btnToggle.textContent = on ? '助手：开' : '助手：关';
  el.btnToggle.classList.toggle('on', on);
  el.stEnabled.textContent = on ? '开启' : '关闭';
  el.stEnabled.className = on ? 'ok' : 'bad';
  logEvent('toggle', { note: on ? 'on' : 'off' });
  if (!on) { state.current = null; ctx.clearRect(0, 0, W, H); }
}

el.btnStart.addEventListener('click', () => startCamera());
el.btnDemo.addEventListener('click', () => {
  state.mode = 'demo'; el.modeTag.textContent = '演示模式'; startLoop();
});
el.btnToggle.addEventListener('click', () => setEnabled(!state.enabled));
el.btnShot.addEventListener('click', () => {
  logEvent('capture', {});
  state.lastAnyAt = 0;                // 快门后允许重新提示
  toast('', '已记录一次拍摄');
});
el.btnExport.addEventListener('click', exportLog);
el.btnClear.addEventListener('click', () => { state.log = []; el.logBox.textContent = '（无事件）'; });
el.thr.addEventListener('input', () => {
  policy.rules.tilt.thresholdDeg = +el.thr.value;
  el.thrVal.textContent = `${el.thr.value}°`;
  logEvent('threshold', { note: el.thr.value });
});

el.simTilt.addEventListener('input', () => {
  state.simTilt = +el.simTilt.value;
  el.simTiltVal.textContent = `${state.simTilt}°`;
  logEvent('simTilt', { note: state.simTilt });
});

document.addEventListener('keydown', (e) => {
  if (e.code === 'Space') { e.preventDefault(); setEnabled(!state.enabled); }
  if (e.key === 'd' || e.key === 'D') { state.mode = 'demo'; el.modeTag.textContent = '演示模式'; startLoop(); }
  if (e.key === 'ArrowLeft') { state.demo.tilt = Math.max(-15, state.demo.tilt - 1); }
  if (e.key === 'ArrowRight') { state.demo.tilt = Math.min(15, state.demo.tilt + 1); }
  if (e.key === '[') { state.simTilt = Math.max(-20, state.simTilt - 1); syncSim(); }
  if (e.key === ']') { state.simTilt = Math.min(20, state.simTilt + 1); syncSim(); }
  if (e.key === 'z' || e.key === 'Z') { state.simTilt = 0; syncSim(); }
  if (e.key === 's' || e.key === 'S') el.btnShot.click();
});

function syncSim() {
  el.simTilt.value = state.simTilt;
  el.simTiltVal.textContent = `${state.simTilt}°`;
}

/* 启动：先加载 policy.json，再开摄像头 */
fetch('./policy.json').then(r => r.json()).then(p => {
  policy = { ...policy, ...p, rules: { ...policy.rules, ...(p.rules || {}) } };
  el.thr.value = policy.rules.tilt.thresholdDeg;
  el.thrVal.textContent = `${policy.rules.tilt.thresholdDeg}°`;
}).catch(() => {}).finally(startCamera);
