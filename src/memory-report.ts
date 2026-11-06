import type {
  MemoryDiagnosticsProbe,
  MemorySnapshot,
  RecognitionResult,
} from "./native-ocr";

export type FrontendMetrics = {
  nativeCallWallMs: number;
  frontendTotalMs: number;
};

export type RecognitionMemoryContext = {
  recognitionId: number;
  baseline: MemorySnapshot;
  released: MemorySnapshot;
  previousSettled?: MemorySnapshot;
};

export const IDLE_MEMORY_SAMPLES = [
  { delayMs: 500, stage: "idle500ms", label: "识别后空闲 0.5 秒" },
  { delayMs: 2_000, stage: "idle2s", label: "识别后空闲 2 秒" },
  { delayMs: 10_000, stage: "idle10s", label: "识别后空闲 10 秒" },
] as const;

const MAPPING_LABELS: Array<[keyof MemorySnapshot["mappingPssKb"], string]> = [
  ["nativeAllocator", "Native 分配区"],
  ["otherAnonymous", "其他匿名映射"],
  ["onnxOpenCv", "ONNX/OpenCV 映射"],
  ["modelFiles", "模型文件映射"],
  ["dalvikArt", "Dalvik/ART"],
  ["webView", "WebView"],
  ["graphics", "图形缓冲"],
  ["threadStacks", "线程栈"],
  ["apkDex", "APK/Dex"],
  ["otherFiles", "其他文件映射"],
];

function isAvailable(value: number): boolean {
  return Number.isFinite(value) && value >= 0;
}

export function formatDuration(value: number): string {
  if (!isAvailable(value)) return "不可用";
  if (value >= 1_000) return `${(value / 1_000).toFixed(2)} 秒`;
  return `${value < 10 ? value.toFixed(1) : value.toFixed(0)} ms`;
}

function formatBytes(value: number): string {
  if (!isAvailable(value)) return "不可用";
  return `${(value / 1024 / 1024).toFixed(1)} MiB`;
}

function formatKb(valueKb: number): string {
  if (!isAvailable(valueKb)) return "不可用";
  return `${(valueKb / 1024).toFixed(1)} MiB`;
}

function formatSignedBytes(value: number): string {
  if (!Number.isFinite(value)) return "不可用";
  const sign = value > 0 ? "+" : "";
  return `${sign}${(value / 1024 / 1024).toFixed(1)} MiB`;
}

function formatSignedKb(valueKb: number): string {
  if (!Number.isFinite(valueKb)) return "不可用";
  const sign = valueKb > 0 ? "+" : "";
  return `${sign}${(valueKb / 1024).toFixed(1)} MiB`;
}

function difference(after: number, before: number): number {
  return isAvailable(after) && isAvailable(before) ? after - before : Number.NaN;
}

function memoryStageLabel(stage: string, modelWasLoaded: boolean): string {
  const labels: Record<string, string> = {
    diagnosticsEnabled: "日志启用基线",
    requestReceived: "Kotlin 收到请求",
    imageBytesRead: "原图读取后",
    imageReady: "图片解码后",
    engineReady: modelWasLoaded ? "热模型推理前" : "冷模型对象创建后",
    inferenceComplete: modelWasLoaded ? "热模型推理后" : "冷模型首次推理后",
    resourcesReleased: "Java 图片资源释放后",
    idle500ms: "识别后空闲 0.5 秒",
    idle2s: "识别后空闲 2 秒",
    idle10s: "识别后空闲 10 秒",
    appHidden: "应用进入后台",
    appVisible: "应用回到前台",
  };
  return labels[stage] ?? stage;
}

function findSnapshot(result: RecognitionResult, stage: string): MemorySnapshot | undefined {
  return result.metrics.memorySnapshots.find((snapshot) => snapshot.stage === stage);
}

export function memorySnapshotLines(snapshot: MemorySnapshot, label: string): string[] {
  const lines = [
    `${label} | 阶段 +${formatDuration(snapshot.elapsedMs)} | 采样 ${formatDuration(snapshot.captureCostMs)} | ` +
      `PSS ${formatKb(snapshot.totalPssKb)}，RSS ${formatKb(snapshot.vmRssKb)}，` +
      `Java ${formatBytes(snapshot.javaHeapUsedBytes)}/${formatBytes(snapshot.javaHeapCommittedBytes)}` +
      `/${formatBytes(snapshot.javaHeapMaxBytes)}，Native 已分配/保留/空闲 ` +
      `${formatBytes(snapshot.nativeHeapAllocatedBytes)}/${formatBytes(snapshot.nativeHeapReservedBytes)}` +
      `/${formatBytes(snapshot.nativeHeapFreeBytes)}`,
    `构成 | Java ${formatKb(snapshot.summaryJavaHeapKb)}，Native ` +
      `${formatKb(snapshot.summaryNativeHeapKb)}，Graphics ${formatKb(snapshot.summaryGraphicsKb)}，` +
      `Code ${formatKb(snapshot.summaryCodeKb)}，Stack ${formatKb(snapshot.summaryStackKb)}，` +
      `PrivateOther ${formatKb(snapshot.summaryPrivateOtherKb)}，System ` +
      `${formatKb(snapshot.summarySystemKb)}；Swap ${formatKb(snapshot.summaryTotalSwapKb)}`,
    `页面 | 匿名 RSS ${formatKb(snapshot.rssAnonKb)}，文件 RSS ${formatKb(snapshot.rssFileKb)}，` +
      `共享 RSS ${formatKb(snapshot.rssShmemKb)}，VmData ${formatKb(snapshot.vmDataKb)}，` +
      `VmSwap ${formatKb(snapshot.vmSwapKb)}，PrivateDirty ${formatKb(snapshot.totalPrivateDirtyKb)}，` +
      `PrivateClean ${formatKb(snapshot.totalPrivateCleanKb)}`,
  ];

  if (snapshot.deep) {
    lines.push(
      `smaps | 匿名 PSS ${formatKb(snapshot.smapsPssAnonKb)}，文件 PSS ` +
        `${formatKb(snapshot.smapsPssFileKb)}，共享 PSS ${formatKb(snapshot.smapsPssShmemKb)}，` +
        `PrivateDirty ${formatKb(snapshot.smapsPrivateDirtyKb)}，` +
        `PrivateClean ${formatKb(snapshot.smapsPrivateCleanKb)}，SwapPSS ` +
        `${formatKb(snapshot.smapsSwapPssKb)}`,
      `映射 | ${MAPPING_LABELS.map(
        ([key, mappingLabel]) => `${mappingLabel} ${formatKb(snapshot.mappingPssKb[key])}`,
      ).join("，")}`,
      `状态 | 线程 ${snapshot.threadCount}，FD ${snapshot.fileDescriptorCount}，` +
        `importance ${snapshot.processImportance}，trim ${snapshot.lastTrimLevel}；` +
        `系统可用/总内存 ${formatBytes(snapshot.systemMemory.availableBytes)}` +
        `/${formatBytes(snapshot.systemMemory.totalBytes)}，低内存阈值 ` +
        `${formatBytes(snapshot.systemMemory.thresholdBytes)}，lowMemory=${snapshot.systemMemory.lowMemory}`,
      `GC | 总次数 ${snapshot.gcStats.gcCount}，阻塞次数 ` +
        `${snapshot.gcStats.blockingGcCount}，累计 ${snapshot.gcStats.gcTimeMs} ms，` +
        `Java 分配/释放 ${formatBytes(snapshot.gcStats.bytesAllocated)}` +
        `/${formatBytes(snapshot.gcStats.bytesFreed)}`,
    );
  }

  if (snapshot.errors.length > 0) lines.push(`采样降级 | ${snapshot.errors.join("，")}`);
  return lines;
}

export function memoryDeltaLine(
  label: string,
  before: MemorySnapshot,
  after: MemorySnapshot,
): string {
  return (
    `${label}：PSS ${formatSignedKb(difference(after.totalPssKb, before.totalPssKb))}，` +
    `RSS ${formatSignedKb(difference(after.vmRssKb, before.vmRssKb))}，` +
    `Native 已分配 ${formatSignedBytes(
      difference(after.nativeHeapAllocatedBytes, before.nativeHeapAllocatedBytes),
    )}，匿名 RSS ${formatSignedKb(difference(after.rssAnonKb, before.rssAnonKb))}，` +
    `Swap ${formatSignedKb(difference(after.vmSwapKb, before.vmSwapKb))}`
  );
}

function mappingDeltaLine(
  label: string,
  before: MemorySnapshot,
  after: MemorySnapshot,
): string | undefined {
  if (!before.deep || !after.deep) return undefined;
  return `${label}：${MAPPING_LABELS.map(([key, mappingLabel]) => {
    const delta = difference(after.mappingPssKb[key], before.mappingPssKb[key]);
    return `${mappingLabel} ${formatSignedKb(delta)}`;
  }).join("，")}`;
}

export function recognitionReport(
  result: RecognitionResult,
  frontend: FrontendMetrics,
  previousSettled?: MemorySnapshot,
): { lines: string[]; context?: RecognitionMemoryContext } {
  const { metrics } = result;
  const modelState = metrics.modelWasLoaded ? "热启动" : "冷启动";
  const bridgeOverheadMs = Math.max(0, frontend.nativeCallWallMs - metrics.nativeTotalMs);
  const snapshotLines = metrics.memorySnapshots.flatMap((snapshot) =>
    memorySnapshotLines(snapshot, memoryStageLabel(snapshot.stage, metrics.modelWasLoaded)),
  );
  const lines: string[] = [];

  lines.push(
    `图片：${metrics.imageCount} 张，原文件共 ${formatBytes(metrics.sourceImageBytes)}，` +
      `最大原图 ${metrics.sourceWidth}x${metrics.sourceHeight}，最大采样倍率 ${metrics.decodeSampleSize}；` +
      `解码累计 ${formatBytes(metrics.decodedBitmapBytes)}，方向校正累计 ` +
      `${formatBytes(metrics.orientedBitmapBytes)}`,
    `OCR 输入：逐张处理，maxSideLen=${metrics.ocrMaxSideLen}，检测框 ${metrics.detectionCount}，` +
      `识别块 ${metrics.recognitionCount}；模型状态 ${modelState}`,
    `检测实际输入：${metrics.detectorInputSizes.map((size, index) => `图 ${index + 1} ${size.width}x${size.height}`).join("，")}；已含补边与对齐，180° 方向分类已关闭`,
    `图片处理：读取 ${formatDuration(metrics.imageReadMs)}，` +
      `解码 ${formatDuration(metrics.imageDecodeMs)}，方向校正 ${formatDuration(metrics.orientationMs)}`,
    `OCR：引擎对象 ${formatDuration(metrics.engineInitMs)}，` +
      `推理 ${formatDuration(metrics.inferenceWallMs)}，库内统计 ${formatDuration(result.elapsedMs)}，` +
      `结果处理 ${formatDuration(metrics.postprocessMs)}`,
    `调用：Kotlin ${formatDuration(metrics.nativeTotalMs)}，` +
      `Bridge/线程约 ${formatDuration(bridgeOverheadMs)}，` +
      `端到端 ${formatDuration(frontend.frontendTotalMs)}`,
    `结果：${result.textLines.length} 行文字，${result.idNumbers.length} 个有效身份证号，` +
      `${result.validityPeriods.length} 个有效期限，` +
      `剪贴板手机号${result.clipboardPhone ? "已读取" : "未读取"}`,
    "内存阶段 | 以下按请求、图片、推理、释放顺序记录",
    ...snapshotLines,
  );

  const baseline = findSnapshot(result, "requestReceived");
  const beforeInference = findSnapshot(result, "engineReady");
  const afterInference = findSnapshot(result, "inferenceComplete");
  const released = findSnapshot(result, "resourcesReleased");

  if (beforeInference && afterInference) {
    const inferenceLabel = metrics.imageCount > 1 ? `${metrics.imageCount} 张图片推理增长` : "单张推理增长";
    lines.push(memoryDeltaLine(inferenceLabel, beforeInference, afterInference));
    const mappingLine = mappingDeltaLine(`${inferenceLabel}·映射`, beforeInference, afterInference);
    if (mappingLine) lines.push(mappingLine);
  }
  if (afterInference && released) {
    lines.push(memoryDeltaLine("最后一张图片释放效果", afterInference, released));
  }
  if (!baseline || !released) return { lines };

  lines.push(memoryDeltaLine("本次识别净常驻增长", baseline, released));
  const netMappingLine = mappingDeltaLine("本次映射增长", baseline, released);
  if (netMappingLine) lines.push(netMappingLine);
  if (previousSettled) lines.push(memoryDeltaLine("相对上一轮稳定值", previousSettled, released));

  return {
    lines,
    context: {
      recognitionId: metrics.recognitionId,
      baseline,
      released,
      previousSettled,
    },
  };
}

export function idleDiagnosisLines(
  context: RecognitionMemoryContext,
  settled: MemorySnapshot,
): string[] {
  const initialGrowthKb = difference(context.released.totalPssKb, context.baseline.totalPssKb);
  const recoveredKb = difference(context.released.totalPssKb, settled.totalPssKb);
  const retainedKb = difference(settled.totalPssKb, context.baseline.totalPssKb);
  const recoveryRate = initialGrowthKb > 0 ? recoveredKb / initialGrowthKb : 0;
  const lines = [
    `10 秒回收结论：本次峰后常驻 ${formatSignedKb(retainedKb)}，` +
      `延迟回收 ${formatSignedKb(recoveredKb)}，回收比例 ` +
      `${Math.max(0, recoveryRate * 100).toFixed(1)}%`,
  ];

  const netMappingLine = mappingDeltaLine("10 秒后映射净增长", context.baseline, settled);
  if (netMappingLine) lines.push(netMappingLine);

  const dominant = MAPPING_LABELS.map(([key, label]) => ({
    label,
    deltaKb: difference(settled.mappingPssKb[key], context.baseline.mappingPssKb[key]),
  }))
    .filter((entry) => Number.isFinite(entry.deltaKb))
    .sort((a, b) => b.deltaKb - a.deltaKb)[0];

  if (retainedKb < 64 * 1024 && recoveryRate >= 0.5) {
    lines.push("趋势判断：大部分推理临时内存已经归还，优先继续观察不同图片尺寸的峰值");
  } else if (recoveryRate < 0.2) {
    lines.push(
      `趋势判断：10 秒后几乎没有回收，偏向 ONNX/OpenCV 内存池高水位或未释放原生对象；` +
        `映射层最大增长为 ${dominant?.label || "不可用"} ` +
        `${dominant ? formatSignedKb(dominant.deltaKb) : ""}`,
    );
  } else {
    lines.push(
      `趋势判断：存在延迟回收但仍有明显常驻；映射层最大增长为 ` +
        `${dominant?.label || "不可用"} ${dominant ? formatSignedKb(dominant.deltaKb) : ""}`,
    );
  }

  if (context.previousSettled) {
    const runGrowthKb = difference(settled.totalPssKb, context.previousSettled.totalPssKb);
    lines.push(
      runGrowthKb > 64 * 1024
        ? `跨轮判断：稳定值比上一轮增加 ${formatSignedKb(runGrowthKb)}，` +
            `需要继续完成 5 至 10 轮同图测试，排查逐次泄漏`
        : `跨轮判断：稳定值相对上一轮 ${formatSignedKb(runGrowthKb)}，` +
            `暂未出现大幅线性增长，更接近固定高水位`,
    );
  }
  return lines;
}

export function environmentLine(probe: MemoryDiagnosticsProbe): string | undefined {
  const environment = probe.environment;
  if (!environment) return undefined;
  return (
    `设备：${environment.device}，Android API ${environment.androidSdk}，` +
    `ABI ${environment.abis.join("/")}，CPU ${environment.processorCount} 核，` +
    `页大小 ${environment.pageSizeBytes} B，PID ${environment.pid}；` +
    `模型当前${probe.modelIsWarm ? "已热启动" : "尚未推理"}`
  );
}
