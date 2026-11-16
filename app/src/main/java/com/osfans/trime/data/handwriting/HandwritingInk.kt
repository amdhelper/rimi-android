/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.handwriting

import com.yuyan.imemodule.handwriting.Stroke

/** 手写笔画的一个采样点（画板视图像素坐标）。纯数据，JVM 可测。 */
data class InkPoint(val x: Float, val y: Float)

/**
 * 笔画 → 雨燕引擎输入转换。纯函数，不碰 Android API，[HandwritingInkTest] 直接跑。
 *
 * 雨燕引擎（Paddle-Lite + PP-OCRv5）吃**原始视图像素坐标**：
 * ImagePreprocessor 内部按墨迹 bbox 等比缩放并渲染成 48px 高的白底黑字图，
 * 不需要外部归一化（zinnia 时代的 encodeNormalized 随旧引擎一起退役）。
 */
object HandwritingInk {
    /**
     * 单个字符是否为汉字：CJK 基本区 + 扩展 A + 兼容区 + 〇。
     * 用 Char 区间比较，minSdk 21 安全（Character.UnicodeScript 需 API 24，不用）。
     * 极罕见的扩展 B+（代理对）不在内，手写场景可接受。纯函数，JVM 可测。
     */
    fun isHanChar(c: Char): Boolean =
        c in '一'..'鿿' || c in '㐀'..'䶿' ||
            c in '豈'..'﫿' || c == '〇'

    /**
     * 候选是否为纯汉字（2026-11-16）。PP-OCRv5 是场景文字 OCR 模型，候选会混入
     * 拉丁字母/标点/符号噪声（你./T你/PA/————）；rimi 手写只要汉字。
     */
    fun isHanText(text: String): Boolean =
        text.isNotEmpty() && text.all { isHanChar(it) }

    /**
     * 提取候选中的汉字部分（2026-11-16 爸爸反馈「写宽了一动不动、无候选」）。
     * PP-OCRv5 候选常混噪声（你./T你/PA）：旧的纯汉字过滤整条丢弃 → 明明写出了字
     * 却一个候选都不给。改为提取汉字保留（你.→你、T你→你、PA→空）。纯函数，JVM 可测。
     */
    fun hanOnly(text: String): String = text.filter { isHanChar(it) }

    /** 空输入/全空笔画返回空列表（识别器据此直接返回空候选，不走推理）。 */
    fun toYuyanStrokes(strokes: List<List<InkPoint>>): List<Stroke> =
        strokes.filter { it.isNotEmpty() }.map { stroke ->
            // 单点笔画（点「丶」）补一个微偏移重复点：ImagePreprocessor 对 <2 点的路径
            // 直接跳过不画，不补点则点画不出来（义/丸/为 等字会丢笔画）。
            val effective =
                if (stroke.size == 1) listOf(stroke[0], InkPoint(stroke[0].x + 0.5f, stroke[0].y)) else stroke
            Stroke(effective.map { Stroke.Point(it.x, it.y) })
        }
}
