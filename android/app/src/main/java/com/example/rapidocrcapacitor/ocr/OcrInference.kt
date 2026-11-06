package com.example.rapidocrcapacitor.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.res.AssetManager
import com.benjaminwan.ocrlibrary.models.DetPoint
import com.benjaminwan.ocrlibrary.models.DetResult
import com.benjaminwan.ocrlibrary.models.RecResult
import com.benjaminwan.ocrlibrary.models.ScaleParam
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections

internal class TextDetector(
    private val environment: OrtEnvironment,
    sessionFactory: () -> OrtSession,
) : Closeable {
    private val sessionDelegate = lazy(sessionFactory)
    private val session by sessionDelegate
    private val inputBuffer = ReusableFloatBuffer()

    fun detect(
        source: Mat,
        scale: ScaleParam,
        boxScoreThreshold: Float,
        boxThreshold: Float,
        unClipRatio: Float,
    ): List<DetResult> {
        val resized = Mat()
        val prediction = Mat()
        val binary = Mat()
        val threshold = Mat()
        val dilated = Mat()
        var kernel: Mat? = null

        try {
            val activeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
                .also { kernel = it }
            Imgproc.resize(source, resized, Size(scale.dstWidth.toDouble(), scale.dstHeight.toDouble()))
            val input = normalizeToNchw(resized, DET_MEAN, DET_NORM, inputBuffer)
            val shape = longArrayOf(1, 3, resized.rows().toLong(), resized.cols().toLong())
            val inputName = session.inputNames.first()

            OnnxTensor.createTensor(environment, input, shape).use { tensor ->
                session.run(Collections.singletonMap(inputName, tensor)).use { output ->
                    val outputTensor = output.first().value as OnnxTensor
                    val info = outputTensor.info as TensorInfo
                    val height = info.shape[2].toInt()
                    val width = info.shape[3].toInt()
                    val values = FloatArray(height * width)
                    outputTensor.floatBuffer.get(values)
                    val bytes = ByteArray(values.size) { index ->
                        (values[index] * 255f).toInt().coerceIn(0, 255).toByte()
                    }

                    prediction.create(height, width, CvType.CV_32F)
                    prediction.put(0, 0, values)
                    binary.create(height, width, CvType.CV_8UC1)
                    binary.put(0, 0, bytes)
                }
            }

            Imgproc.threshold(binary, threshold, boxThreshold * 255.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.dilate(threshold, dilated, activeKernel)
            return findBoxes(prediction, dilated, scale, boxScoreThreshold, unClipRatio)
        } finally {
            kernel?.release()
            dilated.release()
            threshold.release()
            binary.release()
            prediction.release()
            resized.release()
        }
    }

    private fun findBoxes(
        prediction: Mat,
        dilated: Mat,
        scale: ScaleParam,
        boxScoreThreshold: Float,
        unClipRatio: Float,
    ): List<DetResult> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(
                dilated,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
            val results = mutableListOf<DetResult>()
            contours.take(MAX_CANDIDATES).forEach { contour ->
                if (contour.total() <= 2L) return@forEach
                val contour2f = MatOfPoint2f(*contour.toArray())
                val rectangle = try {
                    Imgproc.minAreaRect(contour2f)
                } finally {
                    contour2f.release()
                }
                val minimumBox = minimumBox(rectangle)
                if (minimumBox.shortSide < MIN_BOX_SIDE) return@forEach
                val score = boxScore(minimumBox.points, prediction)
                if (score < boxScoreThreshold) return@forEach

                val expanded = unClip(minimumBox.points, unClipRatio)
                if (expanded.size.height < 1.001 && expanded.size.width < 1.001) return@forEach
                val expandedBox = minimumBox(expanded)
                if (expandedBox.shortSide < MIN_BOX_SIDE + 2) return@forEach

                val points = expandedBox.points.map { point ->
                    DetPoint(
                        (point.x / scale.ratioWidth).toInt().coerceIn(0, scale.srcWidth - 1),
                        (point.y / scale.ratioHeight).toInt().coerceIn(0, scale.srcHeight - 1),
                    )
                }
                results += DetResult(points, score)
            }
            return results.asReversed()
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
        }
    }

    override fun close() {
        try {
            if (sessionDelegate.isInitialized()) session.close()
        } finally {
            inputBuffer.clear()
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 1_000
        const val MIN_BOX_SIDE = 3f
        val DET_MEAN = floatArrayOf(0.485f * 255f, 0.456f * 255f, 0.406f * 255f)
        val DET_NORM = floatArrayOf(
            1f / 0.229f / 255f,
            1f / 0.224f / 255f,
            1f / 0.225f / 255f,
        )
    }
}

internal class TextRecognizer(
    private val environment: OrtEnvironment,
    assets: AssetManager,
    keysName: String,
    sessionFactory: () -> OrtSession,
) : Closeable {
    private val sessionDelegate = lazy(sessionFactory)
    private val session by sessionDelegate
    private val inputBuffer = ReusableFloatBuffer()
    private val keys = assets.open(keysName).bufferedReader().use { reader ->
        reader.readLines().toMutableList().apply {
            add(0, "#")
            add(" ")
        }
    }

    fun recognize(parts: List<Mat>): List<RecResult> = parts.map(::recognizeOne)

    private fun recognizeOne(source: Mat): RecResult {
        require(!source.empty()) { "文字区域为空" }
        val scale = TEXT_HEIGHT / source.rows()
        // 极细长框只限制整行缩放宽度，不截断文字；正常文字框沿用原比例。
        val width = (source.cols() * scale).coerceIn(16.0, MAX_REC_WIDTH)
        val resized = Mat()
        try {
            Imgproc.resize(source, resized, Size(width, TEXT_HEIGHT))
            val input = normalizeToNchw(resized, SIMPLE_MEAN, SIMPLE_NORM, inputBuffer)
            val shape = longArrayOf(1, 3, resized.rows().toLong(), resized.cols().toLong())
            OnnxTensor.createTensor(environment, input, shape).use { tensor ->
                session.run(Collections.singletonMap(session.inputNames.first(), tensor)).use { output ->
                    val outputTensor = output.first().value as OnnxTensor
                    val info = outputTensor.info as TensorInfo
                    return decode(outputTensor.floatBuffer, info.shape)
                }
            }
        } finally {
            resized.release()
        }
    }

    private fun decode(buffer: FloatBuffer, shape: LongArray): RecResult {
        require(shape.size == 3 && shape[0] == 1L && shape[1] > 0L) { "识别模型输出形状不匹配" }
        val steps = shape[1].toInt()
        val classes = shape[2].toInt()
        require(classes == keys.size && steps.toLong() * classes == buffer.remaining().toLong()) {
            "识别模型输出与字典不匹配"
        }
        val text = StringBuilder()
        val scores = mutableListOf<Float>()
        var previous = 0

        repeat(steps) {
            var bestIndex = 0
            var bestScore = Float.NEGATIVE_INFINITY
            repeat(classes) { classIndex ->
                val score = buffer.get()
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = classIndex
                }
            }
            if (bestIndex in 1 until keys.size && bestIndex != previous) {
                text.append(keys[bestIndex])
                scores += bestScore
            }
            previous = bestIndex
        }
        return RecResult(text.toString(), scores)
    }

    override fun close() {
        try {
            if (sessionDelegate.isInitialized()) session.close()
        } finally {
            inputBuffer.clear()
        }
    }
}

internal fun normalizeToNchw(
    source: Mat,
    means: FloatArray,
    normalizers: FloatArray,
    reusableBuffer: ReusableFloatBuffer,
): FloatBuffer {
    source.convertTo(source, CvType.CV_32FC3)
    val channels = source.channels()
    val imageSize = source.cols() * source.rows()
    val interleaved = FloatArray(imageSize * channels)
    val normalized = reusableBuffer.acquire(interleaved.size)
    source.get(0, 0, interleaved)

    repeat(imageSize) { pixel ->
        repeat(channels) { channel ->
            normalized.put(
                channel * imageSize + pixel,
                (interleaved[pixel * channels + channel] - means[channel]) * normalizers[channel],
            )
        }
    }
    normalized.rewind()
    return normalized
}

internal class ReusableFloatBuffer {
    private var storage: ByteBuffer? = null

    fun clear() {
        storage = null
    }

    fun acquire(elementCount: Int): FloatBuffer {
        val requiredBytes = Math.multiplyExact(elementCount, Float.SIZE_BYTES)
        val bytes = storage
            ?.takeIf { it.capacity() >= requiredBytes }
            ?: ByteBuffer.allocateDirect(requiredBytes)
                .order(ByteOrder.nativeOrder())
                .also { storage = it }
        bytes.clear()
        return bytes.asFloatBuffer().apply { limit(elementCount) }
    }
}

// 采用官方 small_rec 推理配置中提供的最大优化宽度作为应用内存保护上限。
private const val MAX_REC_WIDTH = 3200.0
private const val TEXT_HEIGHT = 48.0
private val SIMPLE_MEAN = floatArrayOf(127.5f, 127.5f, 127.5f)
private val SIMPLE_NORM = floatArrayOf(1f / 127.5f, 1f / 127.5f, 1f / 127.5f)
