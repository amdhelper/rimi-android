/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.osfans.trime.data.rimi.RimiCloud
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.util.AppUtils
import com.osfans.trime.util.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 日弥语音输入（M4）。
 *
 * 交互：长按键盘底排空格键 → 输入法窗口内浮出录音面板（立即开始录音）→「上屏」结束并识别。
 * 识别走 wumi 的 paraformer 通道 `POST /asr/transcribe`（body = 原始音频，JWT 鉴权），
 * 与桌面 rimi_voice / wumi App 同一条链路。结果直接 commitText 到当前输入框。
 *
 * ⚠️ 面板必须挂在输入法窗口内部（[TrimeInputMethodService.showOverlay]），不能用 Dialog：
 * 真机实测（PLR-AL30 / EMUI）APPLICATION_ATTACHED_DIALOG 能显示、能收 BACK，
 * 但触摸被系统手势窗口（GestureSildeOut）吃掉，按钮点不动。
 *
 * 录音格式：16k / 单声道 / PCM16 → WAV（服务端 ffmpeg 探测解码，格式不敏感）。
 */
object VoiceInputController {
    private const val SAMPLE_RATE = 16000
    private const val MAX_RECORD_MS = 120_000L
    private const val MIN_RECORD_BYTES = SAMPLE_RATE / 2 // 约 0.5s，太短不发

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var recorder: Recorder? = null
    private var panel: View? = null
    private var ticker: Job? = null

    fun start(service: TrimeInputMethodService) {
        if (recorder != null) return
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            service.toast("请先授予录音权限：日弥设置 → 日弥云同步 → 录音权限")
            // 直接落到「日弥云同步」页（录音权限开关就在这一页），不让用户自己在设置里找
            AppUtils.launchMainToRimiSync(service)
            return
        }
        if (!RimiCloud.isLoggedIn) {
            showLoginPrompt(service)
            Timber.i("Voice input blocked: not logged in, login prompt shown")
            return
        }
        val rec = Recorder()
        if (!rec.start()) {
            service.toast("打不开麦克风（可能被其它应用占用）")
            return
        }
        recorder = rec
        showPanel(service, rec)
        Timber.i("Voice input recording started")
    }

    /**
     * 未登录时的语音入口引导弹窗（2026-11-04 爸爸令）：toast 一闪而过用户看不懂，
     * 改成输入法窗口内 overlay 弹窗——问用户要不要登录、写清免费级别、给登录入口。
     * ⚠️ 必须走 showOverlay（同 showPanel 的教训：Dialog 触摸会被系统手势窗吞掉），
     * 且面板贴窗口底部（IME touchable region 只覆盖键盘区）。
     * 免费规则与桌面 rimi_voice 同口径：站长（founder）/医者（elder）免费云端，
     * 其余级别暂不提供免费云端转写。
     */
    private fun showLoginPrompt(service: TrimeInputMethodService) {
        val ctx = service
        val root =
            FrameLayout(ctx).apply {
                setBackgroundColor(0x99000000.toInt())
                isClickable = true
            }
        val box =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 18), dp(ctx, 18), dp(ctx, 18), dp(ctx, 18))
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(ctx, 20).toFloat()
                        setColor(0xFF1E1E2E.toInt())
                    }
            }
        val title =
            TextView(ctx).apply {
                text = "🎤 语音输入"
                setTextColor(0xFFCDD6F4.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
            }
        val body =
            TextView(ctx).apply {
                text = "语音转写需要登录 wumi 账户，要登录吗？\n\n" +
                    "免费范围：站长、医者——免费使用云端语音转写。\n" +
                    "其他级别（地纪行者、天纪卦师、人纪学员、麻瓜）：暂不提供免费云端转写。\n\n" +
                    "点「去登录」会直接打开登录界面：贴入 12 个助记词（wumi 备份里那串）或 64 位 seed 即可。"
                setTextColor(0xFFCDD6F4.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(ctx, 12), 0, dp(ctx, 16))
            }
        val buttons =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        val later =
            actionButton(ctx, "先不用", 0xFF45475A.toInt()).apply {
                setOnClickListener { closePanel() }
            }
        val login =
            actionButton(ctx, "去登录", 0xFF89B4FA.toInt()).apply {
                setOnClickListener {
                    closePanel()
                    // 2026-11-04 爸爸反馈：点「去登录」只打开设置首页，还要用户自己找
                    // 「日弥云同步 → 账户」。改成直达登录页并自动弹出登录框。
                    AppUtils.launchMainToRimiSync(service, promptLogin = true)
                }
            }
        buttons.addView(later, rowParams(ctx))
        buttons.addView(login, rowParams(ctx))
        box.addView(title)
        box.addView(body)
        box.addView(buttons)
        val width = minOf(service.resources.displayMetrics.widthPixels - dp(ctx, 24), dp(ctx, 360))
        root.addView(
            box,
            FrameLayout.LayoutParams(
                width,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(ctx, 36) },
        )
        panel = root
        service.showOverlay(root)
    }

    private fun showPanel(service: TrimeInputMethodService, rec: Recorder) {
        val ctx = service

        // 半透明遮罩 + 居中面板，全部落在输入法窗口内，触摸不会被系统手势窗吞掉
        val root =
            FrameLayout(ctx).apply {
                setBackgroundColor(0x99000000.toInt())
                isClickable = true
            }
        val box =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 18), dp(ctx, 18), dp(ctx, 18), dp(ctx, 18))
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(ctx, 20).toFloat()
                        setColor(0xFF1E1E2E.toInt())
                    }
            }
        val title =
            TextView(ctx).apply {
                text = "🎤 语音输入"
                setTextColor(0xFFCDD6F4.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
            }
        val status =
            TextView(ctx).apply {
                text = "正在录音…"
                setTextColor(0xFF89B4FA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 10), 0, dp(ctx, 14))
            }
        val buttons =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        val cancel =
            actionButton(ctx, "取消", 0xFF45475A.toInt()).apply {
                setOnClickListener { cancelRecording() }
            }
        val send =
            actionButton(ctx, "上屏", 0xFF89B4FA.toInt()).apply {
                setOnClickListener {
                    val wav = rec.stop()
                    if (wav.size <= 44 + MIN_RECORD_BYTES) {
                        service.toast("录音太短，请说久一点")
                        closePanel()
                        recorder = null
                        return@setOnClickListener
                    }
                    status.text = "识别中…"
                    isEnabled = false
                    cancel.isEnabled = false
                    Timber.i("Voice input sending %d bytes (%.1fs)", wav.size, (wav.size - 44) / 32000.0)
                    transcribeAndCommit(service, wav)
                }
            }
        buttons.addView(cancel, rowParams(ctx))
        buttons.addView(send, rowParams(ctx))
        box.addView(title)
        box.addView(status)
        box.addView(buttons)

        // ⚠️ 必须贴在输入法窗口底部：IME 窗口的 touchable region 只有键盘那一块
        // （真机实测 touchable region=(0,1640,1316,2832)），面板放在屏幕中间时按钮点不到。
        val width = minOf(service.resources.displayMetrics.widthPixels - dp(ctx, 24), dp(ctx, 360))
        root.addView(
            box,
            FrameLayout.LayoutParams(
                width,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(ctx, 36) },
        )
        panel = root
        service.showOverlay(root)

        val startedAt = SystemClock.elapsedRealtime()
        ticker =
            scope.launch {
                while (isActive && recorder === rec) {
                    delay(250)
                    // 键盘被收起（切走 App / 用户隐藏键盘）→ 立刻停录音，别占着麦克风
                    if (panel == null || !TrimeInputMethodService.isInputViewShowing) {
                        rec.stop()
                        recorder = null
                        withContext(Dispatchers.Main) { closePanel() }
                        break
                    }
                    val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000
                    withContext(Dispatchers.Main) {
                        if (recorder === rec) {
                            status.text = "正在录音… ${elapsed}s（最长 ${MAX_RECORD_MS / 1000}s）"
                        }
                    }
                    if (SystemClock.elapsedRealtime() - startedAt >= MAX_RECORD_MS) {
                        withContext(Dispatchers.Main) { send.performClick() }
                        break
                    }
                }
            }
    }

    /** 取消录音：停掉麦克风并关面板。 */
    fun cancelRecording() {
        ticker?.cancel()
        ticker = null
        recorder?.stop()
        recorder = null
        closePanel()
    }

    private fun closePanel() {
        panel = null
        runCatching { TrimeInputMethodService.instance?.hideOverlay() }
    }

    private fun transcribeAndCommit(service: TrimeInputMethodService, wav: ByteArray) {
        scope.launch {
            var text: String? = null
            var error: String? = null
            try {
                text = RimiCloud.transcribe(wav)
            } catch (e: Exception) {
                Timber.w(e, "Voice input transcribe failed")
                error = e.message ?: e.toString()
            }
            recorder = null
            Timber.i("Voice input result: text=%s error=%s", text, error)
            withContext(Dispatchers.Main) {
                closePanel()
                when {
                    error != null -> service.toast("语音识别失败：${error.take(80)}")
                    text.isNullOrBlank() -> service.toast("没有听清，再说一次？")
                    else -> {
                        service.toast(text)
                        service.commitText(text)
                    }
                }
            }
        }
    }

    private fun actionButton(
        service: TrimeInputMethodService,
        label: String,
        color: Int,
    ): TextView =
        TextView(service).apply {
            text = label
            val light = color == 0xFF89B4FA.toInt()
            setTextColor(if (light) 0xFF1E1E2E.toInt() else 0xFFCDD6F4.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            setPadding(dp(service, 18), dp(service, 10), dp(service, 18), dp(service, 10))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(service, 12).toFloat()
                    setColor(color)
                }
        }

    private fun rowParams(service: TrimeInputMethodService) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(service, 6)
            marginEnd = dp(service, 6)
        }

    private fun dp(
        service: TrimeInputMethodService,
        value: Int,
    ): Int = (value * service.resources.displayMetrics.density).toInt()

    /** 16k 单声道 PCM16 录音器：后台线程读，stop() 返回完整 WAV。 */
    private class Recorder {
        private var record: AudioRecord? = null
        private var thread: Thread? = null
        private val pcm = ByteArrayOutputStream(SAMPLE_RATE * 2 * 8)

        @Volatile
        private var running = false

        fun start(): Boolean {
            val minBuf =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            if (minBuf <= 0) return false
            val bufSize = max(minBuf * 2, 8192)
            val ar =
                try {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize,
                    )
                } catch (e: Exception) {
                    Timber.w(e, "AudioRecord create failed")
                    return false
                }
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                return false
            }
            return try {
                ar.startRecording()
                record = ar
                running = true
                thread =
                    Thread({
                        val buf = ByteArray(bufSize)
                        while (running) {
                            val n =
                                try {
                                    ar.read(buf, 0, buf.size)
                                } catch (e: Exception) {
                                    -1
                                }
                            if (n > 0) {
                                synchronized(pcm) { pcm.write(buf, 0, n) }
                            } else if (n < 0) {
                                break
                            }
                        }
                    }, "rimi-voice-recorder").apply { start() }
                true
            } catch (e: Exception) {
                Timber.w(e, "AudioRecord start failed")
                runCatching { ar.release() }
                false
            }
        }

        fun stop(): ByteArray {
            running = false
            runCatching { record?.stop() }
            runCatching { thread?.join(800) }
            runCatching { record?.release() }
            record = null
            thread = null
            val data = synchronized(pcm) { pcm.toByteArray() }
            return wav(data)
        }

        private fun wav(pcmData: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(44 + pcmData.size)
            val byteRate = SAMPLE_RATE * 2
            fun le32(v: Int) {
                out.write(v and 0xff)
                out.write((v shr 8) and 0xff)
                out.write((v shr 16) and 0xff)
                out.write((v shr 24) and 0xff)
            }
            fun le16(v: Int) {
                out.write(v and 0xff)
                out.write((v shr 8) and 0xff)
            }
            out.write("RIFF".toByteArray())
            le32(36 + pcmData.size)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            le32(16)
            le16(1) // PCM
            le16(1) // mono
            le32(SAMPLE_RATE)
            le32(byteRate)
            le16(2) // block align
            le16(16) // bits
            out.write("data".toByteArray())
            le32(pcmData.size)
            out.write(pcmData)
            return out.toByteArray()
        }
    }
}
