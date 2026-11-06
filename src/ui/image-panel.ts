import { NativeOcr, type PickImageResult, type SelectedImagePreview } from "../native-ocr";
import { requiredElement } from "./dom";
import { errorMessage, formatFileSize } from "./format";

type ImagePanelHooks = {
  onSelected: (selection: PickImageResult) => void;
  onCleared: () => void;
  onError: (message: string) => void;
};

export class ImagePanel {
  private readonly selectButton = requiredElement<HTMLButtonElement>("#select-image");
  private readonly selectLabel = requiredElement<HTMLElement>("#select-label");
  private readonly selectDetail = requiredElement<HTMLElement>("#select-detail");
  private readonly previewRegion = requiredElement<HTMLElement>("#preview-region");
  private readonly imageGrid = requiredElement<HTMLElement>("#image-grid");
  private readonly imageCount = requiredElement<HTMLElement>("#image-count");
  private readonly selectionSummary = requiredElement<HTMLElement>("#selection-summary");
  private readonly removeButton = requiredElement<HTMLButtonElement>("#remove-image");
  private selectedCount = 0;
  private locked = false;

  constructor(private readonly hooks: ImagePanelHooks) {
    this.selectButton.addEventListener("click", () => void this.pickImages());
    this.removeButton.addEventListener("click", () => void this.clear());
  }

  get hasSelection(): boolean {
    return this.selectedCount > 0;
  }

  setLocked(locked: boolean): void {
    this.locked = locked;
    this.selectButton.disabled = locked;
    this.removeButton.disabled = locked;
    this.imageGrid.querySelectorAll<HTMLButtonElement>("button").forEach((button) => {
      button.disabled = locked;
    });
  }

  private async pickImages(): Promise<void> {
    this.selectButton.disabled = true;
    this.selectLabel.textContent = "正在打开图片";

    try {
      const selection = await NativeOcr.pickImage();
      if (selection.cancelled) {
        this.updatePickerCopy();
        return;
      }
      const images = selection.images || [];
      if (images.length === 0) throw new Error("原生端没有返回图片预览");

      this.selectedCount = images.length;
      this.renderImages(images);
      this.previewRegion.hidden = false;
      this.imageCount.textContent = `${images.length} 张`;
      this.selectionSummary.textContent = `已选择 ${images.length} 张 · ${formatFileSize(selection.totalSizeBytes)}`;
      this.updatePickerCopy();
      this.hooks.onSelected(selection);
    } catch (error) {
      this.updatePickerCopy();
      this.hooks.onError(errorMessage(error));
    } finally {
      this.selectButton.disabled = this.locked;
    }
  }

  private renderImages(images: SelectedImagePreview[]): void {
    const fragment = document.createDocumentFragment();
    images.forEach((image, position) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "image-tile";
      button.dataset.order = String(position + 1);
      button.setAttribute("aria-label", `查看第 ${position + 1} 张原图`);
      button.style.animationDelay = `${Math.min(position, 10) * 24}ms`;
      const preview = document.createElement("img");
      preview.src = image.previewDataUrl;
      preview.alt = "";
      preview.loading = "eager";
      button.append(preview);
      button.addEventListener("click", () => void this.showImage(image.index));
      fragment.append(button);
    });
    this.imageGrid.replaceChildren(fragment);
  }

  private async showImage(index: number): Promise<void> {
    if (this.locked) return;
    try {
      await NativeOcr.showSelectedImage({ index });
    } catch (error) {
      this.hooks.onError(errorMessage(error));
    }
  }

  private async clear(): Promise<void> {
    this.resetView();
    this.hooks.onCleared();
    try {
      await NativeOcr.clearSelection();
    } catch {
      // 页面状态已经清空，原生进程重建时可能没有可清理的选择。
    }
  }

  private updatePickerCopy(): void {
    this.selectLabel.textContent = this.hasSelection ? "重新选择" : "选择图片";
    this.selectDetail.textContent = this.hasSelection
      ? "角标顺序即识别顺序"
      : "单张或多张，最多 4 张";
  }

  private resetView(): void {
    this.selectedCount = 0;
    this.imageGrid.replaceChildren();
    this.previewRegion.hidden = true;
    this.imageCount.textContent = "0 张";
    this.selectionSummary.textContent = "";
    this.updatePickerCopy();
  }
}
