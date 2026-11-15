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
    })