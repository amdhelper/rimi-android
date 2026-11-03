/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.rimi

import com.osfans.trime.R
import com.osfans.trime.util.appContext
import java.io.File
import java.security.MessageDigest

/**
 * BIP39 助记词（中文简体词表）→ wumi 账户 seed。
 *
 * wumi / zen_auth 的口径（与 rimi 桌面面板逐字节一致）：
 *   16 字节熵 ↔ 12 个中文词；seed = SHA256(entropy)，再用 seed 派 Ed25519。
 *
 * 纯解析逻辑与 Android 资源解耦，便于 JVM 单测（见 RimiBip39Test）。
 */
object RimiBip39 {
    private const val WORDLIST_ASSET = R.raw.bip39_zh

    val wordlist: List<String> by lazy {
        val raw =
            appContext.resources.openRawResource(WORDLIST_ASSET).bufferedReader().use {
                it.readText()
            }
        parseWordlist(raw)
    }

    /** 把助记词文本转成 32 字节 seed；格式不对或校验位不符会抛 IllegalArgumentException。 */
    fun seedFromMnemonic(text: String): ByteArray = seedFromMnemonic(
        text.trim().split(Regex("[\\s,，、]+")).filter { it.isNotEmpty() },
        wordlist,
    )

    /**
     * 词表是扁平 JSON 字符串数组（2048 个中文词，无转义），用最小解析避免
     * org.json 在 JVM 单测里 “not mocked”。
     */
    fun parseWordlist(rawJson: String): List<String> = rawJson
        .trim()
        .removePrefix("[")
        .removeSuffix("]")
        .split(",")
        .map { it.trim().trim('"') }
        .filter { it.isNotEmpty() }

    /** 词表里是否存在该词（供登录界面做输入提示）。 */
    fun isKnownWord(word: String): Boolean = wordlist.contains(word)

    val size: Int get() = wordlist.size

    /** 纯函数版本：words 为助记词，list 为 2048 词表。 */
    fun seedFromMnemonic(
        words: List<String>,
        list: List<String>,
    ): ByteArray {
        require(words.size in intArrayOf(12, 15, 18, 21, 24)) {
            "助记词个数应为 12/15/18/21/24，当前 ${words.size} 个"
        }
        require(list.size == 2048) { "BIP39 词表必须为 2048 词，当前 ${list.size}" }
        val totalBits = words.size * 11
        val checksumBits = totalBits / 33
        val entropyBits = totalBits - checksumBits
        val bits = IntArray(totalBits)
        words.forEachIndexed { i, word ->
            val idx = list.indexOf(word)
            require(idx >= 0) { "词表里没有「$word」" }
            for (j in 0 until 11) {
                bits[i * 11 + j] = (idx shr (10 - j)) and 1
            }
        }
        val entropy = ByteArray(entropyBits / 8)
        for (i in 0 until entropyBits) {
            if (bits[i] == 1) {
                entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - (i % 8)))).toByte()
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(entropy)
        for (i in 0 until checksumBits) {
            val expected = (digest[i / 8].toInt() shr (7 - (i % 8))) and 1
            require(expected == bits[entropyBits + i]) { "助记词校验位不匹配，请检查是否输错词或顺序" }
        }
        // wumi 口径：seed = SHA256(entropy)，不是 BIP39 的 PBKDF2
        return MessageDigest.getInstance("SHA-256").digest(entropy)
    }

    /** 测试/工具用：从文件加载词表（app 运行时不走这里）。 */
    internal fun loadWordlistFromFile(file: File): List<String> = parseWordlist(file.readText())
}
