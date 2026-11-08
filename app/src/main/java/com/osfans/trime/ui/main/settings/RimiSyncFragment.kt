/*
 * SPDX-FileCopyrightText: 2026 Zoom IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.osfans.trime.R
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.rimi.RimiCloud
import com.osfans.trime.data.rimi.RimiDictSync
import com.osfans.trime.data.rimi.RimiLoginRequest
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.withLoadingDialog
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.customFormatTimeInDefault
import com.osfans.trime.util.toast
import com.osfans.trime.worker.RimiCloudSyncWork
import kotlinx.coroutines.launch

/**
 * 非全盲视障优化输入法云同步设置页：wumi 账户登录 + 词库双向同步（M3）。
 */
class RimiSyncFragment : PaddingPreferenceFragment() {
    private lateinit var accountPreference: Preference
    private lateinit var logoutPreference: Preference
    private lateinit var syncNowPreference: Preference
    private lateinit var autoSyncPreference: SwitchPreferenceCompat
    private lateinit var screenOffOnlyPreference: SwitchPreferenceCompat
    private lateinit var intervalPreference: EditTextIntPreference
    private lateinit var statusPreference: Preference
    private lateinit var serverPreference: Preference
    private lateinit var voicePermissionPreference: Preference

    /**
     * 手动创建的对话框挂在 Activity 窗口上，不会随 Fragment 销毁。
     * Fragment 被导航替换后，旧对话框仍留在屏幕上，点按钮会回到已 detach 的
     * 旧实例执行 `requireContext` → IllegalStateException 崩进程
     * （真机实测：`Fragment RimiSyncFragment ... not attached to a context`）。
     * 所以这里统一登记，onDestroyView 时全部收掉。
     */
    private val trackedDialogs = mutableListOf<AlertDialog>()
    private var loginDialog: AlertDialog? = null

    private fun AlertDialog.showTracked(): AlertDialog {
        trackedDialogs += this
        setOnDismissListener { trackedDialogs.remove(this) }
        show()
        return this
    }

    override fun onDestroyView() {
        trackedDialogs.toList().forEach { runCatching { it.dismiss() } }
        trackedDialogs.clear()
        loginDialog = null
        super.onDestroyView()
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val ctx = requireContext()
        preferenceScreen =
            preferenceManager.createPreferenceScreen(ctx).apply {
                addCategory(R.string.rimi_cloud_account) {
                    isIconSpaceReserved = false
                    addPreference(
                        Preference(ctx).apply {
                            accountPreference = this
                            isIconSpaceReserved = false
                            setTitle(R.string.rimi_cloud_account_title)
                            setOnPreferenceClickListener {
                                if (RimiCloud.isLoggedIn) {
                                    context.toast(R.string.rimi_cloud_already_logged_in)
                                } else {
                                    promptLogin()
                                }
                                true
                            }
                        },
                    )
                    addPreference(
                        Preference(ctx).apply {
                            logoutPreference = this
                            isIconSpaceReserved = false
                            setTitle(R.string.rimi_cloud_logout)
                            setOnPreferenceClickListener {
                                confirmLogout()
                                true
                            }
                        },
                    )
                }
                addCategory(R.string.rimi_cloud_sync_category) {
                    isIconSpaceReserved = false
                    addPreference(
                        Preference(ctx).apply {
                            syncNowPreference = this
                            isIconSpaceReserved = false
                            setTitle(R.string.rimi_cloud_sync_now)
                            setSummary(R.string.rimi_cloud_sync_now_summary)
                            setOnPreferenceClickListener {
                                syncNow()
                                true
                            }
                        },
                    )
                    addPreference(
                        SwitchPreferenceCompat(ctx).apply {
                            autoSyncPreference = this
                            key = "rimi_cloud_auto_sync"
                            isPersistent = false
                            isIconSpaceReserved = false
                            setTitle(R.string.rimi_cloud_auto_sync)
                            setSummary(R.string.rimi_cloud_auto_sync_summary)
                            setOnPreferenceChangeListener { _, newValue ->
                                RimiCloud.autoSync = newValue as Boolean
                                RimiCloudSyncWork.restart(context)
                                true
                            }
                        },
                    )
                    addPreference(
                        SwitchPreferenceCompat(ctx).apply {
                            screenOffOnlyPreference = this
                            key = "rimi_cloud_screen_off_only"
                            isPersistent = false
                            isIconSpaceReserved = false
                            setTitle(R.string.rimi_cloud_screen_off_only)
                            setSummary(R.string.rimi_cloud_screen_off_only_summary)
                            setOnPreferenceChangeListener { _, newValue ->
                                RimiCloud.syncOnlyWhenScreenOff = newValue as Boolean
                                RimiCloudSyncWork.restart(context)
                                true
                            }
                        },
                    )
                    addPreference(
                        EditTextIntPreference(ctx).apply {
                            intervalPreference = this
                            key = "rimi_cloud_sync_interval_pref"
                            isIconSpaceReserved = false
                            setTitle(R.string.rimi_cloud_sync_interval)
                            min = RimiCloud.MIN_SYNC_INTERVAL_MINUTES
                            unit = getString(R.string.rimi_cloud_minutes)
                            setDefaultValue(RimiCloud.syncIntervalMinutes)
                            summaryProvider = EditTextIntPreference.SimpleSummaryProvider
                            setOnPreferenceChangeListener { _, newValue ->
                                val minutes = newValue as? Int ?: return@setOnPreferenceChangeListener false
                                RimiCloud.syncIntervalMinutes = minutes
                                RimiCloudSyncWork.restart(context)
                                true
                            }
                        },
                    )
                    addPreference(
                        Preference(ctx).apply {
                            statusPreference = this
                            isIconSpaceReserved = false
                            setTitle(R.string.rimi_cloud_status)
                        },
                    )
                }
                addCategory(R.string.rimi_cloud_server_category) {
                    isIconSpaceReserved = false
                    addPreference(
                        Preference(ctx).apply {
                            serverPreference = this
                            isIconSpaceReserved = false
                            setTitle(R.string.rimi_cloud_server)
                            setOnPreferenceClickListener {
                                promptServer()
                                true
                            }
                        },
                    )
                }
                addCategory(R.string.rimi_voice_category) {
                    isIconSpaceReserved = false
                    addPreference(
                        Preference(ctx).apply {
                            voicePermissionPreference = this
                            isIconSpaceReserved = false
                            setTitle(R.string.rimi_voice_permission)
                            setOnPreferenceClickListener {
                                if (ContextCompat.checkSelfPermission(
                                        ctx,
                                        Manifest.permission.RECORD_AUDIO,
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    ctx.toast(R.string.rimi_voice_permission_granted)
                                } else {
                                    activity?.let { act ->
                                        XXPermissions
                                            .with(act)
                                            .permission(Permission.RECORD_AUDIO)
                                            .request(null)
                                    }
                                }
                                true
                            }
                        },
                    )
                }
            }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        // 键盘语音入口点「去登录」→ 直接落到本页并立刻弹登录框（2026-11-04 爸爸反馈）
        if (RimiLoginRequest.consume() && !RimiCloud.isLoggedIn) {
            promptLogin()
        }
    }

    private fun refreshUi() {
        if (!isAdded) return
        val ctx = context ?: return
        val loggedIn = RimiCloud.isLoggedIn
        accountPreference.summary =
            if (loggedIn) {
                val key = RimiCloud.publicKey.orEmpty()
                val short = if (key.length > 12) key.take(12) + "…" else key
                val level = RimiCloud.membershipLevel.ifEmpty { "—" }
                getString(R.string.rimi_cloud_account_logged_in, short, level)
            } else {
                getString(R.string.rimi_cloud_account_not_logged_in)
            }
        logoutPreference.isVisible = loggedIn
        syncNowPreference.isEnabled = loggedIn
        autoSyncPreference.isChecked = RimiCloud.autoSync
        autoSyncPreference.isEnabled = loggedIn
        screenOffOnlyPreference.isChecked = RimiCloud.syncOnlyWhenScreenOff
        screenOffOnlyPreference.isEnabled = loggedIn && RimiCloud.autoSync
        intervalPreference.isEnabled = loggedIn && RimiCloud.autoSync
        serverPreference.summary = RimiCloud.server
        voicePermissionPreference.summary =
            if (ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                getString(R.string.rimi_voice_permission_granted)
            } else {
                getString(R.string.rimi_voice_permission_denied)
            }
        statusPreference.summary =
            when {
                !loggedIn -> getString(R.string.rimi_cloud_account_not_logged_in)

                RimiCloud.lastError.isNotEmpty() ->
                    getString(
                        R.string.rimi_cloud_last_error,
                        RimiCloud.lastError.take(160),
                    )

                RimiCloud.lastSyncAt > 0L -> {
                    val time = customFormatTimeInDefault("yyyy-MM-dd HH:mm", RimiCloud.lastSyncAt)
                    getString(R.string.rimi_cloud_last_sync, time) +
                        "\n" + RimiCloud.lastSyncDetail
                }

                else -> getString(R.string.rimi_cloud_never_synced)
            }
    }

    private fun promptLogin() {
        val ctx = context ?: return
        // 已经弹出登录框时不要再叠一层（onResume 的自动弹框与手动点击会撞车）
        if (loginDialog?.isShowing == true) return
        val input =
            EditText(ctx).apply {
                hint = getString(R.string.rimi_cloud_login_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                maxLines = 6
                gravity = Gravity.TOP or Gravity.START
            }
        val container =
            FrameLayout(ctx).apply {
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
        val dialog =
            AlertDialog
                .Builder(ctx)
                .setTitle(R.string.rimi_cloud_login)
                .setMessage(R.string.rimi_cloud_login_message)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    login(input.text.toString())
                }.create()
        dialog.setOnShowListener {
            // 直达登录页时用户下一步就是贴助记词：自动聚焦并拉起软键盘，少点一次
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        loginDialog = dialog
        dialog.showTracked()
    }

    private fun login(secret: String) {
        val ctx = context ?: return
        lifecycleScope.launch {
            var error: String? = null
            withLoadingDialog(ctx) {
                runCatching { RimiCloud.login(secret) }.onFailure { error = it.message ?: it.toString() }
            }
            if (error != null) {
                ctx.toast(getString(R.string.rimi_cloud_login_failed, error))
            } else {
                ctx.toast(R.string.rimi_cloud_login_success)
                RimiCloudSyncWork.restart(ctx)
                refreshUi()
                // 登录后立即拉一次云端词库，用户不用再找「立即同步」
                syncNow()
                return@launch
            }
            refreshUi()
        }
    }

    private fun confirmLogout() {
        val ctx = context ?: return
        AlertDialog
            .Builder(ctx)
            .setTitle(R.string.rimi_cloud_logout)
            .setMessage(R.string.rimi_cloud_logout_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                RimiCloud.logout()
                RimiCloudSyncWork.start(ctx)
                refreshUi()
            }.create()
            .showTracked()
    }

    private fun syncNow() {
        if (!RimiCloud.isLoggedIn) return
        val ctx = context ?: return
        lifecycleScope.launch {
            var report: RimiDictSync.Report? = null
            var error: String? = null
            withLoadingDialog(ctx) {
                val session = RimeDaemon.createSession(javaClass.name)
                try {
                    report = session.runOnReady { RimiDictSync.sync(this) }
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                } finally {
                    RimeDaemon.destroySession(javaClass.name)
                }
            }
            val result = report
            if (result != null) {
                ctx.toast(
                    getString(
                        R.string.rimi_cloud_sync_success,
                        result.pushed,
                        result.pulled,
                        result.applied,
                    ),
                )
            } else {
                error?.let { RimiCloud.recordError(it) }
                ctx.toast(getString(R.string.rimi_cloud_sync_failed, error ?: ""))
            }
            refreshUi()
        }
    }

    private fun promptServer() {
        val ctx = context ?: return
        val input =
            EditText(ctx).apply {
                setText(RimiCloud.server)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSelection(text.length)
            }
        val container =
            FrameLayout(ctx).apply {
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
        AlertDialog
            .Builder(ctx)
            .setTitle(R.string.rimi_cloud_server)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                RimiCloud.server = input.text.toString()
                refreshUi()
            }.create()
            .showTracked()
    }
}
