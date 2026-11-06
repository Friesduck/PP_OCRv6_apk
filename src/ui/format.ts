import type { RecognitionResult } from "../native-ocr";

export function formatFileSize(value?: number): string {
  if (!value || value < 1) return "大小未知";
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(0)} KiB`;
  return `${(value / 1024 / 1024).toFixed(1)} MiB`;
}

export function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === "object" && error && "message" in error) {
    return String(error.message);
  }
  return String(error || "未知错误");
}

function cleanLine(value: string): string {
  return value.replace(/[\t ]+/g, " ").trim();
}

function uniqueClean(values: string[]): string[] {
  return [...new Set(values.map(cleanLine).filter(Boolean))];
}

export function recognitionTextGroups(result: RecognitionResult): string[][] {
  const groups = result.textGroups.length > 0 ? result.textGroups : [result.textLines];
  return groups.map((group) => group.map(cleanLine).filter(Boolean));
}

export function recognitionIdentifiers(result: RecognitionResult): string {
  return uniqueClean(result.idNumbers).join("、") || "未识别到";
}

export function recognitionPhone(result: RecognitionResult): string {
  return cleanLine(result.clipboardPhone || "") || "未识别到";
}

export function recognitionValidityPeriods(result: RecognitionResult): string {
  return uniqueClean(result.validityPeriods || []).join("、") || "未识别到";
}

export function composeOcrBody(result: RecognitionResult): string {
  return recognitionTextGroups(result)
    .map((lines, index) => [
      `========== 图片 ${index + 1} ==========`,
      ...(lines.length > 0 ? lines : ["未识别到文字"]),
    ].join("\n"))
    .join("\n\n");
}

export function composeRecognitionText(result: RecognitionResult): string {
  const body = composeOcrBody(result) || "未识别到文字";
  return [
    `身份证号：${recognitionIdentifiers(result)}`,
    `手机号：${recognitionPhone(result)}`,
    `有效期限：${recognitionValidityPeriods(result)}`,
    "",
    "------------------------------",
    "",
    body,
  ].join("\n");
}
