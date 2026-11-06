/**
 * 单元测试：场景识别 + 目标引导
 * 运行：node test_aim.js
 */
import { detectVerticals, estimateSky, foregroundTexture, classifyScene } from './detect.js';
import { buildAim } from './aim.js';

let pass = 0, fail = 0;
const check = (name, cond, detail = '') => {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + ' ' + detail); }
};

const W = 320, H = 240;

/** 合成建筑图：上半天空 + 下半楼体，带 N 条竖线（可倾斜） */
function makeBuilding(vertDeg = 0, skyRatio = 0.18, lineCount = 6) {
  const g = new Uint8Array(W * H).fill(150);
  const skyH = Math.round(H * skyRatio);
  for (let y = 0; y < skyH; y++) for (let x = 0; x < W; x++) g[y * W + x] = 205; // 天空偏亮
  // 楼体
  for (let y = skyH; y < H; y++) for (let x = 0; x < W; x++) g[y * W + x] = 95;
  // 竖线（带倾角）
  const rad = vertDeg * Math.PI / 180;
  for (let i = 0; i < lineCount; i++) {
    const x0 = Math.round((i + 0.5) * W / lineCount);
    for (let y = skyH + 2; y < H - 2; y++) {
      const x = Math.round(x0 + (y - skyH) * Math.tan(rad));
      if (x > 0 && x < W) g[y * W + x] = 30;
    }
  }
  return g;
}

/** 合成天空 RGBA（用于 estimateSky） */
function makeSkyRGBA(skyRatio) {
  const data = new Uint8ClampedArray(W * H * 4);
  const skyH = Math.round(H * skyRatio);
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const p = (y * W + x) * 4;
      const isSky = y < skyH;
      data[p] = isSky ? 150 : 80;       // R
      data[p + 1] = isSky ? 185 : 95;   // G
      data[p + 2] = isSky ? 225 : 70;   // B
      data[p + 3] = 255;
    }
  }
  return data;
}

console.log('detectVerticals');
{
  const g = makeBuilding(0);
  const r = detectVerticals(g, W, H);
  check('竖直楼体 → 角度≈0', Math.abs(r.angleDeg) < 1.2, `angle=${r.angleDeg.toFixed(2)}`);
  check('竖直楼体 → 有足够覆盖率', r.coverage > 0.4, `cov=${r.coverage.toFixed(2)}`);
}
{
  const g = makeBuilding(6);
  const r = detectVerticals(g, W, H);
  check('倾斜 6° → 估算 4.5~7.5°', r.angleDeg > 4.5 && r.angleDeg < 7.5, `angle=${r.angleDeg.toFixed(2)}`);
}

console.log('estimateSky');
{
  const r = estimateSky(makeSkyRGBA(0.2), W, H);
  check('20% 天空 → 占比≈0.2', Math.abs(r.ratio - 0.2) < 0.03, `ratio=${r.ratio.toFixed(3)}`);
  check('天空底边 ≈ 0.2', Math.abs(r.skyBottomRatio - 0.2) < 0.03, `bottom=${r.skyBottomRatio.toFixed(3)}`);
}
{
  const r = estimateSky(new Uint8ClampedArray(W * H * 4).fill(90), W, H); // 全暗
  check('无天空 → 占比≈0', r.ratio < 0.02, `ratio=${r.ratio.toFixed(3)}`);
}

console.log('foregroundTexture');
{
  const flat = new Uint8Array(W * H).fill(120);
  check('纯色 → 纹理密度≈0', foregroundTexture(flat, W, H).density < 0.01);
  const noisy = new Uint8Array(W * H);
  for (let i = 0; i < noisy.length; i++) noisy[i] = (i * 37) % 255;
  check('噪声图 → 纹理密度高', foregroundTexture(noisy, W, H).density > 0.3);
}

console.log('classifyScene');
{
  const g = makeBuilding(0, 0.18);
  const vert = detectVerticals(g, W, H);
  const sky = estimateSky(makeSkyRGBA(0.18), W, H);
  check('竖线+天空 → building',
    classifyScene({ vert, sky, hz: { confidence: 0.5, lineYRatio: 0.5 }, aes: {} }) === 'building');
}
{
  const g = new Uint8Array(W * H).fill(150);
  const skyH = 80;
  for (let y = 0; y < skyH; y++) for (let x = 0; x < W; x++) g[y * W + x] = 205;
  for (let y = skyH; y < H; y++) for (let x = 0; x < W; x++) g[y * W + x] = 90;
  const vert = detectVerticals(g, W, H);
  const sky = estimateSky(makeSkyRGBA(0.33), W, H);
  const s = classifyScene({ vert, sky, hz: { confidence: 0.5, lineYRatio: 0.33 }, aes: {} });
  check('无竖线+天空+地平线 → landscape', s === 'landscape', 'got=' + s);
}

console.log('buildAim · building');
{
  // 楼顶刚好在 14%（理想区间内），竖线竖直 → 达成
  const m = { scene: 'building', vert: { angleDeg: 0.4, confidence: 0.8, coverage: 0.6 }, sky: { skyBottomRatio: 0.14 }, hz: {}, fg: {} };
  const a = buildAim(m);
  check('建筑·理想构图 → achieved', a.achieved === true, JSON.stringify({ t: a.text, sub: a.sub }));
  check('建筑·返回 frame 类型', a.type === 'frame');
}
{
  // 楼顶被切（天空只有 3%）→ 提示后退
  const m = { scene: 'building', vert: { angleDeg: 0.5, confidence: 0.8, coverage: 0.6 }, sky: { skyBottomRatio: 0.03 }, hz: {}, fg: {} };
  const a = buildAim(m);
  check('建筑·楼顶被切 → 提示后退', a.achieved === false && a.text.includes('后退'), a.text);
}
{
  // 建筑太小（天空占 40%）
  const m = { scene: 'building', vert: { angleDeg: 0.5, confidence: 0.8, coverage: 0.6 }, sky: { skyBottomRatio: 0.40 }, hz: {}, fg: {} };
  const a = buildAim(m);
  check('建筑·建筑太小 → 提示走近', a.text.includes('走近'), a.text);
}
{
  // 竖线歪 5°（透视）
  const m = { scene: 'building', vert: { angleDeg: 5, confidence: 0.8, coverage: 0.6 }, sky: { skyBottomRatio: 0.14 }, hz: {}, fg: {} };
  const a = buildAim(m);
  check('建筑·竖线歪 → 提示转手机', a.text.includes('手机往'), a.text);
}

console.log('buildAim · landscape');
{
  // 地平线正好在 1/3 → 达成
  const m = { scene: 'landscape', hz: { confidence: 0.7, lineYRatio: 0.33 }, vert: {}, sky: {}, fg: {} };
  const a = buildAim(m);
  check('风景·地平线在三分线 → achieved', a.achieved === true, a.text);
  check('风景·返回 line 类型', a.type === 'line');
}
{
  // 地平线在正中间 → 需要移动
  const m = { scene: 'landscape', hz: { confidence: 0.7, lineYRatio: 0.5 }, vert: {}, sky: {}, fg: {} };
  const a = buildAim(m);
  check('风景·地平线居中 → 未达成且给方向', a.achieved === false && /抬|压/.test(a.text), a.text);
}
{
  // 没有地平线
  const m = { scene: 'landscape', hz: { confidence: 0.05, lineYRatio: 0.5 }, vert: {}, sky: {}, fg: {} };
  const a = buildAim(m);
  check('风景·无地平线 → 提示抬高手机', a.achieved === false && a.text.includes('抬'), a.text);
}

console.log('buildAim · other');
{
  const a = buildAim({ scene: 'other' });
  check('通用场景 → 不给目标', a.type === null && a.achieved === false);
}

console.log(`\n结果：${pass} 通过 / ${fail} 失败`);
process.exit(fail ? 1 : 0);
