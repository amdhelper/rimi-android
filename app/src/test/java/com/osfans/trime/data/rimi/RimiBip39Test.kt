/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.rimi

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * 钉住「wumi 助记词 → seed」口径，防止以后替换 BIP39/SHA256 实现时漂移。
 *
 * 向量由独立实现（Python + 同一份 2048 词表）生成：
 *   entropy = 000102030405060708090a0b0c0d0e0f
 *   mnemonic = 「的 三 欧 三 考 于 据 保 量 损 破 战」
 *   seed = SHA256(entropy)（wumi 口径，非 PBKDF2）
 */
class RimiBip39Test :
    BehaviorSpec({
        val root = File(".").absoluteFile
        val candidates =
            listOf(
                File(root, "src/main/res/raw/bip39_zh.json"),
                File(root, "app/src/main/res/raw/bip39_zh.json"),
            )
        val wordlistFile = candidates.firstOrNull { it.exists() }

        Given("中文 BIP39 词表") {
            Then("词表必须是 2048 词") {
                requireNotNull(wordlistFile) { "找不到 bip39_zh.json（cwd=$root）" }
                RimiBip39.loadWordlistFromFile(wordlistFile).size shouldBe 2048
            }
            Then("固定向量：12 词 → wumi seed") {
                requireNotNull(wordlistFile)
                val words =
                    RimiBip39.loadWordlistFromFile(wordlistFile)
                val mnemonic = "的 三 欧 三 考 于 据 保 量 损 破 战".split(" ")
                RimiBip39.seedFromMnemonic(mnemonic, words).joinToString("") { "%02x".format(it) } shouldBe
                    "be45cb2605bf36bebde684841a28f0fd43c69850a3dce5fedba69928ee3a8991"
            }
            Then("校验位错误必须拒绝") {
                requireNotNull(wordlistFile)
                val words = RimiBip39.loadWordlistFromFile(wordlistFile)
                // 把最后一个词换成「一」（index 1）→ 校验位不匹配
                val wrong = "的 三 欧 三 考 于 据 保 量 损 破 一".split(" ")
                shouldThrow<IllegalArgumentException> {
                    RimiBip39.seedFromMnemonic(wrong, words)
                }
            }
            Then("词数非法必须拒绝") {
                requireNotNull(wordlistFile)
                val words = RimiBip39.loadWordlistFromFile(wordlistFile)
                shouldThrow<IllegalArgumentException> {
                    RimiBip39.seedFromMnemonic(listOf("的", "三"), words)
                }
            }
        }
    })
