package com.example.rapidocrcapacitor

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.ActivityResult
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.example.rapidocrcapacitor.ocr.OptimizedOcrEngine
import com.example.rapidocrcapacitor.ocr.OptimizedOcrResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@CapacitorPlugin(name = "NativeOcr")
class OcrPlugin : Plugin() {
    private val executor = Executors.newSingleThreadExecutor()
    private val recognitionInProgress = AtomicBoolean(false)
    private val selectionInProgress = AtomicBoolean(false)
    private val stateLock = Any()

    @Volatile
    private var destroyed = false
    private val diagnosticRecognitionSequence = AtomicInteger(0)

    @Volatile
    private var selectedImageUris: List<Uri> = emptyList()

    @Volatile
    private var ocrEngine: OptimizedOcrEngine? = null

    private var fullScreenDialog: Dialog? = null

    @Volatile
    private var modelIsWarm = false

    @Volatile
    private var diagnosticsEnabled = false

    @Volatile
    private var lastResourcesReleasedAtNs = 0L

    @PluginMethod
    fun pickImage(call: PluginCall) {
        synchronized(stateLock) {
            if (destroyed || recognitionInProgress.get() || !selectionInProgress.compareAndSet(false, true)) {
                call.reject("当前页面已关闭或图片任务正在处理，请稍后重试")
                return
            }
        }

        if (!hasFullGalleryAccess()) {
            selectionInProgress.set(false)
            call.reject("尚未获得全部照片权限，请在系统设置中将照片权限改为允许所有照片")
            return
        }

        openSystemGallery(call)
    }

    private fun openSystemGallery(call: PluginCall) {
        val intent = Intent(Intent.ACTION_PICK).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            putExtra(Intent.EXTRA_MIME_TYPES, SUPPORTED_MIME_TYPES.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        runCatching {
            startActivityForResult(call, intent, "handleImagePicked")
        }.onFailure { error ->
            selectionInProgress.set(false)
            reject(call, error, "无法打开系统图库")
        }
    }

    private fun requiredGalleryPermission(): String = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    ) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun hasFullGalleryAccess(): Boolean {
        val pluginContext = context ?: return false
        return ContextCompat.checkSelfPermission(
            pluginContext,
            requiredGalleryPermission(),
        ) == PackageManager.PERMISSION_GRANTED
    }

    @ActivityCallback
    private fun handleImagePicked(call: PluginCall?, result: ActivityResult) {
        if (call == null) {
            selectionInProgress.set(false)
            return
        }
        if (result.resultCode != Activity.RESULT_OK) {
            selectionInProgress.set(false)
            call.resolve(JSObject().put("cancelled", true))
            return
        }

        val uris = try {
            selectedUris(result.data)
        } catch (error: Throwable) {
            selectionInProgress.set(false)
            reject(call, error, "无法读取图库选择结果")
            return
        }
        if (uris.isEmpty()) {
            selectionInProgress.set(false)
            call.reject("系统图片选择器没有返回有效图片")
            return
        }
        if (uris.size > MAX_SELECTED_IMAGES) {
            selectionInProgress.set(false)
            call.reject("一次最多选择 $MAX_SELECTED_IMAGES 张图片")
            return
        }

        submitWork(call, onFinished = { selectionInProgress.set(false) }) {
            var imageBytes: ByteArray? = null
            try {
                val previews = JSArray()
                var totalSizeBytes = 0L
                uris.forEachIndexed { index, uri ->
                    val info = queryImageInfo(uri)
                    info.sizeBytes?.let { size ->
                        require(size <= MAX_IMAGE_BYTES) { "第 ${index + 1} 张图片不能超过 25 MB" }
                    }
                    info.mimeType?.let { mimeType ->
                        require(mimeType in SUPPORTED_MIME_TYPES) { "第 ${index + 1} 张图片格式不受支持" }
                    }

                    val bytes = readImageBytes(uri)
                    imageBytes = bytes
                    val preview = createPreview(bytes)
                    totalSizeBytes += info.sizeBytes ?: bytes.size.toLong()
                    previews.put(
                        JSObject().apply {
                            put("index", index)
                            put("previewDataUrl", preview.dataUrl)
                        },
                    )
                    bytes.fill(0)
                    imageBytes = null
                }
                synchronized(stateLock) {
                    check(!destroyed) { "当前页面已关闭" }
                    selectedImageUris = uris
                }

                val response = JSObject().apply {
                    put("cancelled", false)
                    put("images", previews)
                    put("totalSizeBytes", totalSizeBytes)
                }
                call.resolve(response)
            } catch (error: OutOfMemoryError) {
                reject(call, error, "图片预览时内存不足，请减少选择数量或图片分辨率")
            } catch (error: Throwable) {
                reject(call, error, "读取图片失败")
            } finally {
                imageBytes?.fill(0)
            }
        }
    }

    @PluginMethod
    fun clearSelection(call: PluginCall) {
        synchronized(stateLock) {
            if (destroyed || recognitionInProgress.get() || selectionInProgress.get()) {
                call.reject("图片任务正在处理或页面已关闭，暂时不能移除图片")
                return
            }
            selectedImageUris = emptyList()
        }
        call.resolve()
    }

    @PluginMethod
    fun showSelectedImage(call: PluginCall) {
        if (destroyed || recognitionInProgress.get() || selectionInProgress.get()) {
            call.reject("当前正在识别，请稍后查看原图")
            return
        }
        val uri = selectedImageUris.getOrNull(call.getInt("index") ?: -1)
        if (uri == null) {
            call.reject("所选图片已经失效")
            return
        }

        submitWork(call) {
            var bytes: ByteArray? = null
            var bitmap: Bitmap? = null
            try {
                bytes = readImageBytes(uri)
                val decoded = decodeSampledBitmap(bytes, MAX_FULLSCREEN_SIDE).bitmap
                bitmap = try {
                    applyExifOrientation(decoded, bytes)
                } catch (error: Throwable) {
                    decoded.recycleSafely()
                    throw error
                }
                val displayBitmap = checkNotNull(bitmap)
                val hostActivity = checkNotNull(activity) { "当前页面已经关闭" }
                hostActivity.runOnUiThread {
                    if (destroyed || hostActivity.isFinishing || hostActivity.isDestroyed) {
                        displayBitmap.recycleSafely()
                        call.reject("当前页面已经关闭")
                        return@runOnUiThread
                    }
                    runCatching { showFullScreenBitmap(displayBitmap) }
                        .onSuccess { call.resolve() }
                        .onFailure { error ->
                            displayBitmap.recycleSafely()
                            reject(call, error, "无法打开原图")
                        }
                }
                bitmap = null
            } catch (error: Throwable) {
                bitmap.recycleSafely()
                reject(call, error, "无法打开原图")
            } finally {
                bytes?.fill(0)
            }
        }
    }

    @PluginMethod
    fun setDiagnosticsEnabled(call: PluginCall) {
        diagnosticsEnabled = call.getBoolean("enabled", false) ?: false
        call.resolve()
    }

    @PluginMethod
    fun captureMemoryDiagnostics(call: PluginCall) {
        val stage = call.getString("stage")
            ?.takeIf { it.matches(DIAGNOSTIC_STAGE_PATTERN) }
            ?: "manual"
        val expectedRecognitionId = call.getInt("recognitionId")

        submitWork(call) {
            val currentRecognitionId = diagnosticRecognitionSequence.get()
            val skipReason = when {
                !diagnosticsEnabled -> "diagnosticsDisabled"
                recognitionInProgress.get() -> "recognitionInProgress"
                expectedRecognitionId != null && expectedRecognitionId != currentRecognitionId -> {
                    "newerRecognitionStarted"
                }
                else -> null
            }

            if (skipReason != null) {
                call.resolve(
                    JSObject().apply {
                        put("skipped", true)
                        put("reason", skipReason)
                        put("recognitionId", currentRecognitionId)
                    },
                )
                return@submitWork
            }

            val releasedAtNs = lastResourcesReleasedAtNs
            val sinceReleaseMs = if (releasedAtNs > 0L) elapsedMs(releasedAtNs) else -1L
            call.resolve(
                JSObject().apply {
                    put("skipped", false)
                    put("recognitionId", currentRecognitionId)
                    put("modelIsWarm", modelIsWarm)
                    put("sinceReleaseMs", sinceReleaseMs)
                    put("environment", MemoryDiagnostics.environment())
                    put(
                        "snapshot",
                        MemoryDiagnostics.capture(
                            context.applicationContext,
                            stage,
                            sinceReleaseMs,
                            true,
                        ),
                    )
                },
            )
        }
    }

    @PluginMethod
    fun recognizeSelected(call: PluginCall) {
        val imageUris: List<Uri>
        synchronized(stateLock) {
            if (destroyed || selectionInProgress.get()) {
                call.reject("图片正在准备或页面已关闭，请稍后重试")
                return
            }
            imageUris = selectedImageUris.toList()
            if (!recognitionInProgress.compareAndSet(false, true)) {
                call.reject("已有识别任务正在运行")
                return
            }
        }
        if (imageUris.isEmpty()) {
            recognitionInProgress.set(false)
            call.reject("请先选择图片")
            return
        }
        if (imageUris.size > MAX_SELECTED_IMAGES) {
            recognitionInProgress.set(false)
            call.reject("一次最多识别 $MAX_SELECTED_IMAGES 张图片")
            return
        }
        val recognitionId = diagnosticRecognitionSequence.incrementAndGet()
        val clipboardPhone = readClipboardPhone()

        submitWork(call, onFinished = { recognitionInProgress.set(false) }) {
            val requestStartedAtNs = SystemClock.elapsedRealtimeNanos()
            val startedAtEpochMs = System.currentTimeMillis()
            val memorySnapshots = JSArray()
            val collectDiagnostics = diagnosticsEnabled
            var imageBytes: ByteArray? = null
            var bitmap: Bitmap? = null

            try {
                captureMemorySnapshot(
                    memorySnapshots,
                    "requestReceived",
                    requestStartedAtNs,
                    collectDiagnostics,
                    true,
                )

                val modelWasLoaded = modelIsWarm
                val engineInitStartedAtNs = SystemClock.elapsedRealtimeNanos()
                val engine = getEngine()
                val detectorMaxSide = detectorSettings().getInt(SETTING_DETECTOR_SIDE, OCR_MAX_SIDE_LEN)
                val engineInitMs = elapsedMs(engineInitStartedAtNs)
                var sourceImageBytes = 0L
                var sourceWidth = 0
                var sourceHeight = 0
                var decodeSampleSize = 1
                var decodedWidth = 0
                var decodedHeight = 0
                var decodedBitmapBytes = 0L
                var orientedWidth = 0
                var orientedHeight = 0
                var orientedBitmapBytes = 0L
                var detectionCount = 0
                var recognitionCount = 0
                var imageReadMs = 0L
                var imageDecodeMs = 0L
                var orientationMs = 0L
                var inferenceWallMs = 0L
                var postprocessMs = 0L
                var ocrElapsedMs = 0L
                val detectorInputSizes = JSArray()
                val textGroups = mutableListOf<List<String>>()
                val idNumbers = linkedSetOf<String>()
                val validityPeriods = linkedSetOf<String>()

                imageUris.forEachIndexed { index, uri ->
                    check(!destroyed) { "当前页面已关闭，停止识别后续图片" }
                    try {
                        val imageReadStartedAtNs = SystemClock.elapsedRealtimeNanos()
                        val bytes = readImageBytes(uri)
                        imageBytes = bytes
                        imageReadMs += elapsedMs(imageReadStartedAtNs)
                        sourceImageBytes += bytes.size.toLong()
                        if (index == 0) {
                            captureMemorySnapshot(
                                memorySnapshots,
                                "imageBytesRead",
                                requestStartedAtNs,
                                collectDiagnostics,
                            )
                        }

                        val decodeStartedAtNs = SystemClock.elapsedRealtimeNanos()
                        val decoded = decodeSampledBitmap(bytes, MAX_DECODE_SIDE)
                        val decodedBitmap = decoded.bitmap
                        imageDecodeMs += elapsedMs(decodeStartedAtNs)
                        sourceWidth = maxOf(sourceWidth, decoded.sourceWidth)
                        sourceHeight = maxOf(sourceHeight, decoded.sourceHeight)
                        decodeSampleSize = maxOf(decodeSampleSize, decoded.sampleSize)
                        decodedWidth = maxOf(decodedWidth, decodedBitmap.width)
                        decodedHeight = maxOf(decodedHeight, decodedBitmap.height)
                        decodedBitmapBytes += decodedBitmap.allocationByteCount.toLong()

                        val orientationStartedAtNs = SystemClock.elapsedRealtimeNanos()
                        val orientedBitmap = try {
                            applyExifOrientation(decodedBitmap, bytes)
                        } catch (error: Throwable) {
                            decodedBitmap.recycleSafely()
                            throw error
                        }
                        bitmap = orientedBitmap
                        orientedWidth = maxOf(orientedWidth, orientedBitmap.width)
                        orientedHeight = maxOf(orientedHeight, orientedBitmap.height)
                        orientedBitmapBytes += orientedBitmap.allocationByteCount.toLong()
                        orientationMs += elapsedMs(orientationStartedAtNs)
                        if (index == 0) {
                            captureMemorySnapshot(
                                memorySnapshots,
                                "imageReady",
                                requestStartedAtNs,
                                collectDiagnostics,
                            )
                            captureMemorySnapshot(
                                memorySnapshots,
                                "engineReady",
                                requestStartedAtNs,
                                collectDiagnostics,
                                true,
                            )
                        }

                        val inferenceStartedAtNs = SystemClock.elapsedRealtimeNanos()
                        val nativeResult = engine.detect(
                            orientedBitmap,
                            false,
                            detectorMaxSide,
                            50,
                            0.5f,
                            0.3f,
                            2.0f,
                        )
                        detectorInputSizes.put(JSObject().apply {
                            put("width", nativeResult.detectorWidth)
                            put("height", nativeResult.detectorHeight)
                        })
                        modelIsWarm = true
                        inferenceWallMs += elapsedMs(inferenceStartedAtNs)
                        ocrElapsedMs += nativeResult.fullTimeMs

                        val postprocessStartedAtNs = SystemClock.elapsedRealtimeNanos()
                        val lines = composeTextLines(nativeResult)
                        textGroups += lines
                        idNumbers += IdCardParser.extract(lines)
                        validityPeriods += ValidityPeriodParser.extract(lines)
                        detectionCount += nativeResult.detResults.size
                        recognitionCount += nativeResult.recResults.size
                        postprocessMs += elapsedMs(postprocessStartedAtNs)
                        if (index == imageUris.lastIndex) {
                            captureMemorySnapshot(
                                memorySnapshots,
                                "inferenceComplete",
                                requestStartedAtNs,
                                collectDiagnostics,
                                true,
                            )
                        }
                    } finally {
                        bitmap.recycleSafely()
                        bitmap = null
                        imageBytes?.fill(0)
                        imageBytes = null
                    }
                }
                lastResourcesReleasedAtNs = SystemClock.elapsedRealtimeNanos()
                captureMemorySnapshot(
                    memorySnapshots,
                    "resourcesReleased",
                    requestStartedAtNs,
                    collectDiagnostics,
                    true,
                )

                val metrics = JSObject().apply {
                    put("recognitionId", recognitionId)
                    put("startedAtEpochMs", startedAtEpochMs)
                    put("modelWasLoaded", modelWasLoaded)
                    put("imageCount", imageUris.size)
                    put("sourceImageBytes", sourceImageBytes)
                    put("sourceWidth", sourceWidth)
                    put("sourceHeight", sourceHeight)
                    put("decodeSampleSize", decodeSampleSize)
                    put("decodedWidth", decodedWidth)
                    put("decodedHeight", decodedHeight)
                    put("decodedBitmapBytes", decodedBitmapBytes)
                    put("orientedWidth", orientedWidth)
                    put("orientedHeight", orientedHeight)
                    put("orientedBitmapBytes", orientedBitmapBytes)
                    put("resultBitmapBytes", 0L)
                    put("detectionCount", detectionCount)
                    put("recognitionCount", recognitionCount)
                    put("ocrMaxSideLen", detectorMaxSide)
                    put("detectorInputSizes", detectorInputSizes)
                    put("imageReadMs", imageReadMs)
                    put("imageDecodeMs", imageDecodeMs)
                    put("orientationMs", orientationMs)
                    put("engineInitMs", engineInitMs)
                    put("inferenceWallMs", inferenceWallMs)
                    put("postprocessMs", postprocessMs)
                    put("nativeTotalMs", elapsedMs(requestStartedAtNs))
                    put("memorySnapshots", memorySnapshots)
                }
                val textGroupArray = JSArray().apply {
                    textGroups.forEach { put(JSArray(it)) }
                }
                val response = JSObject().apply {
                    put("idNumbers", JSArray(idNumbers.toList()))
                    put("validityPeriods", JSArray(validityPeriods.toList()))
                    put("textLines", JSArray(textGroups.flatten()))
                    put("textGroups", textGroupArray)
                    clipboardPhone?.let { put("clipboardPhone", it) }
                    put("elapsedMs", ocrElapsedMs)
                    put("metrics", metrics)
                }
                call.resolve(response)
            } catch (error: OutOfMemoryError) {
                reject(call, error, "图片处理时内存不足，请选择分辨率更低的图片")
            } catch (error: LinkageError) {
                reject(call, error, "OCR 原生库加载失败")
            } catch (error: Throwable) {
                reject(call, error, "OCR 识别失败")
            } finally {
                bitmap.recycleSafely()
                imageBytes?.fill(0)
            }
        }
    }

    @PluginMethod
    fun copyText(call: PluginCall) {
        val value = call.getString("value")?.takeIf { it.isNotEmpty() }
        if (value == null) {
            call.reject("没有可复制的文字")
            return
        }
        if (value.length > MAX_CLIPBOARD_CHARS) {
            call.reject("复制内容过长")
            return
        }

        val hostActivity = activity
        if (destroyed || hostActivity == null || hostActivity.isFinishing || hostActivity.isDestroyed) {
            call.reject("当前页面已经关闭")
            return
        }
        hostActivity.runOnUiThread {
            runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OCR 识别结果", value))
            }.onSuccess {
                call.resolve()
            }.onFailure { error ->
                reject(call, error, "复制失败")
            }
        }
    }

    override fun handleOnDestroy() {
        synchronized(stateLock) {
            if (destroyed) return
            destroyed = true
            selectedImageUris = emptyList()
        }
        fullScreenDialog?.dismiss()
        fullScreenDialog = null
        modelIsWarm = false
        diagnosticsEnabled = false
        lastResourcesReleasedAtNs = 0L
        // 先让在途推理退出，再在同一线程读取并关闭最终引擎实例。
        executor.execute {
            val engine = ocrEngine
            ocrEngine = null
            runCatching { engine?.close() }
        }
        executor.shutdown()
        super.handleOnDestroy()
    }

    private fun getEngine(): OptimizedOcrEngine {
        check(!destroyed) { "当前页面已经关闭" }
        ocrEngine?.let { return it }
        return synchronized(this) {
            ocrEngine ?: run {
                loadOpenCv()
                OptimizedOcrEngine(context.applicationContext).also { ocrEngine = it }
            }
        }
    }

    private fun loadOpenCv() {
        try {
            System.loadLibrary(OPENCV_LIBRARY_NAME)
        } catch (error: UnsatisfiedLinkError) {
            val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }
                .getOrNull()
                ?.toString()
                ?: "unknown"
            val message = buildString {
                append("OpenCV 原生库加载失败")
                append("；ABI=")
                append(Build.SUPPORTED_ABIS.joinToString())
                append("；pageSize=")
                append(pageSize)
                append("；nativeLibraryDir=")
                append(context.applicationInfo.nativeLibraryDir)
                error.message?.let {
                    append("；原因=")
                    append(it)
                }
            }
            Log.e(TAG, message, error)
            throw IllegalStateException(message, error)
        }
    }

    private fun queryImageInfo(uri: Uri): ImageInfo {
        var sizeBytes: Long? = null
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                }
            }
        }
        return ImageInfo(sizeBytes, context.contentResolver.getType(uri))
    }

    private fun readImageBytes(uri: Uri): ByteArray {
        val output = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_IMAGE_BYTES) { "图片不能超过 25 MB" }
                output.write(buffer, 0, count)
            }
        } ?: error("无法打开所选图片")

        return output.toByteArray().also {
            require(it.isNotEmpty()) { "图片内容为空" }
        }
    }

    private fun createPreview(imageBytes: ByteArray): PreviewResult {
        val decodedBitmap = decodeSampledBitmap(imageBytes, MAX_PREVIEW_SIDE).bitmap
        val orientedBitmap = try {
            applyExifOrientation(decodedBitmap, imageBytes)
        } catch (error: Throwable) {
            decodedBitmap.recycleSafely()
            throw error
        }

        return try {
            val output = ByteArrayOutputStream()
            require(orientedBitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, output)) {
                "无法生成图片预览"
            }
            val previewBytes = output.toByteArray()
            try {
                PreviewResult(
                    dataUrl = "data:image/jpeg;base64," +
                        Base64.encodeToString(previewBytes, Base64.NO_WRAP),
                )
            } finally {
                previewBytes.fill(0)
            }
        } finally {
            orientedBitmap.recycleSafely()
        }
    }

    private fun decodeSampledBitmap(imageBytes: ByteArray, maxSide: Int): DecodedBitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)

        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "无法读取图片尺寸，请选择有效图片"
        }
        require(bounds.outMimeType in SUPPORTED_MIME_TYPES) {
            "不支持的图片格式：${bounds.outMimeType ?: "unknown"}"
        }
        require(bounds.outWidth <= MAX_SOURCE_SIDE && bounds.outHeight <= MAX_SOURCE_SIDE) {
            "图片尺寸异常：${bounds.outWidth} x ${bounds.outHeight}"
        }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxSide || bounds.outHeight / sampleSize > maxSide) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
            ?: error("无法解码图片，请选择有效图片")
        return DecodedBitmap(
            bitmap = bitmap,
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
            sampleSize = sampleSize,
        )
    }

    private fun applyExifOrientation(bitmap: Bitmap, imageBytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ByteArrayInputStream(imageBytes).use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrElse {
            Log.w(TAG, "无法读取图片 EXIF 方向，将按原方向识别", it)
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }

        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        ).also { oriented ->
            if (oriented !== bitmap) bitmap.recycleSafely()
        }
    }

    private fun cleanTextLines(lines: List<String>): List<String> {
        val cleaned = mutableListOf<String>()
        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isNotEmpty()) cleaned += line
        }
        return cleaned
    }

    private fun composeTextLines(result: OptimizedOcrResult): List<String> {
        val positionedText = mutableListOf<PositionedText>()

        result.recResults.forEachIndexed { index, recognition ->
            val text = recognition.text.trim()
            if (text.isEmpty()) return@forEachIndexed

            val points = result.detResults.getOrNull(index)?.points.orEmpty()
            if (points.isEmpty()) {
                return cleanTextLines(result.recResults.map { it.text })
            }

            positionedText += PositionedText(
                text = text,
                left = points.minOf { it.x },
                top = points.minOf { it.y },
                bottom = points.maxOf { it.y },
            )
        }

        return cleanTextLines(TextLineComposer.compose(positionedText))
    }

    private fun captureMemorySnapshot(
        target: JSArray,
        stage: String,
        requestStartedAtNs: Long,
        enabled: Boolean,
        deep: Boolean = false,
    ) {
        if (enabled) {
            target.put(
                MemoryDiagnostics.capture(
                    context.applicationContext,
                    stage,
                    elapsedMs(requestStartedAtNs),
                    deep,
                ),
            )
        }
    }

    private fun elapsedMs(startedAtNs: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNs) / 1_000_000L

    // 所有后台入口统一处理销毁、提交失败和未预期异常，避免遗留未结束的 Bridge 调用。
    private fun submitWork(call: PluginCall, onFinished: () -> Unit = {}, work: () -> Unit) {
        try {
            executor.execute {
                try {
                    check(!destroyed) { "当前页面已经关闭" }
                    work()
                } catch (error: Throwable) {
                    reject(call, error, "后台处理失败")
                } finally {
                    onFinished()
                }
            }
        } catch (error: RejectedExecutionException) {
            onFinished()
            reject(call, error, "当前页面已经关闭")
        }
    }

    private fun detectorSettings() = context.applicationContext.getSharedPreferences(
        "ocr-settings", Context.MODE_PRIVATE,
    )

    private fun detectorSettingsResponse() = JSObject().apply {
        put("detectorMaxSide", detectorSettings().getInt(SETTING_DETECTOR_SIDE, OCR_MAX_SIDE_LEN))
        put("defaultDetectorMaxSide", OCR_MAX_SIDE_LEN)
    }

    @PluginMethod
    fun getOcrSettings(call: PluginCall) {
        submitWork(call) { call.resolve(detectorSettingsResponse()) }
    }

    @PluginMethod
    fun setOcrSettings(call: PluginCall) {
        val side = call.getInt("detectorMaxSide")
        if (side == null || side !in MIN_DETECTOR_SIDE..MAX_DETECTOR_SIDE || side % 32 != 0) {
            call.reject("检测长边必须为 320～2400 之间的 32 倍数")
            return
        }
        submitWork(call) {
            check(detectorSettings().edit().putInt(SETTING_DETECTOR_SIDE, side).commit()) {
                "保存 OCR 参数失败"
            }
            call.resolve(detectorSettingsResponse())
        }
    }

    private fun reject(call: PluginCall, error: Throwable, contextMessage: String) {
        Log.e(TAG, contextMessage, error)
        call.reject("$contextMessage：${describeError(error)}")
    }

    private fun describeError(error: Throwable): String = generateSequence(error) { it.cause }
        .take(4)
        .map { cause ->
            val detail = cause.message?.trim().orEmpty()
            if (detail.isEmpty()) cause.javaClass.simpleName
            else "${cause.javaClass.simpleName}: $detail"
        }
        .distinct()
        .joinToString("；")

    private fun Bitmap?.recycleSafely() {
        if (this != null && !isRecycled) recycle()
    }

    private fun selectedUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val uris = mutableListOf<Uri>()
        // ClipData 的返回顺序是预览编号、OCR 遍历和结果分组共用的唯一顺序。
        intent.clipData?.let { clipData ->
            repeat(clipData.itemCount) { index ->
                clipData.getItemAt(index).uri?.let(uris::add)
            }
        }
        if (uris.isEmpty()) intent.data?.let(uris::add)
        return uris.distinctBy(Uri::toString)
    }

    private fun readClipboardPhone(): String? = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            .orEmpty()
        ClipboardPhoneParser.extract(text)
    }.getOrNull()

    private fun showFullScreenBitmap(bitmap: Bitmap) {
        fullScreenDialog?.dismiss()
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val image = ImageView(activity).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
            alpha = 0f
            scaleX = 0.985f
            scaleY = 0.985f
            contentDescription = "点击关闭原图"
        }
        fun close() {
            image.animate()
                .alpha(0f)
                .scaleX(0.985f)
                .scaleY(0.985f)
                .setDuration(160L)
                .withEndAction { if (dialog.isShowing) dialog.dismiss() }
                .start()
        }
        image.setOnClickListener { close() }
        dialog.setContentView(
            image,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        dialog.setOnDismissListener {
            image.setImageDrawable(null)
            bitmap.recycleSafely()
            if (fullScreenDialog === dialog) fullScreenDialog = null
        }
        dialog.setOnShowListener {
            @Suppress("DEPRECATION")
            dialog.window?.decorView?.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            image.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200L).start()
        }
        dialog.show()
        fullScreenDialog = dialog
    }

    private data class ImageInfo(
        val sizeBytes: Long?,
        val mimeType: String?,
    )

    private data class PreviewResult(
        val dataUrl: String,
    )

    private data class DecodedBitmap(
        val bitmap: Bitmap,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val sampleSize: Int,
    )

    companion object {
        private const val TAG = "NativeOcr"
        private const val OPENCV_LIBRARY_NAME = "opencv_java4"
        private const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
        private const val MAX_SELECTED_IMAGES = 4
        private const val MAX_CLIPBOARD_CHARS = 500_000
        private const val MAX_SOURCE_SIDE = 50_000
        private const val MAX_DECODE_SIDE = 2_400
        private const val OCR_MAX_SIDE_LEN = 1_600
        private const val MIN_DETECTOR_SIDE = 320
        private const val MAX_DETECTOR_SIDE = 2_400
        private const val SETTING_DETECTOR_SIDE = "detector-max-side"
        private const val MAX_PREVIEW_SIDE = 240
        private const val MAX_FULLSCREEN_SIDE = 4_096
        private const val PREVIEW_JPEG_QUALITY = 72
        private const val STREAM_BUFFER_SIZE = 16 * 1024
        private val DIAGNOSTIC_STAGE_PATTERN = Regex("[A-Za-z0-9_-]{1,40}")
        private val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}

internal object ClipboardPhoneParser {
    private val phonePattern = Regex(
        "(?<!\\d)(?:\\+?86[\\s-]?)?(1[3-9](?:[\\s-]?\\d){9})(?!\\d)",
    )

    fun extract(text: String): String? = phonePattern.find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.filter(Char::isDigit)
        ?.takeIf { it.length == 11 }
}

internal data class PositionedText(
    val text: String,
    val left: Int,
    val top: Int,
    val bottom: Int,
) {
    val centerY: Double get() = (top + bottom) / 2.0
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

internal object TextLineComposer {
    fun compose(items: List<PositionedText>): List<String> {
        if (items.isEmpty()) return emptyList()

        val rows = mutableListOf<TextRow>()
        items
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<PositionedText> { it.top }.thenBy { it.left })
            .forEach { item ->
                val row = rows
                    .filter { isSameVisualLine(it, item) }
                    .minByOrNull { kotlin.math.abs(it.centerY - item.centerY) }

                if (row == null) rows += TextRow(item) else row.add(item)
            }

        return rows
            .sortedWith(compareBy<TextRow> { it.top }.thenBy { it.left })
            .map { row ->
                row.items
                    .sortedBy { it.left }
                    .joinToString(" ") { it.text.trim() }
            }
    }

    private fun isSameVisualLine(row: TextRow, item: PositionedText): Boolean {
        val overlap = (
            minOf(row.bottom, item.bottom) - maxOf(row.top, item.top)
        ).coerceAtLeast(0)
        val minimumHeight = minOf(row.height, item.height).coerceAtLeast(1)
        val overlapRatio = overlap.toDouble() / minimumHeight.toDouble()
        val centerDistance = kotlin.math.abs(row.centerY - item.centerY)
        val centerTolerance = maxOf(row.height, item.height) * 0.45
        return overlapRatio >= 0.35 || centerDistance <= centerTolerance
    }

    private class TextRow(first: PositionedText) {
        val items = mutableListOf(first)
        var left: Int = first.left
            private set
        var top: Int = first.top
            private set
        var bottom: Int = first.bottom
            private set
        val centerY: Double get() = (top + bottom) / 2.0
        val height: Int get() = (bottom - top).coerceAtLeast(1)

        fun add(item: PositionedText) {
            items += item
            left = minOf(left, item.left)
            top = minOf(top, item.top)
            bottom = maxOf(bottom, item.bottom)
        }
    }
}

internal object IdCardParser {
    private val candidatePattern = Regex(
        "(?<![0-9A-Za-z])([0-9]{17}[0-9Xx])(?![0-9A-Za-z])",
    )
    private val provinces = setOf(
        11, 12, 13, 14, 15, 21, 22, 23, 31, 32, 33, 34, 35, 36, 37,
        41, 42, 43, 44, 45, 46, 50, 51, 52, 53, 54, 61, 62, 63, 64, 65,
        71, 81, 82,
    )
    private val weights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    private const val checkCodes = "10X98765432"

    fun extract(lines: List<String>): List<String> {
        val results = mutableListOf<String>()
        val seen = linkedSetOf<String>()

        lines.forEach { line ->
            candidatePattern.findAll(line).forEach { match ->
                val candidate = match.groupValues[1].uppercase()
                if (isValid18(candidate) && seen.add(candidate)) results += candidate
            }
        }
        return results
    }

    private fun isValid18(candidate: String): Boolean {
        if (candidate.length != 18) return false
        if (!candidate.take(17).all { it in '0'..'9' }) return false
        if (candidate.last() !in '0'..'9' && candidate.last() != 'X') return false
        if (!hasValidProvince(candidate) || candidate.substring(14, 17) == "000") return false

        val year = candidate.substring(6, 10).toIntOrNull() ?: return false
        val month = candidate.substring(10, 12).toIntOrNull() ?: return false
        val day = candidate.substring(12, 14).toIntOrNull() ?: return false
        if (!isValidDate(year, month, day)) return false

        val checksum = candidate.take(17)
            .mapIndexed { index, digit -> digit.digitToInt() * weights[index] }
            .sum()
        return candidate.last() == checkCodes[checksum % 11]
    }

    private fun hasValidProvince(candidate: String): Boolean {
        val province = candidate.take(2).toIntOrNull() ?: return false
        return province in provinces
    }

    private fun isValidDate(year: Int, month: Int, day: Int): Boolean {
        if (year !in 1800..2099 || month !in 1..12) return false
        val leapYear = year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)
        val daysInMonth = when (month) {
            2 -> if (leapYear) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day in 1..daysInMonth
    }
}

internal object ValidityPeriodParser {
    private val candidatePattern = Regex(
        "(?<![0-9])([0-9]{4}\\.[0-9]{2}\\.[0-9]{2}-(?:[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}|长期))(?![0-9])",
    )

    fun extract(lines: List<String>): List<String> {
        val results = mutableListOf<String>()
        val seen = linkedSetOf<String>()

        lines.forEach { line ->
            candidatePattern.findAll(line).forEach { match ->
                val candidate = match.groupValues[1].replace(".", "")
                if (seen.add(candidate)) results += candidate
            }
        }
        return results
    }
}
