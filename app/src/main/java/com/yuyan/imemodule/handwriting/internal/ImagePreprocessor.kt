package com.yuyan.imemodule.handwriting.internal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.yuyan.imemodule.handwriting.Stroke

class ImagePreprocessor {

    companion object {
        const val REC_INPUT_HEIGHT = 48
        const val REC_MAX_WIDTH = 320
        private const val MARGIN_RATIO = 0.08f
        private const val MIN_CONTENT_WIDTH_RATIO = 0.72f
        private const val MAX_EXPECTED_CHARS = 6
        private const val MAX_CONTENT_ASPECT = 8f
        private const val FLAT_MAX_STROKES = 3
        private const val FLAT_MAX_WIDTH_RATIO = 2f

        /**
         * 渲染缩放决策（纯算术，不碰 Android API，JVM 可直接单测）。
         *
         * 单字模式（expectedChars=1，rimi 一次写一字）：汉字是方块字，宽墨迹
         * （左右结构写宽了，如「灿」）必须横向压回方形——否则 PP-OCRv5 把宽图
         * 读成两个字（灿→火山），更宽时吐拉丁/标点噪声被过滤光 → 无候选
         * （2026-11-16 爸爸反馈「灿经常打成火山 / 写宽了一动不动」）。
         * 扁笔画（「一」，aspect>8 且笔画≤3）除外，保持原有宽渲染。
         */
        internal fun computeRenderScales(
            boxWidth: Float,
            boxHeight: Float,
            targetHeight: Int,
            maxWidth: Int,
            expectedChars: Int,
            strokeCount: Int,
            marginRatio: Float = MARGIN_RATIO,
        ): RenderScales {
            val contentHeight = targetHeight * (1f - 2 * marginRatio)
            val aspect = boxWidth / boxHeight
            val flat = aspect > MAX_CONTENT_ASPECT && strokeCount <= FLAT_MAX_STROKES
            val widthLimit = if (flat) {
                minOf(maxWidth, (targetHeight * FLAT_MAX_WIDTH_RATIO).toInt())
            } else {
                maxWidth
            }
            val contentMaxWidth = (widthLimit - 4).toFloat()
            var scale = contentHeight / boxHeight
            if (boxWidth * scale > contentMaxWidth) {
                scale = contentMaxWidth / boxWidth
            }
            val minContentWidth = if (expectedChars > 1) {
                minOf(contentHeight * expectedChars.coerceAtMost(MAX_EXPECTED_CHARS), contentMaxWidth)
            } else {
                targetHeight * MIN_CONTENT_WIDTH_RATIO
            }
            val scaleX = when {
                expectedChars == 1 && !flat && boxWidth * scale > contentHeight -> contentHeight / boxWidth
                boxWidth * scale < minContentWidth -> minContentWidth / boxWidth
                else -> scale
            }
            return RenderScales(scaleX, scale, widthLimit)
        }
    }

    fun renderStrokesForRec(
        strokes: List<Stroke>,
        targetHeight: Int = REC_INPUT_HEIGHT,
        maxWidth: Int = REC_MAX_WIDTH,
        expectedChars: Int = 1,
        strokeBase: Float = 3f,
        smooth: Boolean = true,
        marginRatio: Float = MARGIN_RATIO,
    ): RecInput {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (stroke in strokes) {
            for (point in stroke.points) {
                if (point.x < minX) minX = point.x
                if (point.y < minY) minY = point.y
                if (point.x > maxX) maxX = point.x
                if (point.y > maxY) maxY = point.y
            }
        }
        if (minX > maxX || minY > maxY) return blankRecInput(targetHeight, maxWidth)
        val boxWidth = maxOf(1f, maxX - minX)
        val boxHeight = maxOf(1f, maxY - minY)
        val scales = computeRenderScales(boxWidth, boxHeight, targetHeight, maxWidth, expectedChars, strokes.size, marginRatio)
        val scaleX = scales.scaleX
        val scaleY = scales.scaleY
        val widthLimit = scales.widthLimit

        val drawWidth = boxWidth * scaleX
        val drawHeight = boxHeight * scaleY
        val imageWidth = (drawWidth + targetHeight * marginRatio * 2)
            .toInt()
            .coerceIn(8, widthLimit)

        val bitmap = Bitmap.createBitmap(imageWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            strokeWidth = maxOf(1.5f, strokeBase) / ((scaleX + scaleY) / 2f)
        }

        val translateX = (imageWidth - drawWidth) / 2f - minX * scaleX
        val translateY = (targetHeight - drawHeight) / 2f - minY * scaleY

        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scaleX, scaleY)
        for (stroke in strokes) {
            val path = (if (smooth) buildSmoothPath(stroke) else buildPolylinePath(stroke)) ?: continue
            canvas.drawPath(path, paint)
        }
        canvas.restore()

        val input = bitmapToRecInput(bitmap, targetHeight, widthLimit)
        bitmap.recycle()
        return input
    }

    private fun bitmapToRecInput(
        bitmap: Bitmap,
        targetHeight: Int,
        maxWidth: Int
    ): RecInput {
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetWidth = Math.round(targetHeight * ratio).coerceIn(8, maxWidth)

        val scaled = if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }

        val pixels = IntArray(targetWidth * targetHeight)
        scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

        val channelSize = targetWidth * targetHeight
        val data = FloatArray(3 * channelSize)
        for (i in 0 until channelSize) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val gray = (r * 299 + g * 587 + b * 114) / 1000f
            val value = (gray / 255f - 0.5f) / 0.5f
            data[i] = value
            data[channelSize + i] = value
            data[2 * channelSize + i] = value
        }

        if (scaled !== bitmap) scaled.recycle()
        return RecInput(data, targetWidth, targetHeight)
    }

    private fun blankRecInput(targetHeight: Int, maxWidth: Int): RecInput {
        val width = minOf(targetHeight * 2, maxWidth)
        return RecInput(FloatArray(3 * width * targetHeight) { -1f }, width, targetHeight)
    }

    /** 不平滑折线（取证 A/B 用：保留连笔尖角原貌，2026-11-16）。 */
    private fun buildPolylinePath(stroke: Stroke): Path? {
        if (stroke.points.size < 2) return null
        val path = Path()
        path.moveTo(stroke.points[0].x, stroke.points[0].y)
        for (i in 1 until stroke.points.size) {
            path.lineTo(stroke.points[i].x, stroke.points[i].y)
        }
        return path
    }

    private fun buildSmoothPath(stroke: Stroke): Path? {
        if (stroke.points.size < 2) return null

        val path = Path()
        val firstPoint = stroke.points[0]
        path.moveTo(firstPoint.x, firstPoint.y)

        for (i in 1 until stroke.points.size - 1) {
            val current = stroke.points[i]
            val next = stroke.points[i + 1]
            val midX = (current.x + next.x) / 2
            val midY = (current.y + next.y) / 2
            path.quadTo(current.x, current.y, midX, midY)
        }

        val lastPoint = stroke.points[stroke.points.size - 1]
        path.lineTo(lastPoint.x, lastPoint.y)
        return path
    }
}

/** 渲染缩放决策结果（纯数据，JVM 可测）。scaleY 恒为高度适配缩放。 */
internal data class RenderScales(val scaleX: Float, val scaleY: Float, val widthLimit: Int)

class RecInput(val data: FloatArray, val width: Int, val height: Int) {
    fun shape(): LongArray = longArrayOf(1, 3, height.toLong(), width.toLong())
    override fun toString(): String = "RecInput(${width}x$height, size=${data.size})"
}
