import { requiredElement } from "./dom";

export type StatusTone = "normal" | "success" | "error";

export class StatusView {
  private readonly element = requiredElement<HTMLElement>("#status");

  show(message: string, tone: StatusTone = "normal"): void {
    this.element.textContent = message;
    this.element.dataset.tone = tone;
  }

  clear(): void {
    this.element.textContent = "";
    delete this.element.dataset.tone;
  }
}
