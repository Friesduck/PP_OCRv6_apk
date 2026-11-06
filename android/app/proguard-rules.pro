-keep class ai.onnxruntime.** { *; }
# 旧 OCR 引擎不再整包保留；直接使用的数据类和 Clipper 由 R8 可达性分析保留。
-keep class com.example.rapidocrcapacitor.OcrPlugin { *; }

# The upstream OcrLibrary AAR references this compile-time annotation only.
-dontwarn kotlinx.parcelize.Parcelize
