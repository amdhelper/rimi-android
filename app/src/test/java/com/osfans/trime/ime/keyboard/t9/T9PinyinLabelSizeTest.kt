/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard.t9

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class T9PinyinLabelSizeTest :
    BehaviorSpec({
        given("T9PinyinLabelSize base size tiers") {
            `when`("label is short") {
                then("uses the largest base size") {
                    T9PinyinLabelSize.baseSize("ni") shouldBe 19f
                }
            }

            `when`("label is medium length") {
                then("steps down once") {
                    T9PinyinLabelSize.baseSize("ni'h") shouldBe 16f
                }
            }

            `when`("label is long") {
                then("uses the smallest base size") {
                    T9PinyinLabelSize.baseSize("ni'hao") shouldBe 13f
                    T9PinyinLabelSize.baseSize("zhong'guo") shouldBe 13f
                }
            }
        }

        given("T9PinyinLabelSize scales with the candidate scale factor") {
            `when`("scale is 1x") {
                then("keeps the base size") {
                    T9PinyinLabelSize.sizeFor("ni", 1f) shouldBe 19f
                    T9PinyinLabelSize.sizeFor("ni'hao", 1f) shouldBe 13f
                }
            }

            `when`("scale is 2x (default)") {
                then("doubles the base size, same multiplier as candidates") {
                    T9PinyinLabelSize.sizeFor("ni", 2f) shouldBe 38f
                    T9PinyinLabelSize.sizeFor("ni'h", 2f) shouldBe 32f
                    T9PinyinLabelSize.sizeFor("ni'hao", 2f) shouldBe 26f
                }
            }

            `when`("scale is 4x") {
                then("quadruples the base size") {
                    T9PinyinLabelSize.sizeFor("ni", 4f) shouldBe 76f
                }
            }

            `when`("scale is invalid (0 or negative)") {
                then("clamps to a positive minimum instead of producing 0") {
                    (T9PinyinLabelSize.sizeFor("ni", 0f) > 0f) shouldBe true
                    (T9PinyinLabelSize.sizeFor("ni'hao", -2f) > 0f) shouldBe true
                }
            }
        }
    })
