/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard.t9

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class T9PinyinMatcherTest :
    BehaviorSpec({
        given("T9PinyinMatcher with various input digits") {
            `when`("input is empty or non-digit") {
                then("returns empty list") {
                    T9PinyinMatcher.matchCandidates("") shouldBe emptyList()
                    T9PinyinMatcher.matchCandidates("abc") shouldBe emptyList()
                    T9PinyinMatcher.matchCandidates("1") shouldBe emptyList()
                }
            }

            `when`("input is a single digit") {
                then("returns the corresponding letters on that key") {
                    T9PinyinMatcher.matchCandidates("2") shouldBe listOf("a", "b", "c")
                    T9PinyinMatcher.matchCandidates("3") shouldBe listOf("d", "e", "f")
                    T9PinyinMatcher.matchCandidates("4") shouldBe listOf("g", "h", "i")
                    T9PinyinMatcher.matchCandidates("6") shouldBe listOf("m", "n", "o")
                    T9PinyinMatcher.matchCandidates("9") shouldBe listOf("w", "x", "y", "z")
                }
            }

            `when`("input is two digits matching standard syllables") {
                then("matches syllables like ni and mi for 64") {
                    val cands64 = T9PinyinMatcher.matchCandidates("64")
                    cands64.take(2) shouldBe listOf("ni", "mi")
                }

                then("matches ai, bi, ci and initial ch for 24") {
                    val cands24 = T9PinyinMatcher.matchCandidates("24")
                    cands24 shouldContain "ai"
                    cands24 shouldContain "bi"
                    cands24 shouldContain "ci"
                    cands24 shouldContain "ch"
                }

                then("matches xi, yi, zi, zh for 94") {
                    val cands94 = T9PinyinMatcher.matchCandidates("94")
                    cands94 shouldContain "xi"
                    cands94 shouldContain "yi"
                    cands94 shouldContain "zh"
                }
            }

            `when`("input represents multi-syllable phrase like 64426 (ni'hao)") {
                then("finds high-frequency two-syllable segmentation ni'hao first") {
                    val cands = T9PinyinMatcher.matchCandidates("64426")
                    cands.first() shouldBe "ni'hao"
                    cands shouldContain "mi'hao"
                }
            }

            `when`("input represents medical phrase like 224948 (bai'zhu)") {
                then("finds bai'zhu combination") {
                    val cands = T9PinyinMatcher.matchCandidates("224948")
                    cands.first() shouldBe "bai'zhu"
                }
            }

            `when`("input has spaces or separators from preedit") {
                then("filters them and matches correctly") {
                    val cands = T9PinyinMatcher.matchCandidates("64 426")
                    cands.first() shouldBe "ni'hao"
                }
            }
        }
    })
