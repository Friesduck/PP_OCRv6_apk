import { registerPlugin } from "@capacitor/core";

export type MemorySnapshot = {
  stage: string;
  elapsedMs: number;
  capturedAtEpochMs: number;
  deep: boolean;
  captureCostMs: number;
  javaHeapUsedBytes: number;
  javaHeapCommittedBytes: number;
  javaHeapMaxBytes: number;
  nativeHeapAllocatedBytes: number;
  nativeHeapReservedBytes: number;
  nativeHeapFreeBytes: number;
  totalPssKb: number;
  dalvikPssKb: number;
  nativePssKb: number;
  otherPssKb: number;
  totalPrivateDirtyKb: number;
  totalPrivateCleanKb: number;
  totalSwappablePssKb: number;
  summaryJavaHeapKb: number;
  summaryNativeHeapKb: number;
  summaryCodeKb: number;
  summaryStackKb: number;
  summaryGraphicsKb: number;
  summaryPrivateOtherKb: number;
  summarySystemKb: number;
  summaryTotalSwapKb: number;
  vmSizeKb: number;
  vmRssKb: number;
  rssAnonKb: number;
  rssFileKb: number;
  rssShmemKb: number;
  vmDataKb: number;
  vmSwapKb: number;
  threadCount: number;
  fileDescriptorCount: number;
  smapsRssKb: number;
  smapsPssKb: number;
  smapsPssAnonKb: number;
  smapsPssFileKb: number;
  smapsPssShmemKb: number;
  smapsSharedCleanKb: number;
  smapsSharedDirtyKb: number;
  smapsPrivateCleanKb: number;
  smapsPrivateDirtyKb: number;
  smapsSwapKb: number;
  smapsSwapPssKb: number;
  mappingPssKb: {
    nativeAllocator: number;
    otherAnonymous: number;
    onnxOpenCv: number;
    modelFiles: number;
    dalvikArt: number;
    webView: number;
    graphics: number;
    threadStacks: number;
    apkDex: number;
    otherFiles: number;
  };
  systemMemory: {
    availableBytes: number;
    totalBytes: number;
    thresholdBytes: number;
    lowMemory: boolean;
  };
  processImportance: number;
  lastTrimLevel: number;
  gcStats: {
    gcCount: number;
    blockingGcCount: number;
    gcTimeMs: number;
    blockingGcTimeMs: number;
    bytesAllocated: number;
    bytesFreed: number;
  };
  errors: string[];
};

export type RecognitionMetrics = {
  recognitionId: number;
  startedAtEpochMs: number;
  modelWasLoaded: boolean;
  imageCount: number;
  sourceImageBytes: number;
  sourceWidth: number;
  sourceHeight: number;
  decodeSampleSize: number;
  decodedWidth: number;
  decodedHeight: number;
  decodedBitmapBytes: number;
  orientedWidth: number;
  orientedHeight: number;
  orientedBitmapBytes: number;
  resultBitmapBytes: number;
  detectionCount: number;
  recognitionCount: number;
  ocrMaxSideLen: number;
  detectorInputSizes: { width: number; height: number }[];
  imageReadMs: number;
  imageDecodeMs: number;
  orientationMs: number;
  engineInitMs: number;
  inferenceWallMs: number;
  postprocessMs: number;
  nativeTotalMs: number;
  memorySnapshots: MemorySnapshot[];
};

export type MemoryDiagnosticsProbe = {
  skipped: boolean;
  reason?: string;
  recognitionId: number;
  modelIsWarm?: boolean;
  sinceReleaseMs?: number;
  environment?: {
    pid: number;
    androidSdk: number;
    device: string;
    abis: string[];
    pageSizeBytes: number;
    processorCount: number;
  };
  snapshot?: MemorySnapshot;
};

export type RecognitionResult = {
  idNumbers: string[];
  validityPeriods: string[];
  textLines: string[];
  textGroups: string[][];
  clipboardPhone?: string;
  elapsedMs: number;
  metrics: RecognitionMetrics;
};

export type SelectedImagePreview = {
  index: number;
  previewDataUrl: string;
};

export type PickImageResult = {
  cancelled: boolean;
  images?: SelectedImagePreview[];
  totalSizeBytes?: number;
};

type NativeOcrPlugin = {
  getOcrSettings(): Promise<OcrSettings>;
  setOcrSettings(options: { detectorMaxSide: number }): Promise<OcrSettings>;
  pickImage(): Promise<PickImageResult>;
  recognizeSelected(): Promise<RecognitionResult>;
  clearSelection(): Promise<void>;
  showSelectedImage(options: { index: number }): Promise<void>;
  setDiagnosticsEnabled(options: { enabled: boolean }): Promise<void>;
  captureMemoryDiagnostics(options: {
    stage: string;
    recognitionId?: number;
  }): Promise<MemoryDiagnosticsProbe>;
  copyText(options: { value: string }): Promise<void>;
};

export type OcrSettings = {
  detectorMaxSide: number;
  defaultDetectorMaxSide: number;
};

export const NativeOcr = registerPlugin<NativeOcrPlugin>("NativeOcr");
