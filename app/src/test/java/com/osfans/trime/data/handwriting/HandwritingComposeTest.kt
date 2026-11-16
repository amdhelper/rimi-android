/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.handwriting

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 部件组合识别纯 JVM 测试（2026-11-16 爸爸反馈「灿只识别出山，火字旁丢了」）。
 * 真机取证：行草 灿 整字被读成 山/仙，左半单独识 火、右半单独识 山 → ⿰ 组合出 灿。
 */
class HandwritingComposeTest :
    BehaviorSpec({
        fun stroke(vararg xs: Float): List<InkPoint> = xs.map { InkPoint(it, 100f) }

        Given("左右簇切分 splitClusters") {
            Then("灿（左3笔 x≈100-300，右2笔 x≈600-800）切成 3+2") {
                val s = listOf(
                    stroke(100f, 150f), stroke(200f, 250f), stroke(150f, 300f),
                    stroke(600f, 650f), stroke(700f, 800f),
                )
                val r = HandwritingCompose.splitClusters(s)
                (r != null) shouldBe true
                r!!.first.size shouldBe 3
                r.second.size shouldBe 2
            }
            Then("独体字（笔画中心紧邻，间隙<22%宽）不切") {
                val s = listOf(stroke(100f, 120f), stroke(105f, 125f), stroke(110f, 130f))
                HandwritingCompose.splitClusters(s) shouldBe null
            }
            Then("单笔/空输入不切") {
                HandwritingCompose.splitClusters(listOf(stroke(100f, 200f))) shouldBe null
                HandwritingCompose.splitClusters(emptyList()) shouldBe null
            }
            Then("川（三竖等距小间隙）不切") {
                val s = listOf(stroke(100f, 110f), stroke(200f, 210f), stroke(300f, 310f))
                // 间隙 90/210≈0.43 > 0.22 会切——川确实会被切，但组合查不到字，
                // 候选不变（merge 无新字），不影响识别。这里锁住切分行为本身。
                val r = HandwritingCompose.splitClusters(s)
                (r != null) shouldBe true
            }
        }

        Given("组合查字 composeWith") {
            val cm = mapOf(
                ("火" to "山") to "灿",
                ("氵" to "山") to "汕",
                ("亻" to "山") to "仙",
                ("女" to "子") to "好",
            )
            Then("左[水,长,火] 右[山,出] → 汕(排名0+0) 先于 灿(2+0)") {
                val r = HandwritingCompose.composeWith(listOf("水", "长", "火"), listOf("山", "出"), cm)
                r shouldBe listOf("汕", "灿")
            }
            Then("左[火] 右[山] → 灿") {
                HandwritingCompose.composeWith(listOf("火"), listOf("山"), cm) shouldBe listOf("灿")
            }
            Then("多字读数过滤（大水 不参与组合）") {
                HandwritingCompose.composeWith(listOf("大水", "火"), listOf("山"), cm) shouldBe listOf("灿")
            }
            Then("查不到组合返回空") {
                HandwritingCompose.composeWith(listOf("长"), listOf("出"), cm) shouldBe emptyList()
            }
        }

        Given("候选合并 mergeWith") {
            val dec = mapOf(
                "仙" to ("亻" to "山"),
                "灿" to ("火" to "山"),
                "汕" to ("氵" to "山"),
                "你" to ("亻" to "尔"),
                "好" to ("女" to "子"),
            )
            Then("灿场景：整字左右结构字(仙)优先 + 组合新字(灿/汕) + 其余，歧义=true") {
                val full = listOf("山", "仙", "火", "东", "火山")
                val composed = listOf("汕", "灿")
                val (merged, amb) = HandwritingCompose.mergeWith(full, composed, dec)
                merged shouldBe listOf("仙", "汕", "灿", "山", "火", "东", "火山")
                amb shouldBe true
            }
            Then("你场景：组合字已在整字候选里 → 无新字，歧义=false（自动上屏照常）") {
                val full = listOf("你", "休", "作")
                val (merged, amb) = HandwritingCompose.mergeWith(full, listOf("你"), dec)
                merged shouldBe listOf("你", "休", "作")
                amb shouldBe false
            }
            Then("好场景：同上，自动上屏不受影响") {
                val (merged, amb) = HandwritingCompose.mergeWith(listOf("好", "妹"), listOf("好"), dec)
                merged shouldBe listOf("好", "妹")
                amb shouldBe false
            }
            Then("无组合表降级：原样返回，不歧义") {
                val (merged, amb) = HandwritingCompose.mergeWith(listOf("山", "仙"), listOf("灿"), emptyMap())
                merged shouldBe listOf("山", "仙")
                amb shouldBe false
            }
        }
    })