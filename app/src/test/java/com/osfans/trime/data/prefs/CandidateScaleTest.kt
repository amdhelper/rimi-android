/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.prefs

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 钉住候选词倍数的档位规则：1× ~ 4×、0.5× 步长、默认 2×。
 * 这些方法不碰 SharedPreferences，可在纯 JVM 测试里跑。
 */
class CandidateScaleTest :
    BehaviorSpec({
        Given("候选词倍数") {
            Then("默认是 2×") {
                CandidateScale.DEFAULT shouldBe 2f
            }
            Then("范围是 1× ~ 4×、步长 0.5×") {
                CandidateScale.MIN shouldBe 1f
                CandidateScale.MAX shouldBe 4f
                CandidateScale.STEP shouldBe 0.5f
            }
        }

        Given("normalize") {
            Then("对齐到 0.5 档位") {
                CandidateScale.normalize(0.2f) shouldBe 1f
                CandidateScale.normalize(1.4f) shouldBe 1.5f
                CandidateScale.normalize(2.7f) shouldBe 2.5f
            }
            Then("夹在 1× ~ 4× 之间") {
                CandidateScale.normalize(-3f) shouldBe 1f
                CandidateScale.normalize(9f) shouldBe 4f
            }
        }

        Given("next / previous") {
            Then("每次走 0.5×") {
                CandidateScale.next(2f) shouldBe 2.5f
                CandidateScale.previous(2f) shouldBe 1.5f
            }
            Then("两端循环") {
                CandidateScale.next(4f) shouldBe 1f
                CandidateScale.previous(1f) shouldBe 4f
            }
        }

        Given("label") {
            Then("整数不带小数点、半档保留一位") {
                CandidateScale.label(1f) shouldBe "1×"
                CandidateScale.label(2f) shouldBe "2×"
                CandidateScale.label(2.5f) shouldBe "2.5×"
                CandidateScale.label(4f) shouldBe "4×"
            }
        }
    })
