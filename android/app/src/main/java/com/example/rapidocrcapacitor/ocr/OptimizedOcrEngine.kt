package com.example.rapidocrcapacitor.ocr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.benjaminwan.ocrlibrary.models.DetResult
import com.benjaminwan.ocrlibrary.models.RecResult
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

data class OptimizedOcrResult(
    val detResults: List<DetResult>,
    val recResults: List<RecResult>,
    val fullTimeMs: Long,
    val detectorWidth: Int,
    val detectorHeight: Int,
)

class OptimizedOcrEngine(context: Context) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val sessions = ModelSessionFactory(context.applicationContext, environment)
    private val detector = TextDetector(environment) { sessions.create(DET_MODEL) }
    private val recognizer = TextRecognizer(environment, context.assets, KEYS_FILE) {
        sessions.create(REC_MODEL)
    }

    fun detect(
        bitmap: Bitmap,
        scaleUp: Boolean,
        maxSideLen: Int,
        padding: Int,
        boxScoreThreshold: Float,
        boxThreshold: Float,
        unClipRatio: Float,
    ): OptimizedOcrResult {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val resources = mutableListOf<Mat>()
        var paddedInput: Mat? = null

        try {
            val inputRgba = Mat(bitmap.height, bitmap.width, CvType.CV_8UC4).also(resources::add)
            val inputBgr = Mat().also(resources::add)
            Utils.bitmapToMat(bitmap, inputRgba)
            org.opencv.imgproc.Imgproc.cvtColor(
                inputRgba,
                inputBgr,
                org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR,
            )

            val sourceMaxSide = maxOf(inputBgr.cols(), inputBgr.rows())
            val scaledSide = when {
                maxSideLen <= 0 -> sourceMaxSide
                scaleUp -> maxSideLen
                sourceMaxSide > maxSideLen -> maxSideLen
                else -> sourceMaxSide
            } + 2 * padding

            val source = makePadding(inputBgr, padding).also {
                if (it !== inputBgr) paddedInput = it
            }
            val scale = scaleParameters(source, scaledSide)
            val detections = detector.detect(
                source,
                scale,
                boxScoreThreshold,
                boxThreshold,
                unClipRatio,
            )
            val parts = cropTextRegions(source, detections)

            try {
                return OptimizedOcrResult(
                    detResults = detections,
                    recResults = recognizer.recognize(parts),
                    fullTimeMs = elapsedMs(startedAt),
                    detectorWidth = scale.dstWidth,
                    detectorHeight = scale.dstHeight,
                )
            } finally {
                parts.forEach { it.release() }
            }
        } finally {
            paddedInput?.release()
            resources.asReversed().forEach { it.release() }
        }
    }

    override fun close() {
        runCatching { recognizer.close() }
        runCatching { detector.close() }
        runCatching { environment.close() }
    }

    private fun elapsedMs(startedAt: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L

    private companion object {
        const val DET_MODEL = "PP-OCRv6_small_det.onnx"
        const val REC_MODEL = "PP-OCRv6_small_rec.onnx"
        const val KEYS_FILE = "ppocrv6_dict.txt"
    }
}

private class ModelSessionFactory(
    context: Context,
    private val environment: OrtEnvironment,
) {
    private val assets = context.assets
    private val modelDirectory = File(context.noBackupFilesDir, "ocr-models")
    private val verifiedModels = mutableSetOf<String>()

    init {
        // 仅清理已停用模型的私有缓存，不接触用户图片。
        listOf(
            "ch_PP-OCRv3_det_infer.onnx",
            "ch_PP-OCRv3_rec_infer.onnx",
            "ch_ppocr_mobile_v2.0_cls_infer.onnx",
        ).forEach { name ->
            File(modelDirectory, name).delete()
            File(modelDirectory, "$name.tmp").delete()
        }
    }

    fun create(modelName: String): OrtSession {
        val modelFile = ensureModelFile(modelName)
        val options = OrtSession.SessionOptions().apply {
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }
        return try {
            environment.createSession(modelFile.absolutePath, options)
        } finally {
            options.close()
        }
    }

    private fun ensureModelFile(modelName: String): File {
        check(modelDirectory.exists() || modelDirectory.mkdirs()) {
            "无法创建 OCR 模型目录"
        }
        val target = File(modelDirectory, modelName)
        val expectedHash = checkNotNull(MODEL_HASHES[modelName]) { "未知 OCR 模型：$modelName" }
        if (target.isFile && (modelName in verifiedModels || sha256(target) == expectedHash)) {
            verifiedModels += modelName
            return target
        }

        val temporary = File(modelDirectory, "$modelName.tmp")
        try {
            assets.open(modelName).use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(sha256(temporary) == expectedHash) { "OCR 模型校验失败：$modelName" }
            // 同目录重命名保持原子替换，不回退到可能留下半个文件的复制。
            check(temporary.renameTo(target)) { "无法准备 OCR 模型：$modelName" }
            verifiedModels += modelName
        } finally {
            temporary.delete()
        }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        val MODEL_HASHES = mapOf(
            "PP-OCRv6_small_det.onnx" to "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
            "PP-OCRv6_small_rec.onnx" to "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
        )
    }
}
