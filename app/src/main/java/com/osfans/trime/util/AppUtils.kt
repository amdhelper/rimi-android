/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util

import android.content.Context
import android.content.Intent
import android.util.SparseArray
import android.view.KeyEvent
import com.osfans.trime.data.rimi.RimiLoginRequest
import com.osfans.trime.ui.main.ClipEditActivity
import com.osfans.trime.ui.main.LogActivity
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.ui.main.NavigationRoute
import timber.log.Timber

object AppUtils {
    private val applicationLaunchKeyCategories =
        SparseArray<String>().apply {
            append(KeyEvent.KEYCODE_EXPLORER, Intent.CATEGORY_APP_BROWSER)
            append(KeyEvent.KEYCODE_ENVELOPE, Intent.CATEGORY_APP_EMAIL)
            append(KeyEvent.KEYCODE_CONTACTS, Intent.CATEGORY_APP_CONTACTS)
            append(KeyEvent.KEYCODE_CALENDAR, Intent.CATEGORY_APP_CALENDAR)
            append(KeyEvent.KEYCODE_MUSIC, Intent.CATEGORY_APP_MUSIC)
            append(KeyEvent.KEYCODE_CALCULATOR, Intent.CATEGORY_APP_CALCULATOR)
        }

    fun launchKeyCategory(
        context: Context,
        keyCode: Int,
    ): Boolean = applicationLaunchKeyCategories[keyCode]?.let {
        Timber.d("launchKeyCategory: $it")
        try {
            context.startActivity(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, it).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
                },
            )
            true
        } catch (_: Exception) {
            false
        }
    } ?: false

    fun launchMainActivity(context: Context) {
        context.startActivity<MainActivity> {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
    }

    private fun launchMainToDest(
        context: Context,
        route: NavigationRoute,
    ) {
        context.startActivity<MainActivity> {
            action = Intent.ACTION_RUN
            putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, route)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
    }

    fun launchMainToSchemaList(context: Context) = launchMainToDest(context, NavigationRoute.SchemaList)

    fun launchMainToKeyboard(context: Context) = launchMainToDest(context, NavigationRoute.VirtualKeyboard)

    /**
     * 直接打开「日弥云同步 → 账户」页（wumi 登录界面所在页）。
     *
     * [promptLogin] = true 时同时挂一条一次性请求，页面 onResume 会立刻弹出登录框，
     * 用户不用自己再点「账户」——键盘语音入口点「去登录」走的就是这条路径。
     */
    fun launchMainToRimiSync(
        context: Context,
        promptLogin: Boolean = false,
    ) {
        if (promptLogin) RimiLoginRequest.request()
        launchMainToDest(context, NavigationRoute.RimiSync)
    }

    fun launchLogActivity(context: Context) {
        context.startActivity<LogActivity>()
    }

    fun launchClipEdit(
        context: Context,
        id: Int,
        type: String,
    ) {
        context.startActivity<ClipEditActivity> {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ClipEditActivity.BEAN_ID, id)
            putExtra(ClipEditActivity.CLIP_TYPE, type)
        }
    }
}
