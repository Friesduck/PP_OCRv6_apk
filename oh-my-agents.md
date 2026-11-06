# 项目交接

## 项目目标

- 使用 Vite、原生 HTML/CSS/TypeScript、Capacitor 和单个 Kotlin 插件构建 Android 16 纯离线证件 OCR。
- 检测、识别使用百度官方 PP-OCRv6 small ONNX 模型及配套字典；保持现有识别参数，展示全部文字并额外提取有效身份证号码。
- 原图由 Kotlin 直接读取，不通过 Base64 或 JavaScript Bridge 传输。
- 项目结构偏向极简、稳健，APK 仅构建 ARM64 Release。

## 当前状态

- 检测、识别使用百度官方 `PP-OCRv6_small_det.onnx`、`PP-OCRv6_small_rec.onnx`，随仓库放入 `android/app/src/main/assets/`；`ppocrv6_dict.txt` 从官方识别配置按原顺序提取 18,708 项，解码器添加 CTC blank 和空格后与模型输出 18,710 类一致。模型字节及字典已核对官方 SHA-256，私有模型缓存首次使用时校验，损坏则从 assets 校验后原子替换。
- 本轮已按用户后续授权完成后台修复和打包调整：关闭文字块 180° 方向分类，保留 EXIF 校正；修复选图与识别互斥、销毁时引擎释放时序、后台异常兜底、重复文字行误删、短边过滤及部分异常路径 Mat 释放。识别输入宽度保护为 16～3200，超长行压缩整行，不截尾；异常长行准确率需真机确认。
- 隐藏开发者区域新增持久化检测长边设置、保存和恢复按钮，以及用途说明；范围为 320～2400 的 32 倍数，每轮多图只读取一次。按用户要求，默认仍为 1600，补边 50 和原对齐算法未改；实际模型输入可能不同于设置值，每轮日志逐图显示真实宽高。736 仅作为可选对照，未设为默认；官方依据和手机验证方法见 `ocr-tuning-notes.md`。
- 云端构建删除旧 AAR 中全部旧模型和字典，移除旧引擎整包 keep 并交由 R8 裁剪，校验 APK 仅包含两个 v6 small ONNX。构建、原有签名、权限及对齐检查继续由 Actions 执行，成功后自动创建 GitHub Release 并附 APK 和包体报告，已取消 Actions artifact 上传；本机尚未实际构建或发布此轮 APK。
- 普通前端界面、图库权限、身份证/有效期/手机号规则和同行合并规则未改，`ocr_single.py` 未改；该参考脚本使用 v6 medium，APK 使用 v6 small，裁剪和批处理流程也有差别，不能用桌面耗时直接推断手机性能。
- 已建立独立 Capacitor 8.5.0 项目，application ID 为 `com.example.rapidocrcapacitor`。
- 前端使用 Vite 7、TypeScript 和 Lucide，并按图片选择、结果展示、状态和开发者日志拆分模块；UI 已参考 `demo1.html` 收敛为极简 iOS 磨砂风格，使用紧凑层级、圆角、按压反馈和轻量入场动画。
- 支持单张或多张图片选择，前端提示和原生选择/识别上限均为 4 张；缩略图按系统图库返回顺序显示 `1～4` 角标且不显示文件名，该 URI 数组同时决定预览、OCR 和结果分组顺序，点击缩略图由原生全屏展示并在关闭时回收 Bitmap。
- `OcrPlugin.kt` 负责共享图库权限、系统图库多选、单图 25 MB 限额、逐张降采样/EXIF/推理/回收、剪贴板手机号提取、同行文字框合并、身份证校验和按需 PSS/Heap 快照。
- 已用自维护的 `OptimizedOcrEngine` 替代 AAR 旧 `OcrEngine` 调用路径：模型复制到 `noBackupFilesDir/ocr-models` 后按文件路径创建 Session，关闭 CPU arena 和 memory pattern，复用直接输入缓冲，显式释放 OpenCV `Mat`，并取消未使用的检测框 Bitmap。
- 身份证候选严格限制为连续 17 位 ASCII 数字加 1 位数字或 `X/x`，不修补空格、横杠、全角字符和 `O/I` 混淆；候选仍需通过省份、日期、顺序码和校验位验证，不提取 15 位旧号码。
- 从全部逐图文字中匹配精确的 `YYYY.MM.DD-YYYY.MM.DD` 或 `YYYY.MM.DD-长期` 有效期限，只校验字符格式，不校验日历值或起止顺序；返回时删除日期中的点，输出为 `YYYYMMDD-YYYYMMDD` 或 `YYYYMMDD-长期`，多项去重后按识别顺序保留。
- 结果区按身份证号、剪贴板手机号、有效期限三行展示关键信息，有效期限多项用顿号连接；下方用“图片编号 + 行数 + 独立正文带”完整展示逐图 OCR 内容，复制全部包含三项摘要和所有图片文字。
- 已新增独立的 `design-style-guide.md`，只描述轻量磨砂移动工具界面的视觉基调、tokens、通用组件、动效、响应式和实施清单，不引用设计来源或当前业务实例。
- Android 配置使用 Java 21、SDK 36、ARM64、R8、资源裁剪和 16 KB 兼容检查；Manifest 显式移除联网权限。应用首次进入前台且未授权时，由 `MainActivity` 在安全生命周期阶段主动申请图库权限，Android 13+ 请求 `READ_MEDIA_IMAGES`，Android 12 及以下请求 `READ_EXTERNAL_STORAGE`；全量授权后不再弹窗，Android 14+ 若只授权部分照片则提示改为“允许所有照片”，选图插件只做同步检查，避免异步权限回调空指针。
- GitHub Actions 支持自动临时签名或固定 Secrets，APK 与包体分类报告发布到仓库 Releases；临时签名的发布说明会提醒跨次更新需卸载旧应用。
- 使用根目录现有 `OCR.png` 生成网页图标和 Android 各密度普通/自适应图标。
- 上一级 Tauri 仓库通过 `/oh ocr Capacitor/` 忽略本项目；本项目应作为独立 Git 仓库管理。
- 按项目规则，尚未在本机安装 npm、Gradle 或 Android 依赖，完整编译和真机验证待 GitHub Actions。
- 已通过 Node TypeScript 语法、HTML DOM、GitHub Actions YAML 及全部 10 个 Bash run 脚本语法检查；对照开工前基线确认权限、普通界面和证件规则未变。未安装任何环境或依赖；本机没有 TypeScript 类型检查器或 Gradle/Kotlin 编译器，未执行本轮类型检查、单测、原生编译、OCR 或应用运行。
- GitHub Actions 构建和 Android 真机识别已跑通；热识别端到端约 2.1～2.4 秒，Capacitor Bridge 与线程调度仅约 2～4 ms，性能瓶颈仍是 OCR 核心而非 Web 前端或 Bridge。
- 旧引擎连续识别时 Bitmap 释放后的 PSS 约 946～993 MiB、Native Heap 约 996 MiB，明显高于首次模型加载后的约 620 MiB；第 3 次相对第 2 次没有继续线性上涨，但该数据尚未验证本轮优化引擎。
- 已静态核对上游 `OcrLibrary 0.1.0` 源码：该预发布 AAR 源于 2022 年，识别路径创建大量 OpenCV `Mat` 却几乎没有显式 `release()`，ONNX Session 也使用默认内存池；这是当前 Native Heap 异常的首要嫌疑，不是 Capacitor 框架开销。
- 新一轮第 2 次热识别日志显示：推理前/后 PSS 为 712.3/1003.7 MiB，Java 图片资源释放后为 1046.6 MiB，空闲 0.5 秒为 1101.6 MiB；主要增长位于 Native 分配区和匿名页，内存明显偏高，但因本次日志缺少 2 秒、10 秒及后续轮次采样，仍不能区分稳定高水位与逐轮泄漏。
- 已进一步核实上游 `Det`、`Cls`、`Rec` 均通过 `AssetManager.open(...).readBytes()` 后调用 `createSession(byte[])`，未使用文件路径；但 0.1.0 整个 AAR 仅约 11.9 MiB，且本轮模型早已热加载，mmap 优化不能单独解释或消除本轮约 291 MiB 的推理 PSS 增量，仍应优先修补临时 `Mat` 和 ONNX 推理内存配置。
- 开发者日志默认关闭，双击页头终端图标才启用并显示；关闭时 Kotlin 跳过全部内存诊断，普通识别不承担评测采样开销。日志保留详细图片处理、OCR、Bridge、内存、结果数量和异常信息，并支持一键复制。
- 开发者日志已扩展为深度内存诊断：记录 Java/Native/Graphics/Code/Stack PSS、Native 分配区、`/proc/self/status` 与 `smaps/smaps_rollup`、模型/ONNX/OpenCV/匿名映射分类、线程/FD、ART GC、系统可用内存和进程 trim 状态；识别后自动在 0.5 秒、2 秒、10 秒及进后台/回前台时复测，并计算推理增长、资源释放回落、10 秒回收比例、跨轮稳定值和疑似高水位/泄漏趋势。
- OCR 结果根据检测框纵向重叠合并同行内容，再按横坐标从左到右拼接；多图结果保留分组并按图片编号分隔。
- Android 16 全屏模式改由原生 `WindowInsets` 提供状态栏与挖孔安全距离，WebView 下移后仍保留页面自身顶部留白，避免页头图标和文字与系统状态栏重叠。

## 下一步 TODO

- 上传完整改动（包含两个 ONNX 文件及字典），手动运行 Actions；确认构建成功后仓库 Releases 出现 APK 和 `apk-size-report.txt`。本机仅静态检查，不运行构建或 OCR。
- 真机验证开发者设置的保存、重启保留、下一轮生效和恢复默认；默认先保持 1600，根据相同图片的准确率、热识别耗时、实际检测宽高和 10 秒稳定内存再讨论是否调整。不要未经验证直接设为 736；统一“设置值等于最终长边”会改变现有缩放行为，应单独讨论。
- 验证关闭文字块 180° 分类后正向照片、EXIF 旋转照片和重复文字行的结果；若确有倒置文字需求，再单独评估方向分类，不照搬桌面脚本的 15° 过滤。
- 用同一张图片在同一进程连续识别 5～10 次，记录每次 `Java 图片资源释放后` 和 10 秒后的 PSS/Native Heap；再强制停止应用并重开，对比旧引擎并区分高水位与逐次泄漏。
- 获取 `apk-size-report.txt`，和 Tauri Release 使用相同图片比较冷/热耗时、PSS、准确率及后台返回。
- 若优化引擎 10 秒稳定值仍接近 1 GiB 或跨轮持续阶梯上涨，再评估独立 OCR 工作进程并在返回结果后退出进程。
- 不采用每次启动清应用缓存或识别后直接 `System.gc()` 作为正式方案；它们无法可靠释放 ONNX/OpenCV 原生内存。
- 通过 GitHub Actions 构建并在 Android 16 真机确认页头已完全避开状态栏和挖孔区域。
- 在 Android 16 真机清除应用数据或卸载重装，确认首次启动立即出现照片权限框；选择“允许所有照片”后点击选图不闪退，杀进程重开不再弹权限框。再用相机、QQ、微信等写入共享图库的图片验证系统图库可见性；选择 1～4 张并核对角标、预览、OCR 和结果分组顺序完全一致。
- 另验证 4 张上限、复制全文、严格 18 位身份证号、两种有效期限格式、多期限顿号连接、剪贴板 11 位手机号和全屏预览关闭回收。

## 工作日志

- 2026-08-22：创建 Capacitor 重构项目；新增 Vite/TypeScript 前端、Capacitor 8.5 配置、Android 16 Gradle 工程、单个 Kotlin OCR 插件、身份证过滤测试、OCR.png 各密度图标和 ARM64 Release GitHub Actions；未安装本机依赖，尚待静态检查和云端编译。
- 2026-08-22：完成静态收尾；修改 `src/main.ts`、两个项目交接文档；修复重新选图失败时前后端状态不一致和复制按钮反馈问题，确认 TypeScript 语法、DOM、XML、YAML、图标及根仓库忽略规则通过，尚待 GitHub Actions 完整编译。
- 2026-08-22：根据首次 Capacitor 真机日志完成性能与内存归因；修改 `oh-my-agents.md`；确认 Bridge 仅约 2～4 ms、OCR 约 1.9～2.2 秒，异常集中在上游 OCR 库的 Native Heap，静态源码存在大量 OpenCV `Mat` 未显式释放；尚待同图连续 5～10 次曲线决定修补库或隔离进程方案。
- 2026-08-22：按 `design-style-guide.md` 完成 UI 与交互重构；修改 `index.html`、`src/styles.css`、`src/main.ts`、`src/native-ocr.ts`、`OcrPlugin.kt`、Android 主题颜色和文档，新增 `src/ui/`、`src/developer-log.ts`、`TextLineComposerTest.kt`，删除旧 `src/diagnostics.ts`；实现紧凑图片点击缩放、单代码块全文复制、身份证号首行、同行文字合并和双击启用的详细日志，日志关闭时跳过 PSS 采样；TypeScript 语法、HTML ID、CSS、XML、JSON、空白和离线约束静态检查通过，未安装依赖或执行 Android 构建，待 GitHub Actions 与真机验证同行阈值和双击手势。
- 2026-08-22：分析真机约 1 GiB 内存日志并修复 Android 16 顶部安全区；修改 `MainActivity.kt`、`oh-my-agents.md`；确认高占用集中在 OCR 推理 Native Heap，高水位在后续识别中基本稳定，并用原生 `WindowInsets` 避让状态栏，尚待 Actions 编译和真机布局验证。
- 2026-08-23：扩展 Capacitor 开发者日志为深度内存归因和延迟回收测试；新增 `MemoryDiagnostics.kt`、`src/memory-report.ts`，修改 `OcrPlugin.kt`、`src/native-ocr.ts`、`src/developer-log.ts`、`README.md` 和交接文档；增加 PSS 构成、smaps 映射、Native 分配区、ART GC、系统内存、后台生命周期和 0.5/2/10 秒复测，尚待 GitHub Actions 编译与真机回传日志。
- 2026-08-23：分析 `markDown1787446657675.md.txt` 的第 2 次热识别内存数据并复核 RapidOCR 上游源码；修改 `oh-my-agents.md`；确认模型使用 `readBytes()`、推理后异常增长集中在 Native/匿名页，纠正“日志启用基线等于冷启动基线”和“mmap 是本轮最大问题”的判断，尚待完整 10 秒及 5～10 轮同图数据定性高水位或泄漏。
- 2026-08-23：完成多图、剪贴板手机号、全屏原图、极简 iOS UI 和 OCR Native 内存重构；新增 `ocr/OptimizedOcrEngine.kt`、`ocr/OcrInference.kt`、`ocr/OcrOpenCv.kt`、`ClipboardPhoneParserTest.kt`，修改 `OcrPlugin.kt`、前端模块、README 和交接文档；模型改为路径 Session，关闭 ONNX CPU arena/memory pattern，复用直接输入缓冲并显式释放临时 `Mat`，最多 45 张图片逐张识别；已通过 11 个 TypeScript 文件语法、25 个 HTML ID、结果组合样例、HTML/CSS/Kotlin 结构和 `git diff --check` 静态检查，尚待 GitHub Actions 编译及 Android 真机内存验证。
- 2026-08-23 16:37：收敛证件提取与多图展示；新增 `design-style-guide.md`，修改 `index.html`、`src/styles.css`、`src/ui/format.ts`、`src/ui/image-panel.ts`、`src/ui/result-panel.ts`、`OcrPlugin.kt`、`IdCardParserTest.kt`、`README.md` 和交接文档；身份证仅保留 18 位校验提取，图片选择/识别双重上限改为 4 张，逐图结果改为带编号、行数和独立正文带的清晰分组，手机号与 OCR 推理逻辑未改；已通过 11 个 TypeScript 文件语法、25 个 HTML 唯一 ID、结果分组样例、Kotlin 关键约束、残留实现和 `git diff --check` 静态检查，本机无依赖未执行 TypeScript 类型检查、Gradle 单测或 Android 构建，尚待服务器与真机验证。
- 2026-08-23 16:47：新增有效期限提取并收紧身份证候选规则；新增 `ValidityPeriodParserTest.kt`，修改 `OcrPlugin.kt`、`IdCardParserTest.kt`、`src/native-ocr.ts`、`src/ui/format.ts`、`src/ui/result-panel.ts`、`src/main.ts`、`src/memory-report.ts`、`index.html`、`src/styles.css`、`README.md`、`design-style-guide.md` 和交接文档；身份证只接受连续 17 位 ASCII 数字加数字或 `X/x` 且不做 OCR 字符修补，有效期限只按两种指定字符格式提取且不校验日期/顺序，结果第三行展示并用顿号连接多项；样式指南重写为无来源引用、无当前业务实例的独立设计规范；已通过身份证/期限正反样例、多值复制样例、11 个 TypeScript 文件语法、26 个 HTML 唯一 ID、CSS 结构、8 项 Kotlin 结构、样式指南禁用词和 `git diff --check` 静态检查，本机无依赖未执行 TypeScript 类型检查、Gradle 单测或 Android 构建，尚待服务器与真机验证。
- 2026-08-23 17:25：调整有效期限返回格式；修改 `OcrPlugin.kt`、`ValidityPeriodParserTest.kt`、`README.md` 和交接文档；仍从 OCR 原文严格匹配两种带点格式，但返回摘要和复制内容统一删除日期中的点，输出 `YYYYMMDD-YYYYMMDD` 或 `YYYYMMDD-长期`，全部文字仍保留 OCR 原文；未修改用户维护的 `design-style-guide.md`；已通过固定期限、长期期限、去重、Kotlin 结构、Manifest 无媒体/存储权限和 `git diff --check` 静态检查，本机未执行 Gradle 单测或 Android 构建，尚待真机验证。
- 2026-08-23 17:36：改为申请共享图库全部图片权限并强化多图顺序；修改 `AndroidManifest.xml`、`OcrPlugin.kt`、`src/ui/image-panel.ts`、`src/styles.css`、`README.md`、GitHub Actions 和交接文档；Android 13+ 请求 `READ_MEDIA_IMAGES`，旧系统请求 `READ_EXTERNAL_STORAGE`，Android 14+ 可检测并拒绝部分照片授权；授权后用 `ACTION_PICK` 打开 MediaStore 系统图库，多选返回的 `ClipData` 顺序统一驱动 `1～4` 角标、预览、OCR 与结果分组；云端增加三项图库权限断言；未修改用户维护的 `design-style-guide.md`；已通过 11 个 TypeScript 文件语法、Manifest、Kotlin 权限/顺序链路、CSS 角标、工作流和 `git diff --check` 静态检查，本机未执行 Gradle 编译或真机权限验证。
- 2026-08-23 18:05：修复点击选图时权限异步回调可能导致的空指针闪退；修改 `MainActivity.kt`、`OcrPlugin.kt`、`README.md` 和交接文档；权限改由 Activity 首次进入 `onPostResume` 且未获全量授权时主动请求，每次 Activity 启动最多请求一次，已全量授权则不再弹窗；Android 14+ 一次请求全量与部分照片状态，但只有 `READ_MEDIA_IMAGES` 获批才放行；选图插件移除 Capacitor 权限别名/回调，只同步检查权限，并对插件 Context 缺失做拒绝兜底；已通过 Kotlin 结构/括号、旧回调残留、Manifest XML/权限范围、GitHub Actions 权限断言和 `git diff --check` 静态检查，尚待服务器构建和 Android 16 真机按清数据首次启动、全量授权、选图、重启流程验证。

- 2026-11-06：仅替换检测、识别为百度官方 PP-OCRv6 small；新增 `android/app/src/main/assets/PP-OCRv6_small_det.onnx`、`PP-OCRv6_small_rec.onnx`、`ppocrv6_dict.txt`，修改 `ocr/OptimizedOcrEngine.kt` 的三个文件名及本交接文档；方向分类及其他代码、配置、界面、CI、权限、身份证逻辑均保持原样。已静态核对官方 SHA-256、ONNX 输入/输出与现有预处理/CTC 格式，未安装环境或依赖，未本机构建或运行；待用户 Actions 构建及真机验证。
  - 检测来源：`https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/tree/28fe5895c24fd108c19eb3e8479f4ab385fbfc62`；`inference.onnx` 为 9,880,512 字节，SHA-256 `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e`。
  - 识别来源：`https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/tree/b8f84f0b80c529de40b4fbb3544b84fa7233a513`；`inference.onnx` 为 21,159,378 字节，SHA-256 `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634`。字典提取自同一官方版本的 `inference.yml`，UTF-8 无 BOM、LF 换行，SHA-256 `b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d`；文件经 Hugging Face 镜像下载，模型字节哈希与官方 LFS 元数据一致，许可为 Apache-2.0。

- 2026-11-06：按用户新授权开始后台修复和包体精简；修改 `ocr/OptimizedOcrEngine.kt`：移除 180° 方向分类调用、返回实际检测尺寸、校验模型 SHA-256 并原子替换损坏缓存、清理停用模型缓存；检测默认 1600 和原缩放逻辑暂不调整。配套调用、推理边界、开发者设置及 Releases 工作流仍在本轮继续完成，尚未本机运行或构建。
- 2026-11-06：完成后台边界与开发者参数入口；修改 `OcrPlugin.kt`、`ocr/OcrInference.kt`、`ocr/OcrOpenCv.kt`、`src/native-ocr.ts`、`src/developer-log.ts`、`index.html`、`src/styles.css`；增加选图互斥、统一后台异常兜底、销毁后串行释放最终引擎，保留重复文字行；检测框按短边过滤，限制极细长文字识别宽度，检查输出/字典一致性并补齐异常 Mat 释放。隐藏开发者区域可保存/恢复 320～2400 的 32 倍数检测参数，沿用 1600 默认与原缩放逻辑；普通界面、权限、身份证及有效期规则未改。后续继续完成打包、Releases 与静态检查。
- 2026-11-06：调整包体与发布位置；修改 `.github/workflows/build-android-apk.yml`、`android/app/proguard-rules.pro`、`src/memory-report.ts`；云端删除旧 AAR 全部模型/字典资源并让 R8 裁剪未使用旧引擎，核验 v6 模型/字典 SHA-256，APK 仅允许两个 ONNX；构建及签名/权限/对齐检查继续由 Actions 执行，产物改为创建 GitHub Release 并附 APK/包体报告，移除 Actions artifact 上传。日志新增逐图实际检测宽高；待本轮静态检查和用户云端/真机验证。
- 2026-11-06：核对官方检测文档及 small 模型配置，新增 `ocr-tuning-notes.md`，更新 `README.md`、`index.html` 和本交接文档；明确 736 是可选低分辨率对照，官方配置中的 736×736 属于 TensorRT 优化形状，960 仅有通用平衡示例依据，不能直接认定手机最佳值；开发者区注明适用场景与补边/对齐差异，默认仍为 1600。已通过 11 个 TypeScript 文件语法、31 个 HTML 唯一 ID、工作流 YAML 与 10 个 Bash run 脚本语法、模型哈希及受保护文件/规则对比；无依赖安装、本机构建或 OCR 运行，最终 Kotlin 结构与差异复核后交用户 Actions/真机验证。
- 2026-11-06：完成最终静态复核与交接收尾；修改本交接文档。Kotlin 括号结构及停用分类器引用检查通过（不代表原生编译通过）；对照开工基线确认 7 个受保护文件 SHA-256、手机号/同行合并/身份证/有效期规则、普通 HTML 与原有 CSS、1600 默认及缩放计算未变，两个模型及字典哈希匹配。此轮共修改 13 个已有文件，新增 `ocr-tuning-notes.md`；未运行应用、OCR、单测或构建，尚待用户上传后 Actions 编译、Releases 产物检查和真机验证。
