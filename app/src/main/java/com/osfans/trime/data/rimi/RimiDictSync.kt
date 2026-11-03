/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.rimi

import com.osfans.trime.core.RimeApi
import com.osfans.trime.util.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 日弥词库云同步（M3）。
 *
 * 数据源不是另一套词库，就是 RIME 自己学出来的个人短语库（userdb），
 * 与桌面端 rimid 完全同一份数据、同一套 /rimi/dict/ 协议：
 *
 *   1. 读本地 userdb（term/code/权重 dee）
 *   2. 与上次同步快照比对 → 增量 push（含墓碑）
 *   3. pull 服务端增量 → 直接写回本地 userdb
 *   4. 重载 RIME 引擎，让新词立即参与候选排序
 *
 * ⚠️ 必须在 RIME 会话线程（RimeSession.runOnReady 的 block 内）调用——
 * JNI 读写 userdb 与引擎同进程，不能从任意线程并发访问。
 */
object RimiDictSync {
    data class Report(
        val localCount: Int,
        val pushed: Int,
        val pulled: Int,
        val applied: Int,
        val cloudCount: Long,
    )

    private const val SNAPSHOT_DIR = "rimi_cloud"
    private const val SNAPSHOT_FILE = "last_snapshot.tsv"
    private const val MAX_TERM_LENGTH = 32
    private const val MAX_CODE_LENGTH = 128
    private const val MAX_PULL_PAGES = 20
    private const val USERDB_RETRIES = 3
    private const val USERDB_RETRY_DELAY_MS = 300L

    fun resetSyncState() {
        RimiCloud.since = 0
        snapshotFile().delete()
    }

    /**
     * 执行一次双向同步。返回统计；网络/服务端错误抛异常，由调用方展示并记录。
     */
    suspend fun sync(rime: RimeApi): Report = withContext(Dispatchers.Default) {
        val dictName = RimiUserDb.DEFAULT_DICT
        var local = RimiUserDb.read(dictName)
        var readTries = 0
        while (local == null && readTries < USERDB_RETRIES) {
            // 输入法引擎（会话）正握着 userdb：重启引擎释放句柄后重读
            rime.updateConfig()
            delay(USERDB_RETRY_DELAY_MS)
            local = RimiUserDb.read(dictName)
            readTries++
        }
        if (local == null) {
            throw RimiCloudException("读取本地词库失败（userdb 被输入法占用），请收起键盘后再试")
        }
        val localMap = HashMap<String, RimiUserDb.Entry>(local.size)
        for (e in local) localMap[e.key] = e
        val snapshot = loadSnapshot()

        val now = System.currentTimeMillis()
        val outgoing = ArrayList<RimiCloud.PushEntry>()
        val pushedKeys = HashSet<String>()

        // 1a. 本地新增/权重变化
        for (e in local) {
            if (!pushable(e.term, e.code)) continue
            val oldWeight = snapshot[e.key]
            if (oldWeight != null && abs(oldWeight - e.weight) < 0.5) continue
            outgoing.add(
                RimiCloud.PushEntry(
                    term = e.term,
                    code = e.code,
                    weight = e.weight.roundToLong().coerceAtLeast(1),
                    updatedAt = now,
                ),
            )
            pushedKeys.add(e.key)
        }

        // 1b. 本地删除 → 墓碑。
        // 安全阀：本地库突然变空（换机/清数据/重部署）时不能把云端全删了。
        val localReset = local.isEmpty() && snapshot.size > 20
        if (!localReset) {
            for (key in snapshot.keys) {
                if (localMap.containsKey(key)) continue
                val parsed = splitKey(key) ?: continue
                if (!pushable(parsed.first, parsed.second)) continue
                outgoing.add(
                    RimiCloud.PushEntry(
                        term = parsed.first,
                        code = parsed.second,
                        weight = 1,
                        updatedAt = now,
                        deleted = true,
                    ),
                )
                pushedKeys.add(key)
            }
        }

        val pushResult =
            if (outgoing.isEmpty()) {
                RimiCloud.PushResult()
            } else {
                RimiCloud.push(outgoing)
            }

        // 2. 拉取远端增量（含其他设备的新增/删除）
        var since = RimiCloud.since
        var pulled = 0
        var appliedTotal = 0
        var pages = 0
        while (pages < MAX_PULL_PAGES) {
            pages++
            val page = RimiCloud.pull(since)
            pulled += page.entries.size
            val toApply =
                page.entries.filter { e ->
                    e.source == "user" &&
                        e.term.isNotEmpty() &&
                        e.code.isNotEmpty() &&
                        // 本轮自己 push 的条目会被服务端原样带回，不必再写一遍
                        !(e.key() in pushedKeys && e.updatedAt <= now)
                }
            if (toApply.isNotEmpty()) {
                var n = RimiUserDb.apply(dictName, toApply)
                var writeTries = 0
                while (n < 0 && writeTries < USERDB_RETRIES) {
                    // 引擎（输入法会话）可能还握着 userdb：重启引擎释放句柄后重试。
                    // 这里只在「键盘已收起」的同步路径上发生，重启不会打断用户输入。
                    rime.updateConfig()
                    delay(USERDB_RETRY_DELAY_MS)
                    n = RimiUserDb.apply(dictName, toApply)
                    writeTries++
                }
                if (n < 0) {
                    throw RimiCloudException("本地词库写入失败（userdb 打不开，可能正被输入法占用），稍后重试")
                }
                appliedTotal += n
            }
            since = page.serverTime.coerceAtLeast(since)
            if (!page.hasMore) break
        }
        RimiCloud.since = since

        // 3. 记录同步后的本地状态，作为下一轮增量比对的基线
        val after = RimiUserDb.read(dictName) ?: local
        saveSnapshot(after)

        // 4. 有远端写入 → 重启引擎（exit/start，不重部署），让新词参与候选排序
        if (appliedTotal > 0) {
            rime.updateConfig()
        }

        val cloudCount = runCatching { RimiCloud.status() }.getOrDefault(-1)
        val report =
            Report(
                localCount = after.size,
                pushed = pushResult.accepted,
                pulled = pulled,
                applied = appliedTotal,
                cloudCount = cloudCount,
            )
        RimiCloud.recordSync(
            "本地 ${report.localCount} 条 · 上传 ${report.pushed} · 拉取 ${report.pulled} · 应用 ${report.applied}",
        )
        report
    }

    // ---------------- 快照 ----------------

    private fun snapshotFile(): File = File(File(appContext.filesDir, SNAPSHOT_DIR), SNAPSHOT_FILE)

    private suspend fun loadSnapshot(): Map<String, Double> = withContext(Dispatchers.IO) {
        val file = snapshotFile()
        if (!file.exists()) return@withContext emptyMap()
        val out = HashMap<String, Double>()
        runCatching {
            file.forEachLine { line ->
                if (line.isEmpty()) return@forEachLine
                val cols = line.split('\t')
                if (cols.size < 3) return@forEachLine
                val weight = cols[2].toDoubleOrNull() ?: return@forEachLine
                out["${cols[0]}\u0000${cols[1]}"] = weight
            }
        }
        out
    }

    private suspend fun saveSnapshot(entries: List<RimiUserDb.Entry>) = withContext(Dispatchers.IO) {
        val file = snapshotFile()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$SNAPSHOT_FILE.tmp")
        tmp.bufferedWriter().use { writer ->
            for (e in entries) {
                writer.append(e.term).append('\t').append(e.code).append('\t')
                writer.append(e.weight.toString()).append('\n')
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    // ---------------- 工具 ----------------

    private fun pushable(term: String, code: String): Boolean = term.isNotEmpty() &&
        code.isNotEmpty() &&
        term.length <= MAX_TERM_LENGTH &&
        code.length <= MAX_CODE_LENGTH &&
        '\t' !in term &&
        '\t' !in code &&
        '\n' !in term &&
        '\n' !in code

    private fun splitKey(key: String): Pair<String, String>? {
        val idx = key.indexOf('\u0000')
        if (idx <= 0 || idx >= key.length - 1) return null
        return key.substring(0, idx) to key.substring(idx + 1)
    }

    private fun RimiCloud.RemoteEntry.key(): String = "$term\u0000$code"
}
