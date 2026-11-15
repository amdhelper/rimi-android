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
import android.view.View
import android.view.ViewGroup
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
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.scaledCandidateTextSize
import com.osfans.trime.data.theme.model.scaledCandidateViewHeight
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.window.BoardWindow
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
 * 完全离线：zinnia + Tegaki 简体模型，写完一笔自动识别，候选字点一下上屏，
 * 上屏后自动清空画板继续写下一个字。布局 = 候选行 + 画板 + 控制行，
 * 高度跟随当前键盘高度（InputView 用 currentKeyboardHeight 设定窗口高度）。
 */
class HandwritingWindow(di: DI) : BoardWindow.BarBoardWindow(di) {
    private val service: TrimeInputMethodService by instance()
    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val theme: Theme get() = scope.theme

    override val title: String get() = context.getString(R.string.handwriting_input)

    // 标题不随候选倍数放大（UI chrome，放大挤占画板；2026-11-15 爸爸反馈）。
    override val scaleTitle: Boolean = false

    private var recognizeJob: Job? = null
    private var viewCreated = false
    private var lastCandidates: List<String> = emptyList()
    private var hintRes: Int = R.string.handwriting_hint_write

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
            fun rowParams() = lParams(0, context.dp(48), weight = 1f).apply {
                marginStart = gap
                marginEnd = gap
            }
            add(makeControlButton(R.string.handwriting_undo) { pad.undo() }, rowParams())
            add(makeControlButton(R.string.handwriting_clear) { pad.clear() }, rowParams())
            add(makeControlButton(R.string.handwriting_space) { service.commitText(" ") }, rowParams())
            add(makeControlButton(R.string.handwriting_backspace) { sendBackspace() }, rowParams())
        }
    }

    private val root by lazy {
        context.verticalLayout {
            add(
                candidateScroll,
                lParams(matchParent, context.dp(theme.style.scaledCandidateViewHeight)),
            )
            add(pad, lParams(matchParent, 0, weight = 1f))
            add(controlRow, lParams(matchParent, wrapContent).apply {
                topMargin = context.dp(4)
                bottomMargin = context.dp(4)
            })
        }
    }

    private fun styleControlButton(button: TextView) {
        button.setTextColor(scope.colors.keyTextColor)
        button.background = GradientDrawable().apply {
            setColor(scope.colors.keyBackColor)
            cornerRadius = context.dp(8).toFloat()
        }
    }

    private fun showHint(textRes: Int) {
        hintRes = textRes
        lastCandidates = emptyList()
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
                    service.commitText(word)
                    pad.clear()
                }
            }
            candidateRow.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        candidateScroll.scrollTo(0, 0)
    }

    /** 写完一笔后防抖 150ms 再识别；识别在 Default 线程，不阻塞 UI。 */
    private fun scheduleRecognize() {
        recognizeJob?.cancel()
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
        recognizeJob?.cancel()
        recognizeJob = null
    }

    override fun refreshColors() {
        if (!viewCreated) return
        pad.refreshColors()
        controlButtons.forEach { styleControlButton(it) }
        if (lastCandidates.isEmpty()) showHint(hintRes) else showCandidates(lastCandidates)
    }
}