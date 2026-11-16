/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.handwriting

import android.content.Context

/**
 * 部件组合识别（2026-11-16 爸爸反馈「灿只识别出山，火字旁丢了」）。
 *
 * 真机取证：爸爸行草「灿」的火旁写成 K 形（长竖+两臂），整字渲染被 PP-OCRv5
 * 读成 山/仙（亻+山）/ku，「灿」永不进候选；但**左半单独识别 火 进前 3、
 * 右半单独识别 山 恒第 1**。故：墨迹按最大间隙切左右簇 → 分别识别 →
 * IDS ⿰ 组合表查字（火+山→灿），补进候选。
 *
 * 组合表 asset hw/ids_lr.txt（9260 条，cjkvi-ids 生成，见 scripts/build_hw_ids.py）：
 * 每行 字\t左部件\t右部件。核心逻辑纯函数，JVM 可测。
 */
object HandwritingCompose {
    private var decompose: Map<String, Pair<String, String>> = emptyMap()
    private var composeMap: Map<Pair<String, String>, String> = emptyMap()

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (decompose.isNotEmpty()) return
        try {
            val dec = HashMap<String, Pair<String, String>>()
            val cm = HashMap<Pair<String, String>, String>()
            context.assets.open("hw/ids_lr.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val p = line.split('\t')
                    if (p.size == 3 && p[0].length == 1) {
                        dec[p[0]] = p[1] to p[2]
                        // 同部件组合多字时保留首个（常用字在前，生成器按 IDS 原序）
                        val key = p[1] to p[2]
                        if (!cm.containsKey(key)) cm[key] = p[0]
                    }
                }
            }
            decompose = dec
            composeMap = cm
        } catch (_: Exception) {
            // 表缺失降级为纯整字识别，不影响主流程。
        }
    }

    /**
     * 按笔画中心 x 的最大间隙切左右簇。间隙 < 宽度 [minGapRatio] 不切
     * （独体字/川 等笔画间距小，防误切）。纯函数，JVM 可测。
     */
    fun splitClusters(
        strokes: List<List<InkPoint>>,
        minGapRatio: Float = 0.22f,
    ): Pair<List<List<InkPoint>>, List<List<InkPoint>>>? {
        if (strokes.size < 2) return null
        val items = strokes.map { s -> s.map { it.x }.average().toFloat() to s }.sortedBy { it.first }
        var bestGap = 0f
        var bestI = -1
        for (i in 0 until items.size - 1) {
            val g = items[i + 1].first - items[i].first
            if (g > bestGap) {
                bestGap = g
                bestI = i
            }
        }
        if (bestI < 0) return null
        val allX = strokes.flatMap { it.map { p -> p.x } }
        val width = (allX.maxOrNull() ?: 0f) - (allX.minOrNull() ?: 0f)
        if (width <= 0f || bestGap / width < minGapRatio) return null
        return items.subList(0, bestI + 1).map { it.second } to items.subList(bestI + 1, items.size).map { it.second }
    }

    fun compose(left: List<String>, right: List<String>, maxLeft: Int = 3, maxRight: Int = 2): List<String> =
        composeWith(left, right, composeMap, maxLeft, maxRight)

    fun merge(full: List<String>, composed: List<String>): Pair<List<String>, Boolean> =
        mergeWith(full, composed, decompose)

    /** 纯函数核：左×右读数按排名和查 ⿰ 组合表，去重保序。 */
    internal fun composeWith(
        left: List<String>,
        right: List<String>,
        cm: Map<Pair<String, String>, String>,
        maxLeft: Int = 3,
        maxRight: Int = 2,
    ): List<String> {
        val l = left.filter { it.length == 1 }.take(maxLeft)
        val r = right.filter { it.length == 1 }.take(maxRight)
        val hits = ArrayList<Triple<Int, Int, String>>()
        for (i in l.indices) {
            for (j in r.indices) {
                // 识别读数是整字（水/人/手），IDS 部件常是偏旁形（氵/亻/扌）——
                // 按变体展开查表（火→火/灬，水→水/氵…），灿=⿰火山 直接命中。
                for (lv in radicalVariants(l[i])) {
                    for (rv in radicalVariants(r[j])) {
                        cm[lv to rv]?.let { hits.add(Triple(i, j, it)) }
                    }
                }
            }
        }
        return hits.sortedWith(compareBy({ it.first + it.second }, { it.first }))
            .map { it.third }.distinct()
    }

    /** 整字 → 偏旁变体（常见形近偏旁，组合查表用；未收录的字原样返回）。 */
    private fun radicalVariants(ch: String): List<String> = RADICAL_VARIANTS[ch] ?: listOf(ch)

    private val RADICAL_VARIANTS = mapOf(
        "水" to listOf("水", "氵", "氺"),
        "火" to listOf("火", "灬"),
        "人" to listOf("人", "亻"),
        "手" to listOf("手", "扌"),
        "心" to listOf("心", "忄", "⺗"),
        "言" to listOf("言", "讠"),
        "金" to listOf("金", "钅"),
        "衣" to listOf("衣", "衤"),
        "示" to listOf("示", "礻"),
        "食" to listOf("食", "饣"),
        "玉" to listOf("玉", "王"),
        "刀" to listOf("刀", "刂"),
        "犬" to listOf("犬", "犭"),
        "网" to listOf("网", "罒"),
        "肉" to listOf("肉", "月"),
        "邑" to listOf("邑", "阝"),
        "阜" to listOf("阜", "阝"),
        "辵" to listOf("辵", "辶"),
        "足" to listOf("足", "𧾷"),
        "車" to listOf("車", "车"),
        "馬" to listOf("馬", "马"),
        "鳥" to listOf("鳥", "鸟"),
        "魚" to listOf("魚", "鱼"),
        "貝" to listOf("貝", "贝"),
        "見" to listOf("見", "见"),
        "頁" to listOf("頁", "页"),
        "風" to listOf("風", "风"),
        "飛" to listOf("飛", "飞"),
        "門" to listOf("門", "门"),
        "長" to listOf("長", "镸"),
        "韋" to listOf("韋", "韦"),
        "麥" to listOf("麥", "麦"),
        "黃" to listOf("黃", "黄"),
        "黑" to listOf("黑", "黒"),
        "齊" to listOf("齊", "齐"),
        "齒" to listOf("齒", "齿"),
        "龍" to listOf("龍", "龙"),
        "龜" to listOf("龜", "龟"),
        "黽" to listOf("黽", "黾"),
        "鼎" to listOf("鼎", "鼑"),
    )

    /**
     * 纯函数核：整字候选里的左右结构字（仙）优先，再补组合新字（灿/汕），最后其余。
     * 返回 (合并候选, 是否歧义)——组合补进了新字=字形歧义，调用方据此暂停自动上屏。
     */
    internal fun mergeWith(
        full: List<String>,
        composed: List<String>,
        dec: Map<String, Pair<String, String>>,
    ): Pair<List<String>, Boolean> {
        // 组合表缺失降级：原样返回整字候选，不标歧义。
        if (dec.isEmpty()) return full to false
        val fullLR = full.filter { dec.containsKey(it) }
        val fullRest = full.filter { !dec.containsKey(it) }
        val newComposed = composed.filter { it !in full }
        return (fullLR + newComposed + fullRest).distinct() to newComposed.isNotEmpty()
    }
}