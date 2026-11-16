/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.handwriting

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.osfans.trime.data.handwriting.HandwritingRecognizer
import com.osfans.trime.data.handwriting.HwDebugLog
import com.osfans.trime.data.handwriting.HwTraceReplay
import timber.log.Timber

/**
 * 手写笔迹回放入口（取证工具，2026-11-16 换引擎验收）：
 * adb shell am broadcast -a com.rimi.ime.action.HW_REPLAY \
 *   -n com.rimi.ime/.ime.handwriting.HwReplayReceiver
 *
 * 安全门 = manifest 上的 android:permission="android.permission.DUMP"：
 * 只有持有 DUMP 权限的发送者（adb shell / 系统）能投递，第三方应用拿不到该权限。
 * ⚠️ 不能用 Binder.getCallingUid() 判发送者——广播由 system_server 转发，
 * onReceive 里拿到的是系统 uid(1000)，不是发送者（2026-11-16 实测踩坑）。
 * 只读写本应用外部私有目录（hw_trace.jsonl → hw_replay.jsonl），不联网、不上传。
 */
class HwReplayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        HwDebugLog.log(appContext, "receiver onReceive action=${intent.action}")
        if (intent.action != ACTION) return
        Thread(
            {
                try {
                    HwDebugLog.log(appContext, "replay thread start")
                    val ready = HandwritingRecognizer.ensureModel(appContext)
                    Timber.i("hw-replay: engine ready=%s", ready)
                    HwDebugLog.log(appContext, "replay engine ready=$ready")
                    val out = HwTraceReplay.replay(appContext)
                    HwDebugLog.log(appContext, "replay done out=${out?.absolutePath}")
                } catch (t: Throwable) {
                    HwDebugLog.log(appContext, "replay FAILED: ${t.message}\n${t.stackTraceToString().take(1500)}")
                    Timber.e(t, "hw-replay failed")
                }
            },
            "hw-replay",
        ).start()
    }

    companion object {
        const val ACTION = "com.rimi.ime.action.HW_REPLAY"
    }
}
