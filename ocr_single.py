"""批量识别 images 中的图片，保留水平文字并过滤倾斜水印。

在 IDE 中直接运行本脚本即可，无须命令行参数，也不依赖 IDE 的工作目录。
图片按文件名自然排序（1、2、10），包括子目录中的常见图片格式。
文字汇总到 output/paddleocr-out.md，每张图片另存一张 *_debug.jpg。
默认检测长边为 736，修改下方参数即可选择其他尺寸；原图不会被修改。
默认追加文字结果，progress.log 标记每张图片是否完成；绿框保留、红框过滤。
您可手动移走已完成图片后继续运行；留在输入目录中的图片会再次识别并追加。
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from datetime import datetime
from math import isfinite
from pathlib import Path
from time import perf_counter
from typing import TYPE_CHECKING
from urllib.parse import quote

import cv2
import numpy as np

if TYPE_CHECKING:
    from paddleocr import PaddleOCR


# ============ 1. 常用参数：直接修改这里，然后在 IDE 点击运行 ============
# 路径以脚本位置为准；也可改成 Path(r"D:\照片目录") 这样的绝对路径。
PROJECT_DIR = Path(__file__).resolve().parent
IMAGES_DIR = PROJECT_DIR / "images"
OUTPUT_DIR = PROJECT_DIR / "output"

DET_MAX_SIDE = 736  # 检测长边（像素），支持 32～4000 之间的 32 倍数。
# 尺寸参考（均可直接填写；也支持范围内其他 32 倍数）：
#   更小：320、384、448、512、576、640、704
#   默认及邻近：736、768、800、832、896
#   更大：960、1024、1152、1280、1536、1920、2048、2560、3072、3840、4000
# 更小通常更快，但可能漏小字；更大计算量更高，识别质量并不一定更好。
# 只限制检测图长边，按原比例缩放后对齐到 32 倍数，不拉成正方形。
# 小图不会因该长边设置而主动放大；识别文字从原图裁剪，调试图也保留原尺寸。

APPEND_RESULTS = True  # True：追加到已有 Markdown；False：本次重新生成 Markdown。
# 此开关只影响文字汇总；日志始终追加，已有调试图始终保留。
MAX_TILT = 15.0  # 超过此角度的文字按水印过滤；改为 90.0 可关闭角度过滤。
CPU_THREADS = 4  # CPU 推理线程数；沿用尺寸测试配置，须为正整数。
TEXT_REC_BATCH_SIZE = 8  # 每批识别的文字块数（不是图片数），须为正整数。

# ============ 2. 固定配置：沿用已验证的模型与后处理规则 ============
MD_PATH = OUTPUT_DIR / "paddleocr-out.md"
LOG_PATH = OUTPUT_DIR / "progress.log"
MODEL_ROOT = PROJECT_DIR / "models" / "official_models"
DETECTION_MODEL = "PP-OCRv6_medium_det"
RECOGNITION_MODEL = "PP-OCRv6_medium_rec"
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff", ".webp"}
ROW_TOLERANCE = 0.5  # 同行文字的中心高度差，不超过较矮文字框高度的此比例。

# 必须在实际导入 PaddleOCR 前设置。初始化时显式指定现有本地模型路径。
os.environ["PADDLE_PDX_CACHE_HOME"] = str(PROJECT_DIR / "models")
os.environ["PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK"] = "True"


def validate_settings() -> None:
    """加载模型前检查参数，避免数值写错后长时间运行或静默采用错误配置。"""
    if type(DET_MAX_SIDE) is not int or not 32 <= DET_MAX_SIDE <= 4000:
        raise ValueError("DET_MAX_SIDE 必须是 32～4000 之间的整数，例如 640、736、960")
    if DET_MAX_SIDE % 32:
        raise ValueError("DET_MAX_SIDE 必须是 32 的倍数，例如 704、736、768、832")
    if type(APPEND_RESULTS) is not bool:
        raise ValueError("APPEND_RESULTS 必须为 True 或 False")
    if (type(MAX_TILT) not in (int, float)
            or not isfinite(MAX_TILT) or not 0 <= MAX_TILT <= 90):
        raise ValueError("MAX_TILT 必须是 0～90 之间的角度")
    for name, value in (("CPU_THREADS", CPU_THREADS),
                        ("TEXT_REC_BATCH_SIZE", TEXT_REC_BATCH_SIZE)):
        if type(value) is not int or value < 1:
            raise ValueError(f"{name} 必须为正整数")
    if IMAGES_DIR.resolve() == OUTPUT_DIR.resolve():
        raise ValueError("输入目录和输出目录不能相同，请为输出指定单独的目录")


def create_ocr() -> PaddleOCR:
    """复用本地 medium 模型，缺少文件就明确报错，不在正式运行中隐式下载。"""
    for model in (DETECTION_MODEL, RECOGNITION_MODEL):
        for filename in ("inference.yml", "inference.json", "inference.pdiparams"):
            model_file = MODEL_ROOT / model / filename
            if not model_file.is_file():
                raise FileNotFoundError(f"缺少本地模型文件：{model_file}")

    # 延迟导入让模型加载提示先显示，没有待处理图片时也不会启动推理库。
    from paddleocr import PaddleOCR

    return PaddleOCR(
        text_detection_model_name=DETECTION_MODEL,
        text_detection_model_dir=str(MODEL_ROOT / DETECTION_MODEL),
        text_recognition_model_name=RECOGNITION_MODEL,
        text_recognition_model_dir=str(MODEL_ROOT / RECOGNITION_MODEL),
        device="cpu",
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        enable_mkldnn=False,  # 保留兼容设置，避免 ConvertPirAttribute2RuntimeAttribute 报错。
        cpu_threads=CPU_THREADS,
        text_recognition_batch_size=TEXT_REC_BATCH_SIZE,
        text_det_limit_type="max",
        text_det_limit_side_len=DET_MAX_SIDE,
    )


@dataclass
class TextBox:
    """只保留阅读排序所需的数据，不跨图片缓存推理结果或大图。"""

    text: str
    x: float
    y: float
    height: float


def natural_key(path: Path) -> tuple:
    """文件名里的数字按数值排序，避免 10.jpg 排在 2.jpg 前面。"""
    relative_name = path.relative_to(IMAGES_DIR).as_posix()
    parts = tuple(
        int(part) if part.isdecimal() else part.casefold()
        for part in re.split(r"(\d+)", relative_name)
    )
    return parts, relative_name.casefold(), relative_name


def tilt_of(poly: np.ndarray) -> float:
    """返回 0~90 度的倾斜角：横排接近 0，斜水印接近 45，竖排接近 90。"""
    pts = np.asarray(poly, dtype=np.float32).reshape(-1, 2)
    box = cv2.boxPoints(cv2.minAreaRect(pts))
    e1 = box[1] - box[0]
    e2 = box[2] - box[1]
    l1, l2 = np.linalg.norm(e1), np.linalg.norm(e2)
    long_edge = e1 if l1 >= l2 else e2
    ratio = max(l1, l2) / max(min(l1, l2), 1e-6)

    ang = np.degrees(np.arctan2(long_edge[1], long_edge[0]))
    ang = (ang + 90) % 180 - 90  # 折算到 [-90, 90)。
    if ratio >= 1.5:
        return float(abs(ang))
    # 单字框接近正方形，长边方向不稳定，取边与水平/垂直方向的较小夹角。
    return float(min(abs(ang), 90 - abs(ang)))


def reading_lines(boxes: list[TextBox]) -> list[str]:
    """按从上到下、同行从左到右整理文字，适合普通横排文档与题目截图。

    根据文字高度容忍同一行的轻微偏移，将选项字母与右侧内容放在同一行。
    这是几何排序，不负责报纸多栏、复杂表格等版面结构还原。
    """
    rows: list[list[TextBox]] = []
    for box in sorted(boxes, key=lambda item: (item.y, item.x)):
        if rows:
            anchor = rows[-1][0]
            tolerance = min(anchor.height, box.height) * ROW_TOLERANCE
            if abs(box.y - anchor.y) <= tolerance:
                rows[-1].append(box)
                continue
        rows.append([box])

    return [
        " ".join(box.text for box in sorted(row, key=lambda item: item.x))
        for row in rows
    ]


def recognize_image(ocr: PaddleOCR, img_path: Path) -> tuple[list[str], np.ndarray, int]:
    """识别一张图，返回排序后的文字、已画框图片和原始文字框数。"""
    # fromfile/imdecode 支持中文路径；空文件与无法解码的图片交给主循环记录。
    data = np.fromfile(str(img_path), dtype=np.uint8)
    if data.size == 0:
        raise ValueError("图片文件为空")
    img = cv2.imdecode(data, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("无法解码图片，请检查文件是否损坏或格式是否受支持")

    results = ocr.predict(img)
    if len(results) != 1:
        raise ValueError(f"单张图片预期返回 1 份结果，实际返回 {len(results)} 份")
    res = results[0]
    texts, polys = res["rec_texts"], res["rec_polys"]

    boxes = []
    # 推理已完成，可以直接在本图上画框，省去额外复制一张大图的内存。
    for text, poly in zip(texts, polys, strict=True):
        pts = np.asarray(poly, dtype=np.float32).reshape(-1, 2)
        keep = tilt_of(pts) <= MAX_TILT
        color = (0, 180, 0) if keep else (0, 0, 255)
        cv2.polylines(img, [pts.astype(np.int32).reshape(-1, 1, 2)], True, color, 2)
        if keep and text.strip():
            boxes.append(TextBox(
                text=text.strip(),
                x=float(pts[:, 0].min()),
                y=float(pts[:, 1].mean()),
                height=max(float(np.ptp(pts[:, 1])), 1.0),
            ))

    return reading_lines(boxes), img, len(texts)


def debug_path_for(img_path: Path, used_paths: set[str]) -> Path:
    """镜像子目录；同批次重名或磁盘上已有同名文件时追加序号，保留历史调试图。"""
    relative = img_path.relative_to(IMAGES_DIR)
    parent = OUTPUT_DIR / relative.parent
    debug_path = parent / f"{img_path.stem}_debug.jpg"
    number = 2
    while str(debug_path).casefold() in used_paths or debug_path.exists():
        debug_path = parent / f"{img_path.stem}_{number}_debug.jpg"
        number += 1
    used_paths.add(str(debug_path).casefold())
    return debug_path


def save_debug(img: np.ndarray, path: Path) -> None:
    """检查 JPEG 编码后独占创建文件，支持中文路径，并避免意外覆盖旧图。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    success, encoded = cv2.imencode(".jpg", img)
    if not success:
        raise OSError("调试图 JPEG 编码失败")
    with path.open("xb") as stream:
        encoded.tofile(stream)


def text_block(text: str) -> str:
    """用代码块保留文字原始换行，防止 OCR 内容被当作 Markdown 语法。"""
    longest_run = max((len(run) for run in re.findall(r"`+", text)), default=0)
    fence = "`" * max(3, longest_run + 1)
    return f"{fence}text\n{text}\n{fence}\n\n"


def main() -> None:
    """只初始化一次 OCR，逐图处理、逐图写盘，避免图片越多占用内存越大。"""
    validate_settings()
    if not IMAGES_DIR.is_dir():
        print(f"找不到图片目录，请先创建并放入图片：{IMAGES_DIR}", flush=True)
        return
    # 如果输出目录放在输入目录内部，扫描时排除它，防止调试图再次进入 OCR。
    input_root, output_root = IMAGES_DIR.resolve(), OUTPUT_DIR.resolve()
    output_inside_input = output_root.is_relative_to(input_root)
    image_paths = sorted(
        (path for path in IMAGES_DIR.rglob("*")
         if path.is_file() and path.suffix.lower() in IMAGE_SUFFIXES
         and not (output_inside_input and path.resolve().is_relative_to(output_root))),
        key=natural_key,
    )
    if not image_paths:
        print(f"未找到支持的图片：{IMAGES_DIR}；已有输出不会被覆盖。", flush=True)
        return

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    total_start = perf_counter()
    run_id = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    current_image: str | None = None
    completed = failed = debug_failed = 0
    status = "运行中"
    used_debug_paths: set[str] = set()

    def log(message: str) -> None:
        # 日志始终追加；每次关闭文件确保记录刷新，控制台和文件显示相同内容。
        entry = f"{datetime.now():%Y-%m-%d %H:%M:%S} [{run_id}] {message}"
        with LOG_PATH.open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(entry + "\n")
        print(message, flush=True)

    def summary() -> str:
        remaining = len(image_paths) - completed - failed - debug_failed
        return (
            f"{status}：完成 {completed} 张，识别失败 {failed} 张，"
            f"调试图保存失败 {debug_failed} 张，未处理或中断 {remaining} 张；"
            f"总耗时 {perf_counter() - total_start:.1f} 秒。"
        )

    log(
        f"[开始批次] {run_id} | {len(image_paths)} 张 | 检测长边 {DET_MAX_SIDE} | "
        f"CPU 线程 {CPU_THREADS} | 文字批大小 {TEXT_REC_BATCH_SIZE} | "
        f"文字模式：{'追加' if APPEND_RESULTS else '重新生成'} | 输入：{IMAGES_DIR.resolve()}"
    )
    try:
        log("[加载模型] 正在加载本地 OCR 依赖与模型...")
        ocr = create_ocr()
        log(f"[模型就绪] 耗时 {perf_counter() - total_start:.1f} 秒")

        # 模型就绪后才打开汇总；重新生成模式也不会因模型加载失败而清空旧文字。
        with MD_PATH.open("a" if APPEND_RESULTS else "w", encoding="utf-8", newline="\n") as md_file:
            md_file.write(
                f"\n\n# OCR 批次 {run_id}\n\n"
                f"图片数：{len(image_paths)}；检测长边：{DET_MAX_SIDE}；"
                f"CPU 线程：{CPU_THREADS}；文字批大小：{TEXT_REC_BATCH_SIZE}；"
                f"倾斜过滤角度：{MAX_TILT}。\n\n"
                "输入目录：\n\n" + text_block(str(IMAGES_DIR.resolve()))
            )
            md_file.flush()
            for index, img_path in enumerate(image_paths, start=1):
                current_image = img_path.relative_to(IMAGES_DIR).as_posix()
                start = perf_counter()
                log(f"[开始 {index}/{len(image_paths)}] {current_image}")
                md_file.write(f"## 图片 {index}\n\n" + text_block(current_image))
                md_file.flush()
                try:
                    lines, debug_img, detected_count = recognize_image(ocr, img_path)
                except Exception as exc:
                    # 一张坏图或推理异常不终止整批；保存错误类型便于用户反馈排查。
                    error = f"{type(exc).__name__}: {exc}"
                    md_file.write("状态：识别失败。\n\n" + text_block(error))
                    md_file.flush()
                    log(f"[识别失败] {current_image} | {perf_counter() - start:.1f} 秒 | {error}")
                    failed += 1
                else:
                    # 先保存文字。即使调试图写入失败，已经识别的内容也不会丢失。
                    md_file.write(text_block("\n".join(lines)) if lines else "未检测到保留的文字。\n\n")
                    md_file.flush()
                    try:
                        debug_path = debug_path_for(img_path, used_debug_paths)
                        save_debug(debug_img, debug_path)
                    except Exception as exc:
                        error = f"{type(exc).__name__}: {exc}"
                        md_file.write("状态：未完成，文字已保存，但调试图保存失败。\n\n" + text_block(error))
                        md_file.flush()
                        log(f"[未完成] {current_image} | 调试图保存失败 | "
                            f"{perf_counter() - start:.1f} 秒 | {error}")
                        debug_failed += 1
                    else:
                        link = quote(debug_path.relative_to(OUTPUT_DIR).as_posix(), safe="/")
                        elapsed = perf_counter() - start
                        md_file.write(
                            f"[查看调试图]({link})\n\n状态：完成；识别 {detected_count} 个文字框，"
                            f"整理为 {len(lines)} 行，耗时 {elapsed:.1f} 秒。\n\n"
                        )
                        md_file.flush()
                        # 只有文字、调试图和完成记录全部写好，才计入完成数。
                        log(f"[完成] {current_image} | {elapsed:.1f} 秒 | "
                            f"{detected_count} 个文字框 / {len(lines)} 行 | "
                            f"调试图：{debug_path.relative_to(OUTPUT_DIR).as_posix()}")
                        completed += 1
                    finally:
                        del debug_img
                current_image = None

            status = "处理结束" if completed == len(image_paths) else "处理结束，存在失败项"
            md_file.write(summary() + "\n\n")
    except KeyboardInterrupt:
        status = "用户中断"
        log(f"[中断] 当前图片：{current_image or '无正在处理的图片'}；"
            "没有 [完成] 记录的图片请保留，下次重新处理。")
    except Exception as exc:
        status = "运行中止"
        log(f"[中止] 当前图片：{current_image or '无正在处理的图片'} | {type(exc).__name__}: {exc}")
        raise
    finally:
        log(f"[批次汇总] {summary()}")
        print(f"文字结果：{MD_PATH}\n处理日志：{LOG_PATH}\n"
              f"调试图片：{OUTPUT_DIR}（绿框=保留，红框=过滤）", flush=True)


if __name__ == "__main__":
    main()
