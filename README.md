# 离线文字识别 · Capacitor

面向 Android 16 的纯离线证件 OCR 应用。前端使用 HTML/CSS/TypeScript，Android 原生层使用一个 Kotlin Capacitor 插件调用百度 PP-OCRv6 small、ONNX Runtime 和 OpenCV。

## 调用结构

```text
HTML / CSS / TypeScript
        ↓ 小型 JSON 命令与结果
Capacitor Bridge
        ↓
OcrPlugin.kt
        ↓
PP-OCRv6 small + ONNX Runtime + OpenCV
```

- 应用首次进入前台且尚未授权时主动申请共享图库的全部图片读取权限：Android 13+ 使用 `READ_MEDIA_IMAGES`，Android 12 及以下使用 `READ_EXTERNAL_STORAGE`。全量授权后不再重复弹窗；选图按钮只检查权限并调用系统图库多选，最多 4 张。
- 原生 `ClipData` 返回顺序作为唯一顺序，前端缩略图显示 `1～4` 角标，预览、逐张识别和结果分组严格复用同一 URI 数组。
- 原图不转换为 Base64，也不传入 JavaScript；前端只接收压缩预览和文字结果。
- `OptimizedOcrEngine` 首次识别时懒加载，并在应用进程存活期间复用。模型先复制到应用 `noBackupFilesDir`，再通过文件路径创建 ONNX Session，避免把完整模型读成 Java `byte[]`；实际 mmap 归类仍以真机 `/proc/self/smaps` 为准。
- ONNX Session 关闭 CPU arena 和 memory pattern；推理输入复用直接缓冲，OpenCV 中间 `Mat` 用完立即 `release()`，不再生成未使用的检测框 Bitmap。
- 实际推理仅包含 v6 small 检测与识别两个模型，关闭文字块 180° 方向分类；图片 EXIF 方向校正继续保留。模型私有缓存用 SHA-256 校验，损坏时重新从 APK 原子替换；退出页面后在后台任务结束时释放最终引擎实例。
- 不申请互联网或网络状态权限，不保存图片、结果或日志历史；图库权限只用于读取共享 MediaStore 中的图片，无法访问其他应用的私有目录。
- 身份证候选必须是连续的 17 位 ASCII 数字加 1 位数字或 `X/x`；不修补空格、横杠、全角字符和 `O/I` 等 OCR 混淆，再在 Kotlin 中完成省级代码、日期、顺序码和校验位检查。
- 有效期限只按字符格式匹配精确的 `YYYY.MM.DD-YYYY.MM.DD` 或 `YYYY.MM.DD-长期`，不校验日历值和起止顺序；返回时删除日期中的点，输出为 `YYYYMMDD-YYYYMMDD` 或 `YYYYMMDD-长期`，多项结果去重后按识别顺序保留。
- 每次识别开始时读取剪贴板且只提取大陆 11 位手机号。结果区依次显示身份证号、手机号和有效期限；复制全部时依次包含三项关键信息及按图片分隔的完整 OCR 文字。
- OCR 文字框按纵向位置合并，同一视觉行按从左到右输出；缩略图不显示文件名，点击后由原生全屏查看，关闭时立即回收显示 Bitmap。
- 开发者日志默认关闭，双击页头终端图标后才采集详细阶段耗时和进程内存，关闭时跳过所有深度采样。日志开启后会记录 Java/Native/Graphics/Code/Stack 的 PSS 构成、`/proc/self/smaps` 映射分类、Native 分配区、匿名页、模型文件、线程/FD、ART GC、系统可用内存和进程 trim 状态；识别后自动在 0.5 秒、2 秒、10 秒及进后台/回前台时复测，并输出回收比例、映射增量和高水位/疑似泄漏判断。

- 隐藏开发者区域可保存检测长边参数（320～2400、32 的倍数），下一轮识别生效并跨启动保留。暂时保留现有默认 1600 和补边/缩放方式，实际模型输入宽高记录在日志中；不将 736 直接设为默认。官方资料、参数差异及手机验证方法见 [OCR 参数依据](ocr-tuning-notes.md)。

## 目录

```text
src/main.ts                 识别主流程编排
src/ui/                     图片、结果、状态等独立界面模块
src/developer-log.ts        隐藏式开发者日志
src/memory-report.ts        内存报告格式化、增量计算和趋势判断
resources/OCR.png            网页及 Android 图标源文件
android/app/src/main/java/   MainActivity、OCR 插件和原生内存诊断
android/app/src/main/java/.../ocr/  路径模型加载、ONNX 推理和 OpenCV 处理
android/app/src/test/        身份证、有效期限、手机号和同行合并单测
.github/workflows/           ARM64 Release 云端构建
```

`android/capacitor.settings.gradle`、`android/app/capacitor.build.gradle` 和 WebView 静态资源由 `npx cap sync android` 生成，不提交。

## GitHub Actions 构建

此目录被上一级 Tauri 仓库忽略，应作为独立仓库提交到 GitHub。进入本目录后初始化 Git，并运行工作流 `Build Capacitor Android APK`。

工作流会自动完成：

1. 安装 Node 22、Java 21、Gradle 8.14.3、Android SDK 36 和 NDK。
2. 构建 Vite 页面并同步 Capacitor Android 资源。
3. 下载 `OcrLibrary-0.1.0-release.aar` 并删除其中全部旧模型和字典，保留代码使用的数据类、Clipper 等辅助代码；未用旧引擎交由 R8 裁剪，校验仓库内两个 v6 small 模型及字典的哈希。
4. 运行 Kotlin 静态逻辑单测并构建 ARM64 Release APK。
5. 检查签名、联网权限、模型、ARM64 原生库和 16 KB 对齐。
6. 在仓库 **Releases** 创建 `apk-运行编号-重试编号` 发布，附上 `offline-ocr-arm64-release.apk` 和 `apk-size-report.txt`；不再上传 Actions artifact。工作流构建任务使用 `contents: write` 创建发布，下载入口为仓库首页的 Releases。

未配置签名 Secrets 时会自动生成一次性评测证书。长期覆盖升级需要配置：

- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `ANDROID_KEY_BASE64`

首次从其他签名或旧 Tauri APK 安装时，需要先卸载旧应用。两个项目使用不同 application ID，可以同时安装用于 A/B 测试。

## 内存诊断流程

1. 强制停止应用后重新打开，在第一次识别前双击页头终端图标启用日志。
2. 先固定使用同一张图片，完成一次识别后等待至少 10 秒，让 0.5 秒、2 秒和 10 秒快照全部写入。
3. 重复识别 5～10 轮，每轮都等待 10 秒；不要提前开始下一轮，否则上一轮未执行的延迟采样会主动取消。
4. 完成一轮后切到另一个应用，等待约 30 秒再返回，采集后台和回前台快照。
5. 使用日志右上角复制按钮回传完整日志，重点保留 `模型文件映射`、`单次推理映射增长`、`10 秒回收结论`、`趋势判断` 和 `跨轮判断`。

深度诊断会读取本进程的 `/proc/self/status`、`smaps_rollup` 和 `smaps`，只在日志开启时运行，不需要存储或网络权限。每次采样的耗时会独立写入日志，不计入 OCR 核心推理时间。

## 本机开发

项目不要求本机具备 Android/Rust 环境。具备 Node 22 时可选运行：

```powershell
npm install
npm run dev
```

浏览器中只预览页面，选图和 OCR 必须在 Android APK 中运行。首次生成 `package-lock.json` 后应将它提交到 Capacitor 独立仓库，后续 Actions 会自动改用 `npm ci`。
