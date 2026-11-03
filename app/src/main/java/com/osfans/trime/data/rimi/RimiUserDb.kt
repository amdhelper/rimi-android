/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.rimi

import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.util.appContext
import java.io.File
import java.io.IOException

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
        return parse(raw)
    }

    /**
     * 引擎运行中 userdb 被 leveldb 的 flock 独占，直接读必然失败。
     *
     * 这里把 userdb 目录里 LevelDB 需要的文件复制一份到 cache 再打开副本 —— 与桌面 rimid 的
     * 「快照副本无锁读」同一思路。复制可能撞上并发 compaction（CURRENT 指向还没拷完的 .ldb），
     * 失败重试一次；仍失败返回 null，调用方再降级为「停引擎后直读」。
     */
    fun readSnapshot(dictName: String = DEFAULT_DICT): List<Entry>? {
        val src = File(UserDataManager.userDataDir, "$dictName.userdb")
        if (!src.isDirectory) return null
        repeat(2) {
            val dst = File(appContext.cacheDir, "rimi_userdb_snapshot/$dictName-${System.nanoTime()}.userdb")
            try {
                if (!dst.mkdirs()) return@repeat
                val files = src.listFiles() ?: return@repeat
                for (f in files) {
                    if (!f.isFile) continue
                    val name = f.name
                    val needed =
                        name == "CURRENT" ||
                            name.startsWith("MANIFEST-") ||
                            name.endsWith(".ldb") ||
                            name.endsWith(".sst") ||
                            name.endsWith(".log")
                    if (!needed) continue
                    f.copyTo(File(dst, name), overwrite = true)
                }
                val raw = nativeReadUserDictAtPath(dst.absolutePath) ?: return@repeat
                return parse(raw)
            } catch (_: IOException) {
                // 复制途中 LevelDB 正在 compaction，重试一次
            } finally {
                dst.deleteRecursively()
            }
        }
        return null
    }

    private fun parse(raw: String): List<Entry> {
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
        val tsv = toTsv(entries) ?: return 0
        return nativeApplyUserDict(dictName, tsv)
    }

    /**
     * 按绝对路径写 userdb：引擎已停（`withEngineReleased` 窗口内 Rime Service 已销毁）时用。
     * 与 [apply] 的区别是不经 `UserDb::Require`，直接用 LevelDb 打开路径。
     */
    fun applyAtPath(dbPath: String, entries: List<RimiCloud.RemoteEntry>): Int {
        val tsv = toTsv(entries) ?: return 0
        return nativeApplyUserDictAtPath(dbPath, tsv)
    }

    /** 按绝对路径只读打开 LevelDB（用于引擎占用 userdb 时的无锁快照读 / 停引擎后直读）。 */
    fun readAtPath(dbPath: String): List<Entry>? {
        val raw = nativeReadUserDictAtPath(dbPath) ?: return null
        return parse(raw)
    }

    fun dbPath(dictName: String = DEFAULT_DICT): String =
        File(UserDataManager.userDataDir, "$dictName.userdb").absolutePath

    private fun toTsv(entries: List<RimiCloud.RemoteEntry>): String? {
        if (entries.isEmpty()) return null
        val sb = StringBuilder(entries.size * 24)
        for (e in entries) {
            val term = e.term.replace('\t', ' ').replace('\n', ' ')
            val code = e.code.replace('\t', ' ').replace('\n', ' ')
            if (term.isEmpty() || code.isEmpty()) continue
            sb.append(term).append('\t').append(code).append('\t')
            sb.append(e.weight.coerceAtLeast(1)).append('\t')
            sb.append(if (e.deleted) '1' else '0').append('\n')
        }
        if (sb.isEmpty()) return null
        return sb.toString()
    }

    fun dbExists(dictName: String = DEFAULT_DICT): Boolean = File(UserDataManager.userDataDir, "$dictName.userdb").exists()

    @JvmStatic
    external fun nativeReadUserDict(dictName: String): String?

    /** 读任意路径下的 LevelDB 副本（用于引擎占用 userdb 时的无锁快照读）。 */
    @JvmStatic
    external fun nativeReadUserDictAtPath(dbPath: String): String?

    @JvmStatic
    external fun nativeApplyUserDict(dictName: String, tsv: String): Int

    /** 按绝对路径写 LevelDB（引擎已停、Rime Service 不可用时用）。 */
    @JvmStatic
    external fun nativeApplyUserDictAtPath(dbPath: String, tsv: String): Int
}
