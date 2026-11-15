/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.handwriting

/** 手写笔画的一个采样点（画板视图像素坐标）。纯数据，JVM 可测。 */
data class InkPoint(val x: Float, val y: Float)

/**
 * 笔画 → JNI 载荷编码。纯函数，不碰 Android API，[HandwritingInkTest] 直接跑。
 *
 * 载荷形状与 rimi_handwriting.cc 的 fillCharacter 对齐：
 * points = [x0,y0,x1,y1,...] 扁平数组，strokeLengths = 每笔画的点数。
 */
object HandwritingInk {
    data class Encoded(val points: FloatArray, val strokeLengths: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is Encoded && points.contentEquals(other.points) &&
                strokeLengths.contentEquals(other.strokeLengths)

        override fun hashCode(): Int = 31 * points.contentHashCode() + strokeLengths.contentHashCode()
    }

    /** 空输入/全空笔画返回 null（识别器据此直接返回空候选，不走 JNI）。 */
    fun encode(strokes: List<List<InkPoint>>): Encoded? {
        val nonEmpty = strokes.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return null
        val points = ArrayList<Float>(nonEmpty.sumOf { it.size } * 2 + nonEmpty.size)
        val lens = IntArray(nonEmpty.size)
        nonEmpty.forEachIndexed { i, stroke ->
            // 单点笔画（点「丶」）补一个微偏移重复点：zinnia 的方向特征需要点对。
            val effective = if (stroke.size == 1) listOf(stroke[0], InkPoint(stroke[0].x + 0.5f, stroke[0].y)) else stroke
            lens[i] = effective.size
            effective.forEach { p ->
                points.add(p.x)
                points.add(p.y)
            }
        }
        return Encoded(points.toFloatArray(), lens)
    }
}