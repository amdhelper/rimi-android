/*
 * SPDX-FileCopyrightText: 2026 Zoom IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 应用内自更新：查井 WUB 更新中继（relay），拿最新 APK 的版本/大小/下载地址。
 *
 * 中继同源多端点竞速：境内 bwg/gz 优先，失败自动换 icn/usw/fr，最后回落到
 * 430033.xyz 公开下载通道。检查不需要登录/邀请码（relay public_apps 白名单）。
 */
data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val hasUpdate: Boolean,
    val fileName: String,
    val size: Long,
    val notes: String,
    val downloadUrl: String,
)

object AppUpdateChecker {
    const val APP_ID = "rimi-android"

    private val BASES = listOf(
        "https://bwg.1230611.xyz:8890",
        "https://gz.1230611.xyz:8890",
        "https://icn.1230611.xyz:8890",
        "https://usw.1230611.xyz:8890",
        "https://fr.1230611.xyz:8890",
        "https://430033.xyz/dl-api",
    )

    private fun checkUrl(base: String): String =
        if (base.endsWith("/dl-api")) "$base/check" else "$base/api/v1/check"

    suspend fun check(currentVersion: String): AppUpdateInfo = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (base in BASES) {
            try {
                val encoded = URLEncoder.encode(currentVersion, "UTF-8")
                val conn = (URL("${checkUrl(base)}?app=$APP_ID&version=$encoded")
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "zoom-ime-android/$currentVersion")
                }
                try {
                    val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                    val json = JSONObject(body)
                    return@withContext AppUpdateInfo(
                        currentVersion = json.optString("current_version", currentVersion),
                        latestVersion = json.optString("latest_version", ""),
                        hasUpdate = json.optBoolean("has_update", false),
                        fileName = json.optString("file", ""),
                        size = json.optLong("size", 0L),
                        notes = json.optString("notes", ""),
                        downloadUrl = json.optString("download_url", ""),
                    )
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no update endpoint reachable")
    }

    /** 交给系统 DownloadManager 下载；完成后由界面层打开安装器。 */
    fun enqueueDownload(context: Context, info: AppUpdateInfo): Long {
        val name = info.fileName.ifBlank { "zoom_${info.latestVersion}.apk" }
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("非全盲视障优化输入法 ${info.latestVersion}")
            .setDescription("正在下载新版本…")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }
}
