/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.handwriting

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * 真实笔迹回放验证器（取证工具，2026-11-16 换引擎验收）。
 *
 * 读 hw_trace.jsonl（爸爸真实手写时落盘的原始笔画），逐条喂进当前引擎，
 * 把候选写进 hw_replay.jsonl（保留旧引擎 cand 字段作对照）。
 * 只由 [com.osfans.trime.ime.handwriting.HwReplayReceiver] 触发（仅 adb shell/root），
 * 不在正常输入流程里运行；recognize 传 trace=false，不回写轨迹文件。
 */
object HwTraceReplay {
    /**
     * 标记文件触发（2026-11-16）：adb push hw_replay.flag 到外部私有目录后，
     * 任意键盘弹出（onStartInputView）即自动回放。华为设备拦截自定义广播，
     * HwReplayReceiver 不可靠，此路在 IME 进程内执行，不依赖广播投递。
     * 无标记文件时只做一次 stat，零开销；标记触发后立即删除（一次性）。
     */
    fun maybeReplay(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            val flag = File(dir, "hw_replay.flag")
            if (!flag.isFile) return
            flag.delete()
            val appContext = context.applicationContext
            Thread(
                {
                    try {
                        HwDebugLog.log(appContext, "flag-triggered replay start")
                        val ready = HandwritingRecognizer.ensureModel(appContext)
                        HwDebugLog.log(appContext, "flag replay engine ready=$ready")
                        val out = replay(appContext)
                        HwDebugLog.log(appContext, "flag replay done out=${out?.absolutePath}")
                    } catch (t: Throwable) {
                        HwDebugLog.log(appContext, "flag replay FAILED: ${t.message}")
                    }
                },
                "hw-replay-flag",
            ).start()
        } catch (_: Exception) {
        }
    }

    fun replay(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val src = File(dir, "hw_trace.jsonl")
        if (!src.isFile) {
            Timber.w("hw-replay: no hw_trace.jsonl")
            return null
        }
        val out = File(dir, "hw_replay.jsonl")
        out.writeText("")
        var count = 0
        src.useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                try {
                    val obj = JSONObject(line)
                    val arr = obj.getJSONArray("strokes")
                    val strokes = ArrayList<List<InkPoint>>(arr.length())
                    for (i in 0 until arr.length()) {
                        val s = arr.getJSONArray(i)
                        val pts = ArrayList<InkPoint>(s.length())
                        for (j in 0 until s.length()) {
                            val p = s.getJSONArray(j)
                            pts.add(InkPoint(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()))
                        }
                        strokes.add(pts)
                    }
                    val cand = HandwritingRecognizer.recognize(context, strokes, 0, 0, trace = false)
                    val rec = JSONObject()
                    rec.put("i", index)
                    rec.put("strokes", strokes.size)
                    rec.put("pts", strokes.sumOf { it.size })
                    rec.put("old", obj.optString("cand", ""))
                    rec.put("cand", JSONArray(cand))
                    out.appendText(rec.toString() + "\n")
                    count++
                    Timber.i("hw-replay: #%d strokes=%d cand=%s", index, strokes.size, cand.take(6))
                } catch (e: Exception) {
                    Timber.w(e, "hw-replay: bad line %d", index)
                }
            }
        }
        Timber.i("hw-replay: done %d traces -> %s", count, out.absolutePath)
        return out
    }
}
