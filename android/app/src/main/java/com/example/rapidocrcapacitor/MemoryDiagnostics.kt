package com.example.rapidocrcapacitor

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import java.io.File

internal object MemoryDiagnostics {
    fun capture(
        context: Context,
        stage: String,
        elapsedMs: Long,
        deep: Boolean,
    ): JSObject {
        val captureStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val errors = mutableListOf<String>()
        val runtime = Runtime.getRuntime()
        val memoryInfo = Debug.MemoryInfo()

        runCatching { Debug.getMemoryInfo(memoryInfo) }
            .onFailure { errors += "Debug.MemoryInfo:${it.javaClass.simpleName}" }

        val processStatus = readMemoryValues(
            "/proc/self/status",
            setOf(
                "VmSize",
                "VmRSS",
                "RssAnon",
                "RssFile",
                "RssShmem",
                "VmData",
                "VmSwap",
                "Threads",
            ),
            errors,
        )
        val smapsRollup = if (deep) {
            readMemoryValues(
                "/proc/self/smaps_rollup",
                setOf(
                    "Rss",
                    "Pss",
                    "Pss_Anon",
                    "Pss_File",
                    "Pss_Shmem",
                    "Shared_Clean",
                    "Shared_Dirty",
                    "Private_Clean",
                    "Private_Dirty",
                    "Swap",
                    "SwapPss",
                ),
                errors,
            )
        } else {
            emptyMap()
        }
        val mappingPss = if (deep) aggregateMappingPss(errors) else emptyMap()
        val systemMemory = readSystemMemory(context, errors)
        val processState = readProcessState(errors)

        return JSObject().apply {
            put("stage", stage)
            put("elapsedMs", elapsedMs)
            put("capturedAtEpochMs", System.currentTimeMillis())
            put("deep", deep)

            put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            put("javaHeapCommittedBytes", runtime.totalMemory())
            put("javaHeapMaxBytes", runtime.maxMemory())
            put("nativeHeapAllocatedBytes", safeLong(errors, "native-allocated") {
                Debug.getNativeHeapAllocatedSize()
            })
            put("nativeHeapReservedBytes", safeLong(errors, "native-reserved") {
                Debug.getNativeHeapSize()
            })
            put("nativeHeapFreeBytes", safeLong(errors, "native-free") {
                Debug.getNativeHeapFreeSize()
            })

            put("totalPssKb", memoryInfo.totalPss.toLong())
            put("dalvikPssKb", memoryInfo.dalvikPss.toLong())
            put("nativePssKb", memoryInfo.nativePss.toLong())
            put("otherPssKb", memoryInfo.otherPss.toLong())
            put("totalPrivateDirtyKb", memoryInfo.totalPrivateDirty.toLong())
            put("totalPrivateCleanKb", memoryInfo.totalPrivateClean.toLong())
            put("totalSwappablePssKb", memoryInfo.totalSwappablePss.toLong())

            put("summaryJavaHeapKb", memoryStat(memoryInfo, "summary.java-heap"))
            put("summaryNativeHeapKb", memoryStat(memoryInfo, "summary.native-heap"))
            put("summaryCodeKb", memoryStat(memoryInfo, "summary.code"))
            put("summaryStackKb", memoryStat(memoryInfo, "summary.stack"))
            put("summaryGraphicsKb", memoryStat(memoryInfo, "summary.graphics"))
            put("summaryPrivateOtherKb", memoryStat(memoryInfo, "summary.private-other"))
            put("summarySystemKb", memoryStat(memoryInfo, "summary.system"))
            put("summaryTotalSwapKb", memoryStat(memoryInfo, "summary.total-swap"))

            put("vmSizeKb", processStatus.value("VmSize"))
            put("vmRssKb", processStatus.value("VmRSS"))
            put("rssAnonKb", processStatus.value("RssAnon"))
            put("rssFileKb", processStatus.value("RssFile"))
            put("rssShmemKb", processStatus.value("RssShmem"))
            put("vmDataKb", processStatus.value("VmData"))
            put("vmSwapKb", processStatus.value("VmSwap"))
            put("threadCount", processStatus.value("Threads"))
            put("fileDescriptorCount", readFileDescriptorCount(errors))

            put("smapsRssKb", smapsRollup.value("Rss"))
            put("smapsPssKb", smapsRollup.value("Pss"))
            put("smapsPssAnonKb", smapsRollup.value("Pss_Anon"))
            put("smapsPssFileKb", smapsRollup.value("Pss_File"))
            put("smapsPssShmemKb", smapsRollup.value("Pss_Shmem"))
            put("smapsSharedCleanKb", smapsRollup.value("Shared_Clean"))
            put("smapsSharedDirtyKb", smapsRollup.value("Shared_Dirty"))
            put("smapsPrivateCleanKb", smapsRollup.value("Private_Clean"))
            put("smapsPrivateDirtyKb", smapsRollup.value("Private_Dirty"))
            put("smapsSwapKb", smapsRollup.value("Swap"))
            put("smapsSwapPssKb", smapsRollup.value("SwapPss"))

            put("mappingPssKb", mappingPss.toJsObject())
            put("systemMemory", systemMemory)
            put("processImportance", processState.first)
            put("lastTrimLevel", processState.second)
            put("gcStats", readGcStats())
            put("captureCostMs", elapsedMs(captureStartedAtNs))
            put("errors", JSArray(errors))
        }
    }

    fun environment(): JSObject = JSObject().apply {
        put("pid", Process.myPid())
        put("androidSdk", Build.VERSION.SDK_INT)
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        put("abis", JSArray(Build.SUPPORTED_ABIS.toList()))
        put("pageSizeBytes", runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(-1L))
        put("processorCount", Runtime.getRuntime().availableProcessors())
    }

    private fun readSystemMemory(context: Context, errors: MutableList<String>): JSObject {
        val info = ActivityManager.MemoryInfo()
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getMemoryInfo(info)
        }.onFailure { errors += "system-memory:${it.javaClass.simpleName}" }

        return JSObject().apply {
            put("availableBytes", info.availMem)
            put("totalBytes", info.totalMem)
            put("thresholdBytes", info.threshold)
            put("lowMemory", info.lowMemory)
        }
    }

    private fun readProcessState(errors: MutableList<String>): Pair<Int, Int> {
        val state = ActivityManager.RunningAppProcessInfo()
        return runCatching {
            ActivityManager.getMyMemoryState(state)
            state.importance to state.lastTrimLevel
        }.getOrElse {
            errors += "process-state:${it.javaClass.simpleName}"
            -1 to -1
        }
    }

    private fun readGcStats(): JSObject = JSObject().apply {
        RUNTIME_STATS.forEach { (field, key) ->
            val value = runCatching { Debug.getRuntimeStat(key) }
                .getOrNull()
                ?.toLongOrNull()
                ?: -1L
            put(field, value)
        }
    }

    private fun readFileDescriptorCount(errors: MutableList<String>): Int = runCatching {
        File("/proc/self/fd").list()?.size ?: -1
    }.getOrElse {
        errors += "file-descriptors:${it.javaClass.simpleName}"
        -1
    }

    private fun readMemoryValues(
        path: String,
        requestedKeys: Set<String>,
        errors: MutableList<String>,
    ): Map<String, Long> = runCatching {
        buildMap {
            File(path).useLines { lines ->
                lines.forEach { line ->
                    val separator = line.indexOf(':')
                    if (separator <= 0) return@forEach
                    val key = line.substring(0, separator)
                    if (key !in requestedKeys) return@forEach
                    put(key, parseFirstLong(line.substring(separator + 1)))
                }
            }
        }
    }.getOrElse {
        errors += "${File(path).name}:${it.javaClass.simpleName}"
        emptyMap()
    }

    private fun aggregateMappingPss(errors: MutableList<String>): Map<String, Long> = runCatching {
        val totals = MAPPING_CATEGORIES.associateWith { 0L }.toMutableMap()
        var currentCategory = "otherAnonymous"

        File("/proc/self/smaps").useLines { lines ->
            lines.forEach { line ->
                if (MAPPING_HEADER.containsMatchIn(line)) {
                    currentCategory = mappingCategory(line)
                } else if (line.startsWith("Pss:")) {
                    totals[currentCategory] = totals.getValue(currentCategory) + parseFirstLong(line)
                }
            }
        }
        totals
    }.getOrElse {
        errors += "smaps:${it.javaClass.simpleName}"
        emptyMap()
    }

    private fun mappingCategory(header: String): String {
        val path = header.trim().split(WHITESPACE, limit = 6).getOrNull(5).orEmpty().lowercase()
        return when {
            path.endsWith(".onnx") || path.contains("/models/") -> "modelFiles"
            path.contains("onnxruntime") || path.contains("opencv") -> "onnxOpenCv"
            path == "[heap]" || path.contains("libc_malloc") || path.contains("scudo") -> {
                "nativeAllocator"
            }
            path.contains("dalvik") || path.endsWith(".art") || path.endsWith(".oat") ||
                path.endsWith(".vdex") || path.startsWith("/apex/com.android.art/") -> "dalvikArt"
            path.contains("webview") || path.contains("chromium") -> "webView"
            path.contains("dmabuf") || path.contains("kgsl") || path.contains("gralloc") ||
                path.contains("gpu") -> "graphics"
            path.startsWith("[stack") || path.contains("stack_and_tls") -> "threadStacks"
            path.endsWith(".apk") || path.endsWith(".dex") -> "apkDex"
            path.isEmpty() || path.startsWith("[") -> "otherAnonymous"
            else -> "otherFiles"
        }
    }

    private fun Map<String, Long>.toJsObject(): JSObject = JSObject().apply {
        MAPPING_CATEGORIES.forEach { category -> put(category, this@toJsObject[category] ?: -1L) }
    }

    private fun Map<String, Long>.value(key: String): Long = this[key] ?: -1L

    private fun memoryStat(memoryInfo: Debug.MemoryInfo, key: String): Long =
        runCatching { memoryInfo.getMemoryStat(key).toLongOrNull() ?: -1L }.getOrDefault(-1L)

    private fun safeLong(
        errors: MutableList<String>,
        label: String,
        block: () -> Long,
    ): Long = runCatching(block).getOrElse {
        errors += "$label:${it.javaClass.simpleName}"
        -1L
    }

    private fun parseFirstLong(value: String): Long =
        FIRST_NUMBER.find(value)?.value?.toLongOrNull() ?: -1L

    private fun elapsedMs(startedAtNs: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNs) / 1_000_000L

    private val FIRST_NUMBER = Regex("\\d+")
    private val MAPPING_HEADER = Regex("^[0-9a-fA-F]+-[0-9a-fA-F]+\\s")
    private val WHITESPACE = Regex("\\s+")
    private val MAPPING_CATEGORIES = listOf(
        "nativeAllocator",
        "otherAnonymous",
        "onnxOpenCv",
        "modelFiles",
        "dalvikArt",
        "webView",
        "graphics",
        "threadStacks",
        "apkDex",
        "otherFiles",
    )
    private val RUNTIME_STATS = mapOf(
        "gcCount" to "art.gc.gc-count",
        "blockingGcCount" to "art.gc.blocking-gc-count",
        "gcTimeMs" to "art.gc.gc-time",
        "blockingGcTimeMs" to "art.gc.blocking-gc-time",
        "bytesAllocated" to "art.gc.bytes-allocated",
        "bytesFreed" to "art.gc.bytes-freed",
    )
}
