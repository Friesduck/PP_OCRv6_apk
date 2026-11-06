import {
  IDLE_MEMORY_SAMPLES,
  environmentLine,
  formatDuration,
  idleDiagnosisLines,
  memoryDeltaLine,
  memorySnapshotLines,
  recognitionReport,
  type FrontendMetrics,
  type RecognitionMemoryContext,
} from "./memory-report";
import { NativeOcr, type MemorySnapshot, type OcrSettings, type RecognitionResult } from "./native-ocr";
import { requiredElement } from "./ui/dom";
import { errorMessage, formatFileSize } from "./ui/format";
import type { StatusView } from "./ui/status-view";

export type { FrontendMetrics } from "./memory-report";

const LOG_LINE_LIMIT = 600;
const DOUBLE_CLICK_WINDOW_MS = 450;

export class DeveloperLog {
  private readonly section = requiredElement<HTMLElement>("#developer-log");
  private readonly toggleButton = requiredElement<HTMLButtonElement>("#developer-toggle");
  private readonly copyButton = requiredElement<HTMLButtonElement>("#copy-log");
  private readonly clearButton = requiredElement<HTMLButtonElement>("#clear-log");
  private readonly output = requiredElement<HTMLElement>("#session-log");
  private readonly detectorSide = requiredElement<HTMLInputElement>("#detector-side");
  private readonly saveSettingsButton = requiredElement<HTMLButtonElement>("#save-ocr-settings");
  private readonly resetSettingsButton = requiredElement<HTMLButtonElement>("#reset-ocr-settings");
  private readonly settingsStatus = requiredElement<HTMLElement>("#ocr-settings-status");
  private defaultDetectorSide?: number;
  private settingsBusy = false;
  private readonly lines: string[] = [];
  private readonly idleTimers: number[] = [];
  private lastToggleClickAt = 0;
  private active = false;
  private toggling = false;
  private sampleGeneration = 0;
  private previousSettledSnapshot?: MemorySnapshot;
  private currentMemoryContext?: RecognitionMemoryContext;
  private sessionStartedAt = performance.now();

  constructor(private readonly status: StatusView) {
    this.toggleButton.addEventListener("click", () => this.handleToggleClick());
    this.copyButton.addEventListener("click", () => void this.copy());
    this.clearButton.addEventListener("click", () => this.clear());
    this.saveSettingsButton.addEventListener("click", () => void this.saveSettings(false));
    this.resetSettingsButton.addEventListener("click", () => void this.saveSettings(true));
    document.addEventListener("visibilitychange", () => void this.captureVisibilitySample());
  }

  get enabled(): boolean {
    return this.active;
  }

  async initialize(): Promise<void> {
    try {
      await NativeOcr.setDiagnosticsEnabled({ enabled: false });
    } catch {
      // 浏览器预览或原生插件尚未就绪时保持默认关闭即可。
    }
  }

  recordSelection(imageCount: number, sizeBytes: number | undefined): void {
    this.append(`图片 | 已选择 ${imageCount} 张，共 ${formatFileSize(sizeBytes)}`);
  }

  recordRecognitionStart(sequence: number): void {
    this.cancelIdleSamples();
    this.append(`识别 ${sequence} | 开始，先读取剪贴板手机号`);
  }

  recordRecognitionFailure(sequence: number, elapsedMs: number, message: string): void {
    this.append(`识别 ${sequence} | 失败 | ${formatDuration(elapsedMs)} | ${message}`);
  }

  recordRecognitionSuccess(result: RecognitionResult, frontend: FrontendMetrics): void {
    if (!this.active) return;
    const report = recognitionReport(result, frontend, this.previousSettledSnapshot);
    this.appendBlock(`识别 ${result.metrics.recognitionId} | 完成`, report.lines);
    if (!report.context) return;

    this.currentMemoryContext = report.context;
    this.previousSettledSnapshot = report.context.released;
    this.append("内存观察 | 0.5 秒、2 秒、10 秒后自动复测，请等待 10 秒再开始下一轮");
    this.scheduleIdleSamples(report.context.recognitionId);
  }

  append(message: string): void {
    if (!this.active) return;

    this.lines.push(`${this.elapsedLabel()}  ${message}`);
    this.render();
  }

  private appendBlock(title: string, lines: string[]): void {
    this.lines.push("", `${this.elapsedLabel()}  ── ${title} ──`);
    this.lines.push(...lines.map((line) => `  ${line}`));
    this.render();
  }

  private scheduleIdleSamples(recognitionId: number): void {
    const generation = ++this.sampleGeneration;
    IDLE_MEMORY_SAMPLES.forEach((sample) => {
      const timer = window.setTimeout(() => {
        void this.captureIdleSample(recognitionId, generation, sample.stage, sample.label);
      }, sample.delayMs);
      this.idleTimers.push(timer);
    });
  }

  private async captureIdleSample(
    recognitionId: number,
    generation: number,
    stage: string,
    label: string,
  ): Promise<void> {
    try {
      const probe = await NativeOcr.captureMemoryDiagnostics({ stage, recognitionId });
      if (!this.active || generation !== this.sampleGeneration) return;
      if (probe.skipped || !probe.snapshot) {
        if (probe.reason !== "newerRecognitionStarted") {
          this.append(`内存·${label}跳过：${probe.reason || "原生端未返回快照"}`);
        }
        return;
      }

      const context = this.currentMemoryContext;
      if (!context || context.recognitionId !== recognitionId) return;

      this.appendBlock(label, [
        ...memorySnapshotLines(probe.snapshot, label),
        memoryDeltaLine("相对图片释放后", context.released, probe.snapshot),
      ]);
      this.previousSettledSnapshot = probe.snapshot;
      if (stage === "idle10s") this.appendBlock("10 秒结论", idleDiagnosisLines(context, probe.snapshot));
    } catch (error) {
      if (this.active && generation === this.sampleGeneration) {
        this.append(`内存·${label}采样失败：${errorMessage(error)}`);
      }
    }
  }

  private async captureEnableBaseline(): Promise<void> {
    try {
      const probe = await NativeOcr.captureMemoryDiagnostics({ stage: "diagnosticsEnabled" });
      if (!this.active || probe.skipped || !probe.snapshot) return;
      const environment = environmentLine(probe);
      if (environment) this.append(environment);
      this.appendBlock("初始内存", memorySnapshotLines(probe.snapshot, "日志启用基线"));
    } catch (error) {
      if (this.active) this.append(`日志启用基线采样失败：${errorMessage(error)}`);
    }
  }

  private async captureVisibilitySample(): Promise<void> {
    if (!this.active) return;
    const hidden = document.visibilityState === "hidden";
    const stage = hidden ? "appHidden" : "appVisible";
    const label = hidden ? "应用进入后台" : "应用回到前台";
    this.append(`生命周期：${label}，开始检查模型常驻和系统回收情况`);

    try {
      const probe = await NativeOcr.captureMemoryDiagnostics({ stage });
      if (!this.active || probe.skipped || !probe.snapshot) {
        if (this.active && probe.reason) this.append(`生命周期采样跳过：${probe.reason}`);
        return;
      }
      this.appendBlock(label, memorySnapshotLines(probe.snapshot, label));
      if (this.previousSettledSnapshot) {
        this.append(memoryDeltaLine(`${label}相对最近稳定值`, this.previousSettledSnapshot, probe.snapshot));
      }
      if (this.currentMemoryContext) this.previousSettledSnapshot = probe.snapshot;
    } catch (error) {
      if (this.active) this.append(`生命周期采样失败：${errorMessage(error)}`);
    }
  }

  private cancelIdleSamples(): void {
    this.sampleGeneration += 1;
    this.idleTimers.splice(0).forEach((timer) => window.clearTimeout(timer));
  }

  private handleToggleClick(): void {
    const now = performance.now();
    if (now - this.lastToggleClickAt <= DOUBLE_CLICK_WINDOW_MS) {
      this.lastToggleClickAt = 0;
      void this.toggle();
      return;
    }
    this.lastToggleClickAt = now;
  }

  private async toggle(): Promise<void> {
    if (this.toggling) return;
    this.toggling = true;
    this.toggleButton.disabled = true;
    const nextState = !this.active;

    try {
      await NativeOcr.setDiagnosticsEnabled({ enabled: nextState });
      if (!nextState) {
        this.append("开发者日志已关闭；延迟内存采样已取消");
        this.cancelIdleSamples();
      }
      this.active = nextState;
      this.section.hidden = !nextState;
      this.toggleButton.classList.toggle("active", nextState);
      this.toggleButton.setAttribute("aria-pressed", String(nextState));
      if (nextState) {
        this.sessionStartedAt = performance.now();
        this.previousSettledSnapshot = undefined;
        this.currentMemoryContext = undefined;
        this.append(`日志已启用 | ${new Date().toLocaleString("zh-CN", { hour12: false })}`);
        this.append("说明 | 左侧时间是启用日志后的相对时间；PSS 越低越好；+ 表示增长，− 表示回落");
        void this.captureEnableBaseline();
        void this.loadSettings();
      }
    } catch (error) {
      this.status.show(`日志切换失败：${errorMessage(error)}`, "error");
    } finally {
      this.toggleButton.disabled = false;
      this.toggling = false;
    }
  }

  private clear(): void {
    this.lines.length = 0;
    this.sessionStartedAt = performance.now();
    this.output.textContent = "";
    this.copyButton.disabled = true;
  }

  private displaySettings(settings: OcrSettings): void {
    this.defaultDetectorSide = settings.defaultDetectorMaxSide;
    this.detectorSide.value = String(settings.detectorMaxSide);
    this.settingsStatus.textContent = `当前已保存 ${settings.detectorMaxSide}；现有默认 ${settings.defaultDetectorMaxSide}（暂不调整）`;
  }

  private async loadSettings(): Promise<void> {
    if (this.settingsBusy) return;
    this.settingsBusy = true;
    try {
      this.displaySettings(await NativeOcr.getOcrSettings());
    } catch (error) {
      this.settingsStatus.textContent = `读取失败：${errorMessage(error)}`;
    } finally {
      this.settingsBusy = false;
      this.saveSettingsButton.disabled = this.defaultDetectorSide === undefined;
      this.resetSettingsButton.disabled = this.defaultDetectorSide === undefined;
    }
  }

  private async saveSettings(reset: boolean): Promise<void> {
    if (this.settingsBusy) return;
    const side = reset ? this.defaultDetectorSide : this.detectorSide.valueAsNumber;
    if (side === undefined || !Number.isInteger(side) || side < 320 || side > 2400 || side % 32 !== 0) {
      this.settingsStatus.textContent = "请输入 320～2400 之间的 32 倍数";
      return;
    }
    this.settingsBusy = true;
    this.saveSettingsButton.disabled = true;
    this.resetSettingsButton.disabled = true;
    this.settingsStatus.textContent = "正在保存；识别进行中时会等待本轮结束";
    try {
      this.displaySettings(await NativeOcr.setOcrSettings({ detectorMaxSide: side }));
      this.append(`参数 | 检测长边已保存为 ${side}，从下一轮识别生效`);
    } catch (error) {
      this.settingsStatus.textContent = `保存失败：${errorMessage(error)}`;
    } finally {
      this.settingsBusy = false;
      this.saveSettingsButton.disabled = false;
      this.resetSettingsButton.disabled = false;
    }
  }

  private async copy(): Promise<void> {
    if (this.lines.length === 0) return;

    try {
      await NativeOcr.copyText({ value: this.lines.join("\n") });
      this.copyButton.classList.add("copied");
      window.setTimeout(() => this.copyButton.classList.remove("copied"), 1200);
    } catch (error) {
      this.status.show(`日志复制失败：${errorMessage(error)}`, "error");
    }
  }

  private elapsedLabel(): string {
    const elapsed = Math.max(0, performance.now() - this.sessionStartedAt);
    const minutes = Math.floor(elapsed / 60_000).toString().padStart(2, "0");
    const seconds = Math.floor((elapsed % 60_000) / 1_000).toString().padStart(2, "0");
    const millis = Math.floor(elapsed % 1_000).toString().padStart(3, "0");
    return `[${minutes}:${seconds}.${millis}]`;
  }

  private render(): void {
    if (this.lines.length > LOG_LINE_LIMIT) this.lines.splice(0, this.lines.length - LOG_LINE_LIMIT);
    this.output.textContent = this.lines.join("\n");
    this.copyButton.disabled = this.lines.length === 0;
    this.output.scrollTop = this.output.scrollHeight;
  }
}
