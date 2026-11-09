/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard.t9

/**
 * 九宫格最左侧候选拼音标签的字号。
 *
 * 基准字号按拼音长度分档（越长的组合用小一号，保证默认倍数下也不挤），
 * 显示时再乘上候选词倍数（[com.osfans.trime.data.prefs.CandidateScale]）——
 * 与候选词用同一个倍数放大；最终由 KeyView 按按键可用宽高收缩，避免被裁切。
 */
object T9PinyinLabelSize {
    /** 倍数下限，防外部传入 0/负数把字号算成 0。 */
    private const val MIN_SCALE = 0.1f

    /** 未放大时的基准字号（sp）。 */
    fun baseSize(label: String): Float = when {
        label.length <= 2 -> 19f
        label.length <= 4 -> 16f
        else -> 13f
    }

    /** 按候选词倍数放大后的字号（sp）。 */
    fun sizeFor(label: String, scale: Float): Float =
        baseSize(label) * scale.coerceAtLeast(MIN_SCALE)
}
