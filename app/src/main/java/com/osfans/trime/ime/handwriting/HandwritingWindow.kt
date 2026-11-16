/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.handwriting

import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.RimeApi
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.handwriting.HandwritingRecognizer
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.scaledCandidateTextSize
import com.osfans.trime.data.theme.model.scaledCandidateViewHeight
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TouchEventReceiverWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.voice.VoiceInputController
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent

/**
 * 手写输入窗口（三点菜单 → 手写输入）。
 *
 * 完全离线：雨燕引擎（Paddle-Lite v2.14 + PP-OCRv5 简体模型，覆盖 18382 字），写完一笔自动识别，候选字点一下上屏，
 * 上屏后自动清空画板继续写下一个字。布局 = 画板 + 控制行，
 * 高度跟随当前键盘高度（InputView 用 currentKeyboardHeight 设定窗口高度）。
 *
 * 候选行悬浮在键盘面板上方（三点菜单之上，InputView.floatingCandidateHost）：
 * 放大候选不再挤占画板（2026-11-15 爸爸令）。触摸由 service.onComputeInsets
 * 把宿主区域并入可触摸区保证。
 */
class HandwritingWindow(di: DI) : BoardWindow.BarBoardWindow(di) {
    private val service: TrimeInputMethodService by instance()
    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val inputView: InputView by instance()
    private val windowManager: BoardWindowManager by instance()
    private val theme: Theme get() = scope.theme

    override val title: String get() = context.getString(R.string.handwriting_input)

    // 标题不随候选倍数放大（UI chrome，放大挤占画板；2026-11-15 爸爸反馈）。
    override val scaleTitle: Boolean = false

    private var recognizeJob: Job? = null
    private var autoCommitJob: Job? = null
    private var viewCreated = false
    private var lastCandidates: List<String> = emptyList()
    private var hintRes: Int = R.string.handwriting_hint_write

    // 手写窗加高（2026-11-16 爸爸反馈「手写区域太低，笔画写到底部就丢」）：
    // 画板高度 = 窗高 - 标题栏 - 控制行，默认跟随键盘高度偏矮，向下笔画（撇/竖钩收尾）
    // 容易冲出画板底部落到控制行/手势区被吞。进窗把窗高抬 40%，画板更大更好写；
    // 退窗恢复原高，不影响普通键盘。
    // 同日二次反馈「高度还可以再增加一点，减少失误区域」：30% → 40%。
    private var savedWindowHeight = 0

    private val candidateRow by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    private val candidateScroll by lazy {
        HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            add(candidateRow, lParams(matchParent, matchParent))
        }
    }

    private val pad by lazy {
        HandwritingPadView(context, scope).apply {
            onStrokeChanged = { scheduleRecognize() }
            // 落笔即取消待发的自动上屏：防止写下一个字写到一半时上一个字被提交。
            onNewStrokeStart = { autoCommitJob?.cancel() }
        }
    }

    /**
     * 悬浮候选行触摸双保险：onComputeInsets 已把宿主并入 IME 可触摸区，
     * 另挂 TouchEventReceiverWindow（preedit/CandidatesView 同款成熟模式）
     * 转发触摸，防个别 OEM 对 IME 触摸区扩展支持不一致导致候选点不到。
     */
    private val touchReceiver by lazy { TouchEventReceiverWindow(candidateScroll) }

    private val candidateLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (candidateScroll.isAttachedToWindow && candidateScroll.width > 0 && candidateScroll.height > 0) {
                touchReceiver.show()
            } else {
                touchReceiver.dismiss()
            }
        }

    private val controlButtons = mutableListOf<TextView>()

    private fun makeControlButton(labelRes: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            setText(labelRes)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { onClick() }
            controlButtons.add(this)
        }

    private val controlRow by lazy {
        context.horizontalLayout {
            val gap = context.dp(4)
            // 2026-11-16 爸爸反馈：最左侧撤销键多余（停笔自动上屏+清空已覆盖），移除；
            // 空格面积最大化（独占 weight=1f），清空/退格只占小份固定权重。
            fun sideParams() = lParams(0, context.dp(48), weight = 0.35f).apply {
                marginStart = gap
                marginEnd = gap
            }
            add(makeControlButton(R.string.handwriting_clear) { pad.clear() }, sideParams())
            add(
                makeControlButton(R.string.handwriting_space) { service.commitText(" ") }.apply {
                    // 2026-11-16 爸爸令：所有模式空格长按呼出 ASR——手写窗空格同等待遇。
                    // 按住录音、松开自动发送（与键盘 KeyView 的 voice_input 释放语义一致；
                    // stopAndSend 有 recorder?:return 空闲守卫，短按空格上屏不受影响）。
                    setOnLongClickListener {
                        VoiceInputController.start(service)
                        true
                    }
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_UP ||
                            event.actionMasked == MotionEvent.ACTION_CANCEL
                        ) {
                            VoiceInputController.stopAndSend()
                        }
                        false
                    }
                },
                lParams(0, context.dp(48), weight = 1f).apply {
                    marginStart = gap
                    marginEnd = gap
                },
            )
            add(makeControlButton(R.string.handwriting_backspace) { sendBackspace() }, sideParams())
        }
    }

    private val root by lazy {
        context.verticalLayout {
            // 候选行不在此布局内——悬浮到键盘面板上方（见 onAttached），画板拿满高度。
            add(pad, lParams(matchParent, 0, weight = 1f))
            add(controlRow, lParams(matchParent, wrapContent).apply {
                topMargin = context.dp(4)
                bottomMargin = context.dp(4)
            })
        }
    }

    /** 悬浮候选行底色：与键盘面板一致，盖在应用内容上仍清晰可读。 */
    private fun styleCandidateRow() {
        candidateScroll.background = GradientDrawable().apply {
            setColor(scope.colors.keyboardBackColor)
        }
    }

    private fun styleControlButton(button: TextView) {
        button.setTextColor(scope.colors.keyTextColor)
        button.background = GradientDrawable().apply {
            setColor(scope.colors.keyBackColor)
            cornerRadius = context.dp(8).toFloat()
            // 2026-11-16 爸爸反馈「底部三个按钮边界形态更明显」：2dp 描边。
            // 用 labelColor（深色主题=浅灰 0x94a3b8，任何主题都回落到可读文字色），
            // 键面与画板同色时靠描边一眼分出按钮区，减少笔画误落。
            setStroke(context.dp(2), scope.colors.labelColor)
        }
    }

    private fun showHint(textRes: Int) {
        hintRes = textRes
        lastCandidates = emptyList()
        autoCommitJob?.cancel()
        // 提示语不随候选倍数放大：它是引导文字，放大只会挤占画板（2026-11-15 爸爸反馈）。
        candidateScroll.updateLayoutParams { height = context.dp(theme.style.candidateViewHeight) }
        candidateRow.removeAllViews()
        val hint = TextView(context).apply {
            setText(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.style.candidateTextSize)
            setTextColor(scope.colors.commentTextColor)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(12), 0, context.dp(12), 0)
        }
        candidateRow.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showCandidates(candidates: List<String>) {
        lastCandidates = candidates
        candidateRow.removeAllViews()
        if (candidates.isEmpty()) {
            showHint(R.string.handwriting_hint_write)
            return
        }
        // 只有真候选字才随倍数放大（候选行高同步），提示态保持原始高度。
        candidateScroll.updateLayoutParams { height = context.dp(theme.style.scaledCandidateViewHeight) }
        candidates.forEach { word ->
            val item = TextView(context).apply {
                text = word
                setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.style.scaledCandidateTextSize)
                setTextColor(scope.colors.candidateTextColor)
                gravity = Gravity.CENTER
                setPadding(context.dp(14), 0, context.dp(14), 0)
                setOnClickListener {
                    // 上屏后清空画板，连续写下一个字（标准手写输入法交互）。
                    // 自动上屏刚清过板时（lastCandidates 已空）忽略迟到的点击，防重复上屏。
                    if (lastCandidates.isEmpty()) return@setOnClickListener
                    autoCommitJob?.cancel()
                    service.commitText(word)
                    pad.clear()
                }
            }
            candidateRow.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        candidateScroll.scrollTo(0, 0)
        scheduleAutoCommit()
    }

    /**
     * 自动上屏（2026-11-15 爸爸反馈：写完一个字墨迹赖在画板，没法直接写下一个）。
     * 停笔超过设定时长 → 首选候选自动上屏 + 清板，与搜狗/百度手写同款交互。
     * 新笔画落笔会取消计时（onNewStrokeStart），识别更新后重新计时，
     * 因此多笔画字（十/二…）连续书写不会被拆成单笔画上屏。
     */
    private fun scheduleAutoCommit() {
        autoCommitJob?.cancel()
        val delayMs = AppPrefs.defaultInstance().keyboard.handwritingAutoCommit.getValue().delayMs
        if (delayMs <= 0 || lastCandidates.isEmpty()) return
        // 部件组合歧义（行草 灿 → 仙/汕/灿 并存）：等用户选字，不自动上屏
        // （2026-11-16 爸爸反馈；你/好 等高置信整字不触发，自动上屏照常）。
        if (HandwritingRecognizer.lastCompositionAmbiguous) return
        autoCommitJob = service.lifecycleScope.launch {
            delay(delayMs)
            // 窗口可能已销毁/切走（InputView 重建不一定走 onDetached）：只在仍是当前窗时上屏。
            if (!windowManager.isAttached(this@HandwritingWindow)) return@launch
            val word = lastCandidates.firstOrNull() ?: return@launch
            service.commitText(word)
            // 同步清空候选，挡住与点击上屏的竞态（pad.clear 的回调是异步的）。
            lastCandidates = emptyList()
            pad.clear()
        }
    }

    /** 写完一笔后防抖 150ms 再识别；识别在 Default 线程，不阻塞 UI。 */
    private fun scheduleRecognize() {
        recognizeJob?.cancel()
        // 笔画变了，旧候选的自动上屏计时作废（识别完成后按新候选重新计时）。
        autoCommitJob?.cancel()
        recognizeJob = service.lifecycleScope.launch {
            delay(150)
            val strokes = pad.snapshot()
            val width = pad.width
            val height = pad.height
            val result = withContext(Dispatchers.Default) {
                HandwritingRecognizer.recognize(service, strokes, width, height)
            }
            showCandidates(result)
        }
    }

    private fun sendBackspace() {
        val ic = service.currentInputConnection ?: return
        val down = SystemClock.uptimeMillis()
        ic.sendKeyEvent(
            KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD),
        )
        ic.sendKeyEvent(
            KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD),
        )
    }

    override fun onCreateView(): View {
        viewCreated = true
        return root
    }

    override fun onAttached() {
        // 进窗加高画板（爸爸反馈手写区太矮丢笔画）：抬 40%，退窗恢复。
        val lp = windowManager.view.layoutParams
        if (lp.height > 0 && savedWindowHeight == 0) {
            savedWindowHeight = lp.height
            lp.height = (savedWindowHeight * 1.4f).toInt()
            windowManager.view.layoutParams = lp
        }
        // 候选行悬浮到键盘面板上方（三点菜单之上）：放大候选不挤占画板。
        // 宿主是 FrameLayout，必须换用 FrameLayout.LayoutParams。
        styleCandidateRow()
        inputView.floatingCandidateHost.apply {
            addView(
                candidateScroll,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(theme.style.candidateViewHeight),
                ),
            )
            visibility = View.VISIBLE
        }
        // 每次布局（含提示态↔候选态高度变化）后同步触摸转发窗位置。
        candidateScroll.addOnLayoutChangeListener(candidateLayoutListener)
        controlButtons.forEach { styleControlButton(it) }
        showHint(R.string.handwriting_loading)
        // 进入手写前把键盘上未上屏的组合先落定，避免残留预编辑混进手写文本。
        // commitComposition 是 suspend：launchOnReady 的 lambda 非挂起上下文，
        // 必须在 lifecycleScope 里包一层（与 switchToSchema 同款）。
        rime.launchOnReady { api: RimeApi ->
            service.lifecycleScope.launch { api.commitComposition() }
        }
        service.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { HandwritingRecognizer.ensureModel(service) }
            if (!ok) {
                showHint(R.string.handwriting_load_failed)
            } else if (pad.snapshot().isEmpty()) {
                showHint(R.string.handwriting_hint_write)
            }
        }
    }

    override fun onDetached() {
        // 恢复普通键盘窗高（进窗时抬了 40%）。
        if (savedWindowHeight > 0) {
            windowManager.view.updateLayoutParams { height = savedWindowHeight }
            savedWindowHeight = 0
        }
        recognizeJob?.cancel()
        recognizeJob = null
        autoCommitJob?.cancel()
        autoCommitJob = null
        // 收起悬浮候选行，恢复普通键盘的可触摸区（onComputeInsets 随布局自动回退）。
        candidateScroll.removeOnLayoutChangeListener(candidateLayoutListener)
        touchReceiver.dismiss()
        inputView.floatingCandidateHost.apply {
            removeView(candidateScroll)
            visibility = View.GONE
        }
    }

    override fun refreshColors() {
        if (!viewCreated) return
        pad.refreshColors()
        styleCandidateRow()
        controlButtons.forEach { styleControlButton(it) }
        if (lastCandidates.isEmpty()) showHint(hintRes) else showCandidates(lastCandidates)
    }
}