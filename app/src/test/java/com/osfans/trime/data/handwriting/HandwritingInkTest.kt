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

        // ---- 归一化（2026-11-15 手写候选全错的根治）----
        // zinnia 各轴独立归一化：传画板像素尺寸会把方形字压扁，且对位置/大小敏感。
        Given("方形墨迹 200×200 的归一化") {
            val ink =
                HandwritingInk
                    .encodeNormalized(listOf(listOf(InkPoint(500f, 200f), InkPoint(700f, 400f))), margin = 0.15f)
                    .shouldNotBeNull()
            Then("方框边长 = 长边 × (1 + 2×margin)") {
                ink.side shouldBe 260
            }
            Then("居中并留 15% 边距（两点落在 30 与 230）") {
                ink.points.toList() shouldBe listOf(30f, 30f, 230f, 230f)
            }
        }

        Given("宽扁墨迹 700×20（写「一」）") {
            val ink =
                HandwritingInk
                    .encodeNormalized(listOf(listOf(InkPoint(100f, 300f), InkPoint(800f, 320f))), margin = 0.15f)
                    .shouldNotBeNull()
            Then("等比缩放：横跨度与纵跨度都保持原值，不被拉伸成方形") {
                (ink.points[2] - ink.points[0]) shouldBe 700f
                (ink.points[3] - ink.points[1]) shouldBe 20f
            }
            Then("方框边长仍由长边决定") {
                ink.side shouldBe 910
            }
        }

        Given("同一字形写在画板不同位置 / 不同大小") {
            val shape = listOf(listOf(InkPoint(0f, 0f), InkPoint(100f, 0f)), listOf(InkPoint(50f, -80f), InkPoint(50f, 80f)))
            fun shift(dx: Float, dy: Float, scale: Float) =
                shape.map { s -> s.map { InkPoint(it.x * scale + dx, it.y * scale + dy) } }
            // 归一化坐标 = 点 / side（zinnia 内部就是这样除的），据此判"位置/大小无关"。
            fun unit(ink: HandwritingInk.Normalized) = ink.points.map { it / ink.side }
            fun expectNear(a: List<Float>, b: List<Float>) {
                a.size shouldBe b.size
                a.indices.forEach { i -> (kotlin.math.abs(a[i] - b[i]) < 0.01f) shouldBe true }
            }
            val a = HandwritingInk.encodeNormalized(shift(100f, 400f, 1f)).shouldNotBeNull()
            val b = HandwritingInk.encodeNormalized(shift(900f, 120f, 1f)).shouldNotBeNull()
            val c = HandwritingInk.encodeNormalized(shift(10f, 10f, 3f)).shouldNotBeNull()
            Then("归一化结果完全一致（位置无关）") {
                expectNear(unit(a), unit(b))
            }
            Then("放大 3 倍后仍一致（大小无关，且边长为 3 倍）") {
                expectNear(unit(a), unit(c))
                c.side shouldBe a.side * 3
            }
        }

        Given("归一化空输入") {
            Then("归一化：无笔画返回 null") {
                HandwritingInk.encodeNormalized(emptyList()).shouldBeNull()
            }
        }
    })