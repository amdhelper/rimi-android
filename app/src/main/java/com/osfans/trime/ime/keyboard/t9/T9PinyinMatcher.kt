/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard.t9

/**
 * 九宫格（T9）拼音音节与按键候选匹配器。
 *
 * 根据用户输入的 2-9 数字键序列，基于现代汉语拼音音节表（413 个标准音节）
 * 与高频音节权重，计算出最优的候选拼音组合列表（供九宫格最左侧按键展示与选择）。
 */
object T9PinyinMatcher {

    /** 现代汉语拼音全量标准单字音节表（413 个） */
    val SYLLABLES: List<String> = listOf(
        "a", "ai", "an", "ang", "ao",
        "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi", "bian", "biao", "bie", "bin", "bing", "bo", "bu",
        "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "cha", "chai", "chan", "chang", "chao", "che", "chen", "cheng", "chi", "chong", "chou", "chu", "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo", "ci", "cong", "cou", "cu", "cuan", "cui", "cun", "cuo",
        "da", "dai", "dan", "dang", "dao", "de", "dei", "den", "deng", "di", "dia", "dian", "diao", "die", "ding", "diu", "dong", "dou", "du", "duan", "dui", "dun", "duo",
        "e", "ei", "en", "eng", "er",
        "fa", "fan", "fang", "fei", "fen", "feng", "fo", "fou", "fu",
        "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng", "gong", "gou", "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo",
        "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong", "hou", "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo",
        "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu", "ju", "juan", "jue", "jun",
        "ka", "kai", "kan", "kang", "kao", "ke", "kei", "ken", "keng", "kong", "kou", "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
        "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian", "liang", "liao", "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu", "luan", "lue", "lun", "luo", "lv", "lve",
        "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian", "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
        "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni", "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu", "nuan", "nue", "nun", "nuo", "nv", "nve",
        "o", "ou",
        "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian", "piao", "pie", "pin", "ping", "po", "pou", "pu",
        "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu", "qu", "quan", "que", "qun",
        "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "rua", "ruan", "rui", "run", "ruo",
        "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai", "shan", "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou", "shu", "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su", "suan", "sui", "sun", "suo",
        "ta", "tai", "tan", "tang", "tao", "te", "teng", "ti", "tian", "tiao", "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
        "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
        "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu", "xu", "xuan", "xue", "xun",
        "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong", "you", "yu", "yuan", "yue", "yun",
        "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zha", "zhai", "zhan", "zhang", "zhao", "zhe", "zhei", "zhen", "zheng", "zhi", "zhong", "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo", "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo",
    )

    /** 汉语声母列表 */
    private val INITIALS = listOf(
        "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l",
        "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w",
    )

    private val CHAR_TO_DIGIT: Map<Char, Char> = mapOf(
        'a' to '2', 'b' to '2', 'c' to '2',
        'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4',
        'j' to '5', 'k' to '5', 'l' to '5',
        'm' to '6', 'n' to '6', 'o' to '6',
        'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8',
        'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9',
    )

    private val DIGIT_TO_LETTERS: Map<Char, String> = mapOf(
        '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
        '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz",
    )

    /** 高频音节权重表（用于多音节与多组合排序优化） */
    private val HIGH_FREQ_SYLLABLES: Map<String, Int> = mapOf(
        "de" to 100, "shi" to 95, "yi" to 90, "bu" to 85, "le" to 80,
        "zai" to 78, "you" to 76, "wo" to 75, "ta" to 74, "zhe" to 73,
        "zhong" to 72, "ren" to 71, "shang" to 70, "men" to 69, "dao" to 68,
        "ge" to 67, "shuo" to 66, "guo" to 65, "lai" to 64, "ji" to 63,
        "hao" to 62, "ni" to 61, "da" to 60, "kan" to 59, "qu" to 58,
        "fa" to 57, "hui" to 56, "dian" to 55, "xiao" to 54, "xiang" to 53,
        "bai" to 50, "zhu" to 50, "shu" to 50, "mi" to 45, "ai" to 45,
        "ci" to 45, "bi" to 45, "guan" to 45, "gan" to 45,
    )

    private val digitToSyllables: Map<String, List<String>>
    private val initialDigits: Map<String, List<String>>

    init {
        val map = mutableMapOf<String, MutableList<String>>()
        for (syllable in SYLLABLES) {
            val d = toDigits(syllable)
            map.getOrPut(d) { mutableListOf() }.add(syllable)
        }
        digitToSyllables = map

        val initMap = mutableMapOf<String, MutableList<String>>()
        for (initial in INITIALS) {
            val d = toDigits(initial)
            initMap.getOrPut(d) { mutableListOf() }.add(initial)
        }
        initialDigits = initMap
    }

    fun toDigits(text: String): String = buildString(text.length) {
        for (ch in text) {
            CHAR_TO_DIGIT[ch]?.let { append(it) }
        }
    }

    /**
     * 根据输入的按键序列（如 "64"、"64426"），计算最匹配的候选拼音组合列表。
     *
     * @param input 用户输入的原始字符串（只提取其中的数字 2-9，忽略其它符号）
     * @param maxCount 最多返回的候选数量（默认 4 个，对应九宫格左侧 4 个键位）
     */
    fun matchCandidates(input: String, maxCount: Int = 4): List<String> {
        val digits = input.filter { it in '2'..'9' }
        if (digits.isEmpty()) return emptyList()

        // 1 位数字：直接返回键位上的基础字母/声母（如 'a', 'b', 'c'）
        if (digits.length == 1) {
            val letters = DIGIT_TO_LETTERS[digits[0]] ?: return emptyList()
            return letters.map { it.toString() }.take(maxCount)
        }

        fun scoreCandidate(comb: String): Int {
            val parts = comb.split("'")
            var score = parts.size * 50 // 音节切分完整的加分
            for (p in parts) {
                score += HIGH_FREQ_SYLLABLES[p] ?: 10
            }
            return score
        }

        val candidates = LinkedHashSet<String>()

        // 1. 完全匹配的单音节（如 64 -> mi, ni）
        digitToSyllables[digits]?.let { list ->
            // 按常用词频微调顺序，例如 ni > mi
            val sorted = list.sortedByDescending { HIGH_FREQ_SYLLABLES[it] ?: 10 }
            candidates.addAll(sorted)
        }

        // 2. 声母前缀匹配（如 24 -> ch, 94 -> zh）
        initialDigits[digits]?.let { list ->
            candidates.addAll(list)
        }

        // 3. 双音节完整切分（如 64426 -> ni'hao, mi'hao, ni'gan, mi'gan）
        val twoSegs = mutableListOf<String>()
        for (i in 1 until digits.length) {
            val p1 = digits.substring(0, i)
            val p2 = digits.substring(i)
            val s1List = digitToSyllables[p1] ?: continue
            val s2List = digitToSyllables[p2] ?: continue
            for (s1 in s1List) {
                for (s2 in s2List) {
                    twoSegs.add("$s1'$s2")
                }
            }
        }
        twoSegs.sortByDescending { scoreCandidate(it) }
        candidates.addAll(twoSegs)

        // 4. 三音节完整切分（适用于长输入，如 6 位及以上）
        if (digits.length >= 6 && candidates.size < maxCount) {
            val threeSegs = mutableListOf<String>()
            for (i in 1 until digits.length - 1) {
                for (j in i + 1 until digits.length) {
                    val p1 = digits.substring(0, i)
                    val p2 = digits.substring(i, j)
                    val p3 = digits.substring(j)
                    val s1List = digitToSyllables[p1] ?: continue
                    val s2List = digitToSyllables[p2] ?: continue
                    val s3List = digitToSyllables[p3] ?: continue
                    for (s1 in s1List) {
                        for (s2 in s2List) {
                            for (s3 in s3List) {
                                threeSegs.add("$s1'$s2'$s3")
                            }
                        }
                    }
                }
            }
            threeSegs.sortByDescending { scoreCandidate(it) }
            candidates.addAll(threeSegs)
        }

        // 5. 单音节前缀候选（如 64426 中可提供 ni, mi 首音节候选，方便选定前缀）
        val prefixCands = mutableListOf<String>()
        for (i in digits.length - 1 downTo 1) {
            val prefixDigits = digits.substring(0, i)
            digitToSyllables[prefixDigits]?.let { list ->
                for (s in list) {
                    if (s !in candidates && s !in prefixCands) {
                        prefixCands.add(s)
                    }
                }
            }
        }
        prefixCands.sortByDescending { HIGH_FREQ_SYLLABLES[it] ?: 0 }
        candidates.addAll(prefixCands)

        return candidates.take(maxCount)
    }
}
