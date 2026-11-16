/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.handwriting

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 手写引擎文件取证日志（2026-11-16）。
 *
 * 为什么不用 logcat：华为 Mate 40 Pro（HarmonyOS）默认屏蔽第三方应用日志，
 * logcat 里只有系统日志，Timber 取证全盲。本日志直接写外部私有目录
 * hw_engine.log（adb pull 可读），不依赖任何日志开关。≤200KB 自动重置。
 */
object HwDebugLog {
    fun log(context: Context, msg: String) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            val f = File(dir, "hw_engine.log")
            if (f.length() > 200_000) f.writeText("")
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$ts [${Thread.currentThread().name}] $msg\n")
        } catch (_: Exception) {
        }
    }
}
