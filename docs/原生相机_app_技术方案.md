# 原生相机 App 技术方案（从基础相机做起）

> 结论先行：**WebView 方案到此为止**。对焦、缩放、曝光补偿这些是相机 App 的底层能力，浏览器/WebView 没有对应 API（W3C 的点对焦 `pointsOfInterest` 基本没实现）。要真正做"相机软件"，必须走原生。
>
> 技术选型：**Kotlin + CameraX**（Android），iOS 用 AVFoundation 另做。

---

## 一、为什么要换原生：能力对照

| 能力 | 现在的 WebView | 原生 CameraX | 说明 |
|---|---|---|---|
| 点击对焦（到指定点） | ❌ 只能碰运气 | ✅ `FocusMeteringAction` | 真正的点对焦 + 测光 |
| 双指缩放 / 双击缩放 | ❌ | ✅ `setZoomRatio` | 支持到最大光学/数码变焦 |
| 曝光补偿 | ❌ | ✅ `setExposureCompensationIndex` | 可做曝光滑杆 |
| 手动对焦距离 | ❌ | ⚠️ 部分机型 | `LENS_FOCUS_DISTANCE` |
| 闪光灯 / 手电筒 | ❌ | ✅ | `setTorchEnabled` |
| 逐帧分析（CV） | ⚠️ 降采样 | ✅ `ImageAnalysis` | 可拿全分辨率帧 |
| 拍照画质 | 低（video 截图） | ✅ 原生 JPEG | 直接走相机管线 |
| 前后摄切换 | 有限 | ✅ | |
| 帧率稳定性 | 差 | ✅ | |

**一句话**：WebView 能验证交互逻辑，但做不了相机。要"能用的拍照 App"，必须原生。

---

## 二、第 1 阶段：基础相机功能清单

先把"能当相机用"这件事做扎实：

| # | 功能 | 实现要点 |
|---|---|---|
| 1 | 取景预览 | `PreviewView` + `CameraX` |
| 2 | 快门拍照 | `ImageCapture.takePicture()`，JPEG 存相册 |
| 3 | 三分线 | 自定义 `View` 画 2 横 2 竖，可开关 |
| 4 | 点击对焦 | `FocusMeteringAction` + 对焦框动画 |
| 5 | 双指缩放 | `ScaleGestureDetector` → `setZoomRatio` |
| 6 | 双击缩放 | 双击切换 1x / 2x |
| 7 | 曝光补偿 | 滑杆 + `setExposureCompensationIndex` |
| 8 | 前后摄切换 | `CameraSelector` |
| 9 | 闪光灯 | `ImageCapture.flashMode` |
| 10 | 比例切换 | 4:3 / 16:9（`PreviewView` + `ResolutionSelector`） |
| 11 | 快门动画 | 白屏闪一下 |
| 12 | 拍摄防抖提示 | 可选 |

### 关键 API 与代码

#### 点击对焦（最重要）

```kotlin
previewView.setOnTouchListener { _, ev ->
    if (ev.actionMasked == MotionEvent.ACTION_UP) {
        val point = previewView.meteringPointFactory
            .createPoint(ev.x, ev.y)                       // 屏幕坐标 → 相机测光坐标
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS)        // 3 秒后恢复连续对焦
            .build()
        camera.cameraControl.startFocusAndMetering(action)
        showFocusRing(ev.x, ev.y)                           // 黄色对焦框动画
    }
    false
}
```

> 注意：`PreviewView.meteringPointFactory` 会自动处理 `FILL_CENTER` / `FIT_CENTER` 的坐标变换，比手动算简单得多。
> 常见坑：用了 `PreviewView.SCALE_TYPE_FIT_CENTER` 但没做坐标映射，导致对焦偏到角落（见 StackOverflow 讨论）。

#### 缩放

```kotlin
// 双指
scaleDetector = ScaleGestureDetector(context, object : SimpleOnScaleGestureListener() {
    override fun onScale(detector: ScaleGestureDetector): Boolean {
        zoomRatio *= detector.scaleFactor
        camera.cameraControl.setZoomRatio(zoomRatio.coerceIn(minZoom, maxZoom))
        return true
    }
})

// 或者用线性缩放（更平滑）
camera.cameraControl.setLinearZoom(0.5f)
```

#### 曝光补偿

```kotlin
val state = camera.cameraInfo.exposureState
val range = state.exposureCompensationRange      // IntRange，比如 -3..3
camera.cameraControl.setExposureCompensationIndex(index)
```

#### 三分线

```kotlin
class GridOverlay(context: Context) : View(context) {
    private val paint = Paint().apply {
        color = Color.argb(90, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawLine(w / 3, 0f, w / 3, h, paint)
        canvas.drawLine(w * 2 / 3, 0f, w * 2 / 3, h, paint)
        canvas.drawLine(0f, h / 3, w, h / 3, paint)
        canvas.drawLine(0f, h * 2 / 3, w, h * 2 / 3, paint)
    }
}
```

---

## 三、参考项目（按推荐度排序）

| 项目 | 语言/库 | 为什么值得看 | 链接 |
|---|---|---|---|
| **GrapheneOS/Camera** | Kotlin + CameraX | 现代、代码干净、功能完整（对焦/缩放/网格/闪光灯都有），是"照着做一个相机 App"最好的参考 | https://github.com/GrapheneOS/Camera |
| **android/camera-samples** | Kotlin + CameraX | Google 官方示例集，`CameraXBasic` 和 `CameraXAdvanced` 直接能跑 | https://github.com/android/camera-samples |
| **android/skills / camera1-to-camerax** | 文档 | 官方迁移指南，讲清每个功能的 CameraX 写法 | https://github.com/android/skills/blob/main/camera/camerax/references/camera1-to-camerax.md |
| **OpenCamera** | Java + Camera2 | 功能最全的老牌开源相机，但对焦等逻辑要看 Camera2 原生写法 | https://sourceforge.net/projects/opencamera/ |
| **Android 官方配置文档** | 文档 | `MeteringPointFactory`、分辨率选择等细节 | https://developer.android.google.cn/media/camera/camerax/configuration |

> **建议**：先跑通 `android/camera-samples` 的 CameraXBasic，再对照 GrapheneOS/Camera 补功能。

---

## 四、主体锁定（你说的"锁定用户想要的主体"）

你的描述完全正确：**点击之后应该锁定主体并持续跟踪，而不是画一条静止的线**。实现路径：

```
用户点击
  → 在该位置做一次目标检测（ML Kit Object Detection）
  → 拿到 bbox + trackingId
  → 画锁定框（四角，标"已锁定"）
  → 后续帧用 trackingId 持续跟踪（ML Kit 自带跨帧跟踪）
  → 框位置每帧更新，主体移动框跟着走
```

### 为什么用 ML Kit

- 自带 **Object Detection & Tracking**，支持 `trackingId` 跨帧跟踪
- 端侧运行、免费、无需联网
- 文档：https://developers.google.cn/ml-kit/vision/object-detection/android

```kotlin
val options = ObjectDetectorOptions.Builder()
    .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
    .enableMultipleObjects()
    .enableClassification()
    .build()
val detector = ObjectDetection.getClient(options)

// 每帧
detector.process(imageProxy)
    .addOnSuccessListener { objects ->
        val locked = objects.firstOrNull { it.trackingId == lockedTrackingId }
        // 更新锁定框位置
    }
```

### 备用方案（无检测结果时）

如果 ML Kit 没检测到物体（比如拍一堵墙、一片天），退化为**兴趣点跟踪**：

- OpenCV 的 `TrackerMOSSE` / `TrackerKCF`（需引入 OpenCV Android SDK，体积大）
- 或简单的模板匹配（在当前帧找与点击区域最相似的块）

> 建议：**先用 ML Kit**，覆盖人/物等常见主体；没有检测结果就退化为"固定兴趣点"（画个十字，不跟踪），并在 UI 上说明"未识别到主体"。

---

## 五、分阶段实施计划

| 阶段 | 时间 | 目标 | 验收 |
|---|---|---|---|
| **P1 基础相机** | 1 周 | 预览/拍照/三分线/对焦/缩放/曝光/前后摄 | 能当普通相机用，对焦准确 |
| **P2 CV 分析** | 1 周 | 把 `detect.js` + `aim.js` 移植成 Kotlin（地平线、亮度、反光、竖线、天空） | 叠加层能实时显示检测结果 |
| **P3 AI 引导** | 1 周 | 水平线对齐 + 主体锁定 + 目标构图框 | 点击锁定主体，对齐时变绿 |
| **P4 打磨** | 1 周 | 性能、UI、异常处理、打包 | 稳定不卡顿，能安装 |

### 关于算法移植

现有 JS 算法（`detect.js` 约 300 行、`aim.js` 约 140 行）都是简单图像处理，移植到 Kotlin 不难：

| JS 函数 | Kotlin 对应 |
|---|---|
| `toGray` | `ImageProxy` → YUV Y 通道直接就是灰度 |
| `estimateHorizon` | 同样的逐列扫描 + 最小二乘 |
| `detectVerticals` | 同上，转置 |
| `estimateSky` | 遍历像素判断天空色 |
| `buildAim` | 纯逻辑，直接翻译 |

**性能提示**：CameraX 的 `ImageAnalysis` 可以设 `STRATEGY_KEEP_ONLY_LATEST`，分析分辨率设 640×480 就够，CPU 占用可控。

---

## 六、AI 模型 / 开源项目参考（次要，先放一边）

| 方向 | 资源 | 用途 |
|---|---|---|
| 主体检测+跟踪 | [ML Kit Object Detection](https://developers.google.cn/ml-kit/vision/object-detection/android) | 主体锁定（P3 必需） |
| 人像分割 | ML Kit Selfie Segmentation | 背景分离、人像构图 |
| 构图/裁剪模型 | [ProCrop](https://github.com/BWGZK-keke/ProCrop) | 参考它的构图评分思路 |
| 取景网络 | [view-finding-network](https://github.com/yiling-chen/view-finding-network) | 学习"好构图"的排序网络 |
| 端侧推理 | TFLite / ONNX Runtime Mobile | 如果要跑自定义模型 |

> 这些是 P3 之后才需要，先不做。

---

## 七、决策点：三个选项

| 选项 | 工作量 | 适合 |
|---|---|---|
| **A. Kotlin + CameraX（推荐）** | 3–4 周 | 必须真机可用、要答辩演示"相机 App" |
| B. Flutter + camera 插件 | 2–3 周 | 想同时支持 iOS；但图像分析要自己写 YUV 转换 |
| C. 继续 WebView | — | 已被证明做不到对焦/缩放 |

如果时间紧，可以先做 **P1 基础相机**（1 周），确保"是个能用的相机"，再逐步加 AI 层。

---

## 八、下一步

如果确认走 A 路线，我可以：
1. 搭一个 CameraX 骨架工程（预览 + 拍照 + 三分线 + 点击对焦 + 缩放 + 曝光），直接能跑；
2. 把 `detect.js` / `aim.js` 移植成 Kotlin；
3. 接 ML Kit 做主体锁定。

需要的话，我从第 1 步开始。
