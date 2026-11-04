/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.rimi

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次性的「打开 wumi 登录界面」请求。
 *
 * 键盘语音入口（空格长按 → 未登录弹窗 →「去登录」）与设置页都可能触发：
 * 先 [request]，再由 [com.osfans.trime.ui.main.settings.RimiSyncFragment] 在 onResume
 * 里 [consume] 并立即弹出登录框。用一次性标志而不是 Intent extra，是因为该请求要能穿过
 * `startActivity → onNewIntent → navigate` 这条链路，且只在落地时生效一次。
 */
object RimiLoginRequest {
    private val pending = AtomicBoolean(false)

    fun request() {
        pending.set(true)
    }

    /** 读取并清除请求；返回 true 表示应当立刻弹出登录框。 */
    fun consume(): Boolean = pending.getAndSet(false)
}
