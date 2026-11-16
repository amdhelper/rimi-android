/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yuyan.imemodule.handwriting.internal

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * 渲染缩放决策的纯 JVM 行为测试（2026-11-16 爸爸反馈「灿经常打成火山 / 写宽了一动不动」）。
 *
 * 根因：旧渲染保留墨迹宽高比，写宽的左右结构字（灿）渲染成宽图，
 * PP-OCRv5（场景文字 OCR）把宽图读成两个字（火山），更宽时吐噪声被滤光=无候选。
 * 修法：单字模式（expectedChars=1）宽墨迹横向压回方形（汉字是方块字）；
 * 扁笔画（一）与多字模式（expectedChars>1）保持原有行为。
 *
 * computeRenderScales 是纯算术（不碰 Android API），这里直接断言缩放结果。
 * 常量：contentHeight = 48×0.84 = 40.32；单字最小内容宽 = 48×0.72 = 34.56。
 */
class ImagePreprocessorScaleTest :
    BehaviorSpec({
        val contentHeight = 48 * 0.84f // 40.32
        fun near(a: Float, b: Float): Boolean = abs(a - b) < 0.01f

        Given("单字模式：宽墨迹压回方形（防 灿→火山）") {
            Then("宽字（bbox 200×100，6 笔）渲染内容为正方形") {
                val s = ImagePreprocessor.computeRenderScales(200f, 100f, 48, 320, 1, 6)
                near(200f * s.scaleX, contentHeight) shouldBe true
                near(100f * s.scaleY, contentHeight) shouldBe true
                s.widthLimit shouldBe 320
            }
            Then("略宽字（120×100）同样压回方形") {
                val s = ImagePreprocessor.computeRenderScales(120f, 100f, 48, 320, 1, 5)
                near(120f * s.scaleX, 100f * s.scaleY) shouldBe true
            }
            Then("极宽字（1000×100，超宽度上限）仍压回方形") {
                val s = ImagePreprocessor.computeRenderScales(1000f, 100f, 48, 320, 1, 6)
                near(1000f * s.scaleX, contentHeight) shouldBe true
                near(100f * s.scaleY, 31.6f) shouldBe true // 高度受宽度上限约束
            }
            Then("二（100×60，2 笔）压回方形") {
                val s = ImagePreprocessor.computeRenderScales(100f, 60f, 48, 320, 1, 2)
                near(100f * s.scaleX, 60f * s.scaleY) shouldBe true
            }
        }

        Given("单字模式：不改变原有正常/窄字行为") {
            Then("方形字（100×100）scaleX == scaleY") {
                val s = ImagePreprocessor.computeRenderScales(100f, 100f, 48, 320, 1, 5)
                near(s.scaleX, s.scaleY) shouldBe true
            }
            Then("窄字（30×100）保持 0.72 最小宽度拉伸，不强行拉成正方形") {
                val s = ImagePreprocessor.computeRenderScales(30f, 100f, 48, 320, 1, 2)
                near(30f * s.scaleX, 48 * 0.72f) shouldBe true
            }
            Then("扁笔画「一」（300×20，1 笔）保持宽渲染，不压方形") {
                val s = ImagePreprocessor.computeRenderScales(300f, 20f, 48, 320, 1, 1)
                s.widthLimit shouldBe 96
                near(s.scaleX, s.scaleY) shouldBe true
                near(300f * s.scaleX, 92f) shouldBe true
            }
        }

        Given("多字模式（expectedChars>1）不受单字方形约束") {
            Then("宽内容（200×100，2 字）保持宽高比") {
                val s = ImagePreprocessor.computeRenderScales(200f, 100f, 48, 320, 2, 6)
                near(s.scaleX, s.scaleY) shouldBe true
                near(200f * s.scaleX, 2 * (100f * s.scaleY)) shouldBe true
            }
        }
    })