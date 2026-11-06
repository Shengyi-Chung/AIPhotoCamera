/**
 * showcase.js —— 演示版逻辑
 * 合成场景（可倾斜/变暗/加反光）→ 复用 detect.js 分析 → 一句话提示 + AR 叠加
 * 一键演示：自动播放 30 秒的故事线
 */
import { toGray, estimateHorizon, measureBrightness, detectGlare, computeAesthetics } from './detect.js';

const VW = 800, VH = 600;        // 画布尺寸
const AW = 320, AH = 240;        // 分析分辨率

const scene = document.getElementById('scene');
const overlay = document.getElementById('overlay');
const sctx = scene.getContext('2d');
const octx = overlay.getContext('2d');

const analysis = Object.assign(document.createElement('canvas'), { width: AW, height: AH });
const actx = analysis.getContext('2d', { willReadFrequently: true });

const el = {
  dot: document.getElementById('dot'),
  stateLabel: document.getElementById('stateLabel'),
  hint: document.getElementById('hint'),
  hintIcon: document.getElementById('hintIcon'),
  hintMain: document.getElementById('hintMain'),
  hintSub: document.getElementById('hintSub'),
  kAngle: document.getElementById('kAngle'),
  kConf: document.getElementById('kConf'),
  kLuma: document.getElementById('kLuma'),
  kGlare: document.getElementById('kGlare'),
  kPrompt: document.getElementById('kPrompt'),
  stepText: document.getElementById('stepText'),
  btnAuto: document.getElementById('btnAuto'),
  btnTiltAuto: document.getElementById('btnTiltAuto'),
  btnDark: document.getElementById('btnDark'),
  btnGlare: document.getElementById('btnGlare'),
  btnToggle: document.getElementById('btnToggle'),
  shutter: document.getElementById('shutter'),
  intro: document.getElementById('intro'),
  btnStart: document.getElementById('btnStart'),
  btnCreative: document.getElementById('btnCreative'),
  creativeText: document.getElementById('creativeText'),
};

const S = {
  enabled: true,
  tilt: 0,            // 场景倾斜（度）
  luma: 1,            // 亮度系数 0..1
  glare: 0,           // 反光强度 0..1
  target: { tilt: 0, luma: 1, glare: 0 },
  prompt: null,       // {cue, main, sub, icon}
  analysis: null,
  auto: null,         // 一键演示的定时器/时间线
  autoT0: 0,
  creative: null,     // {text, ghost, until} 灵感模式
  aes: null,
};

/* ---------------- 场景绘制 ---------------- */
function drawScene() {
  const t = S.tilt * Math.PI / 180;
  sctx.save();
  sctx.clearRect(0, 0, VW, VH);

  // 背景：天空渐变
  const sky = sctx.createLinearGradient(0, 0, 0, VH);
  sky.addColorStop(0, '#9fc3dd');
  sky.addColorStop(1, '#dfe9f0');
  sctx.fillStyle = sky;
  sctx.fillRect(0, 0, VW, VH);

  // 地平线（倾斜）
  sctx.translate(VW / 2, VH * 0.56);
  sctx.rotate(t);
  sctx.translate(-VW / 2, -VH * 0.56);

  // 远处地面
  const ground = sctx.createLinearGradient(0, VH * 0.56, 0, VH);
  ground.addColorStop(0, '#8c9a72');
  ground.addColorStop(1, '#4b5a3d');
  sctx.fillStyle = ground;
  sctx.fillRect(-VW, VH * 0.56, VW * 3, VH);

  // 山
  sctx.fillStyle = '#6f8a93';
  sctx.beginPath();
  sctx.moveTo(-200, VH * 0.56);
  sctx.lineTo(120, VH * 0.30);
  sctx.lineTo(330, VH * 0.50);
  sctx.lineTo(520, VH * 0.26);
  sctx.lineTo(820, VH * 0.52);
  sctx.lineTo(820, VH * 0.56);
  sctx.closePath();
  sctx.fill();

  // 人物（主体）
  sctx.fillStyle = '#2f3a44';
  sctx.beginPath(); sctx.arc(500, 300, 30, 0, Math.PI * 2); sctx.fill();      // 头
  sctx.beginPath();
  sctx.moveTo(500, 330); sctx.lineTo(466, 460); sctx.lineTo(534, 460); sctx.closePath();
  sctx.fill();                                                                // 身体

  sctx.restore();

  // 反光（屏幕/窗户反光）
  if (S.glare > 0.01) {
    sctx.save();
    sctx.globalAlpha = 0.9 * S.glare;
    const g = sctx.createRadialGradient(VW * 0.24, VH * 0.42, 10, VW * 0.24, VH * 0.42, 190);
    g.addColorStop(0, 'rgba(255,255,255,0.98)');
    g.addColorStop(0.55, 'rgba(255,255,255,0.85)');
    g.addColorStop(1, 'rgba(255,255,255,0)');
    sctx.fillStyle = g;
    sctx.beginPath(); sctx.ellipse(VW * 0.24, VH * 0.42, 200, 120, 0.3, 0, Math.PI * 2); sctx.fill();
    sctx.restore();
  }

  // 亮度（压暗）
  if (S.luma < 0.99) {
    sctx.save();
    sctx.fillStyle = `rgba(0,0,0,${(1 - S.luma) * 0.88})`;
    sctx.fillRect(0, 0, VW, VH);
    sctx.restore();
  }
}

/* ---------------- 分析 ---------------- */
function analyze() {
  actx.drawImage(scene, 0, 0, AW, AH);
  const img = actx.getImageData(0, 0, AW, AH);
  const gray = toGray(img.data, AW, AH);
  return {
    hz: estimateHorizon(gray, AW, AH),
    br: measureBrightness(gray),
    gl: detectGlare(img.data, AW, AH),
  };
}

/* ---------------- 提示决策（对应设计里的规则层） ---------------- */
const CUE = {
  tilt: { icon: '↺', main: 'Level the horizon', sub: '把相机转正，让地平线回到水平' },
  dark: { icon: '☀', main: 'Move to the light', sub: '画面偏暗，转向光源或靠近窗户' },
  glare: { icon: '◐', main: 'Angle away from the glare', sub: '检测到反光，侧一点角度即可避开' },
  ready: { icon: '✓', main: 'Hold still — you can shoot', sub: '画面就绪，可以按下快门了' },
};

function decide(a) {
  if (!S.enabled) return null;
  if (a.br.meanLuma < 62 && a.br.darkRatio > 0.35) return 'dark';
  if (a.gl.ratio > 0.06) return 'glare';
  if (Math.abs(a.hz.angleDeg) > 5 && a.hz.confidence >= 0.55) return 'tilt';
  return a.hz.confidence > 0.5 ? 'ready' : null;
}

/* ---------------- 渲染提示 ---------------- */
function renderHint(cue) {
  if (cue !== S.prompt) {
    S.prompt = cue;
    if (cue) {
      const c = CUE[cue];
      el.hintIcon.textContent = c.icon;
      el.hintMain.textContent = c.main;
      el.hintSub.textContent = c.sub;
      el.hint.classList.add('show');
      el.kPrompt.textContent = c.main;
    } else {
      el.hint.classList.remove('show');
      el.kPrompt.textContent = '—';
    }
  }
}

function renderOverlay(a) {
  octx.clearRect(0, 0, VW, VH);
  if (!S.enabled) return;

  // 倾斜时画“理想水平线” + 旋转箭头
  if (S.prompt === 'tilt' && a) {
    octx.strokeStyle = 'rgba(63,214,192,.95)';
    octx.lineWidth = 2.5;
    octx.setLineDash([10, 8]);
    octx.beginPath();
    octx.moveTo(30, VH / 2); octx.lineTo(VW - 30, VH / 2);
    octx.stroke();
    octx.setLineDash([]);

    const dir = a.hz.angleDeg > 0 ? '↺' : '↻';
    octx.fillStyle = 'rgba(63,214,192,.98)';
    octx.font = 'bold 40px "Microsoft YaHei", sans-serif';
    octx.textAlign = 'center';
    octx.fillText(dir, VW / 2, VH / 2 - 26);
    octx.font = 'bold 16px "Microsoft YaHei", sans-serif';
    octx.fillText(`当前 ${Math.abs(a.hz.angleDeg).toFixed(1)}°`, VW / 2, VH / 2 + 30);
  }

  // 反光时框出高光区域
  if (S.prompt === 'glare') {
    octx.strokeStyle = 'rgba(255,180,84,.95)';
    octx.lineWidth = 2.5;
    octx.beginPath();
    octx.ellipse(VW * 0.24, VH * 0.42, 190, 120, 0.3, 0, Math.PI * 2);
    octx.stroke();
  }
}

/* ---------------- 状态栏 ---------------- */
function renderHud(a) {
  if (a) {
    el.kAngle.textContent = `${a.hz.angleDeg.toFixed(1)}°`;
    el.kConf.textContent = a.hz.confidence.toFixed(2);
    el.kLuma.textContent = a.br.meanLuma.toFixed(0);
    el.kGlare.textContent = `${(a.gl.ratio * 100).toFixed(1)}%`;
  }
  el.dot.className = 'dot' + (S.enabled ? '' : ' off');
  el.stateLabel.textContent = S.enabled ? '助手已开启' : '助手已关闭';
}

/* ---------------- 灵感模式：审美建议 ---------------- */
const AESTHETIC = [
  {
    test: a => a.subjectHeight > 0 && a.subjectHeight < 0.32,
    text: '走近一点 —— 让人物占满画面',
    why: '主体只占 ' + '%s' + ' 画面高度，放大后更有存在感',
    ghost: { x: 250, y: 120, w: 300, h: 380 },
  },
  {
    test: a => a.horizonRatio > 0.42 && a.horizonRatio < 0.58,
    text: '把相机放低一点 —— 让地平线高一些',
    why: '地平线正中间容易显得平；放高或放低都更有张力',
    ghost: { y: 0.3 },
  },
  {
    test: a => a.borderClutter > 0.22,
    text: '往右挪一步 —— 让边缘干净些',
    why: '画面边缘细节太多，会抢走注意力',
    ghost: null,
  },
  {
    test: a => a.symmetry > 0.86,
    text: '这张本来就对称 —— 干脆对准中线',
    why: '画面接近左右对称，正中构图会更稳',
    ghost: { x: 300, y: 80, w: 200, h: 440 },
  },
];

function pickCreative(aes) {
  const hit = AESTHETIC.find(r => r.test(aes));
  if (!hit) {
    return { text: '现在这样就不错 —— 试试蹲下来拍一张', why: '换个视角，同一场景会不一样', ghost: { y: 0.64 } };
  }
  const why = hit.why.replace('%s', Math.round(aes.subjectHeight * 100) + '%');
  return { text: hit.text, why, ghost: hit.ghost };
}

function renderCreative() {
  const c = S.creative;
  if (!c) return;
  if (Date.now() > c.until) { S.creative = null; return; }

  const pulse = 0.55 + 0.45 * Math.sin(performance.now() / 260);
  octx.save();
  octx.setLineDash([12, 9]);
  octx.lineWidth = 3;
  octx.strokeStyle = `rgba(63,214,192,${pulse.toFixed(2)})`;

  if (c.ghost && c.ghost.w) {
    octx.strokeRect(c.ghost.x, c.ghost.y, c.ghost.w, c.ghost.h);
  } else if (c.ghost && c.ghost.y !== undefined && c.ghost.y < 1) {
    const yy = c.ghost.y * VH;
    octx.beginPath(); octx.moveTo(20, yy); octx.lineTo(VW - 20, yy); octx.stroke();
  }

  octx.setLineDash([]);
  octx.fillStyle = 'rgba(63,214,192,.95)';
  octx.font = 'bold 20px "Microsoft YaHei", sans-serif';
  octx.textAlign = 'center';
  octx.fillText('✨ ' + c.text, VW / 2, VH - 40);
  octx.restore();
}

/* ---------------- 主循环 ---------------- */
function tick() {
  // 平滑逼近目标值（模拟相机移动）
  S.tilt += (S.target.tilt - S.tilt) * 0.06;
  S.luma += (S.target.luma - S.luma) * 0.06;
  S.glare += (S.target.glare - S.glare) * 0.06;

  drawScene();
  const a = analyze();
  S.analysis = a;
  S.aes = computeAesthetics(toGray(analysis.getImageData(0, 0, AW, AH).data, AW, AH), AW, AH, a.hz);
  const cue = decide(a);
  renderHint(cue);
  renderOverlay(a);
  renderCreative();
  renderHud(a);
  requestAnimationFrame(tick);
}

/* ---------------- 一键演示（30 秒时间线） ---------------- */
const STEPS = [
  { at: 0,   set: { tilt: 0, luma: 1, glare: 0 },     note: '① 正常取景：画面水平、明亮' },
  { at: 3000, set: { tilt: 14 },                       note: '② 相机拿歪了 14°：提示出现' },
  { at: 9000, set: { tilt: 0 },                        note: '③ 摆正：提示消失，显示"可以拍了"' },
  { at: 12000, set: { luma: 0.45 },                    note: '④ 光线太暗：提示 "Move to the light"' },
  { at: 15000, set: { luma: 1 },                       note: '⑤ 回到亮处：提示消失' },
  { at: 18000, set: { glare: 1 },                      note: '⑥ 出现反光：提示 "Angle away from the glare"' },
  { at: 21000, set: { glare: 0 },                      note: '⑦ 避开反光：提示消失' },
  { at: 24000, set: { tilt: -10 },                     note: '⑧ 往另一边歪：反向提示' },
  { at: 27000, set: { tilt: 0 },                       note: '⑨ 结束：回到就绪状态' },
];

function playAuto() {
  stopAuto();
  S.autoT0 = performance.now();
  S.auto = { idx: 0, marks: STEPS };
  const run = () => {
    if (!S.auto) return;
    const t = performance.now() - S.autoT0;
    while (S.auto.idx < STEPS.length && t >= STEPS[S.auto.idx].at) {
      const s = STEPS[S.auto.idx];
      Object.assign(S.target, s.set);
      el.stepText.textContent = s.note;
      S.auto.idx++;
    }
    if (S.auto.idx >= STEPS.length && t > 30000) { stopAuto(); el.stepText.textContent = '演示结束，可再次播放'; return; }
    S.auto.raf = requestAnimationFrame(run);
  };
  run();
}

function stopAuto() {
  if (S.auto && S.auto.raf) cancelAnimationFrame(S.auto.raf);
  S.auto = null;
}

/* ---------------- 交互 ---------------- */
el.btnAuto.addEventListener('click', playAuto);
el.btnTiltAuto.addEventListener('click', () => { stopAuto(); S.target = { ...S.target, tilt: 14 }; el.stepText.textContent = '模拟：相机拿歪 14°'; });

el.btnCreative.addEventListener('click', () => {
  if (!S.enabled) { el.creativeText.textContent = '助手已关闭 —— 需要灵感时先重新开启'; return; }
  const sug = pickCreative(S.aes || { subjectHeight: 0, horizonRatio: 0.5, borderClutter: 0, symmetry: 0 });
  S.creative = { ...sug, until: Date.now() + 7000 };
  el.creativeText.textContent = sug.text + '（' + sug.why + '）';
});
el.btnDark.addEventListener('click', () => { stopAuto(); S.target = { ...S.target, luma: 0.45 }; el.stepText.textContent = '模拟：光线太暗'; });
el.btnGlare.addEventListener('click', () => { stopAuto(); S.target = { ...S.target, glare: 1 }; el.stepText.textContent = '模拟：镜头反光'; });
el.btnToggle.addEventListener('click', () => {
  S.enabled = !S.enabled;
  el.btnToggle.textContent = S.enabled ? '关闭助手（对照组 A）' : '重新开启助手（对照组 B）';
  el.stepText.textContent = S.enabled ? '助手已开启：对比看提示是否出现' : '助手已关闭：同一场景，任何提示都没有';
  renderOverlay(S.analysis);
});

el.shutter.addEventListener('click', () => {
  // 快门反馈：闪一下 + 重置场景到正常
  S.target = { tilt: 0, luma: 1, glare: 0 };
  el.stepText.textContent = '已按下快门（演示场景）';
});

el.btnStart.addEventListener('click', () => {
  el.intro.classList.add('hide');
  playAuto();
});

requestAnimationFrame(tick);
