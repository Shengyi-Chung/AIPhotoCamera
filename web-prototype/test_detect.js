/**
 * 单元测试：detect.js 的三个 cue
 * 运行：node test_detect.js
 */
import { toGray, estimateHorizon, measureBrightness, detectGlare } from './detect.js';

let pass = 0, fail = 0;
function check(name, cond, detail = '') {
  if (cond) { pass++; console.log(`  ✓ ${name}`); }
  else { fail++; console.log(`  ✗ ${name} ${detail}`); }
}

const W = 320, H = 240;

/** 合成一张“倾斜地平线”灰度图 */
function makeHorizon(slopeDeg, skyVal = 200, groundVal = 45) {
  const slope = Math.tan(slopeDeg * Math.PI / 180);
  const g = new Uint8Array(W * H);
  for (let x = 0; x < W; x++) {
    const yLine = H / 2 + slope * (x - W / 2);
    for (let y = 0; y < H; y++) {
      const base = y < yLine ? skyVal : groundVal;
      const noise = ((x * 7 + y * 13) % 11) - 5;   // 伪随机噪声
      g[y * W + x] = Math.max(0, Math.min(255, base + noise));
    }
  }
  return g;
}

console.log('estimateHorizon');
{
  const r = estimateHorizon(makeHorizon(0), W, H);
  check('水平线 → 角度≈0°', Math.abs(r.angleDeg) < 1.0, `angle=${r.angleDeg.toFixed(2)}`);
  check('水平线 → 置信度高', r.confidence > 0.6, `conf=${r.confidence.toFixed(2)}`);
}
{
  const r = estimateHorizon(makeHorizon(10), W, H);
  check('10° 倾斜 → 估算 8.5–11.5°', r.angleDeg > 8.5 && r.angleDeg < 11.5, `angle=${r.angleDeg.toFixed(2)}`);
}
{
  const r = estimateHorizon(makeHorizon(-6), W, H);
  check('-6° 倾斜 → 估算 -7.5–-4.5°', r.angleDeg < -4.5 && r.angleDeg > -7.5, `angle=${r.angleDeg.toFixed(2)}`);
}

console.log('measureBrightness');
{
  const dark = new Uint8Array(W * H).fill(30);
  const b = measureBrightness(dark);
  check('全暗图 → 暗部占比 1.0', b.darkRatio === 1, `dark=${b.darkRatio}`);
  check('全暗图 → 平均亮度 30', b.meanLuma === 30, `luma=${b.meanLuma}`);
  const bright = new Uint8Array(W * H).fill(250);
  check('全亮图 → 溢出占比 1.0', measureBrightness(bright).overflowRatio === 1);
}

console.log('detectGlare');
{
  const data = new Uint8ClampedArray(W * H * 4);
  for (let i = 0; i < W * H; i++) {
    const white = i < W * H * 0.2;                 // 前 20% 像素为纯白
    data[i * 4] = data[i * 4 + 1] = data[i * 4 + 2] = white ? 250 : 60;
    data[i * 4 + 3] = 255;
  }
  const g = detectGlare(data, W, H);
  check('20% 纯白区域 → 反光占比 ≈0.2', Math.abs(g.ratio - 0.2) < 0.01, `ratio=${g.ratio.toFixed(3)}`);
}

console.log('虚拟倾斜（整帧旋转后再检测）');
{
  // 最近邻旋转：模拟浏览器里 canvas.rotate 的效果
  function rotateGray(g, deg) {
    const rad = deg * Math.PI / 180, cos = Math.cos(rad), sin = Math.sin(rad);
    const out = new Uint8Array(W * H);
    const cx = W / 2, cy = H / 2;
    for (let y = 0; y < H; y++) {
      for (let x = 0; x < W; x++) {
        const dx = x - cx, dy = y - cy;
        const sx = Math.round(cx + dx * cos + dy * sin);
        const sy = Math.round(cy - dx * sin + dy * cos);
        const inX = sx >= 0 && sx < W, inY = sy >= 0 && sy < H;
        out[y * W + x] = (inX && inY) ? g[sy * W + sx] : g[y * W + x];
      }
    }
    return out;
  }
  for (const deg of [6, -9]) {
    const r = estimateHorizon(rotateGray(makeHorizon(0), deg), W, H);
    check(`旋转 ${deg}° → 估算 ${deg - 1.5}~${deg + 1.5}°`,
          Math.abs(r.angleDeg - deg) < 1.5, `angle=${r.angleDeg.toFixed(2)}`);
  }
}

console.log('toGray');
{
  const data = new Uint8ClampedArray([255, 255, 255, 255, 0, 0, 0, 255]);
  const g = toGray(data, 2, 1);
  check('白色 → 255', g[0] === 255);
  check('黑色 → 0', g[1] === 0);
}

console.log(`\n结果：${pass} 通过 / ${fail} 失败`);
process.exit(fail ? 1 : 0);
