/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.handwriting

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * 离线手写识别器（zinnia + Tegaki 简体中文轻量模型，覆盖 6763 字）。
 *
 * 模型打进 assets/handwriting/，首次使用拷到 filesDir 后按路径打开
 * （zinnia 内部 mmap，不占 Java 堆）。全部方法 synchronized：
 * zinnia::Recognizer::classify 对同一实例不可重入。
 */
object HandwritingRecognizer {
    private const val ASSET_PATH = "handwriting/handwriting-zh_CN.model"
    private const val NBEST_DEFAULT = 12

    // 模型版本号：换模型文件时 +1，触发重新拷贝。
    // 不能用 assets.openFd() 判大小——APK 里 asset 默认被压缩，openFd 会抛
    // FileNotFoundException（真机/模拟器实测），必须走 assets.open() 流式拷贝。
    private const val MODEL_VERSION = 1

    private var handle = 0L
    private var loadFailed = false

    val isReady: Boolean @Synchronized get() = handle != 0L

    /** 拷贝模型（如需）并打开。失败只记一次，不反复重试拷贝。 */
    @Synchronized
    fun ensureModel(context: Context): Boolean {
        if (handle != 0L) return true
        if (loadFailed) return false
        return try {
            val dir = File(context.filesDir, "handwriting")
            dir.mkdirs()
            val modelFile = File(dir, "handwriting-zh_CN.model")
            val stampFile = File(dir, "model.version")
            val stampOk = runCatching { stampFile.readText().trim() == MODEL_VERSION.toString() }.getOrDefault(false)
            if (!modelFile.exists() || modelFile.length() == 0L || !stampOk) {
                context.assets.open(ASSET_PATH).use { input ->
                    modelFile.outputStream().use { output -> input.copyTo(output) }
                }
                stampFile.writeText(MODEL_VERSION.toString())
            }
            handle = HandwritingNative.nativeOpenModel(modelFile.absolutePath)
            if (handle == 0L) {
                Timber.e("Handwriting model open failed: %s", modelFile.absolutePath)
                loadFailed = true
                false
            } else {
                Timber.i("Handwriting model ready: %s", modelFile.absolutePath)
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "Handwriting model load failed")
            loadFailed = true
            false
        }
    }

    /**
     * 识别笔画，返回候选字（可能为空）。
     *
     * 归一化在 [HandwritingInk.encodeNormalized] 里完成：按墨迹 bbox 等比缩放居中，
     * width/height 一律传方框边长（zinnia 各轴独立归一化，传非方形尺寸会把字压扁）。
     * 画板尺寸只用于日志（排查写多大字更容易看）。
     */
    @Synchronized
    fun recognize(
        context: Context,
        strokes: List<List<InkPoint>>,
        width: Int,
        height: Int,
        nbest: Int = NBEST_DEFAULT,
    ): List<String> {
        val ink = HandwritingInk.encodeNormalized(strokes) ?: return emptyList()
        if (!ensureModel(context)) return emptyList()
        return try {
            val result =
                HandwritingNative.nativeRecognize(
                    handle,
                    ink.side,
                    ink.side,
                    ink.points,
                    ink.strokeLengths,
                    nbest,
                )?.toList() ?: emptyList()
            // release 包的 Timber 会丢掉 < INFO 的日志，取证一律用 i 级（只在你手写时打）。
            Timber.i(
                "hw: strokes=%d pts=%d pad=%dx%d side=%d cand=%s",
                strokes.count { it.isNotEmpty() },
                ink.points.size / 2,
                width,
                height,
                ink.side,
                result.take(NBEST_LOG),
            )
            result
        } catch (e: Exception) {
            Timber.e(e, "Handwriting recognize failed")
            emptyList()
        }
    }

    private const val NBEST_LOG = 6
}