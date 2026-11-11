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

            `when`("filtering candidates with a selected T9 pinyin") {
                then("returns identity mapping when no pinyin is selected") {
                    val cands = arrayOf(
                        CandidateProto("切入", "", "1"),
                        CandidateProto("社区", "", "2"),
                    )
                    val filtered = ChinesePinyinHelper.filterAndSortCandidatesWithIndices(cands, "")
                    filtered.candidates shouldBe cands
                    filtered.originalIndices.toList() shouldBe listOf(0, 1)
                }

                then("maps 社区 back to its original Rime index (shequ)") {
                    val table = mapOf('切' to "qie", '入' to "ru", '社' to "she", '区' to "qu")
                    ChinesePinyinHelper.withPinyinTableForTest(table) {
                        val cands = arrayOf(
                            CandidateProto("切入", "", "1"),
                            CandidateProto("社区", "", "2"),
                        )
                        val filtered =
                            ChinesePinyinHelper.filterAndSortCandidatesWithIndices(cands, "she'qu")
                        filtered.candidates.map { it.text } shouldBe listOf("社区", "切入")
                        filtered.originalIndices.toList() shouldBe listOf(1, 0)
                        // 点击显示位置 0 必须选中原始下标 1（社区），不能是切入
                        filtered.originalIndex(0) shouldBe 1
                        filtered.displayIndex(0) shouldBe 1
                    }
                }

                then("maps 徐州 back to its original Rime index (xuzhou)") {
                    val table = mapOf('宇' to "yu", '宙' to "zhou", '徐' to "xu", '州' to "zhou")
                    ChinesePinyinHelper.withPinyinTableForTest(table) {
                        val cands = arrayOf(
                            CandidateProto("宇宙", "", "1"),
                            CandidateProto("徐州", "", "2"),
                        )
                        val filtered =
                            ChinesePinyinHelper.filterAndSortCandidatesWithIndices(cands, "xu'zhou")
                        filtered.candidates.map { it.text } shouldBe listOf("徐州", "宇宙")
                        filtered.originalIndices.toList() shouldBe listOf(1, 0)
                        filtered.originalIndex(0) shouldBe 1
                        // 原始高亮在 0（宇宙），重排后应显示在位置 1
                        filtered.displayIndex(filtered.originalIndex(0)) shouldBe 0
                    }
                }

                then("restores the pinyin table after the test hook") {
                    ChinesePinyinHelper.isMatch("社区", "she'qu") shouldBe false
                }
            }
        }
    })
