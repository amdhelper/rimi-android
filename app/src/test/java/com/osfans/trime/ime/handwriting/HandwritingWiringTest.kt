/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.handwriting

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * 手写输入接线回归锁（2026-11-15 爸爸令：入口在三点菜单 26键/9键 右侧）。
 *
 * 与 RimiSyncDialogSafetyTest 同款：纯 JVM 静态断言，守住五条结构性约束——
 * ① 菜单枚举有 Handwriting 且条目紧跟 9键之后（第一行第 3 格）；
 * ② 点击接 HandwritingWindow；
 * ③ JNI 源文件显式进 CMake（防 aux_source_directory 固化导致符号缺失）；
 * ④ Kotlin external 声明与 C++ JNI 符号逐字对齐；
 * ⑤ 模型 asset 真实存在（防打包漏掉 9MB 模型）。
 */
class HandwritingWiringTest :
    BehaviorSpec({
        val moduleRoot = File(".").absoluteFile
        fun source(rel: String): File =
            listOf(File(moduleRoot, rel), File(moduleRoot, "app/$rel")).first { it.isFile }

        Given("三点菜单入口") {
            val entrySrc = source("src/main/java/com/osfans/trime/ime/switches/SwitchOptionEntry.kt").readText()
            val windowSrc = source("src/main/java/com/osfans/trime/ime/switches/SwitchOptionWindow.kt").readText()

            Then("枚举包含 Handwriting") {
                entrySrc shouldContain "Handwriting,"
            }
            Then("手写条目紧跟 9键九宫格之后（26键/9键 右侧）") {
                val t9 = windowSrc.indexOf("SwitchOptionEntry.Static.Type.KeyboardT9,")
                val hw = windowSrc.indexOf("SwitchOptionEntry.Static.Type.Handwriting,")
                val theme = windowSrc.indexOf("SwitchOptionEntry.Static.Type.ThemeList,")
                (t9 >= 0 && hw > t9 && (theme < 0 || hw < theme)) shouldBe true
            }
            Then("点击打开 HandwritingWindow") {
                windowSrc shouldContain "SwitchOptionEntry.Static.Type.Handwriting ->"
                windowSrc shouldContain "windowManager.attachWindow(HandwritingWindow(di))"
            }
        }

        Given("JNI 构建接线") {
            val jniCmake = source("src/main/jni/librime_jni/CMakeLists.txt").readText()
            val rootCmake = source("src/main/jni/CMakeLists.txt").readText()

            Then("rimi_handwriting.cc 显式列入源文件") {
                jniCmake shouldContain "rimi_handwriting.cc"
            }
            Then("zinnia 静态库链进 rime_jni") {
                jniCmake shouldContain "zinnia"
                rootCmake shouldContain "add_subdirectory(zinnia)"
            }
        }

        Given("Kotlin ↔ C++ 符号对齐") {
            val kt = source("src/main/java/com/osfans/trime/data/handwriting/HandwritingNative.kt").readText()
            val cc = source("src/main/jni/librime_jni/rimi_handwriting.cc").readText()

            Then("三个 external 函数都有对应 JNI 实现") {
                listOf("nativeOpenModel", "nativeRecognize", "nativeCloseModel").forEach { name ->
                    kt shouldContain "external fun $name"
                    cc shouldContain "Java_com_osfans_trime_data_handwriting_HandwritingNative_$name"
                }
            }
        }

        Given("模型 asset") {
            val model = source("src/main/assets/handwriting/handwriting-zh_CN.model")
            Then("模型文件存在且非空") {
                (model.length() > 1_000_000) shouldBe true
            }
        }

        Given("画板区域视觉约定（2026-11-15 爸爸反馈）") {
            val windowSrc = source("src/main/java/com/osfans/trime/ime/handwriting/HandwritingWindow.kt").readText()
            val padSrc = source("src/main/java/com/osfans/trime/ime/handwriting/HandwritingPadView.kt").readText()

            Then("标题不随候选倍数放大") {
                windowSrc shouldContain "override val scaleTitle: Boolean = false"
            }
            Then("提示语用原始字号，只有真候选才缩放") {
                windowSrc shouldContain "setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.style.candidateTextSize)"
                windowSrc shouldContain "height = context.dp(theme.style.candidateViewHeight)"
                windowSrc shouldContain "height = context.dp(theme.style.scaledCandidateViewHeight)"
            }
            Then("画板底色区别于键盘背景（用键面色）") {
                padSrc shouldContain "setBackgroundColor(scope.colors.keyBackColor)"
            }
        }

        Given("悬浮候选行（2026-11-15 爸爸令：候选在三点菜单上方，不挤占画板）") {
            val windowSrc = source("src/main/java/com/osfans/trime/ime/handwriting/HandwritingWindow.kt").readText()
            val inputViewSrc = source("src/main/java/com/osfans/trime/ime/core/InputView.kt").readText()
            val serviceSrc = source("src/main/java/com/osfans/trime/ime/core/TrimeInputMethodService.kt").readText()

            Then("InputView 有悬浮宿主且锚在键盘面板上方") {
                inputViewSrc shouldContain "val floatingCandidateHost"
                // 宿主与 preedit 同锚位：above(keyboardView)
                val hostAdd = inputViewSrc.indexOf("floatingCandidateHost,")
                val anchor = inputViewSrc.indexOf("above(keyboardView)", hostAdd)
                (hostAdd >= 0 && anchor > hostAdd && anchor - hostAdd < 300) shouldBe true
            }
            Then("候选行不进画板布局，attach 时挂到宿主、detach 时收起") {
                val rootBlock = windowSrc.substringAfter("private val root by lazy").substringBefore("private fun styleCandidateRow")
                (rootBlock.contains("candidateScroll")) shouldBe false
                windowSrc shouldContain "inputView.floatingCandidateHost.apply {"
                windowSrc shouldContain "removeView(candidateScroll)"
            }
            Then("宿主可见时 onComputeInsets 把该区域并入可触摸区") {
                serviceSrc shouldContain "floatingCandidateHost"
                serviceSrc shouldContain "Insets.TOUCHABLE_INSETS_REGION"
                serviceSrc shouldContain "outInsets.touchableRegion.set(0, floatingHostLocation[1], decorView.width, decorView.height)"
            }
        }

        Given("手写自动上屏（2026-11-15 爸爸反馈：写完一个字没法直接写下一个）") {
            val windowSrc = source("src/main/java/com/osfans/trime/ime/handwriting/HandwritingWindow.kt").readText()
            val padSrc = source("src/main/java/com/osfans/trime/ime/handwriting/HandwritingPadView.kt").readText()
            val prefsSrc = source("src/main/java/com/osfans/trime/data/prefs/AppPrefs.kt").readText()

            Then("偏好存在且默认 1.5 秒（可关）") {
                prefsSrc shouldContain "HANDWRITING_AUTO_COMMIT"
                prefsSrc shouldContain "HandwritingAutoCommit.NORMAL"
                prefsSrc shouldContain "OFF(R.string.handwriting_auto_commit_off, 0L)"
            }
            Then("停笔超时自动上屏首选并清板，窗口已切走时不上屏") {
                windowSrc shouldContain "private fun scheduleAutoCommit()"
                windowSrc shouldContain "handwritingAutoCommit.getValue().delayMs"
                windowSrc shouldContain "if (!windowManager.isAttached(this@HandwritingWindow)) return@launch"
            }
            Then("新笔画落笔取消待发自动上屏（防多笔画字被拆开上屏）") {
                padSrc shouldContain "var onNewStrokeStart: (() -> Unit)? = null"
                val downBlock = padSrc.substringAfter("MotionEvent.ACTION_DOWN -> {").substringBefore("MotionEvent.ACTION_MOVE")
                downBlock shouldContain "onNewStrokeStart?.invoke()"
                windowSrc shouldContain "onNewStrokeStart = { autoCommitJob?.cancel() }"
            }
            Then("悬浮候选触摸双保险：insets 扩区 + TouchEventReceiverWindow 转发") {
                windowSrc shouldContain "TouchEventReceiverWindow(candidateScroll)"
                windowSrc shouldContain "addOnLayoutChangeListener(candidateLayoutListener)"
                windowSrc shouldContain "touchReceiver.dismiss()"
            }
        }
    })