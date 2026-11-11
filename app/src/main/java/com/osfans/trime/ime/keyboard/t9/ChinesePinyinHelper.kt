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
    ): Array<CandidateProto> = filterAndSortCandidatesWithIndices(candidates, targetPinyin).candidates

    /**
     * 同 [filterAndSortCandidates]，但同时返回「显示位置 → 原始候选下标」的映射。
     *
     * 九宫格最左侧拼音组合触发的只是**展示层重排**，Rime 引擎里的候选顺序没有变。
     * 点击候选词时必须用原始下标去 select/delete，否则会选中原始顺序里同一位置的词
     * （shequ 选「社区」却上屏「切入」、xuzhou 选「徐州」却上屏「宇宙」就是这个问题）。
     */
    fun filterAndSortCandidatesWithIndices(
        candidates: Array<CandidateProto>,
        targetPinyin: String,
    ): FilteredCandidates {
        if (targetPinyin.isEmpty() || candidates.isEmpty()) {
            return FilteredCandidates(candidates, IntArray(candidates.size) { it })
        }

        val matched = mutableListOf<CandidateProto>()
        val matchedIndices = mutableListOf<Int>()
        val others = mutableListOf<CandidateProto>()
        val otherIndices = mutableListOf<Int>()

        candidates.forEachIndexed { index, cand ->
            if (isMatch(cand.text, targetPinyin)) {
                matched.add(cand)
                matchedIndices.add(index)
            } else {
                others.add(cand)
                otherIndices.add(index)
            }
        }

        return FilteredCandidates(
            (matched + others).toTypedArray(),
            (matchedIndices + otherIndices).toIntArray(),
        )
    }

    /** 过滤重排后的候选列表及其到原始候选下标的映射。 */
    data class FilteredCandidates(
        val candidates: Array<CandidateProto>,
        val originalIndices: IntArray,
    ) {
        /** 显示位置 → 原始候选下标；越界时原样返回（等同于未过滤）。 */
        fun originalIndex(displayPosition: Int): Int = originalIndices.getOrNull(displayPosition) ?: displayPosition

        /** 原始高亮下标 → 显示位置；不在当前列表里返回 -1（不显示高亮）。 */
        fun displayIndex(originalIndex: Int): Int = originalIndices.indexOf(originalIndex)
    }

    /**
     * 仅供单元测试：临时替换拼音表，执行完自动还原，避免污染其它用例。
     */
    internal fun <T> withPinyinTableForTest(
        table: Map<Char, String>,
        block: () -> T,
    ): T {
        val backup = HashMap(hanziPinyinMap)
        hanziPinyinMap.clear()
        hanziPinyinMap.putAll(table)
        try {
            return block()
        } finally {
            hanziPinyinMap.clear()
            hanziPinyinMap.putAll(backup)
        }
    }
}
