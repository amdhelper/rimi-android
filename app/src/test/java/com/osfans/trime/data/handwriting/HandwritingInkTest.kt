/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.handwriting

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

/** 笔画→JNI 载荷编码的纯 JVM 测试（与 rimi_handwriting.cc fillCharacter 对齐）。 */
class HandwritingInkTest :
    BehaviorSpec({
        Given("空输入") {
            Then("无笔画返回 null") {
                HandwritingInk.encode(emptyList()).shouldBeNull()
            }
            Then("全空笔画返回 null") {
                HandwritingInk.encode(listOf(emptyList(), emptyList())).shouldBeNull()
            }
        }

        Given("一条两点的笔画") {
            val ink = HandwritingInk.encode(listOf(listOf(InkPoint(10f, 20f), InkPoint(30f, 40f))))
            Then("扁平坐标 x,y 交替") {
                ink.shouldNotBeNull()
                ink.points.toList() shouldBe listOf(10f, 20f, 30f, 40f)
                ink.strokeLengths.toList() shouldBe listOf(2)
            }
        }

        Given("多条笔画") {
            val ink =
                HandwritingInk.encode(
                    listOf(
                        listOf(InkPoint(0f, 0f), InkPoint(1f, 1f), InkPoint(2f, 2f)),
                        listOf(InkPoint(5f, 5f), InkPoint(6f, 6f)),
                    ),
                )
            Then("点数与顺序正确") {
                ink.shouldNotBeNull()
                ink.strokeLengths.toList() shouldBe listOf(3, 2)
                ink.points.size shouldBe 10
                ink.points[6] shouldBe 5f
                ink.points[7] shouldBe 5f
            }
        }

        Given("单点笔画（点「丶」）") {
            val ink = HandwritingInk.encode(listOf(listOf(InkPoint(50f, 60f))))
            Then("补微偏移重复点，保证 zinnia 方向特征可算") {
                ink.shouldNotBeNull()
                ink.strokeLengths.toList() shouldBe listOf(2)
                ink.points.toList() shouldBe listOf(50f, 60f, 50.5f, 60f)
            }
        }

        Given("混有空笔画") {
            val ink =
                HandwritingInk.encode(
                    listOf(emptyList(), listOf(InkPoint(1f, 2f), InkPoint(3f, 4f)), emptyList()),
                )
            Then("空笔画被过滤") {
                ink.shouldNotBeNull()
                ink.strokeLengths.toList() shouldBe listOf(2)
                ink.points.toList() shouldBe listOf(1f, 2f, 3f, 4f)
            }
        }
    })