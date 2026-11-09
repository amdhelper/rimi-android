/*
 * SPDX-FileCopyrightText: 2026 Zoom IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.osfans.trime.R
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.rimi.RimiDictMarket
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.withLoadingDialog
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 词库市场（D，2026-11-10 爸爸令）。
 *
 * 覆盖所有人而不是只照顾医学：通用/网络热词/成语诗词/地名人名/专业领域/生活娱乐
 * 六类词库按需安装。清单来自后端 `/rimi/dict/market`（公开只读），客户端离线时用缓存。
 *
 * 安装流程：下载 → 校验 → 写入 user_data_dir → 重建 rimi.mobile.dict.yaml 的
 * import_tables → 全量部署（重编译词库表）。卸载反向执行。
 */
class RimiDictMarketFragment : PaddingPreferenceFragment() {
    private var catalog: RimiDictMarket.Catalog? = null

    private val trackedDialogs = mutableListOf<AlertDialog>()
    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null

    private fun AlertDialog.showTracked(): AlertDialog {
        trackedDialogs += this
        setOnDismissListener { trackedDialogs.remove(this) }
        show()
        return this
    }

    override fun onDestroyView() {
        trackedDialogs.toList().forEach { runCatching { it.dismiss() } }
        trackedDialogs.clear()
        progressDialog = null
        progressBar = null
        progressText = null
        super.onDestroyView()
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        // 用户升级/重装后，把「内置基线 + 已装市场词库」重新对齐一次
        lifecycleScope.launch { runCatching { RimiDictMarket.ensureUserMobileDict() } }
        RimiDictMarket.cachedCatalog()?.let { render(it) }
    }

    override fun onResume() {
        super.onResume()
        if (catalog == null) refresh()
    }

    // ---------------- 清单加载 ----------------

    private fun refresh() {
        val ctx = context ?: return
        lifecycleScope.launch {
            var error: String? = null
            withLoadingDialog(ctx) {
                runCatching { RimiDictMarket.fetchCatalog() }
                    .onSuccess { cat ->
                        catalog = cat
                        withContext(Dispatchers.Main) { render(cat) }
                    }
                    .onFailure { error = it.message ?: it.toString() }
            }
            if (error != null) {
                val cached = RimiDictMarket.cachedCatalog()
                if (cached != null) {
                    render(cached)
                    ctx.toast(getString(R.string.rimi_dict_market_offline, error))
                } else {
                    ctx.toast(getString(R.string.rimi_dict_market_failed, error))
                }
            }
        }
    }

    // ---------------- 渲染 ----------------

    @SuppressLint("NotifyDataSetChanged")
    private fun render(cat: RimiDictMarket.Catalog) {
        val ctx = context ?: return
        catalog = cat
        val screen = preferenceScreen
        screen.removeAll()

        screen.addCategory(R.string.rimi_dict_market) {
            isIconSpaceReserved = false
            addPreference(
                Preference(ctx).apply {
                    isIconSpaceReserved = false
                    setTitle(R.string.rimi_dict_market_intro_title)
                    setSummary(R.string.rimi_dict_market_intro)
                },
            )
            addPreference(
                Preference(ctx).apply {
                    isIconSpaceReserved = false
                    setTitle(R.string.rimi_dict_market_refresh)
                    setSummary(R.string.rimi_dict_market_refresh_summary)
                    setOnPreferenceClickListener {
                        refresh()
                        true
                    }
                },
            )
        }

        val installed = RimiDictMarket.installedNames()
        val ordered = cat.categories.sortedBy { it.sort }
        for (c in ordered) {
            val dicts = cat.dicts.filter { it.category == c.id }
            if (dicts.isEmpty()) continue
            screen.addCategory(c.name) {
                isIconSpaceReserved = false
                for (d in dicts) {
                    val isInstalled = d.dictName in installed
                    addPreference(
                        Preference(ctx).apply {
                            isIconSpaceReserved = false
                            setTitle(if (d.recommended) "⭐ ${d.name}" else d.name)
                            setSummary(
                                getString(
                                    R.string.rimi_dict_market_meta,
                                    d.description,
                                    RimiDictMarket.humanEntries(d.entries),
                                    RimiDictMarket.humanSize(d.sizeBytes),
                                    getString(
                                        if (isInstalled) {
                                            R.string.rimi_dict_market_installed
                                        } else {
                                            R.string.rimi_dict_market_available
                                        },
                                    ),
                                ),
                            )
                            setOnPreferenceClickListener {
                                if (isInstalled) confirmUninstall(d) else confirmInstall(d)
                                true
                            }
                        },
                    )
                }
            }
        }

        if (cat.categories.isEmpty() || cat.dicts.isEmpty()) {
            screen.addCategory("") {
                isIconSpaceReserved = false
                addPreference(
                    Preference(ctx).apply {
                        isIconSpaceReserved = false
                        setTitle(R.string.rimi_dict_market_empty)
                    },
                )
            }
        }
    }

    // ---------------- 安装 / 卸载 ----------------

    private fun confirmInstall(d: RimiDictMarket.Dict) {
        val ctx = context ?: return
        AlertDialog
            .Builder(ctx)
            .setTitle(getString(R.string.rimi_dict_market_install_title, d.name))
            .setMessage(
                getString(
                    R.string.rimi_dict_market_install_message,
                    d.description,
                    RimiDictMarket.humanSize(d.sizeBytes),
                ),
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> install(d) }
            .create()
            .showTracked()
    }

    private fun install(d: RimiDictMarket.Dict) {
        val ctx = context ?: return
        lifecycleScope.launch {
            var error: String? = null
            showProgress(
                getString(
                    R.string.rimi_dict_market_downloading,
                    RimiDictMarket.humanSize(0),
                    RimiDictMarket.humanSize(d.sizeBytes),
                ),
            )
            try {
                RimiDictMarket.install(d) { done, total ->
                    activity?.runOnUiThread {
                        progressText?.text =
                            getString(
                                R.string.rimi_dict_market_downloading,
                                RimiDictMarket.humanSize(done),
                                RimiDictMarket.humanSize(total),
                            )
                        if (total > 0) progressBar?.progress = ((done * 100) / total).toInt()
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            dismissProgress()
            if (error != null) {
                ctx.toast(getString(R.string.rimi_dict_market_failed, error))
                return@launch
            }
            ctx.toast(getString(R.string.rimi_dict_market_install_done, d.name))
            deployAndRefresh()
        }
    }

    private fun confirmUninstall(d: RimiDictMarket.Dict) {
        val ctx = context ?: return
        AlertDialog
            .Builder(ctx)
            .setTitle(getString(R.string.rimi_dict_market_uninstall_title, d.name))
            .setMessage(R.string.rimi_dict_market_uninstall_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> uninstall(d) }
            .create()
            .showTracked()
    }

    private fun uninstall(d: RimiDictMarket.Dict) {
        val ctx = context ?: return
        lifecycleScope.launch {
            var error: String? = null
            withLoadingDialog(ctx) {
                runCatching { RimiDictMarket.uninstall(d) }
                    .onFailure { error = it.message ?: it.toString() }
            }
            if (error != null) {
                ctx.toast(getString(R.string.rimi_dict_market_failed, error))
                return@launch
            }
            ctx.toast(getString(R.string.rimi_dict_market_uninstall_done, d.name))
            deployAndRefresh()
        }
    }

    // ---------------- 部署 ----------------

    private suspend fun deployNow() {
        val session = RimeDaemon.createSession(javaClass.name)
        try {
            session.runOnReady { RimiDictMarket.deploy(this) }
        } finally {
            RimeDaemon.destroySession(javaClass.name)
        }
    }

    private fun deployAndRefresh() {
        val ctx = context ?: return
        lifecycleScope.launch {
            var error: String? = null
            withLoadingDialog(ctx, R.string.rimi_dict_market_deploying) {
                runCatching { deployNow() }.onFailure { error = it.message ?: it.toString() }
            }
            if (error != null) {
                ctx.toast(getString(R.string.rimi_dict_market_failed, error))
            }
            catalog?.let { render(it) }
        }
    }

    // ---------------- 下载进度对话框 ----------------

    private fun showProgress(text: String) {
        val ctx = context ?: return
        dismissProgress()
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val label = TextView(ctx).apply {
            this.text = text
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label)
            addView(
                bar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val dialog =
            AlertDialog
                .Builder(ctx)
                .setTitle(R.string.rimi_dict_market)
                .setView(container)
                .setCancelable(false)
                .create()
        progressBar = bar
        progressText = label
        progressDialog = dialog
        dialog.showTracked()
    }

    private fun dismissProgress() {
        runCatching { progressDialog?.dismiss() }
        progressDialog = null
        progressBar = null
        progressText = null
    }
}
