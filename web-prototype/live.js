/**
 * live.js —— 实拍版：真实摄像头 + 实时检测 + 真的拍照
 * 复用 detect.js；所有画面计算都在本机完成，不上传。
 */
import {
  toGray, estimateHorizon, measureBrightness, detectGlare, computeAesthetics,
  detectVerticals, estimateSky, foregroundTexture, classifyScene,
} from './detect.js';
import { buildAim, SCENE_LABEL } from './aim.js';

const AW = 320, AH = 240;            // 分析分辨率

const el = {
  video: document.getElementById('video'),
  overlay: document.getElementById('overlay'),
  dot: document.getElementById('dot'),
  stateLabel: document.getElementById('stateLabel'),
  hint: document.getElementById('hint'),
  hintIc: document.getElementById('hintIc'),
  hintMain: document.getElementById('hintMain'),
  hintSub: document.getElementById('hintSub'),
  suggest: document.getElementById('suggest'),
  shutter: document.getElementById('shutter'),
  thumb: document.getElementById('thumb'),
  review: document.getElementById('review'),
  rvTitle: document.getElementById('rvTitle'),
  rvGood: document.getElementById('rvGood'),
  rvTip: document.getElementById('rvTip'),
  rvDownload: document.getElementById('rvDownload'),
  rvClose: document.getElementById('rvClose'),
  btnStart: document.getElementById('btnStart'),
  btnToggle: document.getElementById('btnToggle'),
  btnFlip: document.getElementById('btnFlip'),
  btnSuggest: document.getElementById('btnSuggest'),
  btnHelp: document.getElementById('btnHelp'),
  focusRing: document.getElementById('focusRing'),
  guide: document.getElementById('guide'),
  btnGuideOk: document.getElementById('btnGuideOk'),
  gate: document.getElementById('gate'),
  status: document.getElementById('status'),
  errBox: document.getElementById('errBox'),
  btnRetry: document.getElementById('btnRetry'),
};

const octx = el.overlay.getContext('2d');
const work = Object.assign(document.createElement('canvas'), { width: AW, height: AH });
const wctx = work.getContext('2d', { willReadFrequently: true });

const S = {
  stream: null,
  track: null,         // 视频轨道（点击对焦用）
  focusMode: '',       // 设备报告的 focusMode 能力
  facing: 'environment',
  enabled: true,
  running: false,
  last: null,          // 最近一次 metrics
  lastPhoto: null,     // dataURL
  prompt: null,
  cooldown: {},
  lastAny: 0,
  fps: 0,
  shots: 0,
  aimOn: true,         // 目标引导（L2）开关
  scene: 'other',
  aim: null,
  sceneStable: 0,      // 场景连续命中次数，防抖
  sceneCandidate: 'other',
  focusPoint: null,    // 用户点击标记的主体位置 {x,y}（归一化 0..1），null=未标记
  promptShownAt: 0,    // 当前提示开始显示的时间（用于最小停留时长）
  lastCue: null,       // 上一个显示过的 cue（用于保持显示）
};

/* ---------------- 摄像头 ---------------- */
const ERR_TIP = {
  NotAllowedError: '权限被拒绝。点地址栏左侧的摄像头图标 → 允许；如果之前点过"阻止"，要先清除记录。',
  NotFoundError: '没有找到摄像头设备。检查设备管理器里摄像头是否被禁用。',
  NotReadableError: '摄像头被其他程序占用。关掉 Zoom / Teams / 微信视频 / 相机 App 再试。',
  OverconstrainedError: '分辨率要求不满足。点"重试"会用默认分辨率。',
  SecurityError: '安全上下文不足：必须通过 http://127.0.0.1 或 https 打开，不能直接双击 html。',
  AbortError: '启动被中断，点"重试"。',
  TypeError: '浏览器不支持摄像头 API（或页面不是 localhost/https）。',
};

function setStatus(t) { if (el.status) el.status.textContent = t; }
function showError(name, msg) {
  el.errBox.style.display = 'block';
  el.errBox.textContent = `⚠ ${name || '错误'}：${msg || ''}\n→ ${ERR_TIP[name] || '点"摄像头自检"查看详细信息。'}`;
}
function clearError() { el.errBox.style.display = 'none'; el.errBox.textContent = ''; }

async function openCamera(facing = S.facing) {
  clearError();

  if (!window.isSecureContext || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    setStatus('❌ 当前页面不是安全上下文');
    showError('SecurityError', `协议 ${location.protocol}，需要 http://127.0.0.1 或 https`);
    return;
  }

  if (S.stream) S.stream.getTracks().forEach(t => t.stop());
  setStatus('① 正在请求摄像头权限…');

  const tries = [
    { video: { facingMode: facing, width: { ideal: 1280 }, height: { ideal: 720 } }, audio: false },
    { video: true, audio: false },
  ];

  let lastErr = null;
  for (let i = 0; i < tries.length; i++) {
    try {
      const timeout = new Promise((_, rej) => setTimeout(() => rej(new Error('TIMEOUT')), 8000));
      const stream = await Promise.race([navigator.mediaDevices.getUserMedia(tries[i]), timeout]);
      S.stream = stream;
      setStatus('② 已拿到视频流，正在启动预览…');
      el.video.srcObject = stream;
      await el.video.play();
      await waitForFrame(3000);
      fitOverlay();
      const t = stream.getVideoTracks()[0];
      S.track = t;                       // 保存轨道，后面点击对焦要用
      const st = t.getSettings ? t.getSettings() : {};
      setStatus(`✅ 摄像头已开启：${st.width || '?'}×${st.height || '?'}${i === 0 ? '' : '（已降级到默认分辨率）'}`);
      logEvent('camera', `focusMode=${(st.focusMode || '?')}`);
      t.addEventListener?.('ended', () => {
        S.running = false;
        setStatus('⚠ 摄像头被关闭了');
        showError('AbortError', '视频轨道结束（可能被其他程序抢占）');
        el.gate.classList.remove('hide');
      });
      if (!S.running) { S.running = true; requestAnimationFrame(loop); }
      return true;
    } catch (e) {
      lastErr = e;
      if (e && e.message === 'TIMEOUT') {
        setStatus('⏱ 8 秒没有响应');
        showError('NotReadableError', 'getUserMedia 一直没有返回，通常是摄像头被占用或驱动卡住');
        return false;
      }
      if (i === 0) { setStatus('① 首选参数失败，尝试默认参数…'); continue; }
    }
  }

  setStatus('❌ 开启失败');
  showError(lastErr && lastErr.name, lastErr && lastErr.message);
  return false;
}

function waitForFrame(ms = 3000) {
  return new Promise((resolve, reject) => {
    const t0 = performance.now();
    const check = () => {
      if (el.video.readyState >= 2 && el.video.videoWidth > 0) return resolve();
      if (performance.now() - t0 > ms) return reject(Object.assign(new Error('视频 3 秒内没有出帧（可能是虚拟摄像头或驱动问题）'), { name: 'NotReadableError' }));
      requestAnimationFrame(check);
    };
    check();
  });
}

function fitOverlay() {
  const r = el.video.getBoundingClientRect();
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  el.overlay.width = Math.round(r.width * dpr);
  el.overlay.height = Math.round(r.height * dpr);
}

/* ---------------- 主循环 ---------------- */
let lastTs = 0;
function loop(ts) {
  requestAnimationFrame(loop);
  if (!S.running || el.video.readyState < 2) return;
  if (ts - lastTs < 1000 / 24) return;
  const dt = ts - lastTs; lastTs = ts;
  S.fps = S.fps ? S.fps * 0.9 + (1000 / dt) * 0.1 : 1000 / dt;

  wctx.drawImage(el.video, 0, 0, AW, AH);
  const img = wctx.getImageData(0, 0, AW, AH);
  const gray = toGray(img.data, AW, AH);

  const hz = estimateHorizon(gray, AW, AH);
  const br = measureBrightness(gray);
  const gl = detectGlare(img.data, AW, AH);
  const aes = computeAesthetics(gray, AW, AH, hz);
  const vert = detectVerticals(gray, AW, AH);
  const sky = estimateSky(img.data, AW, AH);
  const fg = foregroundTexture(gray, AW, AH);

  // 场景识别（连续 3 帧一致才切换，防抖）
  const cand = classifyScene({ vert, sky, hz, aes });
  if (cand === S.sceneCandidate) S.sceneStable++;
  else { S.sceneCandidate = cand; S.sceneStable = 1; }
  if (S.sceneStable >= 3 && S.scene !== cand) {
    S.scene = cand;
    logEvent('scene', cand);
  }

  S.last = { hz, br, gl, aes, vert, sky, fg, ts };

  // 平滑：目标框/目标线不要每帧乱跳（低通滤波）
  S.smooth = S.smooth || { skyBottom: null, lineY: null };
  const k = 0.18;
  if (sky.skyBottomRatio > 0) {
    S.smooth.skyBottom = S.smooth.skyBottom == null
      ? sky.skyBottomRatio
      : S.smooth.skyBottom + (sky.skyBottomRatio - S.smooth.skyBottom) * k;
  }
  if (hz.confidence > 0.2) {
    S.smooth.lineY = S.smooth.lineY == null
      ? hz.lineYRatio
      : S.smooth.lineY + (hz.lineYRatio - S.smooth.lineY) * k;
  }

  S.aim = S.aimOn ? buildAim({
    scene: S.scene,
    vert,
    sky: { ...sky, skyBottomRatio: S.smooth.skyBottom == null ? sky.skyBottomRatio : S.smooth.skyBottom },
    hz: { ...hz, lineYRatio: S.smooth.lineY == null ? hz.lineYRatio : S.smooth.lineY },
    fg,
  }) : null;

  const cue = decide(S.last, ts);
  draw(cue, S.last, S.aim);
  updateHint(cue, S.aim);
}

/* ---------------- 规则：默认层只纠错 ---------------- */
// 提示最小停留：条件恢复了也先显示完，避免"一闪而过"
const HOLD_CUE_MS = 3000;
const COOLDOWN_MS = 6000;     // 同类提示冷却
const GAP_MS = 12000;         // 任意两次提示之间的最小间隔

function decide(m, ts) {
  if (!S.enabled) return null;

  const cands = [];
  // 阈值都比之前宽松：宁可少提示，也不要一直滴滴响
  if (m.br.meanLuma < 55 && m.br.darkRatio > 0.45) cands.push('dark');
  if (m.gl.ratio > 0.10) cands.push('glare');
  if (Math.abs(m.hz.angleDeg) > 8 && m.hz.confidence >= 0.5) cands.push('tilt');

  const prio = ['dark', 'glare', 'tilt'];
  cands.sort((a, b) => prio.indexOf(a) - prio.indexOf(b));
  const top = cands[0] || null;

  // 没有新问题：如果当前提示还没显示够时长，继续显示
  if (!top) {
    if (S.prompt && ts - S.promptShownAt < HOLD_CUE_MS) return S.prompt;
    S.prompt = null;
    return null;
  }

  // 已经是同一条提示：继续显示（不重复触发冷却）
  if (S.prompt === top) return top;

  // 换提示前检查冷却与间隔
  if (ts - (S.cooldown[top] || 0) < COOLDOWN_MS) return S.prompt;
  if (ts - S.lastAny < GAP_MS) return S.prompt;

  S.cooldown[top] = ts;
  S.lastAny = ts;
  S.prompt = top;
  S.promptShownAt = ts;
  logEvent('prompt', top);
  return top;
}

const CUE = {
  tilt: { ic: '↺', main: '把水平线转正', sub: '让画面里的水平线（橙色）和白色虚线平行' },
  dark: { ic: '☀', main: '换个亮一点的地方', sub: '现在太暗，转向窗户或光源' },
  glare: { ic: '◐', main: '避开反光', sub: '画面里有强反光，侧一点角度再拍' },
};

function updateHint(cue, aim) {
  if (!S.enabled) {
    el.hint.classList.remove('show');
    el.hintMain.textContent = '助手已关闭';
    el.hintSub.textContent = '';
    return;
  }

  // ① 纠错优先（L1）：有基础问题时先说问题
  if (cue) {
    const c = CUE[cue];
    el.hintIc.textContent = c.ic;
    el.hintMain.textContent = c.main;
    el.hintSub.textContent = c.sub + (cue === 'tilt' && S.last ? `（当前 ${S.last.hz.angleDeg.toFixed(0)}°）` : '');
    el.hint.classList.add('show');
    return;
  }

  // ② 目标引导（L2）：没有错误时，给“拍得更好”的目标
  if (aim && aim.type && S.aimOn) {
    const tag = SCENE_LABEL[aim.scene] || '通用';
    el.hintIc.textContent = aim.achieved ? '✓' : (aim.type === 'frame' ? '▣' : '═');
    el.hintMain.textContent = aim.achieved ? `构图到位（${tag}）` : aim.text;
    el.hintSub.textContent = aim.sub + `　·　${tag}模式`;
    el.hint.classList.add('show');
    if (aim.achieved && !S._aimOkAt) S._aimOkAt = performance.now();
    if (!aim.achieved) S._aimOkAt = null;
    return;
  }

  // ③ 什么都没有：就绪（顺便告诉用户水平线状态）
  const ready = S.last && S.last.hz.confidence > 0.5;
  const levelOk = ready && Math.abs(S.last.hz.angleDeg) <= 2;
  el.hint.classList.toggle('show', !!ready);
  el.hintIc.textContent = levelOk ? '✓' : '—';
  el.hintMain.textContent = levelOk ? '水平线已对齐' : '画面就绪，可以拍了';
  el.hintSub.textContent = ready
    ? (levelOk ? '白色虚线与画面水平线平行' : `水平线偏 ${S.last.hz.angleDeg.toFixed(1)}°（转手机对齐白色虚线）`)
    : '点击画面标记主体，或直接对准目标';
}

/* ---------------- 叠加层 ---------------- */
function draw(cue, m, aim) {
  const W = el.overlay.width, H = el.overlay.height;
  const sx = W / AW, sy = H / AH;
  const dpr = window.devicePixelRatio || 1;
  octx.clearRect(0, 0, W, H);
  if (!S.enabled || !m) return;

  // ===== 水平线引导 =====
  // ① 目标水平线：画面正中的白色水平虚线（这就是"水平线"）
  // ② 实际水平线：检测到的画面里那条线（琥珀=歪，绿=正）
  // ③ 主体虚线：在主体那一行画一条水平虚线，让主体压上去
  const levelOk = m.hz.confidence > 0.35 && Math.abs(m.hz.angleDeg) <= 2;

  // ③ 主体目标虚线（在主体高度上）
  if (S.focusPoint) {
    const fy = S.focusPoint.y * H;
    octx.save();
    octx.setLineDash([14 * dpr, 10 * dpr]);
    octx.strokeStyle = 'rgba(255,212,121,.75)';
    octx.lineWidth = Math.max(1.6, 1.8 * dpr);
    octx.beginPath();
    octx.moveTo(0, fy); octx.lineTo(W, fy);
    octx.stroke();
    octx.restore();
    octx.fillStyle = 'rgba(255,212,121,.95)';
    octx.font = `${Math.round(11 * dpr)}px "Microsoft YaHei", sans-serif`;
    octx.textAlign = 'left';
    octx.fillText('主体线', 8 * dpr, fy - 6 * dpr);
  }

  // ① 目标水平线（居中，白色虚线）——对齐后变绿
  octx.save();
  octx.setLineDash([16 * dpr, 10 * dpr]);
  octx.strokeStyle = levelOk ? 'rgba(63,214,192,.95)' : 'rgba(255,255,255,.55)';
  octx.lineWidth = Math.max(1.8, 2 * dpr);
  octx.beginPath();
  octx.moveTo(0, H / 2); octx.lineTo(W, H / 2);
  octx.stroke();
  octx.restore();

  // ② 实际水平线（检测到的）
  if (m.hz.confidence > 0.3) {
    const { slope, lineY } = m.hz;
    octx.strokeStyle = levelOk ? 'rgba(63,214,192,.95)' : 'rgba(255,180,84,.9)';
    octx.lineWidth = Math.max(2, 2.4 * dpr);
    octx.beginPath();
    octx.moveTo(0, (lineY - slope * AW / 2) * sy);
    octx.lineTo(W, (lineY + slope * AW / 2) * sy);
    octx.stroke();

    // 两端小标签，告诉用户哪条是"实际水平线"
    octx.fillStyle = levelOk ? 'rgba(63,214,192,.95)' : 'rgba(255,180,84,.95)';
    octx.font = `${Math.round(11 * dpr)}px "Microsoft YaHei", sans-serif`;
    octx.textAlign = 'right';
    octx.fillText(levelOk ? '水平 ✓' : '水平线', W - 8 * dpr, (lineY + slope * AW / 2) * sy - 6 * dpr);
  }

  // 倾斜时的旋转箭头（纠错层）
  if (cue === 'tilt' && m) {
    const dir = m.hz.angleDeg > 0 ? '↺' : '↻';
    octx.fillStyle = 'rgba(255,180,84,.95)';
    octx.font = `bold ${Math.round(34 * dpr)}px "Microsoft YaHei", sans-serif`;
    octx.textAlign = 'center';
    octx.fillText(dir, W / 2, H / 2 - 18 * dpr);
  }

  // 目标引导（L2）：只有在没有纠错提示时才显示，避免同时两套指令
  if (aim && aim.type && !cue) drawAim(aim, W, H, dpr);

  // 用户点击标记的主体点：一直显示一个细十字，方便对齐
  if (S.focusPoint) {
    const fx = S.focusPoint.x * W, fy = S.focusPoint.y * H;
    const arm = 12 * dpr;
    octx.strokeStyle = 'rgba(255,212,121,.85)';
    octx.lineWidth = Math.max(1.5, 1.8 * dpr);
    octx.beginPath();
    octx.moveTo(fx - arm, fy); octx.lineTo(fx - arm / 3, fy);
    octx.moveTo(fx + arm / 3, fy); octx.lineTo(fx + arm, fy);
    octx.moveTo(fx, fy - arm); octx.lineTo(fx, fy - arm / 3);
    octx.moveTo(fx, fy + arm / 3); octx.lineTo(fx, fy + arm);
    octx.stroke();
    octx.beginPath();
    octx.arc(fx, fy, 3 * dpr, 0, Math.PI * 2);
    octx.fillStyle = 'rgba(255,212,121,.95)';
    octx.fill();
  }
}

/* ---------------- 目标引导绘制 ---------------- */
function drawAim(aim, W, H, dpr) {
  const ok = aim.achieved;
  const main = ok ? 'rgba(63,214,192,.95)' : 'rgba(120,190,255,.9)';
  const soft = ok ? 'rgba(63,214,192,.55)' : 'rgba(120,190,255,.45)';
  const lw = Math.max(2, Math.round(2.4 * dpr));

  if (aim.type === 'frame') {
    const f = aim.frame;
    const x0 = f.x0 * W, x1 = f.x1 * W;
    const y0 = f.y0 * H, y1 = f.y1 * H;

    // 目标框（虚线）
    octx.save();
    octx.setLineDash([10 * dpr, 8 * dpr]);
    octx.strokeStyle = soft;
    octx.lineWidth = lw;
    octx.strokeRect(x0, y0, x1 - x0, y1 - y0);
    octx.restore();

    // 四角（达成时变绿加粗）
    const c = Math.min(34 * dpr, (x1 - x0) / 3);
    octx.strokeStyle = main;
    octx.lineWidth = lw * 1.4;
    octx.beginPath();
    // 左上
    octx.moveTo(x0, y0 + c); octx.lineTo(x0, y0); octx.lineTo(x0 + c, y0);
    // 右上
    octx.moveTo(x1 - c, y0); octx.lineTo(x1, y0); octx.lineTo(x1, y0 + c);
    // 左下
    octx.moveTo(x0, y1 - c); octx.lineTo(x0, y1); octx.lineTo(x0 + c, y1);
    // 右下
    octx.moveTo(x1 - c, y1); octx.lineTo(x1, y1); octx.lineTo(x1, y1 - c);
    octx.stroke();

    // 顶部留白线（楼顶应对齐这里）
    octx.save();
    octx.setLineDash([6 * dpr, 6 * dpr]);
    octx.strokeStyle = main;
    octx.lineWidth = lw;
    octx.beginPath();
    octx.moveTo(x0, y0); octx.lineTo(x1, y0);
    octx.stroke();
    octx.restore();

    // 竖线角度校正示意
    if (!ok && typeof aim.vertDeg === 'number' && Math.abs(aim.vertDeg) > 2) {
      const cx = W / 2, cy = H / 2, len = H * 0.18;
      const rad = aim.vertDeg * Math.PI / 180;
      octx.strokeStyle = 'rgba(255,180,84,.95)';
      octx.lineWidth = lw;
      octx.beginPath();
      octx.moveTo(cx - Math.sin(rad) * len, cy - Math.cos(rad) * len);
      octx.lineTo(cx + Math.sin(rad) * len, cy + Math.cos(rad) * len);
      octx.stroke();
      octx.beginPath();
      octx.moveTo(cx, cy - len); octx.lineTo(cx, cy + len);
      octx.strokeStyle = 'rgba(255,255,255,.35)';
      octx.stroke();
    }

  } else if (aim.type === 'line') {
    const yTarget = aim.line.y * H;
    const yCur = aim.line.current * H;

    // 目标线
    octx.save();
    octx.setLineDash([12 * dpr, 8 * dpr]);
    octx.strokeStyle = main;
    octx.lineWidth = lw;
    octx.beginPath();
    octx.moveTo(0, yTarget); octx.lineTo(W, yTarget);
    octx.stroke();
    octx.restore();

    // 当前地平线到目标线的指示箭头
    if (!ok) {
      const mid = W / 2;
      const up = yCur > yTarget;    // 当前在目标下方 → 需要往上（抬手机）
      const from = yCur, to = yTarget;
      octx.strokeStyle = soft;
      octx.lineWidth = lw;
      octx.beginPath(); octx.moveTo(mid, from); octx.lineTo(mid, to); octx.stroke();
      // 箭头
      const dir = Math.sign(to - from), half = 10 * dpr;
      octx.beginPath();
      octx.moveTo(mid, to);
      octx.lineTo(mid - half, to - dir * half);
      octx.lineTo(mid + half, to - dir * half);
      octx.closePath();
      octx.fillStyle = soft;
      octx.fill();
      // 文字
      octx.fillStyle = 'rgba(120,190,255,.95)';
      octx.font = `bold ${Math.round(14 * dpr)}px "Microsoft YaHei", sans-serif`;
      octx.textAlign = 'center';
      octx.fillText(up ? '抬手机 ↑' : '压手机 ↓', mid, to + (up ? 26 : -18) * dpr);
    }

    // 三分点提示
    octx.fillStyle = 'rgba(255,255,255,.25)';
    for (const t of [1 / 3, 2 / 3]) {
      octx.fillRect(0, t * H - 0.5, W, 1);
    }
  }
}

/* ---------------- 拍照 ---------------- */
function takePhoto() {
  if (!S.stream || el.video.readyState < 2) return;
  const c = document.createElement('canvas');
  c.width = el.video.videoWidth;
  c.height = el.video.videoHeight;
  c.getContext('2d').drawImage(el.video, 0, 0);

  const url = c.toDataURL('image/jpeg', 0.9);
  S.lastPhoto = url;
  S.shots++;
  el.thumb.src = url;
  el.thumb.style.display = 'block';

  const m = S.last;
  const good = [];
  const tips = [];
  if (m) {
    if (Math.abs(m.hz.angleDeg) < 2) good.push('地平线很稳');
    if (m.br.meanLuma >= 62 && m.br.meanLuma < 200) good.push('曝光正常');
    if (m.gl.ratio <= 0.06) good.push('没有明显反光');
    if (S.aim && S.aim.achieved) good.push(`${SCENE_LABEL[S.aim.scene]}构图到位`);
    if (!good.length) good.push('拍下来了就是第一步');

    if (Math.abs(m.hz.angleDeg) > 4) tips.push('下次把相机转正一些');
    else if (m.br.meanLuma < 62) tips.push('面向光源再拍一次会更亮');
    else if (m.gl.ratio > 0.06) tips.push('侧一点角度避开反光');
    else if (S.aim && S.aim.type && !S.aim.achieved) tips.push(S.aim.text);
    else {
      const s = pickSuggestion(m.aes);
      tips.push(s.text);
    }
  }
  el.rvTitle.textContent = `第 ${S.shots} 张`;
  el.rvGood.textContent = '✓ ' + good.slice(0, 2).join('、');
  el.rvTip.textContent = '→ ' + (tips[0] || '可以重拍一次对比');
  el.review.classList.add('show');
  el.suggest.classList.remove('show');
  logEvent('capture', { note: `tilt=${m ? m.hz.angleDeg.toFixed(1) : '?'}` });
}

el.rvDownload.addEventListener('click', () => {
  if (!S.lastPhoto) return;
  const a = document.createElement('a');
  a.href = S.lastPhoto;
  a.download = `photo-${Date.now()}.jpg`;
  a.click();
});
el.rvClose.addEventListener('click', () => el.review.classList.remove('show'));
el.thumb.addEventListener('click', () => el.rvDownload.click());

/* ---------------- 灵感层 ---------------- */
function pickSuggestion(aes) {
  if (!aes) return { text: '换个角度拍拍看' };
  if (aes.horizonRatio > 0.42 && aes.horizonRatio < 0.58) return { text: '把相机放低，让地平线高一些' };
  if (aes.borderClutter > 0.22) return { text: '往边上挪一步，让画面边缘干净些' };
  if (aes.symmetry > 0.86) return { text: '这张很对称，试试对准中线' };
  return { text: '蹲下来换个视角，同一场景会不一样' };
}

el.btnSuggest.addEventListener('click', () => {
  if (!S.enabled) { flashSuggest('助手已关闭，先重新开启'); return; }
  const s = pickSuggestion(S.last && S.last.aes);
  el.suggest.textContent = '✨ ' + s.text;
  el.suggest.classList.add('show');
  clearTimeout(s.timer);
  s.timer = setTimeout(() => el.suggest.classList.remove('show'), 6000);
  logEvent('suggest', s.text);
});

function flashSuggest(t) {
  el.suggest.textContent = t;
  el.suggest.classList.add('show');
  setTimeout(() => el.suggest.classList.remove('show'), 2500);
}

/* ---------------- 日志 ---------------- */
const events = [];
function logEvent(type, detail = '') {
  events.push({ t: Date.now(), type, detail });
  if (events.length > 200) events.shift();
}
window.__getLog = () => events;   // 控制台可拿

/* ---------------- 交互 ---------------- */
el.btnStart.addEventListener('click', async () => {
  const ok = await openCamera(S.facing);
  if (ok) el.gate.classList.add('hide');
});

el.btnRetry.addEventListener('click', async () => {
  S.facing = 'environment';
  const ok = await openCamera(S.facing);
  if (ok) el.gate.classList.add('hide');
});

el.btnToggle.addEventListener('click', () => {
  S.enabled = !S.enabled;
  el.btnToggle.textContent = S.enabled ? '关闭' : '开启';
  el.dot.className = 'dot' + (S.enabled ? '' : ' off');
  el.stateLabel.textContent = S.enabled ? '助手开' : '助手关';
  if (!S.enabled) { S.prompt = null; octx.clearRect(0, 0, el.overlay.width, el.overlay.height); }
  logEvent('toggle', S.enabled ? 'on' : 'off');
});

el.btnFlip.addEventListener('click', async () => {
  S.facing = S.facing === 'environment' ? 'user' : 'environment';
  try { await openCamera(S.facing); } catch (e) { flashSuggest('切换镜头失败：' + e.message); }
});

el.shutter.addEventListener('click', takePhoto);

/* ---------------- 点击标记主体 ---------------- */
// 说明：浏览器/WebView 无法控制硬件对焦，但点击位置可以当作"我要拍的主体"，
// 后续所有建议都围绕这个点来给（比自动猜主体更准）。
el.overlay.addEventListener('pointerdown', (e) => {
  if (!S.stream) return;
  const r = el.overlay.getBoundingClientRect();
  const x = clamp01((e.clientX - r.left) / r.width);
  const y = clamp01((e.clientY - r.top) / r.height);

  S.focusPoint = { x, y, at: performance.now() };

  // 黄色对焦框动画
  el.focusRing.style.left = (x * 100) + '%';
  el.focusRing.style.top = (y * 100) + '%';
  el.focusRing.style.display = 'block';
  el.focusRing.classList.remove('pulse');
  void el.focusRing.offsetWidth;      // 强制重排，让动画重播
  el.focusRing.classList.add('pulse');
  clearTimeout(el.focusRing._hide);
  el.focusRing._hide = setTimeout(() => { el.focusRing.style.display = 'none'; }, 1200);

  // 顺便清掉当前提示，让用户重新看构图
  S.prompt = null;

  // 尝试触发对焦，并给出可见反馈
  tryFocus(x, y).then((how) => {
    const msg = {
      point: '已对焦到该点',
      manual: '已手动对焦',
      'single-shot': '已重新对焦',
      continuous: '连续对焦中',
      unsupported: '此设备不支持点击对焦',
    }[how] || '';
    flashSuggest(`🎯 ${msg}`);
    logEvent('focus', `${x.toFixed(2)},${y.toFixed(2)} ${how}`);
  });

  logEvent('focus-point', `${x.toFixed(2)},${y.toFixed(2)}`);
});

function clamp01(v) { return Math.max(0, Math.min(1, v)); }

/* ---------------- 点击对焦（尽力而为） ----------------
 * WebView 的限制：W3C 规范里的点对焦是 `pointsOfInterest`，
 * 但 Chrome/Android WebView 基本不实现；能用的只有 focusMode。
 * 策略：按能力从强到弱尝试，全部失败就提示"设备不支持"。
 */
async function tryFocus(nx, ny) {
  const t = S.track;
  if (!t || !t.applyConstraints) return 'unsupported';

  let caps = {};
  try { caps = t.getCapabilities ? t.getCapabilities() : {}; } catch (e) { /* 忽略 */ }
  const modes = caps.focusMode || [];
  S.focusMode = modes.join(',');

  // ① 规范里的点对焦（几乎没实现，但试一下不亏）
  if (caps.pointsOfInterest !== undefined || modes.length === 0) {
    try {
      await t.applyConstraints({
        advanced: [{ pointsOfInterest: [{ x: nx, y: ny }] }],
      });
      return 'point';
    } catch (e) { /* 继续 */ }
  }

  // ② 手动对焦 + 距离（部分设备支持）
  if (modes.includes('manual') && caps.focusDistance) {
    try {
      await t.applyConstraints({ advanced: [{ focusMode: 'manual', focusDistance: caps.focusDistance.max }] });
      setTimeout(() => {
        t.applyConstraints({ advanced: [{ focusMode: 'continuous' }] }).catch(() => {});
      }, 1600);
      return 'manual';
    } catch (e) { /* 继续 */ }
  }

  // ③ 兜底：强制单次对焦，让镜头重新合焦一次
  if (modes.includes('single-shot')) {
    try {
      await t.applyConstraints({ advanced: [{ focusMode: 'single-shot' }] });
      setTimeout(() => {
        t.applyConstraints({ advanced: [{ focusMode: 'continuous' }] }).catch(() => {});
      }, 1600);
      return 'single-shot';
    } catch (e) { /* 继续 */ }
  }

  // ④ 切一下模式，逼某些机型重新对焦
  if (modes.includes('continuous')) {
    try {
      await t.applyConstraints({ advanced: [{ focusMode: 'continuous' }] });
      return 'continuous';
    } catch (e) { /* 继续 */ }
  }

  return 'unsupported';
}

/* ---------------- 使用说明 ---------------- */
function showGuide() { el.guide.classList.add('show'); }
function hideGuide() {
  el.guide.classList.remove('show');
  try { localStorage.setItem('pa.guideSeen', '1'); } catch (e) { /* 忽略 */ }
}
el.btnGuideOk.addEventListener('click', hideGuide);
el.btnHelp.addEventListener('click', showGuide);
el.guide.addEventListener('click', (e) => { if (e.target === el.guide) hideGuide(); });

// 首次进入自动展示一次
try {
  if (!localStorage.getItem('pa.guideSeen')) setTimeout(showGuide, 900);
} catch (e) { /* 忽略 */ }

window.addEventListener('resize', fitOverlay);
document.addEventListener('keydown', (e) => {
  if (e.code === 'Space') { e.preventDefault(); takePhoto(); }
  if (e.key === 'm' || e.key === 'M') el.btnToggle.click();
  if (e.key === 'f' || e.key === 'F') el.btnFlip.click();
  if (e.key === 'g' || e.key === 'G') {
    S.aimOn = !S.aimOn;
    flashSuggest(S.aimOn ? '目标引导：开' : '目标引导：关');
    logEvent('aim', S.aimOn ? 'on' : 'off');
  }
});

if (!window.isSecureContext || !navigator.mediaDevices) {
  document.getElementById('secureWarn').style.display = 'block';
  document.getElementById('secureWarn').textContent =
    location.protocol === 'file:'
      ? '⚠ 你是直接双击打开的 html：浏览器不会给摄像头权限。请用 run.py 启动后访问 http://127.0.0.1:端口/live.html'
      : '⚠ 当前不是 HTTPS/localhost，浏览器不会给摄像头权限。';
}
