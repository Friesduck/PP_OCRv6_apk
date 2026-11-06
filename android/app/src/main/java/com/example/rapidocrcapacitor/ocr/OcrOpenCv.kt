package com.example.rapidocrcapacitor.ocr

import androidx.core.math.MathUtils
import com.benjaminwan.ocrlibrary.models.DetPoint
import com.benjaminwan.ocrlibrary.models.DetResult
import com.benjaminwan.ocrlibrary.models.ScaleParam
import de.lighti.clipper.Clipper
import de.lighti.clipper.ClipperOffset
import de.lighti.clipper.Path
import de.lighti.clipper.Paths
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

internal fun scaleParameters(source: Mat, targetSize: Int): ScaleParam {
    val sourceWidth = source.cols()
    val sourceHeight = source.rows()
    val scale = targetSize.toFloat() / max(sourceWidth, sourceHeight).toFloat()
    var destinationWidth = (sourceWidth * scale).toInt()
    var destinationHeight = (sourceHeight * scale).toInt()
    if (destinationWidth % 32 != 0) {
        destinationWidth = max((destinationWidth / 32 - 1) * 32, 32)
    }
    if (destinationHeight % 32 != 0) {
        destinationHeight = max((destinationHeight / 32 - 1) * 32, 32)
    }
    return ScaleParam(
        srcWidth = sourceWidth,
        srcHeight = sourceHeight,
        dstWidth = destinationWidth,
        dstHeight = destinationHeight,
        ratioWidth = destinationWidth.toFloat() / sourceWidth,
        ratioHeight = destinationHeight.toFloat() / sourceHeight,
    )
}

internal fun makePadding(source: Mat, padding: Int): Mat {
    if (padding <= 0) return source
    return Mat().also { padded ->
        try {
            Core.copyMakeBorder(
                source, padded, padding, padding, padding, padding,
                Core.BORDER_ISOLATED, Scalar(255.0, 255.0, 255.0),
            )
        } catch (error: Throwable) {
            padded.release()
            throw error
        }
    }
}

internal data class MinimumBox(val points: Array<Point>, val shortSide: Float)

internal fun minimumBox(rectangle: RotatedRect): MinimumBox {
    val points = Array(4) { Point() }
    rectangle.points(points)
    points.sortBy { it.x }
    val ordered = arrayOfNulls<Point>(4)
    if (points[1].y > points[0].y) {
        ordered[0] = points[0]
        ordered[3] = points[1]
    } else {
        ordered[0] = points[1]
        ordered[3] = points[0]
    }
    if (points[3].y > points[2].y) {
        ordered[1] = points[2]
        ordered[2] = points[3]
    } else {
        ordered[1] = points[3]
        ordered[2] = points[2]
    }
    @Suppress("UNCHECKED_CAST")
    return MinimumBox(ordered as Array<Point>, minOf(rectangle.size.width, rectangle.size.height).toFloat())
}

internal fun boxScore(points: Array<Point>, prediction: Mat): Float {
    val minX = MathUtils.clamp(
        floor(points.minOf { it.x }).toFloat(),
        0f,
        prediction.cols() - 1f,
    ).toInt()
    val maxX = MathUtils.clamp(
        ceil(points.maxOf { it.x }).toFloat(),
        0f,
        prediction.cols() - 1f,
    ).toInt()
    val minY = MathUtils.clamp(
        floor(points.minOf { it.y }).toFloat(),
        0f,
        prediction.rows() - 1f,
    ).toInt()
    val maxY = MathUtils.clamp(
        ceil(points.maxOf { it.y }).toFloat(),
        0f,
        prediction.rows() - 1f,
    ).toInt()
    val resources = mutableListOf<Mat>()
    return try {
        val mask = Mat.zeros(maxY - minY + 1, maxX - minX + 1, CvType.CV_8UC1).also(resources::add)
        val polygon = MatOfPoint(*points.map { Point(it.x - minX, it.y - minY) }.toTypedArray())
            .also(resources::add)
        val cropped = prediction.submat(Rect(minX, minY, maxX - minX + 1, maxY - minY + 1))
            .also(resources::add)
        Imgproc.fillPoly(mask, listOf(polygon), Scalar(1.0))
        Core.mean(cropped, mask).`val`[0].toFloat()
    } finally {
        resources.asReversed().forEach { it.release() }
    }
}

internal fun unClip(points: Array<Point>, ratio: Float): RotatedRect {
    val area = abs(points.indices.sumOf { index ->
        val next = points[(index + 1) % points.size]
        points[index].x * next.y - points[index].y * next.x
    } / 2.0)
    val perimeter = points.indices.sumOf { index ->
        val next = points[(index + 1) % points.size]
        sqrt((points[index].x - next.x).pow(2.0) + (points[index].y - next.y).pow(2.0))
    }
    if (perimeter <= 0.0) return RotatedRect(Point(), Size(1.0, 1.0), 0.0)
    val path = Path().apply {
        addAll(points.map { de.lighti.clipper.Point.LongPoint(it.x.toLong(), it.y.toLong()) })
    }
    val solution = Paths()
    ClipperOffset().apply {
        addPath(path, Clipper.JoinType.ROUND, Clipper.EndType.CLOSED_POLYGON)
        execute(solution, area * ratio / perimeter)
    }
    val expanded = solution.flatten().map { Point(it.x.toDouble(), it.y.toDouble()) }
    if (expanded.isEmpty()) return RotatedRect(Point(), Size(1.0, 1.0), 0.0)
    val expandedMat = MatOfPoint2f(*expanded.toTypedArray())
    return try {
        Imgproc.minAreaRect(expandedMat)
    } finally {
        expandedMat.release()
    }
}

internal fun cropTextRegions(source: Mat, detections: List<DetResult>): List<Mat> {
    val parts = ArrayList<Mat>(detections.size)
    try {
        detections.forEach { parts += cropTextRegion(source, it.points) }
        return parts
    } catch (error: Throwable) {
        parts.forEach { it.release() }
        throw error
    }
}

private fun cropTextRegion(source: Mat, box: List<DetPoint>): Mat {
    require(box.size == 4) { "文字框必须包含四个顶点" }
    val left = box.minOf { it.x }
    val right = box.maxOf { it.x }
    val top = box.minOf { it.y }
    val bottom = box.maxOf { it.y }
    val sourcePoints = box.map { Point((it.x - left).toDouble(), (it.y - top).toDouble()) }
    val width = sqrt(
        (sourcePoints[0].x - sourcePoints[1].x).pow(2.0) +
            (sourcePoints[0].y - sourcePoints[1].y).pow(2.0),
    ).coerceAtLeast(1.0)
    val height = sqrt(
        (sourcePoints[0].x - sourcePoints[3].x).pow(2.0) +
            (sourcePoints[0].y - sourcePoints[3].y).pow(2.0),
    ).coerceAtLeast(1.0)
    val destinationPoints = arrayOf(
        Point(0.0, 0.0),
        Point(width, 0.0),
        Point(width, height),
        Point(0.0, height),
    )
    val resources = mutableListOf<Mat>()
    var ownedPart: Mat? = null
    val part: Mat
    try {
        val cropped = source.submat(Rect(left, top, max(1, right - left), max(1, bottom - top)))
            .also(resources::add)
        val sourceMat = MatOfPoint2f(*sourcePoints.toTypedArray()).also(resources::add)
        val destinationMat = MatOfPoint2f(*destinationPoints).also(resources::add)
        val transform = Imgproc.getPerspectiveTransform(sourceMat, destinationMat).also(resources::add)
        part = Mat().also { ownedPart = it }
        Imgproc.warpPerspective(
            cropped,
            part,
            transform,
            Size(width, height),
            Imgproc.INTER_LINEAR,
            Core.BORDER_REPLICATE,
            Scalar(0.0),
        )
    } catch (error: Throwable) {
        ownedPart?.release()
        throw error
    } finally {
        resources.asReversed().forEach { it.release() }
    }

    if (part.rows() < part.cols() * 1.5) return part
    val rotated = Mat()
    return try {
        Core.transpose(part, rotated)
        Core.flip(rotated, rotated, 0)
        rotated
    } catch (error: Throwable) {
        rotated.release()
        throw error
    } finally {
        part.release()
    }
}

