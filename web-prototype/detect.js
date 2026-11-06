/**
 * detect.js —— 纯计算模块（无 DOM，可在 Node 里单测）
 * 三个 cue：地平线倾斜 / 明暗 / 反光
 */

/** RGBA ImageData → 灰度 Uint8Array */
export function toGray(data, w, h) {
  const g = new Uint8Array(w * h);
  for (let i = 0, p = 0; i < g.length; i++, p += 4) {
    // 0.299R + 0.587G + 0.114B
    g[i] = (data[p] * 77 + data[p + 1] * 150 + data[p + 2] * 29) >> 8;
  }
  return g;
}

function weightedFit(pts) {
  let sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
  for (const p of pts) {
    const w = p.w;
    sw += w; sx += w * p.x; sy += w * p.y;
    sxx += w * p.x * p.x; sxy += w * p.x * p.y;
  }
  const den = sw * sxx - sx * sx;
  if (Math.abs(den) < 1e-6) return { a: 0, b: sy / sw };
  const a = (sw * sxy - sx * sy) / den;
  const b = (sxx * sy - sx * sxy) / den;
  return { a, b };
}

function stddev(arr) {
  const m = arr.reduce((s, v) => s + v, 0) / arr.length;
  return Math.sqrt(arr.reduce((s, v) => s + (v - m) * (v - m), 0) / arr.length);
}

/**
 * 估计地平线倾斜角。
 * 做法：每列找“最强水平边”所在行 → 加权最小二乘拟合直线 → 内点筛选后重拟合。
 * @returns {{angleDeg:number, confidence:number, sampleCount:number}}
 */
export function estimateHorizon(gray, w, h) {
  const pts = [];
  const minStrength = 12;
  const m = 2;

  for (let x = m; x < w - m; x++) {
    let bestG = 0, bestY = -1;
    for (let y = m; y < h - m; y++) {
      const i = y * w + x;
      const gy = Math.abs(gray[i - w] - gray[i + w]);        // 水平边响应
      const gx = Math.abs(gray[i - 1] - gray[i + 1]) * 0.25; // 抑制竖直边
      const g = gy - gx;
      if (g > bestG) { bestG = g; bestY = y; }
    }
    if (bestG >= minStrength && bestY > 0) pts.push({ x, y: bestY, w: bestG });
  }

  if (pts.length < w * 0.35) return { angleDeg: 0, confidence: 0, sampleCount: pts.length };

  let fit = weightedFit(pts);
  const resid = pts.map(p => Math.abs(p.y - (fit.a * p.x + fit.b)));
  const sd = stddev(resid);
  const keep = pts.filter((p, i) => resid[i] <= Math.max(2, 2 * sd));
  if (keep.length < w * 0.3) return { angleDeg: 0, confidence: 0, sampleCount: keep.length };

  fit = weightedFit(keep);
  const angleDeg = Math.atan(fit.a) * 180 / Math.PI;
  const cover = keep.length / w;
  const stable = sd < 3 ? 1 : Math.max(0.4, 3 / sd);
  const lineY = fit.a * (w / 2) + fit.b;          // 画面中心处的地平线高度
  return {
    angleDeg,
    confidence: Math.min(1, cover * 1.2) * stable,
    sampleCount: keep.length,
    lineY,                                        // 像素（分析分辨率下）
    lineYRatio: lineY / h,                        // 0=顶部 1=底部
    slope: fit.a,
  };
}

/** 明暗：平均亮度 + 高光溢出比例 + 暗部比例 */
export function measureBrightness(gray) {
  let sum = 0, overflow = 0, dark = 0;
  for (let i = 0; i < gray.length; i++) {
    const v = gray[i];
    sum += v;
    if (v > 245) overflow++;
    if (v < 40) dark++;
  }
  const n = gray.length;
  return { meanLuma: sum / n, overflowRatio: overflow / n, darkRatio: dark / n };
}

/** 反光：高亮 + 低饱和像素占比 */
export function detectGlare(data, w, h) {
  let n = 0, hits = 0;
  for (let p = 0; p < data.length; p += 4) {
    const r = data[p], g = data[p + 1], b = data[p + 2];
    const max = Math.max(r, g, b), min = Math.min(r, g, b);
    const luma = (r * 77 + g * 150 + b * 29) >> 8;
    const sat = max === 0 ? 0 : (max - min) / max;
    n++;
    if (luma > 235 && sat < 0.12) hits++;
  }
  return { ratio: n ? hits / n : 0 };
}

/**
 * 审美信号（不是“错误”，只是“另一种可能”的线索）
 * @returns {{subjectHeight:number, subjectInFrame:boolean, borderClutter:number, symmetry:number, horizonRatio:number}}
 */
export function computeAesthetics(gray, w, h, hz) {
  // 1) 主体：最暗的一团（演示场景里的人物是深色剪影）
  let minX = w, minY = h, maxX = -1, maxY = -1;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (gray[y * w + x] < 70) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
    }
  }
  const found = maxX >= 0;
  const subjectHeight = found ? (maxY - minY) / h : 0;

  // 2) 背景杂乱：外圈 20% 区域的强梯度密度
  let edge = 0, total = 0;
  const bx = Math.round(w * 0.2), by = Math.round(h * 0.2);
  for (let y = 1; y < h - 1; y++) {
    for (let x = 1; x < w - 1; x++) {
      const inBorder = x < bx || x > w - bx || y < by || y > h - by;
      if (!inBorder) continue;
      const i = y * w + x;
      const g = Math.abs(gray[i - 1] - gray[i + 1]) + Math.abs(gray[i - w] - gray[i + w]);
      if (g > 40) edge++;
      total++;
    }
  }
  const borderClutter = total ? edge / total : 0;

  // 3) 对称性：左右镜像差异
  let diff = 0, cnt = 0;
  for (let y = 0; y < h; y += 2) {
    for (let x = 0; x < w / 2; x += 2) {
      diff += Math.abs(gray[y * w + x] - gray[y * w + (w - 1 - x)]);
      cnt++;
    }
  }
  const symmetry = cnt ? 1 - Math.min(1, diff / cnt / 60) : 0;

  return {
    subjectHeight,
    subjectInFrame: found,
    borderClutter,
    symmetry,
    horizonRatio: hz ? hz.lineYRatio : 0.5,
  };
}

/**
 * 检测竖直线（建筑场景的核心信号：楼体立柱是否竖直 = 手机是否端平）
 * 与地平线检测对称：逐行找最强竖直边 → 拟合 x = a·y + b
 * @returns {{angleDeg:number, confidence:number, coverage:number}} angleDeg 为偏离竖直的角度
 */
export function detectVerticals(gray, w, h) {
  const pts = [];
  const minStrength = 12;
  const m = 2;

  for (let y = m; y < h - m; y++) {
    let bestG = 0, bestX = -1;
    for (let x = m; x < w - m; x++) {
      const i = y * w + x;
      const gx = Math.abs(gray[i - 1] - gray[i + 1]);          // 竖直边响应
      const gy = Math.abs(gray[i - w] - gray[i + w]) * 0.25;   // 抑制水平边
      const g = gx - gy;
      if (g > bestG) { bestG = g; bestX = x; }
    }
    if (bestG >= minStrength && bestX > 0) pts.push({ x: bestX, y, w: bestG });
  }

  const coverage = pts.length / h;
  if (pts.length < h * 0.30) return { angleDeg: 0, confidence: 0, coverage };

  // 拟合 x 关于 y：把 (x,y) 转置成 (x'=y, y'=x)
  let fit = weightedFit(pts.map(p => ({ x: p.y, y: p.x, w: p.w })));
  const resid = pts.map(p => Math.abs(p.x - (fit.a * p.y + fit.b)));
  const sd = stddev(resid);
  const keep = pts.filter((p, i) => resid[i] <= Math.max(2, 2 * sd));
  if (keep.length < h * 0.25) return { angleDeg: 0, confidence: 0, coverage };

  fit = weightedFit(keep.map(p => ({ x: p.y, y: p.x, w: p.w })));
  const angleDeg = Math.atan(fit.a) * 180 / Math.PI;   // 偏离竖直方向的角度
  const cover = keep.length / h;
  const stable = sd < 3 ? 1 : Math.max(0.4, 3 / sd);
  return {
    angleDeg,
    confidence: Math.min(1, cover * 1.2) * stable,
    coverage: cover,
  };
}

/**
 * 天空占比 + 天空底边（建筑顶 ≈ 天空底边）
 * 判据：足够亮 + 偏蓝或低饱和
 */
export function estimateSky(data, w, h) {
  const rowSky = new Array(h).fill(0);
  let total = 0;

  for (let y = 0; y < h; y++) {
    let cnt = 0;
    for (let x = 0; x < w; x++) {
      const p = (y * w + x) * 4;
      const r = data[p], g = data[p + 1], b = data[p + 2];
      const luma = (r * 77 + g * 150 + b * 29) >> 8;
      const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
      const sat = mx === 0 ? 0 : (mx - mn) / mx;
      const isSky = (luma > 140 && b >= r - 12 && sat < 0.45) || (luma > 210 && sat < 0.14);
      if (isSky) cnt++;
    }
    rowSky[y] = cnt / w;
    total += cnt;
  }

  // 从顶部往下，最后一个“过半是天空”的行
  let skyBottom = -1;
  for (let y = 0; y < h; y++) if (rowSky[y] >= 0.5) skyBottom = y;

  return {
    ratio: total / (w * h),
    skyBottom,
    skyBottomRatio: skyBottom < 0 ? 0 : skyBottom / h,
  };
}

/** 前景纹理：画面底部 1/3 的强梯度密度（有前景 = 画面有层次） */
export function foregroundTexture(gray, w, h) {
  const y0 = Math.floor(h * 2 / 3);
  let edge = 0, total = 0;
  for (let y = y0 + 1; y < h - 1; y++) {
    for (let x = 1; x < w - 1; x++) {
      const i = y * w + x;
      const g = Math.abs(gray[i - 1] - gray[i + 1]) + Math.abs(gray[i - w] - gray[i + w]);
      if (g > 40) edge++;
      total++;
    }
  }
  return { density: total ? edge / total : 0 };
}

/**
 * 场景分类（规则版，无需模型）
 * building: 大量竖直线 + 有天空
 * landscape: 少竖直线 + 有天空
 */
export function classifyScene({ vert, sky, hz, aes }) {
  const strongVert = vert && vert.coverage > 0.35 && vert.confidence > 0.35;
  const hasSky = sky && sky.ratio > 0.12;

  if (strongVert && hasSky) return 'building';
  if (!strongVert && hasSky && hz && hz.confidence > 0.3) return 'landscape';
  if (strongVert) return 'building';
  return 'other';
}
