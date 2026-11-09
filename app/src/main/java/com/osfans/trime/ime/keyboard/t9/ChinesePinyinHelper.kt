/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard.t9

import android.content.Context
import com.osfans.trime.core.CandidateProto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 汉字单字与词组拼音推导及候选词拼音过滤工具。
 *
 * 优先从 assets/shared/cn_dicts/8105.dict.yaml 异步载入《通用规范汉字表》拼音，
 * 内存占用仅约 100KB。支持候选词拼音推导、拼音前缀匹配与排序。
 */
object ChinesePinyinHelper {

    private val hanziPinyinMap = mutableMapOf<Char, String>()
    @Volatile
    private var isLoaded = false

    /**
     * 异步加载常用字拼音映射表。
     */
    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext
        try {
            val assetPath = "shared/cn_dicts/8105.dict.yaml"
            context.assets.open(assetPath).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line!!.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#") ||
                            trimmed.startsWith("---") || trimmed.startsWith("...")
                        ) {
                            continue
                        }
                        val parts = trimmed.split("\t")
                        if (parts.size >= 2) {
                            val word = parts[0]
                            val pinyin = parts[1].trim()
                            if (word.length == 1 && word[0] !in hanziPinyinMap) {
                                hanziPinyinMap[word[0]] = pinyin
                            }
                        }
                    }
                }
            }
            isLoaded = true
            Timber.i("ChinesePinyinHelper: loaded ${hanziPinyinMap.size} hanzi pinyin items")
        } catch (e: Exception) {
            Timber.w(e, "ChinesePinyinHelper: failed to load 8105.dict.yaml from assets")
        }
    }

    /**
     * 获取词条的推导全拼（以单引号连接每个字的拼音，例如 "ni'hao"）。
     */
    fun getWordPinyin(word: String): String {
        if (word.isEmpty()) return ""
        val syllables = mutableListOf<String>()
        for (ch in word) {
            val py = hanziPinyinMap[ch] ?: return ""
            syllables.add(py)
        }
        return syllables.joinToString("'")
    }

    /**
     * 判断某个候选词是否与目标拼音匹配。
     *
     * 匹配规则：
     * 1. 词的拼音等于 [targetPinyin]（完全匹配）；
     * 2. 或词的拼音以 [targetPinyin] 开头（前缀匹配，例如目标是 "ni"，词是 "ni'hao" 或单字 "ni"）；
     * 3. 或目标拼音包含单引号分节（如 "ni'hao"），词由对应的拼音组成。
     */
    fun isMatch(candidateText: String, targetPinyin: String): Boolean {
        if (candidateText.isEmpty() || targetPinyin.isEmpty()) return false
        val cleanTarget = targetPinyin.replace("'", "")

        val wordPinyin = getWordPinyin(candidateText)
        if (wordPinyin.isNotEmpty()) {
            val cleanWord = wordPinyin.replace("'", "")
            if (cleanWord.startsWith(cleanTarget) || cleanTarget.startsWith(cleanWord)) {
                return true
            }
        }

        // 首字拼音匹配
        val firstCharPy = hanziPinyinMap[candidateText[0]]
        if (firstCharPy != null) {
            val targetFirst = targetPinyin.substringBefore("'")
            if (firstCharPy == targetFirst || firstCharPy.startsWith(targetFirst)) {
                return true
            }
        }

        return false
    }

    /**
     * 根据选定的拼音组合过滤并重排候选词列表：
     * 匹配该拼音的词提升到最前列，其余词跟在后面。
     */
    fun filterAndSortCandidates(
        candidates: Array<CandidateProto>,
        targetPinyin: String,
    ): Array<CandidateProto> {
        if (targetPinyin.isEmpty() || candidates.isEmpty()) return candidates

        val matched = mutableListOf<CandidateProto>()
        val others = mutableListOf<CandidateProto>()

        for (cand in candidates) {
            if (isMatch(cand.text, targetPinyin)) {
                matched.add(cand)
            } else {
                others.add(cand)
            }
        }

        return (matched + others).toTypedArray()
    }
}
