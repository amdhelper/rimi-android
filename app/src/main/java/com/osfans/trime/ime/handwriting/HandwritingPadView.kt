/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.handwriting

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import com.osfans.trime.data.handwriting.HwDebugLog
import com.osfans.trime.data.handwriting.InkPoint
import com.osfans.trime.data.theme.ThemeScope
import splitties.dimensions.dp

/**
 * 手写画板：捕获笔画并绘制墨迹。
 *
 * 坐标保持视图像素原样交给雨燕引擎（Paddle-Lite + PP-OCRv5，内部自行 bbox 归一化渲染），
 * 不做缩放，避免引入二次误差。只跟踪第一根手指，忽略多指。
 */
@SuppressLint("ViewConstructor")
class HandwritingPadView(
    context: Context,
    private val scope: ThemeScope,
) : View(context) {
    private val strokes = mutableListOf<MutableList<InkPoint>>()
    private var activePointerId = -1
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** 笔画变化（写完一笔 / 撤销 / 清空）时回调，用于触发识别。 */
    var onStrokeChanged: (() -> Unit)? = null

    /**
     * 新笔画落笔（ACTION_DOWN）时回调，先于笔画入列。
     * 手写窗用它取消待发的自动上屏——避免写到一半把上一个字 committed 掉。
     */
    var onNewStrokeStart: (() -> Unit)? = null

    init {
        refreshColors()
    }

    fun refreshColors() {
        // 深色键盘上用候选字色画墨迹，对比度与候选词一致。
        paint.color = scope.colors.candidateTextColor
        paint.strokeWidth = context.dp(6).toFloat()
        // 画板底色用键面色（比键盘背景稍亮一档），让用户一眼看出可手写的范围
        // （2026-11-15 爸爸反馈：底板颜色要区别于 UI 其他地方）。
        setBackgroundColor(scope.colors.keyBackColor)
        invalidate()
    }

    /** 当前全部笔画（只读快照）。 */
    fun snapshot(): List<List<InkPoint>> = strokes.map { it.toList() }

    fun undo(): Boolean {
        if (strokes.isEmpty()) return false
        strokes.removeAt(strokes.lastIndex)
        invalidate()
        onStrokeChanged?.invoke()
        return true
    }

    fun clear() {
        if (strokes.isEmpty()) return
        strokes.clear()
        invalidate()
        onStrokeChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (stroke in strokes) {
            if (stroke.isEmpty()) continue
            path.reset()
            path.moveTo(stroke[0].x, stroke[0].y)
            for (i in 1 until stroke.size) {
                path.lineTo(stroke[i].x, stroke[i].y)
            }
            canvas.drawPath(path, paint)
        }
    }

    /**
     * 排除系统手势拦截（2026-11-16 爸爸反馈「有些笔画写上去没反应」的根因）。
     * 全面屏手势导航在屏幕左右各保留约 82px 的返回手势区，覆盖画板边缘；
     * 手指从边缘起笔会被系统当成「返回」手势抢走，画板收不到 → 笔画丢失。
     * adb input 注入不经过系统手势判定，故注入测试全宽可写、真机边缘丢笔。
     * 把整个画板设为手势排除区，边缘笔画也能正常落笔。画板高约 166dp < 系统
     * 单边 200dp 上限，整块排除有效。API 29+ 才有此接口。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onNewStrokeStart?.invoke()
                activePointerId = event.getPointerId(0)
                strokes.add(mutableListOf(InkPoint(event.x, event.y)))
                HwDebugLog.log(context, "pad DOWN x=${event.x.toInt()} y=${event.y.toInt()} ptr=$activePointerId")
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePointerId < 0 || strokes.isEmpty()) return false
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return false
                val stroke = strokes.last()
                val x = event.getX(index)
                val y = event.getY(index)
                val last = stroke.last()
                // 最小间距过滤：去掉抖动重复点，保留笔迹形状。
                val dx = x - last.x
                val dy = y - last.y
                if (dx * dx + dy * dy >= 4f) {
                    stroke.add(InkPoint(x, y))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activePointerId < 0) return false
                val index = event.findPointerIndex(activePointerId)
                if (index >= 0 && strokes.isNotEmpty()) {
                    val stroke = strokes.last()
                    stroke.add(InkPoint(event.getX(index), event.getY(index)))
                }
                activePointerId = -1
                invalidate()
                HwDebugLog.log(context, "pad UP strokes=${strokes.size} lastPts=${strokes.lastOrNull()?.size ?: 0}")
                onStrokeChanged?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}