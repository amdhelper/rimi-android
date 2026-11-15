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
    /**
     * 归一化留白比例（每边）。Tegaki 训练样本里字一般不满幅，实测 15% 边距 TOP1 最高
     * （0/10/15/20/25/30% 对照：50/50/56/56/56/50%）。
     */
    const val NORMALIZE_MARGIN = 0.15f

    data class Encoded(val points: FloatArray, val strokeLengths: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is Encoded && points.contentEquals(other.points) &&
                strokeLengths.contentEquals(other.strokeLengths)

        override fun hashCode(): Int = 31 * points.contentHashCode() + strokeLengths.contentHashCode()
    }

    /**
     * 归一化后的载荷：点已按墨迹 bbox 等比缩放并居中，`side` 是方框边长
     * （width=height=side 传 zinnia，两轴同一缩放因子 → 不产生各向异性畸变）。
     */
    data class Normalized(
        val points: FloatArray,
        val strokeLengths: IntArray,
        val side: Int,
        val sideRaw: Float,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Normalized && points.contentEquals(other.points) &&
                strokeLengths.contentEquals(other.strokeLengths) && side == other.side

        override fun hashCode(): Int =
            31 * (31 * points.contentHashCode() + strokeLengths.contentHashCode()) + side
    }

    /**
     * 按墨迹 bbox 等比归一化到正方形（zinnia 唯一的正确输入形态）。
     *
     * 为什么必须这样：zinnia 的特征把点各轴独立归一化（`x/width`, `y/height`）。
     * 画板是 1152×594 的超宽矩形，用户写的方形字会被横向压扁近 2 倍；而且同一字写在
     * 画板不同位置/大小，归一化结果完全不同 → 候选乱跳。实测 16×10 随机位置样本：
     * 旧实现 TOP1 19%，bbox 等比归一化后 56%。
     */
    fun encodeNormalized(
        strokes: List<List<InkPoint>>,
        margin: Float = NORMALIZE_MARGIN,
    ): Normalized? {
        val ink = encode(strokes) ?: return null
        val pts = ink.points
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < pts.size) {
            val x = pts[i]
            val y = pts[i + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            i += 2
        }
        val boxW = (maxX - minX).coerceAtLeast(1f)
        val boxH = (maxY - minY).coerceAtLeast(1f)
        // 长边决定方框：两轴共用一个缩放因子，字形比例不变。
        val side = boxW.coerceAtLeast(boxH) * (1f + 2f * margin)
        val offX = (side - boxW) / 2f - minX
        val offY = (side - boxH) / 2f - minY
        val out = FloatArray(pts.size)
        var j = 0
        while (j < pts.size) {
            out[j] = pts[j] + offX
            out[j + 1] = pts[j + 1] + offY
            j += 2
        }
        return Normalized(out, ink.strokeLengths, kotlin.math.round(side).toInt().coerceAtLeast(1), side)
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