/**
 * aim.js —— 目标构图逻辑（纯函数，可在 Node 里单测）
 *
 * 思路：不判断“你错了”，而是给出一个可以达成的目标——
 *   建筑：一个目标框（整栋楼 + 顶部留白），让用户前后移动去套住它
 *   风景：一条目标线（地平线放到三分线），让用户抬/压手机去贴上它
 */

export const RULES = {
  building: {
    skyBottomMin: 0.05,     // 建筑顶上方至少留 5% 天空（原 8%，放宽）
    skyBottomMax: 0.28,     // 天空超过 28% 才算"建筑太小"（原 22%）
    vertTolerance: 4.0,     // 竖线容差（原 2°，太严）
    frameInset: 0.06,       // 目标框左右内缩
  },
  landscape: {
    targets: [0.333, 0.667],
    tolerance: 0.08,        // 地平线容差（原 0.05）
  },
};

/**
 * @param {{scene:string, vert:object, sky:object, hz:object, fg:object}} m
 * @returns {{
 *   type:'frame'|'line'|null,
 *   scene:string,
 *   achieved:boolean,
 *   text:string,          // 一句引导（未达成）
 *   sub:string,           // 补充说明
 *   frame?:{x0:number,y0:number,x1:number,y1:number},
 *   line?:{y:number, current:number},
 *   progress:number       // 0..1，越接近 1 越接近达成
 * }}
 */
export function buildAim(m) {
  if (!m) return { type: null, scene: 'other', achieved: false, text: '', sub: '', progress: 0 };

  if (m.scene === 'building') return buildingAim(m);
  if (m.scene === 'landscape') return landscapeAim(m);
  return { type: null, scene: m.scene || 'other', achieved: false, text: '', sub: '', progress: 0 };
}

function buildingAim(m) {
  const R = RULES.building;
  const skyBottom = (m.sky && typeof m.sky.skyBottomRatio === 'number')
    ? m.sky.skyBottomRatio
    : 0.14;
  const vertDeg = m.vert ? m.vert.angleDeg : 0;
  const vertConf = m.vert ? m.vert.confidence : 0;

  // 目标：天空结束的位置（楼顶）落在 [min,max] 区间
  const target = clamp(skyBottom, R.skyBottomMin, R.skyBottomMax);

  const problems = [];
  if (skyBottom < R.skyBottomMin) {
    problems.push({ key: 'top', text: '后退半步，把楼顶收进来', sub: '楼顶被切掉了，上方留一点天空' });
  } else if (skyBottom > R.skyBottomMax) {
    problems.push({ key: 'scale', text: '走近一点，让建筑占满画面', sub: '建筑有点小，靠近或变焦一点' });
  }
  if (vertConf > 0.35 && Math.abs(vertDeg) > R.vertTolerance) {
    problems.push({
      key: 'vert',
      text: vertDeg > 0 ? '手机往左转一点，让柱子竖直' : '手机往右转一点，让柱子竖直',
      sub: `竖线偏离 ${Math.abs(vertDeg).toFixed(1)}°，是仰拍造成的透视`,
    });
  }

  const achieved = problems.length === 0;
  // 进度：距离理想区间中点的接近程度
  const mid = (R.skyBottomMin + R.skyBottomMax) / 2;
  const progress = clamp(1 - Math.abs(skyBottom - mid) / 0.25, 0, 1);

  return {
    type: 'frame',
    scene: 'building',
    achieved,
    text: achieved ? '构图到位' : problems[0].text,
    sub: achieved ? '整栋楼在画面里，柱子是直的' : problems[0].sub,
    frame: {
      x0: R.frameInset,
      y0: target,                 // 楼顶应在的位置
      x1: 1 - R.frameInset,
      y1: 1,
    },
    line: null,
    vertDeg,
    skyBottom,
    progress,
  };
}

function landscapeAim(m) {
  const R = RULES.landscape;
  const current = m.hz && m.hz.confidence > 0.2 ? m.hz.lineYRatio : 0.5;

  // 就近选择一个三分线作为目标（不放正中）
  let target = R.targets.reduce((best, t) =>
    Math.abs(current - t) < Math.abs(current - best) ? t : best, R.targets[0]);

  const diff = current - target;
  const achieved = Math.abs(diff) <= R.tolerance && m.hz && m.hz.confidence > 0.2;

  let text = '', sub = '';
  if (!m.hz || m.hz.confidence <= 0.2) {
    text = '抬一点手机，让地平线进画面';
    sub = '还没找到明显的地平线';
  } else if (!achieved) {
    if (diff > 0) {
      text = '抬一点手机，把地平线抬上去';
      sub = `地平线在 ${(current * 100).toFixed(0)}%，目标在 ${(target * 100).toFixed(0)}%`;
    } else {
      text = '压一点手机，把地平线降下来';
      sub = `地平线在 ${(current * 100).toFixed(0)}%，目标在 ${(target * 100).toFixed(0)}%`;
    }
  } else {
    text = '构图到位';
    sub = target > 0.5 ? '地平线压到下面，天空成了主体' : '地平线抬到上面，地面成了主体';
  }

  return {
    type: 'line',
    scene: 'landscape',
    achieved,
    text,
    sub,
    frame: null,
    line: { y: target, current },
    progress: clamp(1 - Math.abs(diff) / 0.3, 0, 1),
  };
}

function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

/** 场景显示名 */
export const SCENE_LABEL = {
  building: '建筑',
  landscape: '风景',
  other: '通用',
};
