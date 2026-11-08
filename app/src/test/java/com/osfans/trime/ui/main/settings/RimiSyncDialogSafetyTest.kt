/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * 真机崩溃回归锁（2026-11-09，Mate40 Pro）
 *
 * 现象：设置页「日弥云同步」的登录框点确定直接崩，屏幕弹出《崩溃日志》：
 * `java.lang.IllegalStateException: Fragment RimiSyncFragment ... not attached to a context`
 * → `RimiSyncFragment.login()` 里的 `requireContext()`。
 *
 * 根因：手动 new 的 AlertDialog 挂在 Activity 窗口上，**不随 Fragment 销毁**。
 * 键盘「去登录」再发一次 `ACTION_RUN` 时 `MainActivity.processIntent` 会
 * `popBackStack(Main)+navigate(RimiSync)`，旧 Fragment 被销毁而旧对话框还留在屏幕上，
 * 再点它的按钮就回到已 detach 的旧实例执行 `requireContext()` → 进程崩溃。
 *
 * 与 ThemeDslExpanderTest 同款做法：纯 JVM 静态断言，不引 Robolectric，
 * 只守住三条结构性约束，防止以后改动把坑挖回来。
 */
class RimiSyncDialogSafetyTest :
    BehaviorSpec({
        val moduleRoot = File(".").absoluteFile
        val fragmentFile =
            listOf(
                File(moduleRoot, "src/main/java/com/osfans/trime/ui/main/settings/RimiSyncFragment.kt"),
                File(moduleRoot, "app/src/main/java/com/osfans/trime/ui/main/settings/RimiSyncFragment.kt"),
            ).first { it.isFile }
        val mainActivityFile =
            listOf(
                File(moduleRoot, "src/main/java/com/osfans/trime/ui/main/MainActivity.kt"),
                File(moduleRoot, "app/src/main/java/com/osfans/trime/ui/main/MainActivity.kt"),
            ).first { it.isFile }

        Given("RimiSyncFragment 手动创建的对话框") {
            val src = fragmentFile.readText()

            Then("必须登记并在 onDestroyView 里收掉，不能留给已 detach 的 Fragment") {
                src shouldContain "private val trackedDialogs = mutableListOf<AlertDialog>()"
                src shouldContain "override fun onDestroyView()"
                src shouldContain "trackedDialogs.toList().forEach { runCatching { it.dismiss() } }"
            }

            Then("登录/退出/服务器三个对话框都必须走 showTracked()") {
                val callSites =
                    src.lineSequence().count { ".showTracked()" in it && "private fun" !in it }
                callSites shouldBe 3
            }

            Then("登录框不能被 onResume 自动弹框叠两层") {
                src shouldContain "if (loginDialog?.isShowing == true) return"
            }
        }

        Given("对话框与偏好回调里的 Context 获取") {
            val src = fragmentFile.readText()

            Then("只允许 onCreatePreferences 里用 requireContext（创建 Preference 时必然 attached）") {
                Regex("""requireContext\(\)""").findAll(src).count() shouldBe 1
            }

            Then("login/promptLogin 等入口必须先判空 context 再干活") {
                src shouldContain "private fun login(secret: String) {\n        val ctx = context ?: return"
                src shouldContain "private fun promptLogin() {\n        val ctx = context ?: return"
                src shouldContain "private fun syncNow() {\n        if (!RimiCloud.isLoggedIn) return\n        val ctx = context ?: return"
            }
        }

        Given("MainActivity 收到重复的 ACTION_RUN") {
            val src = mainActivityFile.readText()

            Then("已经在目标页时必须直接返回，不能再 popBackStack + navigate 销毁 Fragment") {
                val guard = src.indexOf("hasRoute(route::class)")
                val pop = src.indexOf("popBackStack(NavigationRoute.Main, false)")
                (guard > 0) shouldBe true
                (pop > guard) shouldBe true
            }
        }
    })
