/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.rimi

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.osfans.trime.BuildConfig
import com.osfans.trime.util.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class RimiCloudException(message: String, cause: Throwable? = null) : Exception(message, cause)

class RimiUnauthorizedException(message: String) : Exception(message)

/**
 * 日弥云账户：wumi 同源身份（Ed25519），只负责登录与 /rimi/dict/ 系列接口。
 *
 * 与桌面 rimid 完全同一套协议：challenge → Ed25519 签名 → /auth/login → JWT。
 * 手机端没有第二个设备可扫码，所以登录凭证是 wumi 的 12 词助记词（或 64 位 seed hex）。
 */
object RimiCloud {
    const val DEFAULT_SERVER = "https://1230611.xyz"
    private const val PREFS_NAME = "rimi_cloud"
    private const val KEY_SEED = "seed"
    private const val KEY_PUBLIC_KEY = "public_key"
    private const val KEY_TOKEN = "token"
    private const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"
    private const val KEY_SERVER = "server"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_AUTO_SYNC = "auto_sync"
    private const val KEY_SYNC_ONLY_SCREEN_OFF = "sync_only_when_screen_off"
    private const val KEY_SYNC_INTERVAL = "sync_interval_minutes"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val KEY_LAST_SYNC_DETAIL = "last_sync_detail"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_SINCE = "server_time"
    private const val KEY_LEVEL = "membership_level"
    private const val KEY_ROLE = "role"

    const val DEFAULT_SYNC_INTERVAL_MINUTES = 360
    const val MIN_SYNC_INTERVAL_MINUTES = 15
    private const val MAX_PUSH_BATCH = 1000

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ---------------- 配置 ----------------

    var server: String
        get() = prefs.getString(KEY_SERVER, DEFAULT_SERVER)?.trimEnd('/') ?: DEFAULT_SERVER
        set(value) {
            val normalized = value.trim().trimEnd('/')
            prefs.edit().putString(KEY_SERVER, normalized.ifEmpty { DEFAULT_SERVER }).apply()
            // 换服务器等于换账户空间，游标与快照必须一起归零，否则本地词条不会被推到新服务器
            RimiDictSync.resetSyncState()
        }

    var autoSync: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()

    /**
     * 后台自动同步是否只在屏幕熄灭时执行（默认开）。
     *
     * 亮屏时用户可能正在打字：同步要读写 userdb 并在写回后 `updateConfig()` 重启引擎，
     * 既抢 IO/网络也会打断输入。熄屏后再跑可以把这两类干扰都降到零。
     * 「立即同步」是用户主动操作，不受此开关影响。
     */
    var syncOnlyWhenScreenOff: Boolean
        get() = prefs.getBoolean(KEY_SYNC_ONLY_SCREEN_OFF, true)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_ONLY_SCREEN_OFF, value).apply()

    var syncIntervalMinutes: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL, DEFAULT_SYNC_INTERVAL_MINUTES)
        set(value) =
            prefs
                .edit()
                .putInt(KEY_SYNC_INTERVAL, value.coerceAtLeast(MIN_SYNC_INTERVAL_MINUTES))
                .apply()

    var since: Long
        get() = prefs.getLong(KEY_SINCE, 0L)
        set(value) = prefs.edit().putLong(KEY_SINCE, value).apply()

    val lastSyncAt: Long get() = prefs.getLong(KEY_LAST_SYNC_AT, 0L)
    val lastSyncDetail: String get() = prefs.getString(KEY_LAST_SYNC_DETAIL, "") ?: ""
    val lastError: String get() = prefs.getString(KEY_LAST_ERROR, "") ?: ""
    val membershipLevel: String get() = prefs.getString(KEY_LEVEL, "") ?: ""
    val role: String get() = prefs.getString(KEY_ROLE, "") ?: ""

    fun recordSync(detail: String) {
        prefs
            .edit()
            .putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
            .putString(KEY_LAST_SYNC_DETAIL, detail)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordError(message: String) {
        prefs.edit().putString(KEY_LAST_ERROR, message).apply()
    }

    // ---------------- 账户 ----------------

    val isLoggedIn: Boolean get() = !prefs.getString(KEY_SEED, null).isNullOrEmpty()

    val publicKey: String? get() = prefs.getString(KEY_PUBLIC_KEY, null)

    fun seed(): ByteArray? = prefs.getString(KEY_SEED, null)?.let { RimiSeedVault.decrypt(it) }

    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val androidId =
                runCatching {
                    Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                }.getOrNull() ?: "unknown"
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest("${Build.MODEL}|$androidId".toByteArray())
                    .joinToString("") { "%02x".format(it) }
                    .take(10)
            "android-$digest".also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
        }
    }

    /** 接受 12/15/18/21/24 个中文助记词，或 64 位十六进制 seed（桌面 rimid 的 ed25519.seed）。 */
    fun seedFromSecret(secret: String): ByteArray {
        val text = secret.trim()
        if (text.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            return ByteArray(32) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
        return RimiBip39.seedFromMnemonic(text)
    }

    suspend fun login(secret: String) {
        val seed = seedFromSecret(secret)
        val newKey = publicKeyOf(seed)
        withContext(Dispatchers.IO) {
            // 先认证再落盘：失败时不留「有 seed 没 token」的半登录状态
            loginWithSeed(seed)
            val previousKey = prefs.getString(KEY_PUBLIC_KEY, null)
            if (previousKey != null && previousKey != newKey) {
                // 换账户：清掉旧账户的同步游标与本地快照
                RimiDictSync.resetSyncState()
            }
            prefs
                .edit()
                .putString(KEY_SEED, RimiSeedVault.encrypt(seed))
                .putString(KEY_PUBLIC_KEY, newKey)
                .apply()
        }
    }

    fun logout() {
        prefs
            .edit()
            .remove(KEY_SEED)
            .remove(KEY_PUBLIC_KEY)
            .remove(KEY_TOKEN)
            .remove(KEY_TOKEN_EXPIRES_AT)
            .remove(KEY_LEVEL)
            .remove(KEY_ROLE)
            .apply()
        // 退出即清同步基线：公钥已删除后 login() 无从判断"换没换账户"，
        // 若把上个账户的游标/快照留给下一个账户，会漏拉/错推。
        RimiDictSync.resetSyncState()
    }

    /** 返回可用 access token；过期或 force 时用本地 seed 静默重登。 */
    suspend fun ensureToken(force: Boolean = false): String {
        val cached = prefs.getString(KEY_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        if (!force && !cached.isNullOrEmpty() && System.currentTimeMillis() < expiresAt - 60_000) {
            return cached
        }
        val seed = seed() ?: throw RimiCloudException("未登录，请先输入 wumi 助记词")
        return withContext(Dispatchers.IO) { loginWithSeed(seed) }
    }

    // ---------------- 认证实现 ----------------

    private suspend fun loginWithSeed(seed: ByteArray): String {
        val pub = publicKeyOf(seed)
        val challengeResp =
            request(
                "GET",
                "/auth/challenge?public_key=" + URLEncoder.encode(pub, "UTF-8"),
            )
        val challenge = parse<ChallengeData>(challengeResp).challenge
        val signature = sign(seed, challenge.toByteArray())
        val body =
            json.encodeToString(
                LoginRequest(
                    publicKey = pub,
                    signature = signature,
                    challenge = challenge,
                    clientInfo = "${Build.MANUFACTURER} ${Build.MODEL} · 日弥输入法安卓版",
                    clientVersion = BuildConfig.VERSION_NAME,
                    clientPlatform = "android",
                ),
            )
        val login = parse<LoginData>(request("POST", "/auth/login", body))
        val token = login.token.ifEmpty { login.accessToken }
        if (token.isEmpty()) throw RimiCloudException("登录响应里没有 token")
        val ttlMillis = if (login.expiresIn > 0) login.expiresIn * 1000L else 3600_000L
        prefs
            .edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_TOKEN_EXPIRES_AT, System.currentTimeMillis() + ttlMillis)
            .putString(KEY_LEVEL, login.user?.membershipLevel ?: "")
            .putString(KEY_ROLE, login.user?.role ?: "")
            .apply()
        return token
    }

    private val ed25519Spec by lazy {
        EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
    }

    private fun keyOf(seed: ByteArray): EdDSAPrivateKey = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, ed25519Spec))

    private fun publicKeyOf(seed: ByteArray): String = Base64.encodeToString(keyOf(seed).abyte, Base64.NO_WRAP)

    private fun sign(seed: ByteArray, message: ByteArray): String {
        val engine = EdDSAEngine(MessageDigest.getInstance(ed25519Spec.hashAlgorithm))
        engine.initSign(keyOf(seed))
        return Base64.encodeToString(engine.signOneShot(message), Base64.NO_WRAP)
    }

    // ---------------- 词库接口 ----------------

    suspend fun push(entries: List<PushEntry>): PushResult {
        if (entries.isEmpty()) return PushResult(0, 0, System.currentTimeMillis())
        var accepted = 0
        var skipped = 0
        var serverTime = 0L
        for (chunk in entries.chunked(MAX_PUSH_BATCH)) {
            val body = json.encodeToString(PushRequest(chunk, deviceId))
            val data =
                withAuth { token ->
                    parse<PushResult>(request("POST", "/rimi/dict/push", body, token))
                }
            accepted += data.accepted
            skipped += data.skipped
            serverTime = data.serverTime
        }
        return PushResult(accepted, skipped, serverTime)
    }

    suspend fun pull(since: Long): PullResult = withAuth { token ->
        parse(request("GET", "/rimi/dict/pull?since=$since", null, token))
    }

    suspend fun status(): Long = withAuth { token ->
        parse<StatusData>(request("GET", "/rimi/dict/status", null, token)).entryCount
    }

    private suspend fun <T> withAuth(block: suspend (String) -> T): T {
        val token = ensureToken()
        return try {
            block(token)
        } catch (e: RimiUnauthorizedException) {
            block(ensureToken(force = true))
        }
    }

    // ---------------- HTTP ----------------

    private suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val url = URL(server + path)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty(
                "User-Agent",
                "rimi-android/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})",
            )
            if (token != null) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw RimiUnauthorizedException(text.take(300))
            }
            if (code !in 200..299) {
                throw RimiCloudException("HTTP $code ${text.take(300)}")
            }
            text
        } catch (e: IOException) {
            throw RimiCloudException("网络错误：${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private inline fun <reified T> parse(raw: String): T {
        val resp = json.decodeFromString<ApiResponse<T>>(raw)
        if (!resp.success) {
            val code = resp.error?.code ?: 0
            val message = resp.error?.message ?: "未知错误"
            if (code == 100201) throw RimiUnauthorizedException(message)
            throw RimiCloudException("$code: $message")
        }
        return resp.data ?: throw RimiCloudException("响应缺少 data")
    }

    // ---------------- 序列化模型 ----------------

    @Serializable
    private data class ApiResponse<T>(
        val success: Boolean = false,
        val data: T? = null,
        val error: ApiError? = null,
    )

    @Serializable
    private data class ApiError(val code: Int = 0, val message: String = "")

    @Serializable
    private data class ChallengeData(val challenge: String = "")

    @Serializable
    private data class LoginRequest(
        @SerialName("public_key") val publicKey: String,
        val signature: String,
        val challenge: String,
        @SerialName("client_info") val clientInfo: String,
        @SerialName("client_version") val clientVersion: String,
        @SerialName("client_platform") val clientPlatform: String,
    )

    @Serializable
    data class LoginUser(
        @SerialName("public_key") val publicKey: String = "",
        val role: String = "",
        @SerialName("membership_level") val membershipLevel: String = "",
    )

    @Serializable
    private data class LoginData(
        val token: String = "",
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("expires_in") val expiresIn: Long = 0,
        val user: LoginUser? = null,
    )

    @Serializable
    private data class PushRequest(
        val entries: List<PushEntry>,
        @SerialName("device_id") val deviceId: String,
    )

    @Serializable
    data class PushEntry(
        val term: String,
        val code: String,
        val weight: Long,
        val source: String = "user",
        @SerialName("updated_at") val updatedAt: Long,
        val deleted: Boolean = false,
    )

    @Serializable
    data class PushResult(
        val accepted: Int = 0,
        val skipped: Int = 0,
        @SerialName("server_time") val serverTime: Long = 0,
    )

    @Serializable
    data class RemoteEntry(
        val term: String = "",
        val code: String = "",
        val weight: Long = 0,
        val source: String = "user",
        val deleted: Boolean = false,
        @SerialName("updated_at") val updatedAt: Long = 0,
    )

    @Serializable
    data class PullResult(
        val entries: List<RemoteEntry> = emptyList(),
        @SerialName("server_time") val serverTime: Long = 0,
        @SerialName("has_more") val hasMore: Boolean = false,
    )

    @Serializable
    private data class StatusData(@SerialName("entry_count") val entryCount: Long = 0)
}
