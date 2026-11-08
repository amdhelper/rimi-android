// SPDX-FileCopyrightText: 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.prefs

import kotlin.math.roundToInt

/**
 * 候选词显示倍数（1× ~ 4×，步长 0.5×，默认 2×）。
 *
 * 键盘工具栏左上角三点按钮右侧的「N×」按钮改的就是这个值；改动会写进
 * [AppPrefs]，并触发 [com.osfans.trime.ime.core.TrimeInputMethodService]
 * 重建输入视图，让候选词按新倍数排版。
 */
object CandidateScale {
    const val MIN = 1.0f
    const val MAX = 4.0f
    const val STEP = 0.5f
    const val DEFAULT = 2.0f

    private const val EPSILON = 1e-3f

    private val pref: PreferenceDelegate<Float>
        get() = AppPrefs.defaultInstance().candidates.candidateScale

    /** 当前倍数；偏好未初始化时退回默认值，避免视图层崩掉。 */
    fun current(): Float = runCatching { normalize(pref.getValue()) }.getOrDefault(DEFAULT)

    fun set(value: Float) {
        runCatching { pref.setValue(normalize(value)) }
    }

    /** 下一档；到顶回到 1×（单击加、长按减）。 */
    fun next(value: Float = current()): Float = if (value >= MAX - EPSILON) MIN else normalize(value + STEP)

    /** 上一档；到底回到 4×。 */
    fun previous(value: Float = current()): Float = if (value <= MIN + EPSILON) MAX else normalize(value - STEP)

    /** 对齐到 0.5 档位并夹在 1× ~ 4×。 */
    fun normalize(value: Float): Float = (value.coerceIn(MIN, MAX) / STEP).roundToInt() * STEP

    /** 按钮上的短标签：`1×`、`1.5×`、`2×`…… */
    fun label(value: Float = current()): String {
        val v = normalize(value)
        return if (v % 1f == 0f) "${v.toInt()}×" else "$v×"
    }
}
