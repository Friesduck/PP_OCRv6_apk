import { Capacitor } from "@capacitor/core";
import {
  CalendarRange,
  ClipboardCopy,
  ContactRound,
  Copy,
  ImagePlus,
  Phone,
  Plus,
  ScanText,
  SquareTerminal,
  Trash2,
  createIcons,
} from "lucide";
import { DeveloperLog } from "./developer-log";
import { NativeOcr } from "./native-ocr";
import "./styles.css";
import { requiredElement } from "./ui/dom";
import { errorMessage } from "./ui/format";
import { ImagePanel } from "./ui/image-panel";
import { ResultPanel } from "./ui/result-panel";
import { StatusView } from "./ui/status-view";

const recognizeButton = requiredElement<HTMLButtonElement>("#recognize");
const recognizeLabel = requiredElement<HTMLElement>("#recognize-label");
const status = new StatusView();
const resultPanel = new ResultPanel(status);
const developerLog = new DeveloperLog(status);
let recognitionSequence = 0;

const imagePanel = new ImagePanel({
  onSelected: (selection) => {
    resultPanel.reset();
    recognizeButton.disabled = false;
    const imageCount = selection.images?.length || 0;
    status.show(`已选择 ${imageCount} 张图片`, "success");
    developerLog.recordSelection(imageCount, selection.totalSizeBytes);
  },
  onCleared: () => {
    resultPanel.reset();
    recognizeButton.disabled = true;
    status.clear();
    developerLog.append("已清空所选图片");
  },
  onError: (message) => {
    recognizeButton.disabled = !imagePanel.hasSelection;
    status.show(message, "error");
    developerLog.append(`图片操作失败：${message}`);
  },
});

recognizeButton.addEventListener("click", async () => {
  if (!imagePanel.hasSelection) return;

  recognitionSequence += 1;
  const sequence = recognitionSequence;
  const frontendStartedAt = performance.now();
  developerLog.recordRecognitionStart(sequence);
  imagePanel.setLocked(true);
  recognizeButton.disabled = true;
  recognizeButton.classList.add("busy");
  recognizeLabel.textContent = "正在识别";
  resultPanel.reset();
  status.show("正在识别");

  try {
    const nativeCallStartedAt = performance.now();
    const result = await NativeOcr.recognizeSelected();
    const nativeCallWallMs = performance.now() - nativeCallStartedAt;
    const frontendTotalMs = performance.now() - frontendStartedAt;

    resultPanel.render(result);
    developerLog.recordRecognitionSuccess(result, { nativeCallWallMs, frontendTotalMs });
    status.show("识别完成", "success");
    resultPanel.scrollIntoView();
  } catch (error) {
    const message = errorMessage(error);
    developerLog.recordRecognitionFailure(sequence, performance.now() - frontendStartedAt, message);
    status.show(message, "error");
  } finally {
    imagePanel.setLocked(false);
    recognizeButton.disabled = !imagePanel.hasSelection;
    recognizeButton.classList.remove("busy");
    recognizeLabel.textContent = "开始识别";
  }
});

createIcons({
  icons: {
    CalendarRange,
    ClipboardCopy,
    ContactRound,
    Copy,
    ImagePlus,
    Phone,
    Plus,
    ScanText,
    SquareTerminal,
    Trash2,
  },
  attrs: {
    width: "18",
    height: "18",
    "stroke-width": "1.8",
  },
});

if (Capacitor.isNativePlatform()) {
  void developerLog.initialize();
} else {
  imagePanel.setLocked(true);
  status.show("图片和 OCR 功能仅在 Android APK 中运行", "error");
}
