// SPDX-FileCopyrightText: 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.theme.model

import com.osfans.trime.data.prefs.CandidateScale
import kotlin.math.roundToInt

/**
 * 候选栏尺寸 = 主题值 × 用户设定的候选词倍数（[CandidateScale]）。
 *
 * 主题模型本身保持原样（测试与主题选择器读的是原始值），只在视图层用这些
 * 派生尺寸，这样调倍数不需要改主题、也不会污染主题基线断言。
 */

val GeneralStyle.candidateScaleFactor: Float get() = CandidateScale.current()

val GeneralStyle.scaledCandidateTextSize: Float get() = candidateTextSize * candidateScaleFactor

val GeneralStyle.scaledCommentTextSize: Float get() = commentTextSize * candidateScaleFactor

val GeneralStyle.scaledCandidateViewHeight: Int
    get() = (candidateViewHeight * candidateScaleFactor).roundToInt()

val GeneralStyle.scaledCommentHeight: Int
    get() = (commentHeight * candidateScaleFactor).roundToInt()

/** 候选栏整行高度（候选区 + 编码提示区）。 */
val GeneralStyle.scaledCandidateBarHeight: Int
    get() = scaledCandidateViewHeight + scaledCommentHeight
