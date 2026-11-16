/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.handwriting

import android.content.Context
import com.yuyan.imemodule.handwriting.internal.ImagePreprocessor
import com.yuyan.imemodule.handwriting.internal.Predictor
import timber.log.Timber
import java.io.File

/**
 * 离线手写识别器（雨燕输入法核心：Paddle-Lite v2.14 + PP-OCRv5 模型，覆盖 18382 字）。
 *
 * 2026-11-16 换引擎：zinnia+Tegaki（2008 年老引擎）对爸爸真实笔迹 TOP1 ≈ 30%，
 * 你/好/我/天 连前 20 都进不去，判定不可用；换雨燕开源核心（BSD-3-Clause，
 * vendored 在 com/yuyan/ 下，出处与许可见 assets/hw/README.md）。
 *
 * rimi 专用编排（区别于雨燕整行手写）：rimi 是「一次写一个字、停笔自动上屏」交互，
 * 故 ① 整字渲染（expectedChars=1，不做多字切分，宽墨迹压回方形）——雨燕的 StrokeSegmenter
 * 会把左右结构单字（好/明）误切成两个字并横向拉扁渲染，整字识别全毁（明→PA）；
 * 写宽的左右结构字（灿）不压方形也会被读成两个字（灿→火山，2026-11-16 爸爸反馈）；
 * ② 候选提取汉字——PP-OCRv5 是场景文字 OCR 模型，会吐拉丁/标点噪声（你./T你/PA），
 * 提取汉字保留（你.→你），不整条丢弃（否则写宽了噪声全被滤光=无候选「一动不动」）。
 * 引擎吃原始视图像素坐标，内部 bbox 渲染（48px 高）→ CTC 束搜索，
 * 完全离线不联网。全部方法 synchronized：推理对同一实例不可重入。
 */
object HandwritingRecognizer {
    private const val NBEST_DEFAULT = 12

    private var predictor: Predictor? = null
    private var loadFailed = false

    private val cpuThreads: Int by lazy {
        val cores = Runtime.getRuntime().availableProcessors()
        when {
            cores >= 6 -> 4
            cores >= 4 -> 3
            else -> 2
        }
    }

    val isReady: Boolean get() = predictor?.isReady() == true

    /**
     * 上一轮识别是否「部件组合歧义」（2026-11-16）：组合补进了新候选字
     * （行草 灿 → 仙/汕/灿 并存）时=true，手写窗据此暂停自动上屏等用户选字。
     * 你/好 等整字高置信识别不触发组合新字，自动上屏照常。
     */
    var lastCompositionAmbiguous: Boolean = false
        private set

    /** 拷贝模型（如需）并加载引擎。失败只记一次，不反复重试拷贝。 */
    @Synchronized
    fun ensureModel(context: Context): Boolean {
        if (isReady) return true
        if (loadFailed) return false
        val ctx = context.applicationContext
        HwDebugLog.log(ctx, "ensureModel begin")
        // 分步诊断（文件取证：华为设备 logcat 屏蔽应用日志）。
        val avail = com.yuyan.inputmethod.core.HandWriting.isAvailable()
        HwDebugLog.log(ctx, "step1 so available=$avail")
        if (!avail) {
            loadFailed = true
            return false
        }
        val model = Predictor.materializeModel(ctx)
        HwDebugLog.log(ctx, "step2 model=${model?.absolutePath} len=${model?.length()}")
        if (model == null || model.length() < 100 * 1024) {
            loadFailed = true
            return false
        }
        return try {
            val p = predictor ?: Predictor(ctx, cpuThreads, com.yuyan.inputmethod.core.HandWriting.POWER_MODE_HIGH).also {
                // 手写只收汉字：CTC 解码约束到汉字类（防行草字形被读成拉丁 ku/K 后过滤光）。
                it.chineseOnly = true
                predictor = it
            }
            val ok = p.load()
            HwDebugLog.log(ctx, "step3 predictor.load=$ok")
            if (ok) {
                Timber.i("Handwriting engine ready (Paddle-Lite + PP-OCRv5)")
            } else {
                Timber.e("Handwriting engine init failed (model corrupt or native error)")
                loadFailed = true
            }
            ok
        } catch (t: Throwable) {
            HwDebugLog.log(ctx, "step3 predictor.load threw: ${t.message}\n${t.stackTraceToString().take(1500)}")
            Timber.e(t, "Handwriting engine load failed")
            loadFailed = true
            false
        }
    }

    /**
     * 识别笔画，返回候选字（可能为空）。
     *
     * 传原始视图像素坐标：整字渲染（expectedChars=1，不切分、宽墨迹压回方形）。
     * 候选提取汉字（去拉丁/标点噪声，不整条丢弃）。画板尺寸只用于日志。
     * trace=false 供回放取证用（不回写 hw_trace.jsonl，防回放污染真实笔迹样本）。
     * tag 区分取证渲染图文件名（live=正常输入，r<idx>=回放条目）。
     */
    @Synchronized
    fun recognize(
        context: Context,
        strokes: List<List<InkPoint>>,
        width: Int,
        height: Int,
        nbest: Int = NBEST_DEFAULT,
        trace: Boolean = true,
        tag: String = "live",
    ): List<String> {
        val ink = HandwritingInk.toYuyanStrokes(strokes)
        if (ink.isEmpty()) return emptyList()
        if (!ensureModel(context)) return emptyList()
        val p = predictor ?: return emptyList()
        lastCompositionAmbiguous = false
        return try {
            // 取证调节（hw_tuning.json 存在时生效：笔画粗细/平滑/边距 A/B，无需重装机）。
            val tuning = readTuning(context)
            // 整字渲染：expectedChars=1 → 不做多字切分、宽墨迹压回方形（防 灿→火山）。
            val input = ImagePreprocessor().renderStrokesForRec(
                ink, expectedChars = 1,
                strokeBase = tuning.stroke, smooth = tuning.smooth, marginRatio = tuning.margin,
            )
            val raw = p.predict(input)
            // 过滤前原始候选+置信度+输入尺寸（2026-11-16 灿→空候选取证：看模型到底吐了什么）。
            HwDebugLog.log(
                context,
                "rec raw ${input.width}x${input.height} tune=${tuning.describe()}: " +
                    raw.take(8).joinToString { "${it.text}:%.2f".format(it.confidence) }.take(280),
            )
            saveRenderPng(context, input, tag)
            val full =
                raw.map { HandwritingInk.hanOnly(it.text.trim()) }
                    .filter { it.isNotEmpty() }
                    .distinct()
            // 部件组合（2026-11-16 爸爸反馈「灿只识别出山」）：行草左右结构字整字
            // 被读成 山/仙，但左半单独识 火、右半单独识 山 → IDS ⿰ 组合出 灿。
            HandwritingCompose.ensureLoaded(context)
            var composed = emptyList<String>()
            val clusters = HandwritingCompose.splitClusters(strokes)
            if (clusters != null) {
                val li = HandwritingInk.toYuyanStrokes(clusters.first)
                val ri = HandwritingInk.toYuyanStrokes(clusters.second)
                if (li.isNotEmpty() && ri.isNotEmpty()) {
                    val lc = p.predict(
                        ImagePreprocessor().renderStrokesForRec(
                            li, expectedChars = 1,
                            strokeBase = tuning.stroke, smooth = tuning.smooth, marginRatio = tuning.margin,
                        ),
                    ).map { HandwritingInk.hanOnly(it.text.trim()) }
                        .filter { it.isNotEmpty() && it.length == 1 }.distinct()
                    val rc = p.predict(
                        ImagePreprocessor().renderStrokesForRec(
                            ri, expectedChars = 1,
                            strokeBase = tuning.stroke, smooth = tuning.smooth, marginRatio = tuning.margin,
                        ),
                    ).map { HandwritingInk.hanOnly(it.text.trim()) }
                        .filter { it.isNotEmpty() && it.length == 1 }.distinct()
                    composed = HandwritingCompose.compose(lc, rc)
                    HwDebugLog.log(context, "compose: L=${lc.take(4)} R=${rc.take(4)} -> ${composed.take(6)}")
                }
            }
            val (merged, ambiguous) = HandwritingCompose.merge(full, composed)
            lastCompositionAmbiguous = ambiguous
            val result = merged.take(nbest)
            // release 包的 Timber 会丢掉 < INFO 的日志，取证一律用 i 级（只在你手写时打）。
            Timber.i(
                "hw: strokes=%d pts=%d pad=%dx%d cand=%s",
                ink.size,
                ink.sumOf { it.points.size },
                width,
                height,
                result.take(NBEST_LOG),
            )
            if (trace) dumpTrace(context, strokes, result)
            HwDebugLog.log(context, "recognize: strokes=${ink.size} cand=${result.take(6)}")
            result
        } catch (e: Exception) {
            HwDebugLog.log(context, "recognize FAILED: ${e.message}")
            Timber.e(e, "Handwriting recognize failed")
            emptyList()
        }
    }

    private const val NBEST_LOG = 6

    private const val TRACE_MAX_BYTES = 400_000L

    /** 渲染调节（取证 A/B）：默认=线上行为；hw_tuning.json 存在时覆盖。 */
    private data class Tuning(val stroke: Float = 3f, val smooth: Boolean = true, val margin: Float = 0.08f) {
        fun describe(): String = "s=$stroke,sm=$smooth,m=$margin"
    }

    private fun readTuning(context: Context): Tuning {
        return try {
            val dir = context.getExternalFilesDir(null) ?: return Tuning()
            val f = File(dir, "hw_tuning.json")
            if (!f.isFile) return Tuning()
            val j = org.json.JSONObject(f.readText())
            Tuning(
                stroke = j.optDouble("stroke", 3.0).toFloat().coerceIn(1f, 8f),
                smooth = j.optBoolean("smooth", true),
                margin = j.optDouble("margin", 0.08).toFloat().coerceIn(0f, 0.3f),
            )
        } catch (e: Exception) {
            HwDebugLog.log(context, "tuning parse failed: ${e.message}")
            Tuning()
        }
    }

    /** 把引擎实际吃到的渲染图落盘（hw_render_<tag>.png，adb pull 直接看模型输入）。 */
    private fun saveRenderPng(context: Context, input: com.yuyan.imemodule.handwriting.internal.RecInput, tag: String) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            val w = input.width
            val h = input.height
            val pixels = IntArray(w * h)
            for (i in pixels.indices) {
                val g = ((input.data[i] + 1f) * 127.5f).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            val bmp = android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888)
            File(dir, "hw_render_$tag.png").outputStream().use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bmp.recycle()
        } catch (_: Exception) {
        }
    }

    /**
     * 落盘最近的手写轨迹（外部私有目录 hw_trace.jsonl，adb 可直接读），用于离线复现
     * 「真实手写识别错」：把用户实际笔迹喂进引擎试不同策略。
     * 超过 400KB 自动清空重来，不参与同步、不上传。
     * side = 原始墨迹 bbox 长边（仅参考；引擎内部自行归一化）。
     */
    private fun dumpTrace(
        context: Context,
        strokes: List<List<InkPoint>>,
        candidates: List<String>,
    ) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            val f = File(dir, "hw_trace.jsonl")
            if (f.length() > TRACE_MAX_BYTES) f.delete()
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (s in strokes) {
                for (p in s) {
                    if (p.x < minX) minX = p.x
                    if (p.x > maxX) maxX = p.x
                    if (p.y < minY) minY = p.y
                    if (p.y > maxY) maxY = p.y
                }
            }
            val side = if (minX > maxX) 0 else kotlin.math.round(maxOf(maxX - minX, maxY - minY)).toInt()
            val sb = StringBuilder(1024)
            sb.append("{\"t\":").append(System.currentTimeMillis())
            sb.append(",\"side\":").append(side)
            sb.append(",\"cand\":\"").append(candidates.joinToString(",")).append("\"")
            sb.append(",\"strokes\":[")
            strokes.forEachIndexed { i, s ->
                if (i > 0) sb.append(',')
                s.joinTo(sb, ",", "[", "]") { "[${it.x.toInt()},${it.y.toInt()}]" }
            }
            sb.append("]}\n")
            f.appendText(sb.toString())
        } catch (e: Exception) {
            Timber.w(e, "hw trace dump failed")
        }
    }
}
