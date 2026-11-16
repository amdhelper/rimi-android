/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.handwriting

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 笔画→雨燕引擎输入转换的纯 JVM 测试（2026-11-16 换引擎）。
 * 雨燕引擎吃原始视图像素坐标（内部自行 bbox 归一化渲染），
 * 转换层只做：过滤空笔画 + 单点笔画补微偏移重复点。
 */
class HandwritingInkTest :
    BehaviorSpec({
        Given("空输入") {
            Then("无笔画返回空列表") {
                HandwritingInk.toYuyanStrokes(emptyList()) shouldBe emptyList()
            }
            Then("全空笔画返回空列表") {
                HandwritingInk.toYuyanStrokes(listOf(emptyList(), emptyList())) shouldBe emptyList()
            }
        }

        Given("一条两点的笔画") {
            val strokes = HandwritingInk.toYuyanStrokes(listOf(listOf(InkPoint(10f, 20f), InkPoint(30f, 40f))))
            Then("原始坐标原样保留（引擎内部归一化，外部不缩放）") {
                strokes.size shouldBe 1
                strokes[0].points.map { it.x to it.y } shouldBe listOf(10f to 20f, 30f to 40f)
            }
        }

        Given("多条笔画") {
            val strokes =
                HandwritingInk.toYuyanStrokes(
                    listOf(
                        listOf(InkPoint(0f, 0f), InkPoint(1f, 1f), InkPoint(2f, 2f)),
                        listOf(InkPoint(5f, 5f), InkPoint(6f, 6f)),
                    ),
                )
            Then("笔画数与点数、顺序正确") {
                strokes.size shouldBe 2
                strokes[0].points.size shouldBe 3
                strokes[1].points.size shouldBe 2
                strokes[1].points[0].x shouldBe 5f
                strokes[1].points[0].y shouldBe 5f
            }
        }

        Given("单点笔画（点「丶」）") {
            val strokes = HandwritingInk.toYuyanStrokes(listOf(listOf(InkPoint(50f, 60f))))
            Then("补微偏移重复点：ImagePreprocessor 跳过 <2 点路径，不补点则点画不出来") {
                strokes.size shouldBe 1
                strokes[0].points.map { it.x to it.y } shouldBe listOf(50f to 60f, 50.5f to 60f)
            }
        }

        Given("混有空笔画") {
            val strokes =
                HandwritingInk.toYuyanStrokes(
                    listOf(emptyList(), listOf(InkPoint(1f, 2f), InkPoint(3f, 4f)), emptyList()),
                )
            Then("空笔画被过滤") {
                strokes.size shouldBe 1
                strokes[0].points.map { it.x to it.y } shouldBe listOf(1f to 2f, 3f to 4f)
            }
        }

        Given("大坐标（真实画板 1152×582 量级）") {
            val strokes =
                HandwritingInk.toYuyanStrokes(
                    listOf(listOf(InkPoint(900f, 500f), InkPoint(1100f, 560f))),
                )
            Then("不做任何缩放/平移（与 zinnia 时代 encodeNormalized 的本质区别）") {
                strokes[0].points.map { it.x to it.y } shouldBe listOf(900f to 500f, 1100f to 560f)
            }
        }

        Given("汉字候选过滤（去 PP-OCRv5 拉丁/标点噪声）") {
            Then("纯汉字（单字/词语/兼容区/〇）保留") {
                HandwritingInk.isHanText("你") shouldBe true
                HandwritingInk.isHanText("你好") shouldBe true
                HandwritingInk.isHanText("〇") shouldBe true
                HandwritingInk.isHanText("龦") shouldBe true // 扩展 A
            }
            Then("拉丁/标点/符号/混合噪声剔除") {
                HandwritingInk.isHanText("") shouldBe false
                HandwritingInk.isHanText("PA") shouldBe false
                HandwritingInk.isHanText("你.") shouldBe false
                HandwritingInk.isHanText("T你") shouldBe false
                HandwritingInk.isHanText("————") shouldBe false
                HandwritingInk.isHanText("我`") shouldBe false
                HandwritingInk.isHanText("日月 ") shouldBe false
            }
        }
    })
