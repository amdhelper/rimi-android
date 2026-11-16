/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yuyan.imemodule.handwriting.internal

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 汉字约束 CTC 解码（2026-11-16 爸爸反馈「灿卡住不识别」）。
 *
 * 真机取证：爸爸行草「灿」（火旁像 K、山写成 U）被 PP-OCRv5 读成 ku/K（0.76），
 * 纯汉字过滤后=空候选。手写只收汉字 → 解码每步只在汉字类里选 top-k（语言先验），
 * 逼模型给出最接近的汉字。本测试用合成概率矩阵锁住该行为：
 * 拉丁概率占优时，无约束解码出拉丁，约束解码出汉字。
 */
class CtcDecoderHanConstraintTest :
    BehaviorSpec({
        // 类别布局：0=blank, 1..7=字典, 8=空格（dictionary.size+2）
        val dict = listOf("灿", "你", "山", "火", "K", "u", "A")
        val classes = dict.size + 2
        val decoder = CtcDecoder(dict)

        fun row(vararg p: Float): FloatArray = p

        // 两个时间步：拉丁 K/u 概率占优，汉字 灿 次之（模拟真机 ku:0.76 场景）
        val output = floatArrayOf(
            // t0: blank, 灿, 你, 山, 火, K, u, A, space
            0.05f, 0.25f, 0.05f, 0.05f, 0.05f, 0.35f, 0.15f, 0.03f, 0.02f,
            // t1
            0.05f, 0.30f, 0.05f, 0.05f, 0.05f, 0.30f, 0.15f, 0.03f, 0.02f,
        )

        Given("掩码构造") {
            val mask = CtcDecoder.hanAllowedMask(dict, classes)
            Then("blank 恒允许，汉字允许，拉丁/空格屏蔽") {
                mask[0] shouldBe true
                mask[1] shouldBe true // 灿
                mask[2] shouldBe true // 你
                mask[5] shouldBe false // K
                mask[6] shouldBe false // u
                mask[7] shouldBe false // A
                mask[8] shouldBe false // 额外空格类
            }
        }

        Given("拉丁占优的合成输出") {
            Then("无约束解码出拉丁（复现真机 ku/K 病灶）") {
                val r = decoder.decodeBeam(output, 2, classes)
                // 两步 K 被 CTC 合并成单 K（比 Ku 概率高）——拉丁占优即病灶
                r.first().text shouldBe "K"
            }
            Then("汉字约束解码出汉字（根治：逼模型在汉字里找最接近字形）") {
                val mask = CtcDecoder.hanAllowedMask(dict, classes)
                val r = decoder.decodeBeam(output, 2, classes, allowed = mask)
                r.first().text shouldBe "灿"
                r.all { it.text.all { c -> CtcDecoder.isHanChar(c) } } shouldBe true
            }
        }
    })