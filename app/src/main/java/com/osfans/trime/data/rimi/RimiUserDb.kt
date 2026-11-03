/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.rimi

import com.osfans.trime.core.data.UserDataManager
import java.io.File

/**
 * librime 个人短语库（userdb）读写桥。
 *
 * 只读 native 导出的 TSV，格式：term \t code \t commits \t dee（权重）。
 * 写入同样走 TSV：term \t code \t weight \t deleted(0/1)。
 */
object RimiUserDb {
    /** 与桌面 rimid 同步的库名；rimi 方案 translator/user_dict 就是 user。 */
    const val DEFAULT_DICT = "user"

    data class Entry(
        val term: String,
        val code: String,
        val commits: Int,
        val weight: Double,
    ) {
        val key: String get() = "$term\u0000$code"
    }

    /**
     * 读取失败返回 null；库还没创建（首次使用）返回空列表。
     * 调用方需要区分「空库」与「打不开」，否则会把读取失败当成用户删光了词。
     */
    fun read(dictName: String = DEFAULT_DICT): List<Entry>? {
        val raw = nativeReadUserDict(dictName) ?: return if (dbExists(dictName)) null else emptyList()
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<Entry>(raw.count { it == '\n' })
        for (line in raw.lineSequence()) {
            if (line.isEmpty()) continue
            val cols = line.split('\t')
            if (cols.size < 4) continue
            val term = cols[0]
            val code = cols[1]
            if (term.isEmpty() || code.isEmpty()) continue
            val commits = cols[2].toIntOrNull() ?: 0
            val weight = cols[3].toDoubleOrNull() ?: 0.0
            out.add(Entry(term, code, commits, weight))
        }
        return out
    }

    /** 把云端词条写入本地 userdb；返回实际写入/删除的条数，-1 表示库打不开。 */
    fun apply(dictName: String, entries: List<RimiCloud.RemoteEntry>): Int {
        if (entries.isEmpty()) return 0
        val sb = StringBuilder(entries.size * 24)
        for (e in entries) {
            val term = e.term.replace('\t', ' ').replace('\n', ' ')
            val code = e.code.replace('\t', ' ').replace('\n', ' ')
            if (term.isEmpty() || code.isEmpty()) continue
            sb.append(term).append('\t').append(code).append('\t')
            sb.append(e.weight.coerceAtLeast(1)).append('\t')
            sb.append(if (e.deleted) '1' else '0').append('\n')
        }
        if (sb.isEmpty()) return 0
        return nativeApplyUserDict(dictName, sb.toString())
    }

    fun dbExists(dictName: String = DEFAULT_DICT): Boolean = File(UserDataManager.userDataDir, "$dictName.userdb").exists()

    @JvmStatic
    external fun nativeReadUserDict(dictName: String): String?

    @JvmStatic
    external fun nativeApplyUserDict(dictName: String, tsv: String): Int
}
