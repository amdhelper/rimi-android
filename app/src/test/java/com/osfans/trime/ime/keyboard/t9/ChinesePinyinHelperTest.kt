/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard.t9

import com.osfans.trime.core.CandidateProto
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ChinesePinyinHelperTest :
    BehaviorSpec({
        given("ChinesePinyinHelper filtering candidates") {
            `when`("candidates list is empty or target pinyin is empty") {
                then("returns unchanged array") {
                    val emptyCands = emptyArray<CandidateProto>()
                    ChinesePinyinHelper.filterAndSortCandidates(emptyCands, "ni") shouldBe emptyCands

                    val cands = arrayOf(CandidateProto("你", "", "1"))
                    ChinesePinyinHelper.filterAndSortCandidates(cands, "") shouldBe cands
                }
            }

            `when`("filtering with empty map before asset load") {
                then("handles gracefully without throwing") {
                    val cands = arrayOf(
                        CandidateProto("你", "", "1"),
                        CandidateProto("米", "", "2"),
                    )
                    ChinesePinyinHelper.isMatch("你", "ni") shouldBe false
                    ChinesePinyinHelper.filterAndSortCandidates(cands, "ni").size shouldBe 2
                }
            }
        }
    })
