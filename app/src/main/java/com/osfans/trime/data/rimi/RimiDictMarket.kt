/*
 * SPDX-FileCopyrightText: 2026 Zoom IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.rimi

import com.osfans.trime.core.data.DataManager
import com.osfans.trime.util.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 日弥词库市场（D，2026-11-10 爸爸令）。
 *
 * 为什么需要它：手机端内置词库底座 RimiMobile 只有「8105 + base + 四个专业库」，
 * 首启部署才能压到 8s；但用户要的词差别极大（网络热词 / 医药 / 计算机 / 成语诗词 /
 * 地名姓名 / 游戏美食…）。市场把词库做成按需下载：装到用户 RIME 目录、挂进
 * `rimi.mobile` 的 import_tables、触发一次重新部署。
 *
 * 为什么写「用户目录的 rimi.mobile.dict.yaml」而不是改 shared 副本：
 *   - RIME 的 ResourceResolver 先查 user_data_dir（files/rime）再查 shared；
 *     用户目录同名 dict 会覆盖内置版本，App 升级时 assets 覆盖 shared 不会带走市场配置；
 *   - 每次调用 [ensureUserMobileDict] 都会以 shared 的最新内置清单为基线重建，
 *     内置词库升级（新增专业库等）也不会被这份副本挡住。
 *
 * 安全：下载域名白名单 + https + 只认 .dict.yaml + 大小上限 + UTF-8/tab 校验，
 * 清单由后端下发也不能把客户端变成任意文件下载器。
 */
object RimiDictMarket {
    private const val CACHE_FILE = "market_catalog.json"
    private const val MAX_DICT_BYTES = 64L * 1024 * 1024
    private const val MIN_DICT_BYTES = 1024L
    private const val ALLOWED_HOST = "raw.githubusercontent.com"
    private const val MARKET_PREFIX = "market."

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class Category(
        val id: String = "",
        val name: String = "",
        val sort: Int = 0,
    )

    @Serializable
    data class Dict(
        val id: String = "",
        val name: String = "",
        val category: String = "",
        val description: String = "",
        val entries: Int = 0,
        @SerialName("size_bytes") val sizeBytes: Long = 0,
        val url: String = "",
        @SerialName("dict_name") val dictName: String = "",
        val source: String = "",
        val recommended: Boolean = false,
    )

    @Serializable
    data class Catalog(
        val version: Int = 0,
        @SerialName("updated_at") val updatedAt: Long = 0,
        val categories: List<Category> = emptyList(),
        val dicts: List<Dict> = emptyList(),
    )

    private fun cacheFile(): File = File(appContext.filesDir, CACHE_FILE)

    /** 本地缓存的清单（离线可用；首次联网后写入）。 */
    fun cachedCatalog(): Catalog? = runCatching {
        val f = cacheFile()
        if (!f.isFile) return null
        json.decodeFromString<Catalog>(f.readText())
    }.getOrNull()

    /** 拉取市场清单（公开接口，不需要登录）。成功后写缓存。 */
    suspend fun fetchCatalog(): Catalog {
        val raw = RimiCloud.publicGet("/rimi/dict/market")
        val resp = json.decodeFromString<ApiEnvelope<Catalog>>(raw)
        if (!resp.success) {
            throw RimiCloudException("${resp.error?.code ?: 0}: ${resp.error?.message ?: "未知错误"}")
        }
        val catalog = resp.data ?: throw RimiCloudException("词库市场响应缺少 data")
        if (catalog.dicts.isEmpty()) throw RimiCloudException("词库市场清单为空")
        withContext(Dispatchers.IO) {
            runCatching { cacheFile().writeText(json.encodeToString(catalog)) }
        }
        return catalog
    }

    @Serializable
    private data class ApiEnvelope<T>(
        val success: Boolean = false,
        val data: T? = null,
        val error: ApiError? = null,
    )

    @Serializable
    private data class ApiError(val code: Int = 0, val message: String = "")

    // ---------------- 安装状态 ----------------

    /** 已安装词库的落地名集合（market.xxx）。 */
    fun installedNames(): Set<String> {
        val dir = DataManager.userDataDir
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(MARKET_PREFIX) && f.name.endsWith(".dict.yaml") }
        return files?.map { it.name.removeSuffix(".dict.yaml") }?.toSet() ?: emptySet()
    }

    fun isInstalled(d: Dict): Boolean = File(DataManager.userDataDir, "${d.dictName}.dict.yaml").isFile

    // ---------------- 安装 / 卸载 ----------------

    /**
     * 下载并安装词库。**不负责部署** —— 调用方在成功后再触发 [deploy]，
     * 这样批量安装时只需部署一次。
     *
     * @param onProgress 已下载字节数回调（可能在 IO 线程，UI 自行切主线程）
     */
    suspend fun install(
        d: Dict,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        validateDictMeta(d)
        val data = download(d, onProgress)
        val text = normalizeDict(data.toString(Charsets.UTF_8), d)
        withContext(Dispatchers.IO) {
            val dir = DataManager.userDataDir.also { it.mkdirs() }
            val dst = File(dir, "${d.dictName}.dict.yaml")
            val tmp = File(dir, "${d.dictName}.dict.yaml.tmp")
            tmp.writeText(text)
            if (dst.exists() && !dst.delete()) throw RimiCloudException("旧词库文件无法覆盖：${dst.name}")
            if (!tmp.renameTo(dst)) throw RimiCloudException("词库落盘失败：${dst.name}")
            ensureUserMobileDictLocked()
        }
    }

    /** 卸载词库（删文件 + 从 import_tables 摘掉），随后调用方触发部署。 */
    suspend fun uninstall(d: Dict): Boolean = withContext(Dispatchers.IO) {
        val dst = File(DataManager.userDataDir, "${d.dictName}.dict.yaml")
        val removed = dst.exists() && dst.delete()
        ensureUserMobileDictLocked()
        removed
    }

    /**
     * 重建「用户目录 rimi.mobile.dict.yaml」= 内置基线 + 已安装 market.*。
     *
     * 基线取自 shared 副本（App 升级后会更新），所以内置词库清单变化能自动跟随；
     * 只需在启动/安装/卸载时调用，成本是读写一个几百字节的文件。
     */
    suspend fun ensureUserMobileDict() = withContext(Dispatchers.IO) { ensureUserMobileDictLocked() }

    private fun ensureUserMobileDictLocked() {
        val shared = File(DataManager.sharedDataDir, "rimi.mobile.dict.yaml")
        if (!shared.isFile) return
        val baseLines = shared.readLines()
        val userDir = DataManager.userDataDir.also { it.mkdirs() }

        val out = ArrayList<String>(baseLines.size + 8)
        var inImports = false
        var inserted = false
        for (line in baseLines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("import_tables:")) {
                inImports = true
                out.add(line)
                continue
            }
            if (inImports) {
                // 原有 import 行：保留非 market.*（内置清单），market.* 稍后统一重加
                if (trimmed.startsWith("- ")) {
                    val name = trimmed.removePrefix("- ").substringBefore('#').trim()
                    if (name.startsWith(MARKET_PREFIX)) continue
                    out.add(line)
                    continue
                }
                // import 段结束：先补市场词库，再吃这一行
                if (!inserted) {
                    installedNames().sorted().forEach { out.add("  - $it") }
                    inserted = true
                }
                inImports = false
            }
            out.add(line)
        }
        if (inImports && !inserted) {
            installedNames().sorted().forEach { out.add("  - $it") }
        }

        val text = out.joinToString("\n") + "\n"
        val dst = File(userDir, "rimi.mobile.dict.yaml")
        if (!dst.isFile || dst.readText() != text) dst.writeText(text)
    }

    // ---------------- 部署 ----------------

    /**
     * 触发 RIME 全量部署（重编译 rimi.mobile 词库表）。
     *
     * 大词库首次编译可能需要 10~60s；调用方负责 loading UI。
     */
    suspend fun deploy(rime: com.osfans.trime.core.RimeApi) {
        rime.deploy()
    }

    // ---------------- 下载与校验 ----------------

    private fun validateDictMeta(d: Dict) {
        if (!Regex("^[a-z0-9][a-z0-9_-]{1,40}$").matches(d.id)) {
            throw RimiCloudException("词库 id 非法：${d.id}")
        }
        if (!Regex("^market\\.[a-z0-9][a-z0-9_.-]{0,40}$").matches(d.dictName)) {
            throw RimiCloudException("词库落地名非法：${d.dictName}")
        }
        val url = runCatching { URL(d.url) }.getOrNull() ?: throw RimiCloudException("词库地址非法")
        if (url.protocol != "https") throw RimiCloudException("词库地址必须是 https")
        if (url.host != ALLOWED_HOST) throw RimiCloudException("词库地址域名不在白名单：${url.host}")
        if (!url.path.endsWith(".dict.yaml")) throw RimiCloudException("词库地址不是 .dict.yaml")
        if (d.sizeBytes > MAX_DICT_BYTES) throw RimiCloudException("词库体积超过上限（${d.sizeBytes / 1048576}MB）")
    }

    private suspend fun download(
        d: Dict,
        onProgress: (Long, Long) -> Unit,
    ): ByteArray = withContext(Dispatchers.IO) {
        val conn = URL(d.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "rimi-android/${com.osfans.trime.BuildConfig.VERSION_NAME}")
            val code = conn.responseCode
            if (code !in 200..299) throw RimiCloudException("词库下载失败（HTTP $code）")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: d.sizeBytes
            if (total > MAX_DICT_BYTES) throw RimiCloudException("词库体积异常（${total / 1048576}MB）")
            val buffer = ByteArray(64 * 1024)
            val out = java.io.ByteArrayOutputStream(if (total in 1..Int.MAX_VALUE.toLong()) total.toInt() else 256 * 1024)
            var downloaded = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    downloaded += n
                    if (downloaded > MAX_DICT_BYTES) throw RimiCloudException("词库体积超过上限")
                    if (downloaded - lastReport >= 256 * 1024) {
                        lastReport = downloaded
                        onProgress(downloaded, total)
                    }
                }
            }
            onProgress(downloaded, total)
            val bytes = out.toByteArray()
            if (bytes.size < MIN_DICT_BYTES) throw RimiCloudException("下载内容过小（${bytes.size} 字节），可能不是词库")
            bytes
        } catch (e: IOException) {
            throw RimiCloudException("词库下载网络错误：${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    /** 校验并统一词库头部：name 改成市场落地名，缺 version/sort 补齐。 */
    private fun normalizeDict(raw: String, d: Dict): String {
        if (!raw.contains("\n...\n") && !raw.trimEnd().endsWith("\n...")) {
            throw RimiCloudException("词库文件缺少文档结束标记（...），不是标准 RIME 词库")
        }
        val tabLines = raw.lineSequence().count { line -> '\t' in line && !line.trimStart().startsWith("#") }
        if (tabLines < 10) throw RimiCloudException("词库词条过少（$tabLines 行），已放弃安装")

        val lines = raw.split('\n')
        var seenDocStart = false
        var inBody = false
        var nameReplaced = false
        var hasVersion = false
        var hasSort = false
        val out = ArrayList<String>(lines.size + 4)
        for (line0 in lines) {
            val line = line0.trimEnd('\r')
            val s = line.trim()
            if (s == "...") {
                inBody = true
                out.add(line)
                continue
            }
            if (s == "---") {
                seenDocStart = true
                out.add(line)
                continue
            }
            if (!inBody && seenDocStart) {
                if (s.startsWith("name:")) {
                    out.add("name: ${d.dictName}")
                    nameReplaced = true
                    continue
                }
                if (s.startsWith("version:")) hasVersion = true
                if (s.startsWith("sort:")) hasSort = true
            }
            out.add(line)
        }
        if (!nameReplaced) throw RimiCloudException("词库文件缺少 name 字段")
        if (!hasVersion || !hasSort) {
            // 在第一个 --- 后补头（librime 对缺 version/sort 的头部会报错甚至整库丢弃）
            val idx = out.indexOfFirst { it.trim() == "---" }
            if (idx >= 0) {
                val inject = ArrayList<String>()
                if (!hasVersion) inject.add("version: \"1.0\"")
                if (!hasSort) inject.add("sort: by_weight")
                out.addAll(idx + 1, inject)
            }
        }
        return out.joinToString("\n")
    }

    // ---------------- 展示辅助 ----------------

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun humanEntries(n: Int): String = when {
        n >= 10000 -> String.format("%.0f 万", n / 10000.0)
        else -> "$n"
    }
}
